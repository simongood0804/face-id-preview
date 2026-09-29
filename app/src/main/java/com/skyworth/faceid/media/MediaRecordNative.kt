package com.skyworth.faceid.media

import android.hardware.HardwareBuffer
import android.util.Log

/**
 * media_record 的 JNI 声明层（1:1 对应 `media_record_jni.cpp`）。
 *
 * 仅做类型转换，不含业务逻辑；上层用 [MediaRecordSession]。
 *
 * 加载顺序很重要：`libdatachannel.so` 的 SONAME 是 `libdatachannel.so.0.21`，而
 * `libmedia_record_core_shared.so` 的 DT_NEEDED 记录的正是该 SONAME。Android 的 linker
 * 会按 SONAME 命中**已加载**的库，因此必须先加载 datachannel，再加载 core/JNI，
 * 否则会报 "cannot find libdatachannel.so.0.21"。
 */
internal object MediaRecordNative {

    private const val TAG = "MediaRecordNative"

    init {
        System.loadLibrary("datachannel")
        System.loadLibrary("media_record_core_shared")
        System.loadLibrary("media_record_jni")
        Log.i(TAG, "media_record native libraries loaded")
    }

    // ---- 版本 ----
    external fun nativeLibraryVersion(): IntArray

    // ---- session 生命周期 ----
    /** 打开会话；成功返回 null 并写入 outHandle[0]，失败返回错误文本。 */
    external fun nativeOpen(
        configJson: String,
        optionsJson: String?,
        surfaceMode: Int,
        resident: Int,
        outHandle: LongArray
    ): String?

    external fun nativeStart(handle: Long): Int

    external fun nativeWaitSession(handle: Long, timeoutMs: Int): Int

    external fun nativeStop(handle: Long): Int

    external fun nativeClose(handle: Long): Int

    // ---- 外部帧推送 ----
    /**
     * 推送相机帧。native 侧把 UYVY 转 RGBA 并缩放到 (dstWidth,dstHeight)
     * （引擎要求帧尺寸与 config 的 source 节点一致）；dst <= 0 表示不缩放。
     */
    external fun nativePushHardwareBuffer(
        handle: Long,
        hwBuffer: HardwareBuffer,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        timestampUs: Long
    ): Int

    external fun nativePushUyvyFrame(
        handle: Long,
        data: ByteArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        timestampUs: Long
    ): Int

    /** 结束录制（排空并 finalize 输出文件）。 */
    external fun nativePushFrameEof(handle: Long): Int

    /** 最近一次推帧的分段耗时（微秒）：[lock, 转换+缩放, push]。 */
    external fun nativeGetLastTimings(): LongArray

    // ---- P4-C：编码器输入 surface（host-drawn）路径 ----
    /**
     * 取编码器输入 surface 并启动宿主侧 EGL 渲染器；outSize[0..1] 写入编码器宽高。
     * 仅当会话以 surfaceMode=1 打开时可用。
     */
    external fun nativeSurfaceStart(handle: Long, outSize: IntArray): Int

    /**
     * 投递一帧给 surface 渲染器（相机 HAL 线程；native 侧在帧有效期内完成
     * UYVY→RGBA 转换，非阻塞）。
     */
    external fun nativeSurfaceEnqueue(
        handle: Long,
        hwBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        timestampUs: Long
    ): Int

    external fun nativeSurfaceStop(handle: Long): Int

    /** [drawn, dropped, drawFailures, notifyFailures, lastDrawUs, lastConvertUs]。 */
    external fun nativeSurfaceStats(handle: Long): LongArray

    // ---- 诊断 ----
    external fun nativeIsRunning(handle: Long): Int

    external fun nativeLastError(handle: Long): String

    /** 按 mr_stats 字段顺序导出：[source_beats, encoder_notify, ... , surface_source]。 */
    external fun nativeGetStats(handle: Long): LongArray
}
