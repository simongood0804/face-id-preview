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
// 两条渲染路径（自动选择）：
//   1) 零拷贝：相机回调线程内 mr_render_frame_buffer(r, ahwb, srcW, srcH, ptsNs)。
//      **同步消费、不持有 buffer**，因此可以在 EVS 回调的短生命周期 buffer 上直接调用。
//      当前真机的相机 buffer 是 vendor 私有格式 0x120，而库的 buffer import 仅支持
//      R8G8B8A8_UNORM，会返回 MR_ERROR_RUNTIME —— 此时**一次性永久降级**到路径 2，
//      等库侧补齐该格式支持后无需改动即可自动启用。
//   2) CPU 回退：相机回调线程内 UYVY→RGBA 转换，交给内部 GL 线程用
//      mr_render_frame(r, draw, user, ptsNs) 上传纹理并按比例 letterbox 绘制。
//
// 为什么不做「EGLImage 跨线程零拷贝」：曾试过 AHardwareBuffer_acquire + 交给 GL 线程，
// 真机上把 vendor gralloc 踩崩（预览 GL 线程在 libqdMetaData.so getMetaDataVa 段错误）。
// 库的 mr_render_frame_buffer 文档也明确警告了这一点。

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
    /// 注意：这里**不**创建库的 mr_render 句柄。库的 EGL context 有线程绑定（见 .cpp
    /// 顶部说明：谁创建就留在谁的线程上），因此 create / frame / destroy 必须同线程，
    /// 句柄交由渲染线程懒创建、并在该线程退出前销毁。
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

    /// 懒启动渲染线程（本平台零拷贝不可用，建帧一律由该线程承担）。
    void ensureThread();
    void threadLoop();
    /// 渲染线程内懒创建库句柄（必须与 mr_render_frame 同线程）。@return false = 创建失败
    bool ensureRenderHandle();
    /// mr_render_frame 的绘制回调（库已 makeCurrent，直接写 GL 即可）
    static void drawCallback(void* user);
    void onDraw();

    bool buildPrograms();
    void drawQuad(int program, int texId, int srcW, int srcH);

    /// 零拷贝快速路径。**当前未启用**：本机相机是 vendor 私有格式 0x120，库的 buffer
    /// import 只收 R8G8B8A8_UNORM（必返回 MR_ERROR_RUNTIME）；且它只能在相机回调线程内
    /// 调用，与「mr_render_* 同线程」约束冲突。留作库侧放开格式后的接入点。
    /// @return true = 本帧已渲染并 notify 完毕
    bool tryZeroCopy(AHardwareBuffer* buffer, int srcW, int srcH, int64_t tsUs);

    // ---- 句柄与几何 ----
    void* window_ = nullptr;  // ANativeWindow*
    mr_session* session_ = nullptr;
    mr_render* render_ = nullptr;  ///< 仅渲染线程创建/使用/销毁（EGL 线程绑定）
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

    // ---- 诊断 ----
    std::atomic<bool> zeroCopy_{false};      ///< 当前实际走的是哪条路径
    std::atomic<bool> zeroCopyUsable_{true}; ///< 是否仍尝试零拷贝（失败一次即永久关闭）
    std::atomic<int> drawn_{0};
    std::atomic<int> dropped_{0};
    std::atomic<int> drawFail_{0};
    std::atomic<int> notifyFail_{0};
    std::atomic<int64_t> lastDrawUs_{0};
    std::atomic<int64_t> lastConvertUs_{0};
};

#endif  // FACEID_ENCODER_SURFACE_RENDERER_H_
