#include <jni.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <cstring>

#include "ahardwarebuffer_util.h"

#define LOG_TAG "HWBufferReader"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_com_skyworth_faceid_core_NativeFrameReader_nativeReadHardwareBuffer(
    JNIEnv *env, jclass /*clazz*/,
    jobject hw_buffer, jint width, jint height) {

    AHardwareBuffer *native_buf = AHardwareBuffer_fromHardwareBuffer(env, hw_buffer);
    if (!native_buf) { LOGE("fromHB failed"); return nullptr; }

    AHardwareBuffer_acquire(native_buf);

    void *data = nullptr;
    int ret = AHardwareBuffer_lock(native_buf,
                                   AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                                   -1, nullptr, &data);
    if (ret != 0) {
        AHardwareBuffer_release(native_buf);
        LOGE("lock failed: %d", ret);
        return nullptr;
    }

    // 相机帧为 UYVY（2 字节/像素）。
    //
    // 关键：AHardwareBuffer 的**分配高度**可能远大于**有效图像高度**
    // （真机实测 1600x3900 的 buffer 里只装了 1600x1300 的画面，有效画面在顶部）。
    // 所以调用方传入的 width/height 是**有效图像尺寸**，这里只读前 height 行，
    // 每行只取 width*2 字节；行间距按 buffer 的真实 stride 走（可能含对齐填充）。
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(native_buf, &desc);
    const int srcStrideBytes = hbRowStrideBytes(desc);
    const int rowBytes = width * 2;

    int rows = height;
    if (srcStrideBytes <= 0 || width <= 0 || height <= 0) {
        AHardwareBuffer_unlock(native_buf, nullptr);
        AHardwareBuffer_release(native_buf);
        LOGE("bad geometry: %dx%d stride=%d", width, height, srcStrideBytes);
        return nullptr;
    }
    if (static_cast<unsigned int>(rows) > desc.height) {
        LOGW("requested %d rows but buffer height=%u, clamping", rows, desc.height);
        rows = static_cast<int>(desc.height);
    }

    const size_t total = static_cast<size_t>(rowBytes) * static_cast<size_t>(rows);
    jbyteArray result = env->NewByteArray(static_cast<jsize>(total));
    if (!result) {
        AHardwareBuffer_unlock(native_buf, nullptr);
        AHardwareBuffer_release(native_buf);
        return nullptr;
    }
    jbyte *dst = env->GetByteArrayElements(result, nullptr);
    if (!dst) {
        AHardwareBuffer_unlock(native_buf, nullptr);
        AHardwareBuffer_release(native_buf);
        return nullptr;
    }

    // 快速拷贝（不阻塞 GL 线程）：RGB 转换在算法线程异步做。
    // 行紧凑时一次 memcpy；有行填充时逐行拷，避免错行。
    const uint8_t *src = static_cast<const uint8_t *>(data);
    if (srcStrideBytes == rowBytes) {
        memcpy(dst, src, total);
    } else {
        for (int y = 0; y < rows; ++y) {
            memcpy(dst + static_cast<size_t>(y) * rowBytes,
                   src + static_cast<size_t>(y) * srcStrideBytes,
                   static_cast<size_t>(rowBytes));
        }
    }

    // 黑帧检测：采样中心 3x3 的 **Y0（亮度）** 分量（UYVY 布局 [U][Y0][V][Y1]，
    // 故每像素对的第 1 字节是亮度；仅修正 stride 寻址，语义不变）
    bool is_black = true;
    const int cy = rows / 2;
    const int cx = width / 2;
    for (int dy = -1; dy <= 1 && is_black; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            const int row = cy + dy;
            const int col = cx + dx;
            if (row < 0 || row >= rows || col < 0 || col >= width) continue;
            const uint8_t *p = src + static_cast<size_t>(row) * srcStrideBytes +
                               static_cast<size_t>(col) * 2;
            if (p[1] > 10) { is_black = false; break; }
        }
    }

    env->ReleaseByteArrayElements(result, dst, 0);
    AHardwareBuffer_unlock(native_buf, nullptr);
    AHardwareBuffer_release(native_buf);

    if (is_black) {
        LOGW("black frame detected %dx%d, dropping", width, height);
        env->DeleteLocalRef(result);
        return nullptr;
    }
    return result;
}

}
