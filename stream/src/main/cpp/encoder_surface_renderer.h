#ifndef FACEID_ENCODER_SURFACE_RENDERER_H_
#define FACEID_ENCODER_SURFACE_RENDERER_H_

// media_record P4-C「host-drawn surface」路径的宿主侧渲染器。
//
// 背景：部分车规 Qualcomm 平台的硬件编码器**只接受 surface 输入**（不提供 I420/NV12
// ByteBuffer 格式），此时 CPU 内存路径（mr_session_push_frame）拿不到任何输出
// （stats: encoder_emitted == 0）。库为此提供 surface 路径：把帧画到编码器输入 surface
// 上，再 mr_session_notify_frame() 通知引擎从该 surface 取帧编码。
//
// EGL 由库统一负责：本类使用库的 mr_render_* 辅助接口，
// **不再自己建 display/config/context/window surface，也不再自己 stamp PTS 和 swap**
// （库的注释指出：漏掉 PTS 会让 MediaCodec 丢弃绝大多数输入 surface 帧）。
//
// 三条渲染路径（自动选择，失败即永久降级到下一条）：
//   1) 路 A（首选，零拷贝）：相机回调内 mr_render_frame(r, draw, ...)，draw 回调里自绑
//      EGLImage（eglGetNativeClientBufferANDROID + eglCreateImageKHR）到
//      GL_TEXTURE_EXTERNAL_OES，片元着色器做 BT.601 转换后画 quad。
//      **数据一次搬运都不做**，且全彩正确（转换在我们自己的 shader 里，不靠驱动）。
//      必须在回调内同步消费：EGLImage 只是对 gralloc buffer 的**引用**，buffer 一回收即失效。
//   2) CPU 回退：相机回调内 UYVY→RGBA（NEON）转换，交给内部 GL 线程用
//      mr_render_frame(r, draw, ...) 上传纹理并按比例绘制（与预览/算法同源）。
//
// **不使用库的 mr_render_frame_buffer**：库把 vendor 私有格式的 buffer 交给 GPU 采样时，
// 驱动**不做 YUV→RGB**（native_ui 里没有任何 YUV/外部纹理采样代码），会静默给出假彩色。
// 库已在 f49fe19 撤回格式放宽、并让该调用对这类格式**明确失败** —— 这条路对任何相机
// （彩色/IR 都一样）都不可用，保留代码仅为记录该失败路径与自动降级逻辑。

#include <android/hardware_buffer.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

struct mr_session;
struct mr_render;

class EncoderSurfaceRenderer {
public:
    /// 创建渲染器（`window` 为 `mr_session_get_input_surface` 返回的 ANativeWindow*，
    /// `width`/`height` 为编码器配置尺寸，`session` 用于每帧 notify）。失败返回 nullptr。
    ///
    /// 注意：库在每次 mr_render_* 调用返回前都会**解绑** EGL context（5e4e3f3 起），
    /// 所以 create / 每帧 / destroy 可以落在不同线程上，只要**任意两次调用不并发**即可
    /// （见 .cpp 顶部说明）。本类保证一次会话只走一条渲染路径，因此不会并发。
    static EncoderSurfaceRenderer* Create(void* window, int width, int height,
                                          mr_session* session);

    ~EncoderSurfaceRenderer();

    /// 投递一帧（**必须在相机回调线程、HardwareBuffer 有效期内调用**）。非阻塞。
    void Enqueue(AHardwareBuffer* buffer, int width, int height, int64_t timestampUs);

    /// 停止渲染线程并销毁 mr_render 句柄。幂等。
    void Stop();

    // ---- 诊断 ----
    int drawnFrames() const { return drawn_.load(); }
    int droppedFrames() const { return dropped_.load(); }
    int drawFailures() const { return drawFail_.load(); }
    int notifyFailures() const { return notifyFail_.load(); }
    /// 最近一次「渲染 + swap + notify」的耗时（微秒）
    int64_t lastDrawUs() const { return lastDrawUs_.load(); }
    /// 最近一次「UYVY → RGBA」（含 lock）的耗时（微秒），相机回调线程测量
    int64_t lastConvertUs() const { return lastConvertUs_.load(); }
    /// 1 = 正在走零拷贝（mr_render_frame_buffer）；0 = CPU 回退
    int zeroCopy() const { return zeroCopy_.load() ? 1 : 0; }

private:
    EncoderSurfaceRenderer() = default;

    /// 懒启动渲染线程（仅 CPU 回退路径使用；路 A 走通时不需要它）。
    void ensureThread();
    void threadLoop();
    /// 懒创建库句柄。库在每次 mr_render_* 返回前会**解绑** EGL context（5e4e3f3 起），
    /// 所以不要求与 frame/destroy 同线程，只要求任意两次调用不并发。
    /// @return false = 创建失败
    bool ensureRenderHandle();
    /// mr_render_frame 的绘制回调（库已 makeCurrent、目标是编码器 surface）
    static void drawCallback(void* user);
    void onDraw();
    /// 路 A：外部纹理绘制（在库的 draw 回调内执行，context 已 current）
    void drawExternal();
    /// 路 A：着色器 + 外部纹理懒创建（必须在库的 GL context 内做）
    bool buildExternalGl();

    bool buildPrograms();
    void drawQuad(int program, int texId, int srcW, int srcH);

    /// 库的 mr_render_frame_buffer（**勿启用**）：库 f49fe19 已撤回 vendor 格式放宽，
    /// 该调用对 0x120 会明确失败（MR_ERROR_RUNTIME）；即使旧库放行，也只是把 buffer 交给
    /// 驱动采样，而驱动不做 YUV→RGB → 假彩色（详见 .cpp Enqueue 顶部说明）。
    /// 保留此函数仅为记录该失败路径与"失败即永久降级"的逻辑。
    bool tryZeroCopy(AHardwareBuffer* buffer, int srcW, int srcH, int64_t tsUs);

    /// 路 A：自绑 EGLImage + 外部纹理（**必须在相机回调内同步调用**）。
    /// @return true = 本帧已渲染并 notify 完毕
    bool tryExternalZeroCopy(AHardwareBuffer* buffer, int srcW, int srcH, int64_t tsUs);

    // ---- 句柄与几何 ----
    void* window_ = nullptr;  // ANativeWindow*
    mr_session* session_ = nullptr;
    mr_render* render_ = nullptr;  ///< 库句柄；create/每帧/destroy 可分处不同线程（不可并发）
    int width_ = 0;
    int height_ = 0;

    // ---- 渲染线程私有：错误日志限频（逐帧打会把 logcat 缓冲冲掉）----
    int64_t lastErrorLogUs_ = 0;  ///< 上次输出错误日志的时刻
    int createAttempts_ = 0;      ///< mr_render_create 尝试次数（用于首次必打）

    // ---- 自建的 GL 对象（全部在 GL 线程内创建/使用）----
    int program2d_ = 0;
    int tex2d_ = 0;
    int texW_ = 0;
    int texH_ = 0;
    bool glReady_ = false;

    // ---- CPU 回退：GL 线程 + ping-pong 帧槽 ----
    std::mutex mtx_;
    std::condition_variable cv_;
    std::thread thread_;
    std::vector<uint8_t> bufs_[2];
    int writeIdx_ = 0;
    int readyIdx_ = -1;
    int readyW_ = 0;
    int readyH_ = 0;
    int64_t readyTsUs_ = 0;
    bool stop_ = false;

    // GL 线程绘制中的当前帧（仅在 GL 线程读写）
    const uint8_t* drawBuf_ = nullptr;
    int drawW_ = 0;
    int drawH_ = 0;

    // ---- 路 A：自绑 EGLImage + 外部纹理（零拷贝，全彩）----
    std::atomic<bool> externalActive_{false};  ///< 当前实际走的是路 A
    /// 是否仍**尝试**路 A。失败一次即永久关闭（降级到 CPU 转换路径）。
    /// 与相机是否彩色无关：转换在我们自己的 shader 里做，彩色机型同样正确。
    std::atomic<bool> externalUsable_{true};
    AHardwareBuffer* pendingBuffer_ = nullptr;  ///< 仅回调内有效，draw 回调读取；不得跨帧持有
    int pendingW_ = 0;
    int pendingH_ = 0;
    /// gralloc **分配高度**（实测 3900），有效画面只占顶部 pendingH_（1300）——纹理坐标要按此裁剪。
    int pendingBufH_ = 0;
    bool extFailedThisFrame_ = false;  ///< 本帧绘制失败（回调内设置，回调外判读）
    int extProgram_ = 0;               ///< samplerExternalOES + BT.601 转换
    int extTex_ = 0;                   ///< GL_TEXTURE_EXTERNAL_OES（每帧重绑 EGLImage）
    int extTexUniform_ = -1;
    bool extGlReady_ = false;

    // ---- 诊断 ----
    std::atomic<bool> zeroCopy_{false};      ///< 当前实际走的是哪条路径（路 A 也置 1）
    /// 是否**尝试**零拷贝。默认关闭，且**与相机是否彩色无关**（勿因换机型而改回）：
    /// 库把 vendor 私有格式（本机 0x120，UYVY 422）的 buffer 交给 GPU 采样时，驱动
    /// **不做 YUV→RGB**，而是把原始分量按 RGBA 交回 → 亮度落进 R 通道、色度落进 G/B。
    /// 本机是 IR 相机（色度恒≈128），故表现为"暗部青、亮部红"的假彩色；**彩色机型同样会错**，
    /// 只是偏色形态不同。因此一律改用我们自己的转换（与预览/算法同源，全彩正确）。
    std::atomic<bool> zeroCopyUsable_{false};
    std::atomic<int> drawn_{0};
    std::atomic<int> dropped_{0};
    std::atomic<int> drawFail_{0};
    std::atomic<int> notifyFail_{0};
    std::atomic<int64_t> lastDrawUs_{0};
    std::atomic<int64_t> lastConvertUs_{0};
};

#endif  // FACEID_ENCODER_SURFACE_RENDERER_H_
