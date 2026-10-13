// media_record JNI 封装：把 mr_* C ABI 暴露给 Kotlin（MediaRecordNative）。
//
// 职责：
//   - session 生命周期：open / start / wait / stop / close；
//   - 外部帧推送（CPU 路径）：把相机 HardwareBuffer（UYVY）转 RGBA 后 mr_session_push_frame；
//   - 编码器输入 surface（P4-C surface 路径）：get_input_surface + 自建 EGL 管线绘制
//     + notify_frame（见 encoder_surface_renderer.*）；
//   - 结束录制：mr_session_push_frame_eof（唯一能正常 finalize MP4 的方式）；
//   - 诊断：is_running / last_error / stats / library_version。
//
// 线程：push/enqueue 由相机 HAL 回调线程调用；其余由 UI/工作线程调用。

#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "ahardwarebuffer_util.h"
#include "encoder_surface_renderer.h"
#include "media_record/media_record_driver.h"

#define LOG_TAG "MediaRecordJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

mr_session* asSession(jlong handle) {
    return reinterpret_cast<mr_session*>(static_cast<intptr_t>(handle));
}

inline uint8_t clamp8(int v) {
    return static_cast<uint8_t>(v < 0 ? 0 : (v > 255 ? 255 : v));
}

// BT.601 limited-range YUV -> RGBA（写 4 字节/像素，A=255）。
inline void yuv2rgba(int y, int u, int v, uint8_t* out) {
    const int c = y - 16;
    const int d = u - 128;
    const int e = v - 128;
    out[0] = clamp8((298 * c + 409 * e + 128) >> 8);          // R
    out[1] = clamp8((298 * c - 100 * d - 208 * e + 128) >> 8); // G
    out[2] = clamp8((298 * c + 516 * d + 128) >> 8);           // B
    out[3] = 255;                                              // A
}

// UYVY（**U Y0 V Y1**，每 2 像素 4 字节）-> RGBA，**同时缩放到目标尺寸**。
//
// 字节序注意：U 在第 0 字节（见 docs/FaceID_SO对接说明.md 与算法路径
// algo/.../FrameProcessor.kt#convertUyvyToRgb888）。按 [Y0][U][Y1][V] 读会得到
// 「整幅纯绿、但轮廓清晰」的错误画面。
//
// 合并「色彩转换 + 缩放」为一步：直接按目标分辨率采样源图，避免先生成
// srcWidth×srcHeight 的中间 RGBA 再缩放（省掉一次大缓冲的内存带宽）。
// 采样用最近邻（缩小场景足够，且确定性好、不阻塞相机回调线程）；
// 目标->源的坐标映射按尺寸缓存，避免逐像素除法。
//
// srcStrideBytes 为源每行字节数（含对齐填充）；输出为紧凑 dstWidth*4 步长。
void uyvyToRgbaScaled(const uint8_t* src, int srcStrideBytes, uint8_t* dst,
                      int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
    if (srcWidth < 2 || srcHeight < 1 || dstWidth < 1 || dstHeight < 1) return;

    // 目标列 -> 源列（对齐到 UYVY 的 2 像素组，保证能取到完整的 Y0/U/V）
    static thread_local std::vector<int> colMap;
    static thread_local std::vector<int> rowMap;
    static thread_local std::vector<uint8_t> rowBuf;  // cacheable 行缓冲

    colMap.resize(dstWidth);
    for (int x = 0; x < dstWidth; ++x) {
        const int sx = static_cast<int>(static_cast<long long>(x) * srcWidth / dstWidth);
        colMap[x] = sx & ~1;
    }
    rowMap.resize(dstHeight);
    for (int y = 0; y < dstHeight; ++y) {
        int sy = static_cast<int>(static_cast<long long>(y) * srcHeight / dstHeight);
        if (sy >= srcHeight) sy = srcHeight - 1;
        rowMap[y] = sy;
    }
    if (static_cast<int>(rowBuf.size()) < srcStrideBytes) {
        rowBuf.resize(srcStrideBytes);
    }

    // 关键优化：HardwareBuffer lock 得到的内存多为**非 cacheable**映射，逐像素随机读
    // 延迟极高（实测 ~180ns/次，整帧 160ms+）。改为**按源行用 memcpy 宽加载**（编译器
    // 会生成 16B NEON 拷贝）读入本地 cacheable 行缓冲，再做水平采样+转换；
    // 同一源行被多个目标行复用时只拷一次。这样把慢内存访问次数降低约一个数量级。
    const int maxSx = srcWidth - 2;  // 保证 p[0..3] 完整
    int cachedSy = -1;
    for (int y = 0; y < dstHeight; ++y) {
        const int sy = rowMap[y];
        if (sy != cachedSy) {
            memcpy(rowBuf.data(), src + static_cast<size_t>(sy) * srcStrideBytes,
                   static_cast<size_t>(srcStrideBytes));
            cachedSy = sy;
        }
        const uint8_t* r = rowBuf.data();
        uint8_t* drow = dst + static_cast<size_t>(y) * dstWidth * 4;
        for (int x = 0; x < dstWidth; ++x) {
            int sx = colMap[x];
            if (sx > maxSx) sx = maxSx;
            const uint8_t* p = r + static_cast<size_t>(sx) * 2;
            // UYVY: [U][Y0][V][Y1] -> 取左像素 (Y0, U, V)
            yuv2rgba(p[1], p[0], p[2], drow + static_cast<size_t>(x) * 4);
        }
    }
}

// 复用 RGBA 转换缓冲（push 在相机回调单线程上调用）。
std::vector<uint8_t>& rgbaScratch(size_t need) {
    static thread_local std::vector<uint8_t> buf;
    if (buf.size() < need) buf.resize(need);
    return buf;
}

// ---- 分段耗时诊断 ----------------------------------------------------------
// 用于定位推帧瓶颈：AHardwareBuffer_lock（GPU->CPU 同步）/ 色彩转换+缩放 / push。
// 由 push 所在线程写入，UI 侧紧接着在同一线程读取（thread_local 即可）。
inline long long nowUs() {
    using namespace std::chrono;
    return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
}

thread_local long long g_lastLockUs = 0;
thread_local long long g_lastConvertUs = 0;
thread_local long long g_lastPushUs = 0;

// ---- 异步推送器 -----------------------------------------------------------
// 把耗时的 mr_session_push_frame（实测 ~92ms，库内部拷贝/同步）移出相机 HAL
// 回调线程，避免阻塞取流：HAL 只做「转换+缩放」并投递到双缓冲，后台线程负责 push。
// ping-pong 双缓冲：HAL 写 writeIdx，后台读 readyIdx，互不干扰；
// 若上一帧后台尚未取走，则丢弃当前帧（推流可丢帧，取流不可阻塞）。
struct AsyncPushState {
    std::mutex mtx;
    std::condition_variable cv;
    std::thread worker;

    std::vector<uint8_t> bufs[2];
    int writeIdx = 0;   // HAL 正在写入的缓冲
    int readyIdx = -1;  // 待后台推送的缓冲（-1 = 无）

    int width = 0;
    int height = 0;
    int64_t tsUs = 0;

    bool started = false;
    bool stop = false;
    mr_session* session = nullptr;

    std::atomic<long long> pushed{0};
    std::atomic<long long> dropped{0};
    std::atomic<long long> lastPushUs{0};
};

AsyncPushState g_async;

void asyncWorkerLoop() {
    for (;;) {
        int idx = -1;
        {
            std::unique_lock<std::mutex> lk(g_async.mtx);
            g_async.cv.wait(lk, [] { return g_async.stop || g_async.readyIdx >= 0; });
            if (g_async.stop) return;
            idx = g_async.readyIdx;
            g_async.readyIdx = -1;
        }
        if (idx < 0) continue;

        mr_session* s = nullptr;
        int w = 0, h = 0;
        int64_t ts = 0;
        {
            std::lock_guard<std::mutex> lk(g_async.mtx);
            s = g_async.session;
            w = g_async.width;
            h = g_async.height;
            ts = g_async.tsUs;
        }
        if (s == nullptr || w <= 0 || h <= 0) continue;

        const long long t0 = nowUs();
        const int rc = mr_session_push_frame(s, g_async.bufs[idx].data(), w * 4, w, h, ts);
        g_async.lastPushUs = nowUs() - t0;
        if (rc == MR_OK) g_async.pushed++;
    }
}

/// 确保异步推送器已按当前会话/尺寸启动（会话或尺寸变化时重启）。
void asyncEnsureStarted(mr_session* session, int width, int height) {
    if (g_async.started && g_async.session == session &&
        g_async.width == width && g_async.height == height) {
        return;
    }
    {
        std::lock_guard<std::mutex> lk(g_async.mtx);
        g_async.stop = true;
    }
    g_async.cv.notify_all();
    if (g_async.worker.joinable()) g_async.worker.join();

    {
        std::lock_guard<std::mutex> lk(g_async.mtx);
        g_async.session = session;
        g_async.width = width;
        g_async.height = height;
        g_async.writeIdx = 0;
        g_async.readyIdx = -1;
        g_async.stop = false;
        g_async.bufs[0].resize(static_cast<size_t>(width) * height * 4);
        g_async.bufs[1].resize(static_cast<size_t>(width) * height * 4);
        g_async.started = true;
    }
    g_async.worker = std::thread(asyncWorkerLoop);
    LOGI("async pusher started: %dx%d", width, height);
}

/// 停止异步推送器（须在 close 会话前调用，确保后台不再触碰 session）。
void asyncStop() {
    {
        std::lock_guard<std::mutex> lk(g_async.mtx);
        g_async.stop = true;
        g_async.session = nullptr;
    }
    g_async.cv.notify_all();
    if (g_async.worker.joinable()) g_async.worker.join();
    {
        std::lock_guard<std::mutex> lk(g_async.mtx);
        g_async.started = false;
        g_async.readyIdx = -1;
        g_async.bufs[0].clear();
        g_async.bufs[0].shrink_to_fit();
        g_async.bufs[1].clear();
        g_async.bufs[1].shrink_to_fit();
    }
}

// ---- surface 路径（P4-C）---------------------------------------------------
// 同一时刻仅一个 surface 会话；渲染器内部自带 GL 线程，这里只保护指针切换。
std::mutex g_surfaceMutex;
EncoderSurfaceRenderer* g_surfaceRenderer = nullptr;
mr_session* g_surfaceSession = nullptr;

/// 停止并销毁 surface 渲染器（须在 eof/stop/close 之前调用，避免 eof 后仍有 notify）。
void surfaceStopInternal() {
    std::lock_guard<std::mutex> lk(g_surfaceMutex);
    if (g_surfaceRenderer != nullptr) {
        g_surfaceRenderer->Stop();
        delete g_surfaceRenderer;
        g_surfaceRenderer = nullptr;
        g_surfaceSession = nullptr;
    }
}

}  // namespace

extern "C" {

// ------------------------------------------------------------------
// 版本
// ------------------------------------------------------------------
JNIEXPORT jintArray JNICALL
Java_com_skyworth_faceid_media_MediaRecordNative_nativeLibraryVersion(
    JNIEnv* env, jobject /*thiz*/) {
    int major = 0, minor = 0, patch = 0;
    const int rc = mr_library_version(&major, &minor, &patch);
    if (rc != MR_OK) {
        LOGE("mr_library_version failed: %d", rc);
    }
    jintArray arr = env->NewIntArray(3);
    if (arr == nullptr) return nullptr;
    const jint vals[3] = {major, minor, patch};
    env->SetIntArrayRegion(arr, 0, 3, vals);
    return arr;
}

// ------------------------------------------------------------------
// session 生命周期
// ------------------------------------------------------------------

/// 打开会话。成功返回 null（并把 handle 写入 outHandle[0]）；失败返回错误文本。
JNIEXPORT jstring JNICALL
Java_com_skyworth_faceid_media_MediaRecordNative_nativeOpen(
    JNIEnv* env, jobject /*thiz*/,
    jstring configJson, jstring optionsJson, jint surfaceMode, jint resident,
    jlongArray outHandle) {
    if (configJson == nullptr || outHandle == nullptr) {
        return env->NewStringUTF("configJson/outHandle is null");
    }
    const char* cfg = env->GetStringUTFChars(configJson, nullptr);
    const char* opt = (optionsJson != nullptr)
                          ? env->GetStringUTFChars(optionsJson, nullptr)
                          : nullptr;

    char err[512];
    err[0] = '\0';
    mr_session* session = nullptr;
    const int rc = mr_session_open(cfg, opt, surfaceMode, resident, &session,
                                   err, sizeof(err));

    env->ReleaseStringUTFChars(configJson, cfg);
    if (opt != nullptr) env->ReleaseStringUTFChars(optionsJson, opt);

    if (rc != MR_OK) {
        LOGE("mr_session_open failed: %d (%s)", rc, err);
        if (err[0] == '\0') snprintf(err, sizeof(err), "mr_session_open rc=%d", rc);
        return env->NewStringUTF(err);
    }
    const jlong handleValue = static_cast<jlong>(reinterpret_cast<intptr_t>(session));
    env->SetLongArrayRegion(outHandle, 0, 1, &handleValue);
    LOGI("mr_session_open ok, handle=%p", static_cast<void*>(session));
    return nullptr;
}

JNIEXPORT jint JNICALL
Java_com_skyworth_faceid_media_MediaRecordNative_nativeStart(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    return mr_session_start(asSession(handle));
}

JNIEXPORT jint JNICALL
Java_com_skyworth_faceid_media_MediaRecordNative_nativeWaitSession(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint timeoutMs) {
    return mr_session_wait(asSession(handle), timeoutMs);
}

JNIEXPORT jint JNICALL
Java_com_skyworth_faceid_media_MediaRecordNative_nativeStop(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    surfaceStopInternal();  // 先停 surface 渲染线程（join GL 线程）
    asyncStop();            // 再停后台推送，避免继续向已中止的会话推帧
    return mr_session_stop(asSession(handle));
}

JNIEXPORT jint JNICALL
Java_com_skyworth_faceid_media_MediaRecordNative_nativeClose(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    // 必须先停止异步推送器 / surface 渲染器（join 后台线程），确保它们不再触碰该
    // session，再 close。
    surfaceStopInternal();
    asyncStop();
    return mr_session_close(asSession(handle));
}

// ------------------------------------------------------------------
// 外部帧推送
// ------------------------------------------------------------------

/// 推送相机 HardwareBuffer（UYVY）一帧：native 侧转 RGBA 并缩放到 (dstWidth,dstHeight)
/// 后交给引擎（引擎要求帧尺寸与 config 的 source 节点声明一致）。
/// dstWidth/dstHeight <= 0 时表示不缩放（保持源尺寸）。
JNIEXPORT jint JNICALL
Java_com_skyworth_faceid_media_MediaRecordNative_nativePushHardwareBuffer(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jobject hwBuffer,
    jint srcWidth, jint srcHeight, jint dstWidth, jint dstHeight,
    jlong timestampUs) {
    mr_session* session = asSession(handle);
    if (session == nullptr || hwBuffer == nullptr || srcWidth <= 0 || srcHeight <= 0) {
        return MR_ERROR_INVALID_ARGUMENT;
    }
    const int outW = (dstWidth > 0) ? dstWidth : srcWidth;
    const int outH = (dstHeight > 0) ? dstHeight : srcHeight;

    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, hwBuffer);
    if (buf == nullptr) {
        LOGE("AHardwareBuffer_fromHardwareBuffer failed");
        return MR_ERROR_INVALID_ARGUMENT;
    }

    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buf, &desc);
    // 行字节数（真机 stride 是字节；见 ahardwarebuffer_util.h）。
    // 注意 buffer 可能远高于有效图像（实测 1600x3900 只装 1600x1300），
    // 有效画面在顶部，故按 srcHeight 行读取是正确的。
    const int srcStrideBytes = hbRowStrideBytes(desc);
    if (srcStrideBytes <= 0) return MR_ERROR_INVALID_ARGUMENT;

    void* data = nullptr;
    const long long t0 = nowUs();
    AHardwareBuffer_acquire(buf);
    const int lockRc = AHardwareBuffer_lock(buf,
                                            AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                                            -1, nullptr, &data);
    const long long t1 = nowUs();
    if (lockRc != 0 || data == nullptr) {
        AHardwareBuffer_release(buf);
        LOGE("AHardwareBuffer_lock failed: %d", lockRc);
        return MR_ERROR_RUNTIME;
    }

    // 确保异步推送器就绪（按目标尺寸分配双缓冲）
    asyncEnsureStarted(session, outW, outH);

    // 取一块空闲写缓冲；若上一帧后台尚未取走（ready 未消费）则丢弃本帧。
    // 注意：ping-pong 保证 HAL 写入的缓冲与后台正在推的缓冲不是同一块。
    uint8_t* dstBuf = nullptr;
    int submitIdx = -1;
    {
        std::lock_guard<std::mutex> lk(g_async.mtx);
        if (g_async.readyIdx >= 0 || g_async.bufs[0].empty()) {
            g_async.dropped++;
            AHardwareBuffer_unlock(buf, nullptr);
            AHardwareBuffer_release(buf);
            return MR_ERROR_OVERFLOW;
        }
        submitIdx = g_async.writeIdx;
        dstBuf = g_async.bufs[submitIdx].data();
    }

    // 耗时部分（GPU->CPU 宽加载 + 色彩转换 + 缩放）仍在 HAL 线程完成
    uyvyToRgbaScaled(static_cast<const uint8_t*>(data), srcStrideBytes,
                     dstBuf, srcWidth, srcHeight, outW, outH);
    const long long t2 = nowUs();

    AHardwareBuffer_unlock(buf, nullptr);
    AHardwareBuffer_release(buf);

    // 投递给后台线程执行 mr_session_push_frame（耗时 ~92ms，不再阻塞取流）
    {
        std::lock_guard<std::mutex> lk(g_async.mtx);
        g_async.readyIdx = submitIdx;
        g_async.writeIdx ^= 1;
        g_async.tsUs = timestampUs;
    }
    g_async.cv.notify_one();

    // 分段耗时（微秒）：lock(GPU->CPU 同步) / 转换+缩放 / push(后台，最近一次)
    g_lastLockUs = t1 - t0;
    g_lastConvertUs = t2 - t1;
    g_lastPushUs = g_async.lastPushUs.load();
    return MR_OK;
}

/// 最近一次推帧的分段耗时（微秒）：[lock, convert+scale, push]，供 UI 诊断。
JNIEXPORT jlongArray JNICALL
Java_com_skyworth_faceid_media_MediaRecordNative_nativeGetLastTimings(
    JNIEnv* env, jobject /*thiz*/) {
    const jlong vals[3] = {g_lastLockUs, g_lastConvertUs, g_lastPushUs};
    jlongArray arr = env->NewLongArray(3);
    if (arr == nullptr) return nullptr;
    env->SetLongArrayRegion(arr, 0, 3, vals);
    return arr;
}

/// 推送一帧 UYVY 字节（备用路径；数据需为 srcWidth*srcHeight*2 紧凑排列）。
/// 同样支持缩放到 (dstWidth,dstHeight)。
JNIEXPORT jint JNICALL
Java_com_skyworth_faceid_media_MediaRecordNative_nativePushUyvyFrame(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jbyteArray data,
    jint srcWidth, jint srcHeight, jint dstWidth, jint dstHeight,
    jlong timestampUs) {
    mr_session* session = asSession(handle);
    if (session == nullptr || data == nullptr || srcWidth <= 0 || srcHeight <= 0) {
        return MR_ERROR_INVALID_ARGUMENT;
    }
    const int outW = (dstWidth > 0) ? dstWidth : srcWidth;
    const int outH = (dstHeight > 0) ? dstHeight : srcHeight;

    const jsize len = env->GetArrayLength(data);
    const size_t expected = static_cast<size_t>(srcWidth) * srcHeight * 2;
    if (static_cast<size_t>(len) < expected) {
        LOGE("pushUyvy: data too small (%d < %zu)", len, expected);
        return MR_ERROR_INVALID_ARGUMENT;
    }

    jbyte* src = env->GetByteArrayElements(data, nullptr);
    if (src == nullptr) return MR_ERROR_RUNTIME;

    const size_t need = static_cast<size_t>(outW) * outH * 4;
    std::vector<uint8_t>& rgba = rgbaScratch(need);
    uyvyToRgbaScaled(reinterpret_cast<const uint8_t*>(src), srcWidth * 2,
                     rgba.data(), srcWidth, srcHeight, outW, outH);

    env->ReleaseByteArrayElements(data, src, JNI_ABORT);

    return mr_session_push_frame(session, rgba.data(), outW * 4,
                                 outW, outH, timestampUs);
}

/// 结束录制：排空并 finalize MP4（唯一正常收尾方式）。
JNIEXPORT jint JNICALL
Java_com_skyworth_faceid_media_MediaRecordNative_nativePushFrameEof(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    // 先停 surface 渲染线程 + 后台推送（避免 eof 之后还有 push/notify 进入），
    // 再发 eof 收尾。
    surfaceStopInternal();
    asyncStop();
    return mr_session_push_frame_eof(asSession(handle));
}

// ------------------------------------------------------------------
// 诊断
// ------------------------------------------------------------------

JNIEXPORT jint JNICALL
Java_com_skyworth_faceid_media_MediaRecordNative_nativeIsRunning(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    int running = 0;
    const int rc = mr_session_is_running(asSession(handle), &running);
    return (rc == MR_OK) ? running : -1;
}

JNIEXPORT jstring JNICALL
Java_com_skyworth_faceid_media_MediaRecordNative_nativeLastError(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    char buf[512];
    buf[0] = '\0';
    mr_session_last_error(asSession(handle), buf, sizeof(buf));
    return env->NewStringUTF(buf);
}

/// stats：按 mr_stats 字段顺序导出为 long[]（便于 Kotlin 诊断展示）。
JNIEXPORT jlongArray JNICALL
Java_com_skyworth_faceid_media_MediaRecordNative_nativeGetStats(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    mr_stats st;
    memset(&st, 0, sizeof(st));
    const int rc = mr_session_get_stats(asSession(handle), &st);
    if (rc != MR_OK) {
        LOGE("mr_session_get_stats failed: %d", rc);
    }
    // 索引 0~11：原有字段（offset 未变）；12~21：库 2026-10-08 追加的推流侧健康字段。
    // Kotlin 端按下标读取，故顺序必须与 mr_stats 声明一致。
    const jlong vals[22] = {
        st.source_beats,
        st.encoder_notify,
        st.encoder_polls,
        st.encoder_poll_failures,
        st.encoder_last_poll_status,
        st.encoder_emitted,
        st.first_keyframe_has_sps,
        st.first_keyframe_has_pps,
        st.first_keyframe_size,
        st.muxer_tmpfile_ok,
        st.muxer_tmpfile_errno,
        st.surface_source,
        st.push_present,
        st.push_active,
        st.push_state,
        st.push_frames_sent,
        st.push_frames_dropped,
        st.push_bytes_sent,
        st.push_rtt_ms,
        st.push_packet_loss_pct_x100,
        st.push_uptime_s,
        st.push_bitrate_kbps,
    };
    jlongArray arr = env->NewLongArray(22);
    if (arr == nullptr) return nullptr;
    env->SetLongArrayRegion(arr, 0, 22, vals);
    return arr;
}

// ------------------------------------------------------------------
// P4-C：编码器输入 surface（host-drawn）路径
// ------------------------------------------------------------------

/// 取编码器输入 surface（ANativeWindow*）并启动宿主侧 EGL 渲染器。
/// outSize[0..1] 写入编码器配置的宽高。返回 MR_OK / 负 mr_status。
JNIEXPORT jint JNICALL
Java_com_skyworth_faceid_media_MediaRecordNative_nativeSurfaceStart(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jintArray outSize) {
    mr_session* session = asSession(handle);
    if (session == nullptr) return MR_ERROR_INVALID_ARGUMENT;

    void* window = nullptr;
    int w = 0;
    int h = 0;
    const int rc = mr_session_get_input_surface(session, &window, &w, &h);
    if (rc != MR_OK) {
        LOGE("mr_session_get_input_surface failed: rc=%d", rc);
        return rc;
    }
    if (window == nullptr || w <= 0 || h <= 0) {
        LOGE("input surface invalid: window=%p %dx%d", window, w, h);
        return MR_ERROR_RUNTIME;
    }

    surfaceStopInternal();  // 替换旧渲染器（通常为 null）

    EncoderSurfaceRenderer* renderer = EncoderSurfaceRenderer::Create(window, w, h, session);
    if (renderer == nullptr) {
        LOGE("EncoderSurfaceRenderer::Create failed");
        return MR_ERROR_RUNTIME;
    }
    {
        std::lock_guard<std::mutex> lk(g_surfaceMutex);
        g_surfaceRenderer = renderer;
        g_surfaceSession = session;
    }
    if (outSize != nullptr) {
        const jint vals[2] = {w, h};
        env->SetIntArrayRegion(outSize, 0, 2, vals);
    }
    LOGI("surface renderer ready: %dx%d", w, h);
    return MR_OK;
}

/// 投递一帧（相机 HAL 回调线程）：native 侧在帧有效期内完成 UYVY→RGBA 转换，
/// 再交给内部 GL 线程做纹理上传/绘制，非阻塞。
JNIEXPORT jint JNICALL
Java_com_skyworth_faceid_media_MediaRecordNative_nativeSurfaceEnqueue(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jobject hwBuffer, jint width, jint height,
    jlong timestampUs) {
    mr_session* session = asSession(handle);
    if (session == nullptr || hwBuffer == nullptr || width <= 0 || height <= 0) {
        return MR_ERROR_INVALID_ARGUMENT;
    }
    AHardwareBuffer* buffer = AHardwareBuffer_fromHardwareBuffer(env, hwBuffer);
    if (buffer == nullptr) {
        LOGE("AHardwareBuffer_fromHardwareBuffer failed");
        return MR_ERROR_INVALID_ARGUMENT;
    }
    std::lock_guard<std::mutex> lk(g_surfaceMutex);
    if (g_surfaceRenderer == nullptr || g_surfaceSession != session) {
        return MR_ERROR_STATE;
    }
    g_surfaceRenderer->Enqueue(buffer, width, height, timestampUs);
    return MR_OK;
}

/// 停止并释放 surface 渲染器（幂等）。
JNIEXPORT jint JNICALL
Java_com_skyworth_faceid_media_MediaRecordNative_nativeSurfaceStop(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong /*handle*/) {
    surfaceStopInternal();
    return MR_OK;
}

/// surface 渲染诊断：
/// [drawn, dropped, drawFailures, notifyFailures, lastDrawUs, lastConvertUs, zeroCopy]。
JNIEXPORT jlongArray JNICALL
Java_com_skyworth_faceid_media_MediaRecordNative_nativeSurfaceStats(
    JNIEnv* env, jobject /*thiz*/, jlong /*handle*/) {
    jlong vals[7] = {0, 0, 0, 0, 0, 0, 0};
    {
        std::lock_guard<std::mutex> lk(g_surfaceMutex);
        if (g_surfaceRenderer != nullptr) {
            vals[0] = g_surfaceRenderer->drawnFrames();
            vals[1] = g_surfaceRenderer->droppedFrames();
            vals[2] = g_surfaceRenderer->drawFailures();
            vals[3] = g_surfaceRenderer->notifyFailures();
            vals[4] = g_surfaceRenderer->lastDrawUs();
            vals[5] = g_surfaceRenderer->lastConvertUs();
            vals[6] = g_surfaceRenderer->zeroCopy();
        }
    }
    jlongArray arr = env->NewLongArray(7);
    if (arr == nullptr) return nullptr;
    env->SetLongArrayRegion(arr, 0, 7, vals);
    return arr;
}

}  // extern "C"
