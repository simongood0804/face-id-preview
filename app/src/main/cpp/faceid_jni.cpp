#include <jni.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <cstring>
#include "faceid_api.h"

#define LOG_TAG "FaceID_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================
// Helper: FaceResult → Java byte array (serialized)
// ============================================================

static void fill_result(JNIEnv *env, jobject result, const FaceResult *fr) {
    jclass cls = env->GetObjectClass(result);
    env->SetFloatField(result, env->GetFieldID(cls, "x1", "F"), fr->x1);
    env->SetFloatField(result, env->GetFieldID(cls, "y1", "F"), fr->y1);
    env->SetFloatField(result, env->GetFieldID(cls, "x2", "F"), fr->x2);
    env->SetFloatField(result, env->GetFieldID(cls, "y2", "F"), fr->y2);
    env->SetFloatField(result, env->GetFieldID(cls, "score", "F"), fr->score);
    env->SetFloatField(result, env->GetFieldID(cls, "liveness", "F"), fr->liveness);
}

// ============================================================
// JNI bridge for FaceIDAlgorithmImpl
// ============================================================

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_skyworth_faceid_algorithm_FaceIDAlgorithmImpl_nativeInit(
    JNIEnv *env, jobject /*thiz*/,
    jstring model_dir, jstring runtime) {

    const char *dir = env->GetStringUTFChars(model_dir, nullptr);
    const char *rt = env->GetStringUTFChars(runtime, nullptr);

    FaceIDHandle handle = faceid_init(dir, rt);

    env->ReleaseStringUTFChars(model_dir, dir);
    env->ReleaseStringUTFChars(runtime, rt);

    if (handle == nullptr) {
        LOGE("faceid_init failed: dir=%s, runtime=%s", dir, rt);
        return 0;
    }
    LOGI("faceid_init success: handle=%p", handle);
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT jint JNICALL
Java_com_skyworth_faceid_algorithm_FaceIDAlgorithmImpl_nativeConfigure(
    JNIEnv *env, jobject /*thiz*/,
    jlong handle, jint flags) {

    if (handle == 0) return -1;
    int ret = faceid_configure(reinterpret_cast<FaceIDHandle>(handle),
                               static_cast<uint32_t>(flags));
    LOGI("faceid_configure: flags=%d, ret=%d", flags, ret);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_skyworth_faceid_algorithm_FaceIDAlgorithmImpl_nativeDetect(
    JNIEnv *env, jobject /*thiz*/,
    jlong handle,
    jbyteArray img_data, jint width, jint height, jint stride, jint format,
    jobjectArray results_array, jint max_faces) {

    if (handle == 0) { LOGE("nativeDetect: handle null"); return -1; }

    jbyte *data = env->GetByteArrayElements(img_data, nullptr);
    uint8_t *img = reinterpret_cast<uint8_t *>(data);

    FaceResult results[FACEID_MAX_FACES];
    int n = faceid_detect(reinterpret_cast<FaceIDHandle>(handle),
                          img, width, height, stride,
                          static_cast<FaceIDFormat>(format),
                          results, max_faces, nullptr);

    env->ReleaseByteArrayElements(img_data, data, JNI_ABORT);

    if (n > 0 && results_array != nullptr) {
        jclass rc = env->GetObjectClass(env->GetObjectArrayElement(results_array, 0));
        jfieldID x1_f = env->GetFieldID(rc, "x1", "F");
        jfieldID y1_f = env->GetFieldID(rc, "y1", "F");
        jfieldID x2_f = env->GetFieldID(rc, "x2", "F");
        jfieldID y2_f = env->GetFieldID(rc, "y2", "F");
        jfieldID sc_f = env->GetFieldID(rc, "score", "F");
        jfieldID li_f = env->GetFieldID(rc, "liveness", "F");
        jfieldID emb_f = env->GetFieldID(rc, "emb", "[F");
        jfieldID kps_f = env->GetFieldID(rc, "kps", "[F");
        jfieldID lm_f = env->GetFieldID(rc, "landmarks", "[F");
        jfieldID lmv_f = env->GetFieldID(rc, "landmarksValid", "Z");
        int cnt = n < max_faces ? n : max_faces;
        for (int i = 0; i < cnt; i++) {
            jobject obj = env->GetObjectArrayElement(results_array, i);
            env->SetFloatField(obj, x1_f, results[i].x1);
            env->SetFloatField(obj, y1_f, results[i].y1);
            env->SetFloatField(obj, x2_f, results[i].x2);
            env->SetFloatField(obj, y2_f, results[i].y2);
            env->SetFloatField(obj, sc_f, results[i].score);
            env->SetFloatField(obj, li_f, results[i].liveness);
            // Copy 512-D embedding if valid
            if (results[i].emb_valid && emb_f != nullptr) {
                jfloatArray emb_arr = env->NewFloatArray(512);
                env->SetFloatArrayRegion(emb_arr, 0, 512, results[i].emb);
                env->SetObjectField(obj, emb_f, emb_arr);
            }
            // Copy 5 facial keypoints (flatten float[5][2] → float[10])
            if (kps_f != nullptr) {
                jfloatArray kps_arr = env->NewFloatArray(10);
                float flat[10];
                for (int k = 0; k < 5; k++) {
                    flat[k * 2]     = results[i].kps[k][0];
                    flat[k * 2 + 1] = results[i].kps[k][1];
                }
                env->SetFloatArrayRegion(kps_arr, 0, 10, flat);
                env->SetObjectField(obj, kps_f, kps_arr);
            }
            // Copy 106 dense landmarks (flatten float[106][2] → float[212])
            if (lm_f != nullptr && results[i].landmarks_valid) {
                jfloatArray lm_arr = env->NewFloatArray(212);
                float flat_lm[212];
                for (int k = 0; k < 106; k++) {
                    flat_lm[k * 2]     = results[i].landmarks[k][0];
                    flat_lm[k * 2 + 1] = results[i].landmarks[k][1];
                }
                env->SetFloatArrayRegion(lm_arr, 0, 212, flat_lm);
                env->SetObjectField(obj, lm_f, lm_arr);
            }
            if (lmv_f != nullptr) {
                env->SetBooleanField(obj, lmv_f, results[i].landmarks_valid ? JNI_TRUE : JNI_FALSE);
            }
        }
    }
    return n;
}

JNIEXPORT jfloat JNICALL
Java_com_skyworth_faceid_algorithm_FaceIDAlgorithmImpl_nativeCompare(
    JNIEnv *env, jobject /*thiz*/,
    jfloatArray emb1, jfloatArray emb2) {

    jfloat *e1 = env->GetFloatArrayElements(emb1, nullptr);
    jfloat *e2 = env->GetFloatArrayElements(emb2, nullptr);
    float sim = faceid_compare(e1, e2);
    env->ReleaseFloatArrayElements(emb1, e1, JNI_ABORT);
    env->ReleaseFloatArrayElements(emb2, e2, JNI_ABORT);
    return sim;
}

JNIEXPORT void JNICALL
Java_com_skyworth_faceid_algorithm_FaceIDAlgorithmImpl_nativeDestroy(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    faceid_destroy(reinterpret_cast<FaceIDHandle>(handle));
}

JNIEXPORT jstring JNICALL
Java_com_skyworth_faceid_algorithm_FaceIDAlgorithmImpl_nativeVersion(
    JNIEnv *env, jobject /*thiz*/) {
    return env->NewStringUTF(faceid_version() ?: "unknown");
}

// ============================================================
// HardwareBuffer → raw byte[] (标准 NDK API)
// ============================================================

/** 读取全图（首帧或 fallback）。 */
JNIEXPORT jbyteArray JNICALL
Java_com_skyworth_faceid_ui_PreviewActivity_nativeReadHardwareBuffer(
    JNIEnv *env, jclass /*clazz*/,
    jobject hw_buffer, jint width, jint height) {

    AHardwareBuffer *native_buf = AHardwareBuffer_fromHardwareBuffer(env, hw_buffer);
    if (!native_buf) { LOGE("fromHB failed"); return nullptr; }

    AHardwareBuffer_acquire(native_buf);

    void *data = nullptr;
    int ret = AHardwareBuffer_lock(native_buf,
                                   AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                                   -1, nullptr, &data);
    if (ret != 0) { AHardwareBuffer_release(native_buf); LOGE("lock failed: %d", ret); return nullptr; }

    int total = height * width * 2;
    jbyteArray result = env->NewByteArray(total);
    if (!result) { AHardwareBuffer_unlock(native_buf, nullptr); AHardwareBuffer_release(native_buf); return nullptr; }
    jbyte *dst = env->GetByteArrayElements(result, nullptr);
    if (!dst) { AHardwareBuffer_unlock(native_buf, nullptr); AHardwareBuffer_release(native_buf); return nullptr; }
    memcpy(dst, data, total);

    // 黑帧检测：采样中心 3×3 区域 Y 值，全 ≤ 10 视为黑帧
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

} // extern "C"
