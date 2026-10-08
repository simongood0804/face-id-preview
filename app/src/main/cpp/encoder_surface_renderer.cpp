// media_record P4-C「host-drawn surface」宿主侧渲染器实现。
//
//   open(cfg, NULL, surface_mode = 1, resident = 1)
//   start()
//   get_input_surface() -> ANativeWindow*
//   mr_render_create(window, w, h)        ← EGL 归库管
//     每帧：mr_render_frame_buffer(...)（零拷贝）或 mr_render_frame(...)（自绘）
//           + mr_session_notify_frame()
//   mr_render_destroy(); push_frame_eof(); wait()
//
// ⚠️ EGL context 的线程规则（库 5e4e3f3 起，头文件亦有明文）：
//   每个 mr_render_* 调用都会在**返回前主动解绑** EGL context（render.cc 的
//   CurrentGuard / ReleaseCurrent），所以 create / 每帧 / destroy 可以落在不同线程上，
//   只要**任意两次调用不并发**即可——并发时先 makeCurrent 的一方会被另一方挤掉，报
//   EGL_BAD_ACCESS（一个 context 同一时刻只能在一个线程上 current）。一旦画不进编码器
//   输入 surface，mr_stats 就是 `encoder_polls > 0 而 encoder_emitted == 0`。
//
//   本类据此保证"一次会话只走一条渲染路径"，路径互斥、不会并发：
//     · 路 A（首选）：相机回调内 mr_render_frame + 回调里自绑 EGLImage/外部纹理 + notify
//     · CPU 回退：相机回调内 UYVY→RGBA（NEON），交给渲染线程 mr_render_frame 上传 + notify
//   路 A 试一次失败即**永久降级**到 CPU 路径（见 tryExternalZeroCopy），不会同时使用两者。
//   库的 mr_render_frame_buffer **已弃用**：驱动对 vendor 私有格式不做 YUV→RGB（假彩色），
//   库 f49fe19 已撤回格式放宽并让该调用明确失败（见文件顶部与 Enqueue 说明）。
//
// 填充模式：kAspectMode = FIT（等比缩放、完整保留画面，比例不一致处留黑边）。
//   相机 1600x1300 与编码 1280x720 比例不同，CROP 会丢掉 31% 的垂直视野，故取 FIT。
//   库的 mr_render_set_fill_mode 只影响已弃用的 mr_render_frame_buffer，与本路径无关。
//
#include "encoder_surface_renderer.h"

#include <android/log.h>
#include <android/native_window.h>

// 路 A 需要 EGLImage + 外部纹理：
//   · eglGetNativeClientBufferANDROID（EGL_ANDROID_get_native_client_buffer）
//   · eglCreateImageKHR / eglDestroyImageKHR（EGL_ANDROID_image_native_buffer）
//   · glEGLImageTargetTexture2DOES + samplerExternalOES（GL_OES_EGL_image_external）
// 打开 *EXT_PROTOTYPES 才有这些扩展函数的原型，否则得用 eglGetProcAddress 手动取。
#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

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

#if defined(__aarch64__) || defined(__ARM_NEON)
#include <arm_neon.h>

/// NEON 版 UYVY→RGBA：一次 16 像素（32 字节源 = 8 个 `[U][Y0][V][Y1]` 组）。
///
/// `vld4_u8` 对 `[U][Y0][V][Y1]` 重复布局天然就是**按 4 交错解交织**：得到
/// val[0]=U、val[1]=Y0、val[2]=V、val[3]=Y1（各 8 个，分别对应偶数/奇数像素）。
/// 算出 R/G/B 后用 `vzip_u8` 把「偶数像素」与「奇数像素」两两交错回像素顺序，
/// 再用 `vst4_u8` 一次写 8 个 RGBA 像素。
///
/// 定点常量 = BT.601 limited 系数 ×64：1.164→74、1.596→102、0.391→25、0.813→52、
/// 2.018→129（系数取整带来的误差 ≤2/255，肉眼不可辨，与标量版 `yuv2rgba` 等价）。
/// 中间一律用**饱和加**：只有 B 的最大值（17686+16512=34198）会触顶，而饱和阈值
/// 32767/64≈512 远高于 255，所以照样 clamp 到 255，结果与不饱和时一致——不会引入偏差。
inline void uyvy16ToRgbaNeon(const uint8_t* s, uint8_t* d) {
    const uint8x8x4_t p = vld4_u8(s);
    const int16x8_t k128 = vdupq_n_s16(128);
    const int16x8_t k16 = vdupq_n_s16(16);
    const int16x8_t k32 = vdupq_n_s16(32);  // 取整偏置：(x+32)>>6 == (4x+128)>>8

    const int16x8_t u =
        vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(p.val[0])), k128);  // U-128
    const int16x8_t v =
        vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(p.val[2])), k128);  // V-128

    // 色度贡献（同一 U/V 服务这一对像素，故只算一次）
    const int16x8_t rC = vmulq_s16(v, vdupq_n_s16(102));  // +1.596*(V-128)
    const int16x8_t gC = vqsubq_s16(vmulq_s16(v, vdupq_n_s16(-52)),
                                    vmulq_s16(u, vdupq_n_s16(25)));  // -0.813e-0.391d
    const int16x8_t bC = vmulq_s16(u, vdupq_n_s16(129));             // +2.018*(U-128)

    const int16x8_t y0 = vmulq_s16(
        vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(p.val[1])), k16), vdupq_n_s16(74));
    const int16x8_t y1 = vmulq_s16(
        vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(p.val[3])), k16), vdupq_n_s16(74));

#define FACEID_YUV_CH(v, yterm)                                                        \
    vqmovun_s16(vshrq_n_s16(vqaddq_s16(vqaddq_s16(yterm, v), k32), 6))
    const uint8x8_t r0 = FACEID_YUV_CH(rC, y0);
    const uint8x8_t g0 = FACEID_YUV_CH(gC, y0);
    const uint8x8_t b0 = FACEID_YUV_CH(bC, y0);
    const uint8x8_t r1 = FACEID_YUV_CH(rC, y1);
    const uint8x8_t g1 = FACEID_YUV_CH(gC, y1);
    const uint8x8_t b1 = FACEID_YUV_CH(bC, y1);
#undef FACEID_YUV_CH

    const uint8x8x2_t rz = vzip_u8(r0, r1);  // val0 = 像素 0..7，val1 = 像素 8..15
    const uint8x8x2_t gz = vzip_u8(g0, g1);
    const uint8x8x2_t bz = vzip_u8(b0, b1);
    const uint8x8_t a = vdup_n_u8(255);

    // 注意：NDK 里 `vst4_u8` 是**宏**，参数里不能出现花括号初始化列表（逗号会被当成
    // 宏参数分隔符），所以先构造具名变量再传。
    uint8x8x4_t lo;
    lo.val[0] = rz.val[0];
    lo.val[1] = gz.val[0];
    lo.val[2] = bz.val[0];
    lo.val[3] = a;
    uint8x8x4_t hi;
    hi.val[0] = rz.val[1];
    hi.val[1] = gz.val[1];
    hi.val[2] = bz.val[1];
    hi.val[3] = a;
    vst4_u8(d, lo);
    vst4_u8(d + 32, hi);
}
#endif  // __aarch64__ || __ARM_NEON

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
        int x = 0;
#if defined(__aarch64__) || defined(__ARM_NEON)
        // 16 像素/次（32 字节源），读的是上面拷进来的**可缓存**行缓冲，NEON 收益才拿得到。
        for (; x + 16 <= w; x += 16, s += 32, d += 64) {
            uyvy16ToRgbaNeon(s, d);
        }
#endif
        for (; x + 1 < w; x += 2) {
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

// ---- 路 A 的片元着色器 ----
//
// ✅ 结论（2026-10-08 真机实测）：本平台驱动对 `samplerExternalOES` **会正确做 YUV→RGB**
// （Android 契约），因此着色器**只需直通输出**别人给的分量。
// 实测依据：直通输出的三通道均值与动态范围完全一致（meanR=meanG=meanB=97.0，
// min/max 均为 0/255），IR 画面得到正确灰度。
//
// ❌ **不要在着色器里再做一次 YUV→RGB**：那是二次转换，本机实测表现为假彩色
//    （暗绿底 + 品红高光）。下方 BT.601 版仅作记录，勿启用。
//
// ⚠️ 另一个必踩的坑：纹理坐标必须做**子矩形裁剪**。EGLImage 覆盖整块 gralloc
//    （实测 1600x3900），有效画面只在顶部 1600x1300；取满 0..1 会把画面压在顶部一条，
//    其余是全 0 无效区（转 RGB 呈暗绿）。见 quadVertices 的 texVMax 参数。
const char* kExternalFragmentShader =
    "#extension GL_OES_EGL_image_external : require\n"
    "precision mediump float;\n"
    "varying vec2 vTexCoord;\n"
    "uniform samplerExternalOES uTexture;\n"
    "void main() { gl_FragColor = vec4(texture2D(uTexture, vTexCoord).rgb, 1.0); }\n";

// 仅供记录（**勿启用**）：在驱动已转换过的分量上再做一次 BT.601 → 二次转换 → 假彩色。
// 若将来换到某个**不**做转换的平台，才需要改用它（届时先按上面的方法用直通版实测确认）。
const char* kExternalFragmentShaderBt601UnusedForReference =
    "#extension GL_OES_EGL_image_external : require\n"
    "precision mediump float;\n"
    "varying vec2 vTexCoord;\n"
    "uniform samplerExternalOES uTexture;\n"
    "void main() {\n"
    "  vec3 c = texture2D(uTexture, vTexCoord).rgb * 255.0;\n"
    "  float y = c.r;\n"
    "  float u = c.g;\n"
    "  float v = c.b;\n"
    "  float yy = 1.164 * (y - 16.0);\n"
    "  float r = yy + 1.596 * (v - 128.0);\n"
    "  float g = yy - 0.813 * (v - 128.0) - 0.391 * (u - 128.0);\n"
    "  float b = yy + 2.018 * (u - 128.0);\n"
    "  gl_FragColor = vec4(clamp(r, 0.0, 255.0), clamp(g, 0.0, 255.0),\n"
    "                      clamp(b, 0.0, 255.0), 255.0) / 255.0;\n"
    "}\n";

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

/// 编译 + 链接一组 shader（顶点属性固定绑 0=aPosition、1=aTexCoord，与 drawQuad 一致）。
int linkProgramWith(const char* vertexSrc, const char* fragmentSrc) {
    const int vs = compileShader(GL_VERTEX_SHADER, vertexSrc);
    const int fs = compileShader(GL_FRAGMENT_SHADER, fragmentSrc);
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

/// CPU 回退路径用的 program（普通 2D 纹理直通）。
int linkProgram() { return linkProgramWith(kVertexShader, kFragmentShader); }

// 真机实测：按「不翻转」采样得到正立画面（翻转反而上下颠倒），故为 false。
// 若换平台后画面上下颠倒，把此项反过来即可。
const bool kFlipY = false;

// 源图与目标表面宽高比不一致时的处理方式（两种情况都保持比例、不变形）。
enum class AspectMode {
    FIT,   // 等比缩放，**完整保留**画面；比例不一致时留黑边（本机为左右竖条）
    CROP,  // center-crop：放大填满、裁掉溢出部分（无黑边，但**丢视野**）
};
// 选 FIT：相机是 1600x1300（1.23:1），编码是 1280x720（16:9）。选 CROP 会只保留
// 69% 的垂直视野（丢掉 31%），DMS 场景丢视野的代价比黑边大，故完整保留。
// 若将来要"填满不留黑边"，可改为 CROP，或把编码分辨率改成与相机同比例
// （如 1280x1040，注意会超过 H.264 level 3.1 的 921600 像素上限，需确认编码器支持）。
const AspectMode kAspectMode = AspectMode::FIT;

/// 生成 NDC 四边形（GL_TRIANGLE_STRIP: BL, BR, TL, TR）。
///
/// `texVMax` = 纹理 v 方向的有效上界。CPU 路径的纹理本身就是有效图像，传 1.0；
/// 路 A 的纹理是整块 gralloc（EGLImage 覆盖全部行），有效画面只占顶部 padding 比例，
/// 必须传 validH/allocH，否则会采到全 0 的无效区（转 RGB 呈暗绿）。
void quadVertices(bool flipY, int srcW, int srcH, int dstW, int dstH, float texVMax,
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
    // flipY == false：表面顶部 <-> 纹理 v=0；v 只取到 texVMax（子矩形采样）
    const float t0[8] = {0.f, texVMax, 1.f, texVMax, 0.f, 0.f, 1.f, 0.f};
    const float t1[8] = {0.f, 0.f, 1.f, 0.f, 0.f, texVMax, 1.f, texVMax};
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

    // EGL（display/config/context/window surface）全交给库。库在每次调用返回前都会解绑
    // context，所以"这里（JNI 线程）创建、相机/渲染线程每帧使用、Stop 线程销毁"是允许的，
    // 只要不同时调用（见文件顶部说明）。
    if (!self->ensureRenderHandle()) {
        ANativeWindow_release(static_cast<ANativeWindow*>(window));
        self->window_ = nullptr;
        delete self;
        return nullptr;
    }
    // 填充模式交给库：CROP = 等比放大裁掉多余，画面填满编码器尺寸、不留黑边。
    // 与 kAspectMode 保持一致（只影响已弃用的 mr_render_frame_buffer，此处仅为不误导读者）。
    mr_render_set_fill_mode(self->render_, MR_FILL_FIT);

    LOGI("renderer created: encoder %dx%d anw=%dx%d (mr_render, fill=CROP)",
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

    // 销毁句柄：库每次调用都解绑 context，所以任意线程销毁都安全（此处已在渲染线程 join 之后）。
    if (render_ != nullptr) {
        mr_render_destroy(render_);
        render_ = nullptr;
    }
    LOGI("renderer stopped: drawn=%d dropped=%d drawFail=%d notifyFail=%d "
         "lastDraw=%lldus lastConvert=%lldus zeroCopy=%d",
         drawn_.load(), dropped_.load(), drawFail_.load(), notifyFail_.load(),
         static_cast<long long>(lastDrawUs_.load()),
         static_cast<long long>(lastConvertUs_.load()), zeroCopy());
}

// ============================================================================
// 路 A：自绑 EGLImage + 外部纹理（零拷贝快速路径）
// ============================================================================

bool EncoderSurfaceRenderer::tryExternalZeroCopy(AHardwareBuffer* buffer, int srcW, int srcH,
                                                 int64_t tsUs) {
    if (!ensureRenderHandle()) {
        externalUsable_ = false;  // 句柄建不起来，降级（错误信息已由 ensureRenderHandle 限频打过）
        return false;
    }

    // 把本帧 buffer 交给 draw 回调读。回调在本次 mr_render_frame 调用内**同步**执行，
    // 因此不跨帧持有 buffer（gralloc 生命周期约束）。
    pendingBuffer_ = buffer;
    pendingW_ = srcW;
    pendingH_ = srcH;
    {
        // gralloc 分配高度（实测 1600x3900），有效画面只占顶部 — 供纹理坐标做子矩形裁剪。
        AHardwareBuffer_Desc desc;
        AHardwareBuffer_describe(buffer, &desc);
        pendingBufH_ = static_cast<int>(desc.height);
    }
    extFailedThisFrame_ = false;

    const int rc = mr_render_frame(render_, &EncoderSurfaceRenderer::drawCallback, this,
                                   static_cast<long long>(tsUs) * 1000);

    pendingBuffer_ = nullptr;
    if (rc != MR_OK || extFailedThisFrame_) {
        char err[256];
        err[0] = '\0';
        if (render_ != nullptr) mr_render_last_error(render_, err, sizeof(err));
        LOGW("路 A（EGLImage+外部纹理零拷贝）失败 rc=%d extFail=%d (%s)；"
             "永久降级到 CPU 转换路径",
             rc, extFailedThisFrame_ ? 1 : 0, err);
        externalUsable_ = false;
        return false;
    }

    if (!zeroCopy_.exchange(true)) {
        LOGI("路 A 启用：EGLImage + GL_TEXTURE_EXTERNAL_OES（零拷贝，着色器内转 BT.601）");
    }
    if (mr_session_notify_frame(session_, tsUs) != MR_OK) notifyFail_++;
    drawn_++;
    return true;
}

bool EncoderSurfaceRenderer::buildExternalGl() {
    extProgram_ = linkProgramWith(kVertexShader, kExternalFragmentShader);
    if (extProgram_ == 0) return false;

    extTexUniform_ = glGetUniformLocation(static_cast<GLuint>(extProgram_), "uTexture");
    glGenTextures(1, reinterpret_cast<GLuint*>(&extTex_));
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, static_cast<GLuint>(extTex_));
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return true;
}

void EncoderSurfaceRenderer::drawExternal() {
    AHardwareBuffer* buf = pendingBuffer_;
    if (buf == nullptr) {
        extFailedThisFrame_ = true;
        return;
    }
    if (!extGlReady_) {
        if (!buildExternalGl()) {
            LOGE("路 A：外部纹理 program/纹理创建失败（GL_OES_EGL_image_external 不可用？）");
            extFailedThisFrame_ = true;
            return;
        }
        extGlReady_ = true;
    }

    const EGLDisplay dpy = eglGetCurrentDisplay();
    if (dpy == EGL_NO_DISPLAY) {
        LOGE("路 A：eglGetCurrentDisplay 失败（库的 context 未 current？）");
        extFailedThisFrame_ = true;
        return;
    }

    // AHardwareBuffer → EGLClientBuffer → EGLImage → 外部纹理。
    EGLClientBuffer client = eglGetNativeClientBufferANDROID(buf);
    if (client == nullptr) {
        LOGE("路 A：eglGetNativeClientBufferANDROID 返回空（EGL_ANDROID_get_native_client_buffer？）");
        extFailedThisFrame_ = true;
        return;
    }
    const EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    EGLImageKHR img =
        eglCreateImageKHR(dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client, attrs);
    if (img == EGL_NO_IMAGE_KHR) {
        LOGE("路 A：eglCreateImageKHR 失败 0x%x（该 vendor 格式不可作为 EGLImage？）",
             eglGetError());
        extFailedThisFrame_ = true;
        return;
    }

    glBindTexture(GL_TEXTURE_EXTERNAL_OES, static_cast<GLuint>(extTex_));
    glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES,
                                 reinterpret_cast<GLeglImageOES>(img));
    // 纹理已持有该 image 的引用，可以立刻销毁 image 对象释放 gralloc 引用——
    // 与库自己在 mr_render_frame_buffer 里的"同步消费、不保留 buffer"策略一致
    // （该策略在真机上已验证不会撕裂；若将来出现撕裂，就改为延迟到下一帧再销毁）。
    eglDestroyImageKHR(dpy, img);

    glViewport(0, 0, width_, height_);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);

    // 几何与 CPU 路径一致（同一份 quadVertices / kAspectMode / kFlipY），但纹理坐标
    // 必须做**子矩形裁剪**：EGLImage 覆盖整块 gralloc（1600x3900），有效画面只在顶部
    // 1600x1300；取满 0..1 会采到全 0 的无效区（转 RGB 呈暗绿，画面被压在顶部一条）。
    const float texVMax =
        (pendingBufH_ > 0)
            ? static_cast<float>(pendingH_) / static_cast<float>(pendingBufH_)
            : 1.f;
    float positions[8];
    float texCoords[8];
    quadVertices(kFlipY, pendingW_, pendingH_, width_, height_, texVMax, positions, texCoords);

    glUseProgram(static_cast<GLuint>(extProgram_));
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, static_cast<GLuint>(extTex_));
    if (extTexUniform_ >= 0) glUniform1i(extTexUniform_, 0);

    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, positions);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 0, texCoords);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
}

// ============================================================================
// 库的 mr_render_frame_buffer（已弃用，默认关闭）
// ============================================================================

bool EncoderSurfaceRenderer::tryZeroCopy(AHardwareBuffer* buffer, int srcW, int srcH,
                                         int64_t tsUs) {
    // ⚠️ 默认不启用（zeroCopyUsable_ 初值 false）：本机 IR 相机走这条路径会得到假彩色
    //    （驱动对 vendor 格式不做 YUV→RGB，亮度落进 R 通道），详见 Enqueue 顶部说明。
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

    // 1) 路 A：自绑 EGLImage + 外部纹理（零拷贝，着色器内转 BT.601）。
    //    必须在**相机回调线程内同步**完成：EGLImage 只是对 gralloc buffer 的引用，
    //    buffer 一回收即失效。失败一次即永久降级到下面的 CPU 路径。
    if (externalUsable_.load()) {
        const long long t0 = nowUs();
        if (tryExternalZeroCopy(buffer, width, height, timestampUs)) {
            lastDrawUs_ = nowUs() - t0;
            return;
        }
    }

    // 2) 库的 mr_render_frame_buffer：**默认关闭，勿启用**（zeroCopyUsable_ 初值 false）。
    //
    // 关闭原因（真机实测，与相机是否彩色无关）：它只是把 vendor 私有格式（0x120/UYVY422）
    // 的 buffer 做成 EGLImage 交给驱动采样（native_ui 里没有任何 YUV 解码路径），驱动
    // **不做 YUV→RGB**，于是原始分量按 RGBA 交回：
    //       亮度 → R 通道，色度 → G/B 通道
    // → 假彩色，但亮度/轮廓正常。本机 IR 相机（色度恒≈128）表现为"暗部偏青、亮部偏红"；
    // 彩色机型同样会错。库 f49fe19 已撤回格式放宽，该调用现在会明确失败并指向替代路径。
    if (zeroCopyUsable_.load()) {
        const long long t0 = nowUs();
        if (tryZeroCopy(buffer, width, height, timestampUs)) {
            lastDrawUs_ = nowUs() - t0;
            return;
        }
    }

    // 3) CPU 回退：取一块可写的 ping-pong 缓冲（上一帧未绘完则丢当前帧，不阻塞取流）
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
    // 路 A：本次回调是替相机帧画的（pendingBuffer_ 非空）→ 走外部纹理零拷贝。
    if (pendingBuffer_ != nullptr) {
        drawExternal();
        return;
    }
    // CPU 回退：库已把 EGL 上下文 makeCurrent，这里直接写 GL。
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
    quadVertices(kFlipY, srcW, srcH, width_, height_, 1.f, positions, texCoords);

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
