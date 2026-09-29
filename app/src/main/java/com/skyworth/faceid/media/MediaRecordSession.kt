package com.skyworth.faceid.media

import android.hardware.HardwareBuffer
import android.util.Log

/**
 * media_record 会话封装（录制 / 推流管线）。
 *
 * 生命周期（外部帧源管线）：
 * ```
 * val s = MediaRecordSession.open(configJson, resident = 1)
 * s.start()
 * // 每帧：s.pushHardwareBuffer(hwBuffer, w, h, tsUs)   // 相机回调线程
 * s.pushEof()      // 结束并 finalize（唯一正常收尾方式）
 * s.waitSession(-1)
 * s.close()
 * ```
 *
 * 注意：**不要用 [stop] 结束录制**——它会中止图、muxer 不 finalize，输出为 0 字节；
 * [stop] 仅用于放弃本次会话。
 */
class MediaRecordSession private constructor(private var handle: Long) {

    /** 会话是否有效（已成功 open 且未 close）。 */
    val isValid: Boolean
        get() = handle != 0L

    /** 开始异步执行（立即返回）。 */
    fun start(): Int = withHandle { MediaRecordNative.nativeStart(it) }

    /** 阻塞等待终止；timeoutMs < 0 表示一直等待。 */
    fun waitSession(timeoutMs: Int): Int =
        withHandle { MediaRecordNative.nativeWaitSession(it, timeoutMs) }

    /**
     * 推送一帧相机 HardwareBuffer。
     *
     * native 侧把 UYVY 转 RGBA 并**缩放到 (dstWidth,dstHeight)**——引擎要求帧尺寸与
     * config 的 source 节点声明一致；dstWidth/dstHeight <= 0 表示不缩放（保持源尺寸）。
     * 非阻塞、线程安全；返回 [MR_ERROR_OVERFLOW] 表示消费慢于生产（可丢帧/重试）。
     */
    fun pushHardwareBuffer(
        buffer: HardwareBuffer,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        timestampUs: Long
    ): Int = withHandle {
        MediaRecordNative.nativePushHardwareBuffer(
            it, buffer, srcWidth, srcHeight, dstWidth, dstHeight, timestampUs
        )
    }

    /** 推送一帧 UYVY 紧凑字节（备用路径，同样支持缩放到目标尺寸）。 */
    fun pushUyvyFrame(
        data: ByteArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        timestampUs: Long
    ): Int = withHandle {
        MediaRecordNative.nativePushUyvyFrame(
            it, data, srcWidth, srcHeight, dstWidth, dstHeight, timestampUs
        )
    }

    /** 结束录制：排空队列并 finalize 输出文件。 */
    fun pushEof(): Int = withHandle { MediaRecordNative.nativePushFrameEof(it) }

    // ---- P4-C：编码器输入 surface（host-drawn）路径 ----------------------------
    //
    // 适用：硬件编码器只接受 surface 输入（不提供 I420/NV12 ByteBuffer 格式），
    // CPU 推送路径拿不到输出（stats: encoder_emitted == 0）。
    //
    // 用法（会话须以 surfaceMode = 1 打开，且 resident = 1）：
    // ```
    // val s = MediaRecordSession.open(cfg, surfaceMode = 1, resident = 1)
    // s.start()
    // val (w, h) = s.startSurface()!!           // 取编码器输入 surface，启动 GL 渲染器
    // // 相机回调线程：s.enqueueToSurface(hwBuffer, camW, camH, tsUs)
    // s.pushEof()                                // 必须：finalize MP4
    // s.waitSession(-1); s.close()
    // ```

    /**
     * 取编码器输入 surface 并启动宿主侧 EGL 渲染器（把相机帧直接画入该 surface）。
     *
     * @return 成功返回编码器尺寸 `(width, height)`；失败返回 null（原因见 logcat）。
     */
    fun startSurface(): Pair<Int, Int>? {
        val h = handle
        if (h == 0L) return null
        val out = IntArray(2)
        val rc = MediaRecordNative.nativeSurfaceStart(h, out)
        return if (rc == MR_OK && out[0] > 0 && out[1] > 0) {
            Log.i(TAG, "startSurface ok: ${out[0]}x${out[1]}")
            out[0] to out[1]
        } else {
            Log.e(TAG, "startSurface failed: rc=$rc")
            null
        }
    }

    /**
     * 投递一帧相机 HardwareBuffer 给 surface 渲染器（相机 HAL 回调线程调用）。
     *
     * 非阻塞：native 侧在**帧有效期内**完成 UYVY → RGBA 转换，交给内部 GL 线程上传纹理、
     * 绘制到编码器输入 surface 并 `mr_session_notify_frame`；上一帧未绘完则丢弃当前帧。
     *
     * 注意：转换必须在回调线程内完成（HardwareBuffer 只在回调期间有效），因此本方法
     * **不可**把 buffer 存起来延后处理。
     */
    fun enqueueToSurface(
        buffer: HardwareBuffer,
        width: Int,
        height: Int,
        timestampUs: Long
    ): Int = withHandle {
        MediaRecordNative.nativeSurfaceEnqueue(it, buffer, width, height, timestampUs)
    }

    /** 停止并释放 surface 渲染器（幂等；[pushEof] 会自动调用）。 */
    fun stopSurface(): Int = withHandle { MediaRecordNative.nativeSurfaceStop(it) }

    /**
     * surface 渲染诊断：
     * `[drawn, dropped, drawFailures, notifyFailures, lastDrawUs, lastConvertUs]`（后两项微秒）。
     */
    fun surfaceStats(): LongArray? =
        if (handle != 0L) MediaRecordNative.nativeSurfaceStats(handle) else null

    /**
     * 最近一次 [pushHardwareBuffer] 的分段耗时（微秒）：`[lock, 转换+缩放, push]`。
     * 用于诊断瓶颈（需与 push 在同一线程读取）。
     */
    fun lastTimings(): LongArray? =
        if (handle != 0L) MediaRecordNative.nativeGetLastTimings() else null

    /** 图是否仍在运行（-1 表示查询失败）。 */
    fun isRunning(): Int = withHandle { MediaRecordNative.nativeIsRunning(it) }

    /** 最近一次错误文本。 */
    fun lastError(): String = withHandleOr("session closed") {
        MediaRecordNative.nativeLastError(it)
    }

    /** 诊断计数器（按 mr_stats 字段顺序）。 */
    fun stats(): LongArray? = if (handle != 0L) {
        MediaRecordNative.nativeGetStats(handle)
    } else {
        null
    }

    /** 放弃本次会话（**不** finalize 输出）。要正常收尾请用 [pushEof]。 */
    fun stop(): Int = withHandle { MediaRecordNative.nativeStop(it) }

    /** 释放会话（幂等；若仍在运行会先停止）。 */
    fun close() {
        if (handle != 0L) {
            val rc = MediaRecordNative.nativeClose(handle)
            Log.i(TAG, "close: rc=$rc")
            handle = 0L
        }
    }

    private inline fun withHandle(block: (Long) -> Int): Int {
        val h = handle
        return if (h != 0L) block(h) else MR_ERROR_STATE
    }

    private inline fun withHandleOr(default: String, block: (Long) -> String): String {
        val h = handle
        return if (h != 0L) block(h) else default
    }

    companion object {
        private const val TAG = "MediaRecordSession"

        // ---- mr_status 常量（与 media_record_driver.h 对齐）----
        const val MR_OK = 0
        const val MR_WAIT_TIMEOUT = 1
        const val MR_ERROR_INVALID_ARGUMENT = -1
        const val MR_ERROR_CONFIG = -2
        const val MR_ERROR_RUNTIME = -3
        const val MR_ERROR_STATE = -4
        const val MR_ERROR_OVERFLOW = -5

        /** 库版本 [major, minor, patch]；失败返回 null。 */
        @JvmStatic
        fun libraryVersion(): IntArray? = try {
            MediaRecordNative.nativeLibraryVersion()
        } catch (e: Throwable) {
            Log.e(TAG, "libraryVersion failed", e)
            null
        }

        /**
         * 打开一个会话。
         *
         * @param configJson 管线配置，可为**文件路径**或 **JSON 文本**（以 '{' 开头者按文本解析）。
         * @param optionsJson 可选的按节点类型覆盖（null = 不覆盖）。
         * @param surfaceMode -1=保持配置；0=强制 CPU 内存路径；1=强制 MediaCodec 输入 surface。
         * @param resident 0=一次性（帧预算耗尽即结束）；1=持续运行，直到 pushEof/stop。
         * @return 成功返回会话；失败返回 null（原因见日志）。
         */
        @JvmStatic
        fun open(
            configJson: String,
            optionsJson: String? = null,
            surfaceMode: Int = 0,
            resident: Int = 1
        ): MediaRecordSession? {
            val out = LongArray(1)
            return try {
                val err = MediaRecordNative.nativeOpen(configJson, optionsJson, surfaceMode, resident, out)
                if (err != null) {
                    Log.e(TAG, "open failed: $err")
                    null
                } else if (out[0] == 0L) {
                    Log.e(TAG, "open returned null session")
                    null
                } else {
                    Log.i(TAG, "open ok, handle=${out[0]}")
                    MediaRecordSession(out[0])
                }
            } catch (e: Throwable) {
                Log.e(TAG, "open exception", e)
                null
            }
        }
    }
}
