#include <jni.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <cstring>

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

    // 仍然返回 UYVY 数据（快速 memcpy，不阻塞 GL 线程）
    // RGB 转换移到算法线程上异步执行，避免卡预览
    int total = height * width * 2;
    jbyteArray result = env->NewByteArray(total);
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
    memcpy(dst, data, total);

    // 黑帧检测
    bool is_black = true;
    const uint8_t *pixels = static_cast<const uint8_t *>(data);
    int cy = height / 2, cx = width / 2;
    for (int dy = -1; dy <= 1 && is_black; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            int row = cy + dy, col = cx + dx;
            if (row < 0 || row >= height || col < 0 || col >= width) continue;
            if (pixels[row * width * 2 + col * 2 + 1] > 10) { is_black = false; break; }
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
