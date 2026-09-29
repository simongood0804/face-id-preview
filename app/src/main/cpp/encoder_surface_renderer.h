#ifndef FACEID_ENCODER_SURFACE_RENDERER_H_
#define FACEID_ENCODER_SURFACE_RENDERER_H_

// media_record P4-C「host-drawn surface」路径的宿主侧渲染器。
//
// 背景：部分车规 Qualcomm 平台的硬件编码器**只接受 surface 输入**（不提供 I420/NV12
// ByteBuffer 格式），此时 CPU 内存路径（mr_session_push_frame）拿不到任何输出
// （stats: encoder_emitted == 0）。库为此提供 surface 路径：调用方用自建 EGL/GLES
// 管线把帧画到编码器输入 surface 上，再 mr_session_notify_frame() 通知引擎从该
// surface 取帧编码。
//
// 线程模型：
//   - Enqueue() 由相机 HAL 回调线程调用：在**帧有效期内**把 UYVY 转成 RGBA 并投递到
//     双缓冲帧槽（上一帧未被取走则丢当前帧，不阻塞取流）；
//   - 内部 GL 线程：取 RGBA 帧 → 2D 纹理上传 → 绘制 → eglSwapBuffers →
//     mr_session_notify_frame；
//   - Stop() 可在任意线程调用：停线程并释放 EGL 资源（幂等）。
//
// 为什么不做 EGLImage 零拷贝（重要）：
//   曾实现过「AHardwareBuffer → EGLImage → samplerExternalOES」的零拷贝路径，但在真机上
//   触发了 vendor gralloc 崩溃（预览 GL 线程在 libqdMetaData.so getMetaDataVa 段错误），
//   原因是同一个相机 gralloc buffer 被预览与推流两条 EGLImage 路径同时引用、且需要跨线程
//   持有该 buffer。改为「回调线程转换 + GL 线程普通纹理上传」后，我们不再触碰相机 buffer
//   的 EGLImage，也不再跨线程持有它，风险面归零。
//   代价：每帧多一次 CPU 色彩转换（拷贝），远小于旧版 mr_session_push_frame 的开销。

#include <android/hardware_buffer.h>
#include <EGL/egl.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

struct mr_session;

class EncoderSurfaceRenderer {
public:
    /// 创建并启动（`window` 为 `mr_session_get_input_surface` 返回的 ANativeWindow*，
    /// `session` 用于每帧 notify）。失败返回 nullptr。
    static EncoderSurfaceRenderer* Create(void* window, int width, int height,
                                          mr_session* session);

    ~EncoderSurfaceRenderer();

    /// 投递一帧（**必须在相机回调线程、HardwareBuffer 有效期内调用**）：
    /// 本方法内部完成 UYVY → RGBA 转换，非阻塞；上一帧尚未绘完则丢当前帧。
    void Enqueue(AHardwareBuffer* buffer, int width, int height, int64_t timestampUs);

    /// 停止 GL 线程并释放 EGL 资源。幂等。
    void Stop();

    // ---- 诊断 ----
    int drawnFrames() const { return drawn_.load(); }
    int droppedFrames() const { return dropped_.load(); }
    int drawFailures() const { return drawFail_.load(); }
    int notifyFailures() const { return notifyFail_.load(); }
    /// 最近一次 GL 绘制 + swap + notify 的耗时（微秒）
    int64_t lastDrawUs() const { return lastDrawUs_.load(); }
    /// 最近一次「UYVY → RGBA」（含 lock）的耗时（微秒），在相机回调线程测量
    int64_t lastConvertUs() const { return lastConvertUs_.load(); }

private:
    EncoderSurfaceRenderer() = default;

    void threadLoop();
    bool initEgl();
    void teardownEgl();
    bool buildPrograms();
    /// 上传 RGBA 并绘制到编码器 surface（srcW/srcH 为有效图像尺寸）
    bool drawRgba(const uint8_t* rgba, int srcW, int srcH);
    /// uMax/vMax 为纹理上有效画面的采样上界
    void drawQuad(int program, int texId, int target, int srcW, int srcH, float uMax,
                  float vMax);

    // ---- EGL 状态（仅 GL 线程访问）----
    void* window_ = nullptr;  // ANativeWindow*
    mr_session* session_ = nullptr;
    /// 库声明的编码器尺寸（mr_session_get_input_surface 返回值）
    int width_ = 0;
    int height_ = 0;
    /// EGL surface 的**真实**尺寸（eglQuerySurface 查询，绘制以它为准）
    int surfaceWidth_ = 0;
    int surfaceHeight_ = 0;

    EGLDisplay display_ = nullptr;
    EGLSurface surface_ = nullptr;
    EGLContext context_ = nullptr;
    EGLConfig config_ = nullptr;

    int program2d_ = 0;
    int tex2d_ = 0;
    int texW_ = 0;  // 已上传纹理的尺寸（用于决定 glTexImage2D / glTexSubImage2D）
    int texH_ = 0;

    // ---- 线程与帧槽（ping-pong）----
    std::mutex mtx_;
    std::condition_variable cv_;
    std::thread thread_;
    std::vector<uint8_t> bufs_[2];  // RGBA 双缓冲
    int writeIdx_ = 0;              // 相机回调线程正在写入的缓冲
    int readyIdx_ = -1;             // 待 GL 线程绘制的缓冲（-1 = 无）
    int readyW_ = 0;
    int readyH_ = 0;
    int64_t readyTsUs_ = 0;
    bool running_ = false;
    bool stop_ = false;

    // ---- 诊断 ----
    std::atomic<int> drawn_{0};
    std::atomic<int> dropped_{0};
    std::atomic<int> drawFail_{0};
    std::atomic<int> notifyFail_{0};
    std::atomic<int64_t> lastDrawUs_{0};
    std::atomic<int64_t> lastConvertUs_{0};
};

#endif  // FACEID_ENCODER_SURFACE_RENDERER_H_
