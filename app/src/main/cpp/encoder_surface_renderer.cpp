// media_record P4-C「host-drawn surface」宿主侧渲染器实现。
//
//   open(cfg, NULL, surface_mode = 1, resident = 1)
//   start()
//   get_input_surface() -> ANativeWindow*  ──┐
//                                            ├─ 本文件：EGL/GLES 画到该 surface
//   notify_frame(pts_us)   ──────────────────┘
//   push_frame_eof()  // 收尾并 finalize MP4
//
// 数据流（不做 EGLImage 零拷贝，原因见头文件）：
//   相机 HAL 回调线程                         本渲染器 GL 线程
//   ─────────────────                        ─────────────────
//   Enqueue(hwBuffer)                         cv.wait(readyIdx_)
//     ├ AHardwareBuffer_lock                    ├ 2D 纹理上传（glTexImage2D/SubImage2D）
//     ├ UYVY -> RGBA（帧有效期内完成）            ├ 按比例 letterbox 绘制
//     ├ unlock                                 ├ eglPresentationTimeANDROID + swap
//     └ 投递到 ping-pong 帧槽                    └ mr_session_notify_frame
//
#include "encoder_surface_renderer.h"

#include <android/log.h>
#include <android/native_window.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>

#include <chrono>
#include <cstring>

#include "ahardwarebuffer_util.h"
#include "media_record/media_record_driver.h"

#define LOG_TAG "EncoderSurface"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

PFNEGLPRESENTATIONTIMEANDROIDPROC g_eglPresentationTime = nullptr;

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
    for (int y = 0; y < h; ++y) {
        const uint8_t* s = src + static_cast<size_t>(y) * srcStrideBytes;
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
///
/// - 几何：按 `kAspectMode` 等比适配 `srcW x srcH` 到 `dstW x dstH`；
/// - 纹理坐标：只取 `[0,uMax] x [0,vMax]`。CPU 路径的纹理尺寸就等于有效图像尺寸
///   （uMax=vMax=1）；保留该参数是为了兼容"纹理比图像大"的场景
///   （例如以后若恢复 EGLImage，AHardwareBuffer 会远大于有效图像）。
void quadVertices(bool flipY, int srcW, int srcH, int dstW, int dstH, float uMax, float vMax,
                  float* positions, float* texCoords) {
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
    const float t0[8] = {0.f, vMax, uMax, vMax, 0.f, 0.f, uMax, 0.f};
    const float t1[8] = {0.f, 0.f, uMax, 0.f, 0.f, vMax, uMax, vMax};
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

    self->thread_ = std::thread([self] { self->threadLoop(); });

    {
        std::unique_lock<std::mutex> lk(self->mtx_);
        self->cv_.wait(lk, [self] { return self->running_ || self->stop_; });
        if (self->stop_ && !self->running_) {
            lk.unlock();
            self->Stop();
            delete self;
            return nullptr;
        }
    }
    LOGI("renderer started: encoder %dx%d, viewport %dx%d (CPU RGBA upload)", width, height,
         self->surfaceWidth_, self->surfaceHeight_);
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
        if (stop_ && !thread_.joinable()) return;
        stop_ = true;
    }
    cv_.notify_all();
    if (thread_.joinable()) thread_.join();
    LOGI("renderer stopped: drawn=%d dropped=%d drawFail=%d notifyFail=%d "
         "lastDraw=%lldus lastConvert=%lldus",
         drawn_.load(), dropped_.load(), drawFail_.load(), notifyFail_.load(),
         static_cast<long long>(lastDrawUs_.load()),
         static_cast<long long>(lastConvertUs_.load()));
}

void EncoderSurfaceRenderer::Enqueue(AHardwareBuffer* buffer, int width, int height,
                                     int64_t timestampUs) {
    if (buffer == nullptr || width <= 0 || height <= 0) return;

    // 取一块可写的 ping-pong 缓冲；上一帧 GL 线程尚未取走则丢当前帧（不阻塞取流）。
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
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buffer, &desc);
    const int srcStrideBytes = hbRowStrideBytes(desc);
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

// ============================================================================
// GL 线程
// ============================================================================

void EncoderSurfaceRenderer::threadLoop() {
    if (!initEgl() || !buildPrograms()) {
        LOGE("GL init failed");
        {
            std::lock_guard<std::mutex> lk(mtx_);
            stop_ = true;
        }
        cv_.notify_all();
        teardownEgl();
        return;
    }
    {
        std::lock_guard<std::mutex> lk(mtx_);
        running_ = true;
    }
    cv_.notify_all();

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

        const long long t0 = nowUs();
        bool ok = drawRgba(bufs_[idx].data(), w, h);
        if (ok) {
            if (g_eglPresentationTime != nullptr) {
                g_eglPresentationTime(display_, surface_, static_cast<long long>(tsUs) * 1000);
            }
            if (eglSwapBuffers(display_, surface_) != EGL_TRUE) {
                LOGE("eglSwapBuffers failed: 0x%x", eglGetError());
                ok = false;
            } else {
                const int rc = mr_session_notify_frame(session_, tsUs);
                if (rc != MR_OK) {
                    // 队列满：编码器慢于生产，丢帧属正常，仅计数。
                    notifyFail_++;
                }
            }
        }
        lastDrawUs_ = nowUs() - t0;
        if (ok) drawn_++; else drawFail_++;

        {
            std::lock_guard<std::mutex> lk(mtx_);
            readyIdx_ = -1;
            if (stop_) {
                running_ = false;
                break;
            }
        }
        cv_.notify_all();
    }

    {
        std::lock_guard<std::mutex> lk(mtx_);
        running_ = false;
    }
    teardownEgl();
}

bool EncoderSurfaceRenderer::initEgl() {
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return false;
    }
    EGLint major = 0;
    EGLint minor = 0;
    if (eglInitialize(display_, &major, &minor) != EGL_TRUE) {
        LOGE("eglInitialize failed: 0x%x", eglGetError());
        display_ = EGL_NO_DISPLAY;
        return false;
    }
    g_eglPresentationTime = reinterpret_cast<PFNEGLPRESENTATIONTIMEANDROIDPROC>(
        eglGetProcAddress("eglPresentationTimeANDROID"));
    if (g_eglPresentationTime == nullptr) {
        LOGW("eglPresentationTimeANDROID unavailable; encoder timestamps may be non-monotonic");
    }

    const EGLint configAttrs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE,
    };
    EGLint numConfigs = 0;
    if (eglChooseConfig(display_, configAttrs, &config_, 1, &numConfigs) != EGL_TRUE ||
        numConfigs < 1) {
        LOGE("eglChooseConfig failed: 0x%x", eglGetError());
        return false;
    }

    surface_ = eglCreateWindowSurface(display_, config_,
                                      static_cast<EGLNativeWindowType>(window_), nullptr);
    if (surface_ == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
        return false;
    }

    // 以 EGL surface 的**真实**尺寸为准绘制（真机实测与库返回值一致，均为 1280x720；
    // 这里仍然查一次并打日志，便于换平台/换编码器时第一时间发现不一致）。
    EGLint surfW = 0;
    EGLint surfH = 0;
    eglQuerySurface(display_, surface_, EGL_WIDTH, &surfW);
    eglQuerySurface(display_, surface_, EGL_HEIGHT, &surfH);
    surfaceWidth_ = (surfW > 0) ? surfW : width_;
    surfaceHeight_ = (surfH > 0) ? surfH : height_;
    LOGI("surface geom: lib=%dx%d anw=%dx%d egl=%dx%d", width_, height_,
         ANativeWindow_getWidth(static_cast<ANativeWindow*>(window_)),
         ANativeWindow_getHeight(static_cast<ANativeWindow*>(window_)), surfW, surfH);
    if (surfaceWidth_ != width_ || surfaceHeight_ != height_) {
        LOGW("encoder input surface size (%dx%d) != library size (%dx%d); using EGL size",
             surfaceWidth_, surfaceHeight_, width_, height_);
    }

    const EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, ctxAttrs);
    if (context_ == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed: 0x%x", eglGetError());
        return false;
    }
    if (eglMakeCurrent(display_, surface_, surface_, context_) != EGL_TRUE) {
        LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        return false;
    }

    glViewport(0, 0, surfaceWidth_, surfaceHeight_);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    LOGI("EGL ready: EGL %d.%d, viewport %dx%d", major, minor, surfaceWidth_, surfaceHeight_);
    return true;
}

void EncoderSurfaceRenderer::teardownEgl() {
    if (display_ != EGL_NO_DISPLAY) {
        // GL 对象必须在上下文仍为 current 时删除，故先删后解绑。
        if (context_ != EGL_NO_CONTEXT && surface_ != EGL_NO_SURFACE &&
            eglGetCurrentContext() != EGL_NO_CONTEXT) {
            if (program2d_ != 0) glDeleteProgram(static_cast<GLuint>(program2d_));
            if (tex2d_ != 0) glDeleteTextures(1, reinterpret_cast<GLuint*>(&tex2d_));
        }
        program2d_ = 0;
        tex2d_ = 0;
        texW_ = 0;
        texH_ = 0;
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
        if (surface_ != EGL_NO_SURFACE) eglDestroySurface(display_, surface_);
        eglTerminate(display_);
    }
    context_ = EGL_NO_CONTEXT;
    surface_ = EGL_NO_SURFACE;
    display_ = EGL_NO_DISPLAY;
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

void EncoderSurfaceRenderer::drawQuad(int program, int texId, int target, int srcW, int srcH,
                                      float uMax, float vMax) {
    glUseProgram(static_cast<GLuint>(program));

    float positions[8];
    float texCoords[8];
    quadVertices(kFlipY, srcW, srcH, surfaceWidth_, surfaceHeight_, uMax, vMax, positions,
                 texCoords);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(static_cast<GLenum>(target), static_cast<GLuint>(texId));
    const int loc = glGetUniformLocation(static_cast<GLuint>(program), "uTexture");
    if (loc >= 0) glUniform1i(loc, 0);

    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, positions);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 0, texCoords);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
    glBindTexture(static_cast<GLenum>(target), 0);
}

bool EncoderSurfaceRenderer::drawRgba(const uint8_t* rgba, int srcW, int srcH) {
    if (rgba == nullptr || srcW <= 0 || srcH <= 0) return false;

    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(tex2d_));
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    if (texW_ != srcW || texH_ != srcH) {
        // 尺寸变化（首帧/换摄像头）：重新分配纹理
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, srcW, srcH, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                     rgba);
        texW_ = srcW;
        texH_ = srcH;
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, srcW, srcH, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    }
    if (glGetError() != GL_NO_ERROR) return false;

    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    drawQuad(program2d_, tex2d_, GL_TEXTURE_2D, srcW, srcH, 1.f, 1.f);
    return true;
}
