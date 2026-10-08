// media_record P4-C「host-drawn surface」宿主侧渲染器实现。
//
//   open(cfg, NULL, surface_mode = 1, resident = 1)
//   start()
//   get_input_surface() -> ANativeWindow*
//   mr_render_create(window, w, h)        ← EGL 归库管（**必须在渲染线程里调**）
//     每帧：mr_render_frame(...)（自绘 + 上传纹理）+ mr_session_notify_frame()
//   mr_render_destroy(); push_frame_eof(); wait()
//
// ⚠️ 线程绑定（真机实测的关键约束，违反则整条链路废掉）：
//   库的 RenderContext::CreateFromNativeWindow() 内部会 eglMakeCurrent，把刚建好的
//   context 留在**创建它的那个线程**上且不释放；而 RenderContext::MakeCurrent() 只是裸的
//   eglMakeCurrent。所以 create / 每帧 frame / destroy 必须都在**同一个线程**里调用，
//   否则每帧 eglMakeCurrent 都报 `EGL_BAD_ACCESS`（libEGL 刷屏，把日志缓冲写满）：
//   一帧都画不进编码器输入 surface，mr_stats 表现为 `encoder_polls > 0 而
//   encoder_emitted == 0`（库文档给的定义：codec 没有输出）→ 推流没有媒体，
//   服务端表现就是「有推流者，但浏览器说找不到流」。
//   故本类固定用**同一个渲染线程**（threadLoop）承担 mr_render_create /
//   mr_render_frame / mr_render_destroy；Create() 只登记几何与 ANativeWindow。
//
// 渲染路径：相机回调线程内做 UYVY→RGBA（buffer 只在回调期间有效），交给渲染线程用
//   mr_render_frame 上传纹理并按比例 letterbox 绘制。
//
// 零拷贝（mr_render_frame_buffer）**未启用**：本机相机是 vendor 私有格式 0x120，而库的
//   buffer import 只收 R8G8B8A8_UNORM（必返回 MR_ERROR_RUNTIME），且它只能在相机回调
//   线程内调用，与上面的「同线程」约束冲突（保留 tryZeroCopy 备库侧放开后接入）。
//
#include "encoder_surface_renderer.h"

#include <android/log.h>
#include <android/native_window.h>
#include <GLES2/gl2.h>

#include <pthread.h>

#include <chrono>
#include <cstring>

#include "ahardwarebuffer_util.h"
#include "media_record/media_record_driver.h"

#define LOG_TAG "EncoderSurface"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

inline long long nowUs() {
    using namespace std::chrono;
    return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
}

inline uint8_t clamp8(int v) {
    return static_cast<uint8_t>(v < 0 ? 0 : (v > 255 ? 255 : v));
}

/// BT.601 limited-range YUV -> RGBA（写 4 字节/像素，A=255）。
inline void yuv2rgba(int y, int u, int v, uint8_t* out) {
    const int c = y - 16;
    const int d = u - 128;
    const int e = v - 128;
    out[0] = clamp8((298 * c + 409 * e + 128) >> 8);
    out[1] = clamp8((298 * c - 100 * d - 208 * e + 128) >> 8);
    out[2] = clamp8((298 * c + 516 * d + 128) >> 8);
    out[3] = 255;
}

/// UYVY -> RGBA（不缩放；缩放交给 GL 采样器，比 CPU 缩放便宜）。
///
/// **字节序（易错，务必按此读）**：`[U][Y0][V][Y1]`（每 4 字节一对像素）。
/// 依据：`docs/FaceID_SO对接说明.md`（`[U0 Y0 V0 Y1] ...`）+ 算法路径
/// `algo/.../FrameProcessor.kt#convertUyvyToRgb888`（实际在跑且人脸识别正确的那份）。
/// 注意项目里 `FrameProcessor.kt` 的 KDoc 把顺序写成 `Y0 U Y1 V`，是**注释写错了**，
/// 以其实现为准。若按 `[Y0][U][Y1][V]` 读，会把 U 当亮度、亮度当色度：
/// IR 画面（U/V≈128）下表现为「整幅纯绿、但人物轮廓清晰」。
void uyvyToRgba(const uint8_t* src, int srcStrideBytes, uint8_t* dst, int w, int h) {
    // 关键优化：AHardwareBuffer lock 得到的内存多为**非 cacheable** 映射，逐像素随机读
    // 延迟极高（实测 ~180ns/次）。改为**按源行 memcpy 宽加载**到本地 cacheable 行缓冲再
    // 转换，把慢内存访问次数降低约一个数量级。
    // 真机实测：不做这一步 conv 约 32ms/帧（1600x1300，已低于 30fps 预算并开始掉帧）。
    static thread_local std::vector<uint8_t> rowBuf;
    const size_t strideBytes = static_cast<size_t>(srcStrideBytes);
    if (rowBuf.size() < strideBytes) rowBuf.resize(strideBytes);

    for (int y = 0; y < h; ++y) {
        memcpy(rowBuf.data(), src + static_cast<size_t>(y) * strideBytes, strideBytes);
        const uint8_t* s = rowBuf.data();
        uint8_t* d = dst + static_cast<size_t>(y) * w * 4;
        for (int x = 0; x + 1 < w; x += 2) {
            const int u = s[0];
            const int y0 = s[1];
            const int v = s[2];
            const int y1 = s[3];
            yuv2rgba(y0, u, v, d);
            yuv2rgba(y1, u, v, d + 4);
            d += 8;
            s += 4;
        }
    }
}

const char* kVertexShader =
    "attribute vec4 aPosition;\n"
    "attribute vec2 aTexCoord;\n"
    "varying vec2 vTexCoord;\n"
    "void main() {\n"
    "  gl_Position = aPosition;\n"
    "  vTexCoord = aTexCoord;\n"
    "}\n";

const char* kFragmentShader =
    "precision mediump float;\n"
    "varying vec2 vTexCoord;\n"
    "uniform sampler2D uTexture;\n"
    "void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }\n";

int compileShader(GLenum type, const char* src) {
    const GLuint shader = glCreateShader(type);
    if (shader == 0) return 0;
    glShaderSource(shader, 1, &src, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (ok == GL_FALSE) {
        GLint len = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &len);
        std::vector<char> log(static_cast<size_t>(len > 1 ? len : 1));
        glGetShaderInfoLog(shader, len, nullptr, log.data());
        LOGE("shader compile failed: %s", log.data());
        glDeleteShader(shader);
        return 0;
    }
    return static_cast<int>(shader);
}

int linkProgram() {
    const int vs = compileShader(GL_VERTEX_SHADER, kVertexShader);
    const int fs = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    if (vs == 0 || fs == 0) {
        if (vs) glDeleteShader(static_cast<GLuint>(vs));
        if (fs) glDeleteShader(static_cast<GLuint>(fs));
        return 0;
    }
    const GLuint program = glCreateProgram();
    glAttachShader(program, static_cast<GLuint>(vs));
    glAttachShader(program, static_cast<GLuint>(fs));
    glBindAttribLocation(program, 0, "aPosition");
    glBindAttribLocation(program, 1, "aTexCoord");
    glLinkProgram(program);
    glDeleteShader(static_cast<GLuint>(vs));
    glDeleteShader(static_cast<GLuint>(fs));
    GLint ok = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (ok == GL_FALSE) {
        GLint len = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &len);
        std::vector<char> log(static_cast<size_t>(len > 1 ? len : 1));
        glGetProgramInfoLog(program, len, nullptr, log.data());
        LOGE("program link failed: %s", log.data());
        glDeleteProgram(program);
        return 0;
    }
    return static_cast<int>(program);
}

// 真机实测：按「不翻转」采样得到正立画面（翻转反而上下颠倒），故为 false。
// 若换平台后画面上下颠倒，把此项反过来即可。
const bool kFlipY = false;

// 源图与目标表面宽高比不一致时的处理方式（两种情况都保持比例、不变形）。
enum class AspectMode {
    FIT,   // letterbox：完整可见，多余处留黑边
    CROP,  // center-crop：放大填满，裁掉溢出部分（无黑边）
};
// 推流一般用 CROP；校验画面完整性用 FIT。
const AspectMode kAspectMode = AspectMode::FIT;

/// 生成 NDC 四边形（GL_TRIANGLE_STRIP: BL, BR, TL, TR）。
void quadVertices(bool flipY, int srcW, int srcH, int dstW, int dstH, float* positions,
                  float* texCoords) {
    float sx = 1.f;
    float sy = 1.f;
    if (srcW > 0 && srcH > 0 && dstW > 0 && dstH > 0) {
        const float r = (static_cast<float>(srcW) / static_cast<float>(srcH)) /
                        (static_cast<float>(dstW) / static_cast<float>(dstH));
        if (kAspectMode == AspectMode::FIT) {
            if (r > 1.f) sy = 1.f / r; else sx = r;
        } else {
            if (r > 1.f) sx = r; else sy = 1.f / r;  // 溢出部分由 viewport 裁掉
        }
    }
    const float p[8] = {-sx, -sy, sx, -sy, -sx, sy, sx, sy};
    // flipY == false：表面顶部 <-> 纹理 v=0
    const float t0[8] = {0.f, 1.f, 1.f, 1.f, 0.f, 0.f, 1.f, 0.f};
    const float t1[8] = {0.f, 0.f, 1.f, 0.f, 0.f, 1.f, 1.f, 1.f};
    memcpy(positions, p, sizeof(p));
    memcpy(texCoords, flipY ? t1 : t0, sizeof(t1));
}

}  // namespace

// ============================================================================
// 生命周期
// ============================================================================

EncoderSurfaceRenderer* EncoderSurfaceRenderer::Create(void* window, int width, int height,
                                                       mr_session* session) {
    if (window == nullptr || session == nullptr || width <= 0 || height <= 0) {
        LOGE("Create: invalid args window=%p session=%p %dx%d", window, session, width, height);
        return nullptr;
    }
    auto* self = new EncoderSurfaceRenderer();
    self->window_ = window;
    self->session_ = session;
    self->width_ = width;
    self->height_ = height;
    ANativeWindow_acquire(static_cast<ANativeWindow*>(window));

    // EGL（display/config/context/window surface）交给库，但**必须在渲染线程里创建**：
    // 库在 CreateFromNativeWindow 里 eglMakeCurrent 之后不释放，跨线程就是 EGL_BAD_ACCESS。
    // 这里只登记几何与窗口，句柄留到 threadLoop 首次建帧时懒创建（见 ensureRenderHandle）。
    LOGI("renderer created: encoder %dx%d anw=%dx%d (mr_render 句柄将由渲染线程创建)",
         width, height, ANativeWindow_getWidth(static_cast<ANativeWindow*>(window)),
         ANativeWindow_getHeight(static_cast<ANativeWindow*>(window)));
    return self;
}

EncoderSurfaceRenderer::~EncoderSurfaceRenderer() {
    Stop();
    if (window_ != nullptr) {
        ANativeWindow_release(static_cast<ANativeWindow*>(window_));
        window_ = nullptr;
    }
}

void EncoderSurfaceRenderer::Stop() {
    {
        std::lock_guard<std::mutex> lk(mtx_);
        stop_ = true;
    }
    cv_.notify_all();
    if (thread_.joinable()) thread_.join();

    // 句柄由渲染线程在退出前自行销毁（EGL 线程绑定：谁创建谁销毁），这里只做汇总。
    LOGI("renderer stopped: drawn=%d dropped=%d drawFail=%d notifyFail=%d "
         "lastDraw=%lldus lastConvert=%lldus zeroCopy=%d",
         drawn_.load(), dropped_.load(), drawFail_.load(), notifyFail_.load(),
         static_cast<long long>(lastDrawUs_.load()),
         static_cast<long long>(lastConvertUs_.load()), zeroCopy());
}

// ============================================================================
// 零拷贝快速路径
// ============================================================================

bool EncoderSurfaceRenderer::tryZeroCopy(AHardwareBuffer* buffer, int srcW, int srcH,
                                         int64_t tsUs) {
    // 注意：本调用必须在相机回调线程内同步完成（库保证不持有该 buffer），
    // 因此对 EVS 那种"回调返回即回收"的 buffer 是安全的。
    const int rc = mr_render_frame_buffer(render_, buffer, srcW, srcH,
                                          static_cast<long long>(tsUs) * 1000);
    if (rc != MR_OK) {
        char err[256];
        err[0] = '\0';
        mr_render_last_error(render_, err, sizeof(err));
        LOGW("mr_render_frame_buffer failed rc=%d (%s); 永久降级到 CPU 纹理上传路径",
             rc, err);
        zeroCopyUsable_ = false;
        return false;
    }

    if (!zeroCopy_.exchange(true)) {
        LOGI("零拷贝路径启用：mr_render_frame_buffer（相机回调线程内同步渲染）");
    }
    const int nrc = mr_session_notify_frame(session_, tsUs);
    if (nrc != MR_OK) notifyFail_++;
    drawn_++;
    return true;
}

// ============================================================================
// CPU 回退路径
// ============================================================================

void EncoderSurfaceRenderer::ensureThread() {
    std::lock_guard<std::mutex> lk(mtx_);
    if (stop_ || thread_.joinable()) return;
    thread_ = std::thread([this] {
        // 显式命名：否则会继承父线程（EVS 回调线程）的名字，日志里分不清谁在报错。
        pthread_setname_np(pthread_self(), "EncSurfRender");
        threadLoop();
    });
    LOGI("渲染线程已启动（唯一调用 mr_render_* 的线程）");
}

bool EncoderSurfaceRenderer::ensureRenderHandle() {
    if (render_ != nullptr) return true;
    render_ = mr_render_create(window_, width_, height_);
    if (render_ == nullptr) {
        // 限频：句柄建不起来时每帧都会走到这里，逐帧打会把 logcat 缓冲冲掉
        // （之前被 libEGL 刷屏冲掉全部诊断信息的教训）。
        const long long now = nowUs();
        if (createAttempts_++ == 0 || now - lastErrorLogUs_ > 1000000LL) {
            lastErrorLogUs_ = now;
            LOGE("mr_render_create failed (window=%p %dx%d, 第 %d 次尝试)", window_, width_,
                 height_, createAttempts_);
        }
        return false;
    }
    LOGI("mr_render 句柄已创建（本渲染线程）：%dx%d", width_, height_);
    return true;
}

void EncoderSurfaceRenderer::Enqueue(AHardwareBuffer* buffer, int width, int height,
                                     int64_t timestampUs) {
    if (buffer == nullptr || width <= 0 || height <= 0) return;

    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buffer, &desc);
    const int srcStrideBytes = hbRowStrideBytes(desc);

    // 零拷贝未启用（原因见文件顶部说明）：一律走 CPU 纹理路径，由渲染线程统一绘制。
    // 关键点是 mr_render_* 必须与创建句柄的线程一致，因此这里不能在相机回调线程里
    // 去碰库的渲染句柄。

    // 2) CPU 回退：取一块可写的 ping-pong 缓冲（上一帧未绘完则丢当前帧，不阻塞取流）
    ensureThread();

    uint8_t* dst = nullptr;
    int idx = -1;
    {
        std::lock_guard<std::mutex> lk(mtx_);
        if (stop_) return;
        if (readyIdx_ >= 0) {
            dropped_++;
            return;
        }
        idx = writeIdx_;
        const size_t need = static_cast<size_t>(width) * height * 4;
        if (bufs_[idx].size() < need) bufs_[idx].resize(need);
        dst = bufs_[idx].data();
    }

    // 色彩转换必须在此刻完成：HardwareBuffer 只在本回调期间有效。
    // 只读有效图像区域（buffer 可能远高于图像，见 ahardwarebuffer_util.h）。
    if (srcStrideBytes <= 0) return;

    const long long t0 = nowUs();
    void* data = nullptr;
    const int lockRc =
        AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &data);
    if (lockRc != 0 || data == nullptr) {
        LOGW("AHardwareBuffer_lock failed: %d", lockRc);
        return;
    }
    uyvyToRgba(static_cast<const uint8_t*>(data), srcStrideBytes, dst, width, height);
    AHardwareBuffer_unlock(buffer, nullptr);
    lastConvertUs_ = nowUs() - t0;

    {
        std::lock_guard<std::mutex> lk(mtx_);
        if (stop_) return;
        readyIdx_ = idx;
        readyW_ = width;
        readyH_ = height;
        readyTsUs_ = timestampUs;
        writeIdx_ ^= 1;
    }
    cv_.notify_one();
}

void EncoderSurfaceRenderer::threadLoop() {
    for (;;) {
        int idx = -1;
        int w = 0;
        int h = 0;
        int64_t tsUs = 0;
        {
            std::unique_lock<std::mutex> lk(mtx_);
            cv_.wait(lk, [this] { return stop_ || readyIdx_ >= 0; });
            if (readyIdx_ < 0) break;  // stop_ 且无待绘帧
            idx = readyIdx_;
            w = readyW_;
            h = readyH_;
            tsUs = readyTsUs_;
        }

        drawBuf_ = bufs_[idx].data();
        drawW_ = w;
        drawH_ = h;

        const long long t0 = nowUs();
        int rc = MR_ERROR_STATE;
        if (ensureRenderHandle()) {
            rc = mr_render_frame(render_, &EncoderSurfaceRenderer::drawCallback, this,
                                 static_cast<long long>(tsUs) * 1000);
        }
        bool ok = (rc == MR_OK);
        if (ok) {
            if (mr_session_notify_frame(session_, tsUs) != MR_OK) notifyFail_++;
        } else {
            char err[256];
            err[0] = '\0';
            if (render_ != nullptr) mr_render_last_error(render_, err, sizeof(err));
            // 限频：首次失败 + 之后每秒最多一条（逐帧打会把 logcat 缓冲冲掉）
            const long long now = nowUs();
            if (drawFail_.load() == 0 || now - lastErrorLogUs_ > 1000000LL) {
                lastErrorLogUs_ = now;
                LOGE("mr_render_frame failed rc=%d (%s)（已失败 %d 帧）", rc, err,
                     drawFail_.load() + 1);
            }
        }
        lastDrawUs_ = nowUs() - t0;
        if (ok) drawn_++; else drawFail_++;

        drawBuf_ = nullptr;
        {
            std::lock_guard<std::mutex> lk(mtx_);
            readyIdx_ = -1;
            if (stop_) break;
        }
        cv_.notify_all();
    }

    // 在创建它的这个线程里销毁句柄（EGL 线程绑定：谁创建谁销毁）。
    if (render_ != nullptr) {
        mr_render_destroy(render_);
        render_ = nullptr;
    }
}

void EncoderSurfaceRenderer::drawCallback(void* user) {
    static_cast<EncoderSurfaceRenderer*>(user)->onDraw();
}

void EncoderSurfaceRenderer::onDraw() {
    // 库已把 EGL 上下文 makeCurrent，这里直接写 GL。
    if (!glReady_) {
        if (!buildPrograms()) return;
        glReady_ = true;
    }
    glViewport(0, 0, width_, height_);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);

    const uint8_t* rgba = drawBuf_;
    const int srcW = drawW_;
    const int srcH = drawH_;
    if (rgba == nullptr || srcW <= 0 || srcH <= 0) return;

    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(tex2d_));
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    if (texW_ != srcW || texH_ != srcH) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, srcW, srcH, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                     rgba);
        texW_ = srcW;
        texH_ = srcH;
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, srcW, srcH, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    }

    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    drawQuad(program2d_, tex2d_, srcW, srcH);
}

bool EncoderSurfaceRenderer::buildPrograms() {
    program2d_ = linkProgram();
    if (program2d_ == 0) return false;

    glGenTextures(1, reinterpret_cast<GLuint*>(&tex2d_));
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(tex2d_));
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return true;
}

void EncoderSurfaceRenderer::drawQuad(int program, int texId, int srcW, int srcH) {
    glUseProgram(static_cast<GLuint>(program));

    float positions[8];
    float texCoords[8];
    quadVertices(kFlipY, srcW, srcH, width_, height_, positions, texCoords);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(texId));
    const int loc = glGetUniformLocation(static_cast<GLuint>(program), "uTexture");
    if (loc >= 0) glUniform1i(loc, 0);

    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, positions);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 0, texCoords);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
    glBindTexture(GL_TEXTURE_2D, 0);
}
