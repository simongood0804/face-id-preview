package com.skyworth.faceid.algorithm

import java.util.concurrent.ExecutorService
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 帧数据处理管理器（单槽替换 + 全图推理）。
 *
 * GL 线程读取 HardwareBuffer → ByteArray（UYVY 原图）后传给此处理器，
 * 算法线程接收原图 → 全图 UYVY→RGB888 → 直接原图推理。
 *
 * 取消图像裁切（FACEP-011 迭代）：直接用原图（1600×1300）做算法推演，
 * 因此算法返回的人脸框/关键点/地标坐标天然为原图空间，**无需再做点位映射转换**
 * （不再需要裁剪偏移修正）。
 *
 * 帧处理效率观测：每处理 [PERF_REPORT_INTERVAL] 帧输出一次 `[PERF]` 摘要日志
 * （转换/推理平均与最大耗时、处理帧间隔与折算 FPS、单槽替换累计丢帧数），
 * 供设备端抓 logcat 评估当前帧处理效率。
 */
class FrameProcessor(
    private val mAlgorithm: IFaceIDAlgorithm,
    private val mExecutor: ExecutorService,
    private val mCallback: (IFaceIDAlgorithm.FaceIDResult) -> Unit
) {
    private val logger = Logger.getLogger("FrameProcessor")

    companion object {
        /** 性能摘要输出间隔（帧数），每 N 帧输出一次 [PERF] 汇总。 */
        private const val PERF_REPORT_INTERVAL = 30
    }

    /**
     * 裁剪偏移（保留字段兼容旧引用，全图推理下恒为 0，表示无偏移）。
     * 注意：`FaceOverlayBridge.updateCropRect` / `PreviewActivity` 仍引用此字段，
     * 取消裁切后不再更新，保持 0 即可（对应方法已不再被模块调用）。
     */
    @Volatile
    var cropLeft: Int = 0

    @Volatile
    var cropTop: Int = 0

    private data class PendingFrame(
        val data: ByteArray,
        val w: Int, val h: Int
    )

    @Volatile private var mPending: PendingFrame? = null
    @Volatile private var mProcessing = false

    /**
     * 复用的全图 RGB 缓冲（仅尺寸变化时重新分配）。
     * 安全前提：算法 executor 为单线程，processLoop 串行执行，且 [IFaceIDAlgorithm.processFrame]
     * 为同步调用，同一时刻只有一处持有该缓冲。
     */
    private var mRgbBuf: ByteArray? = null

    // ---- 帧处理效率统计（仅在算法线程访问；mPerfDropped 另在 submitFrame 同步块内写） ----
    private var mPerfCount = 0
    private var mPerfConvertSumMs = 0L
    private var mPerfInferSumMs = 0L
    private var mPerfMaxConvertMs = 0L
    private var mPerfMaxInferMs = 0L
    private var mPerfIntervalSumMs = 0L
    private var mPerfIntervalCount = 0L
    private var mPerfLastDoneMs = 0L
    private var mPerfDropped = 0L   // 单槽被覆盖 = 丢帧（累计）

    init {
        logger.info("FrameProcessor started (full-frame inference, no crop)")
    }

    fun submitFrame(data: ByteArray, w: Int, h: Int) {
        // 原图 dump：收到完整 UYVY 帧时，dump 未裁剪的原始画面
        mAlgorithm.dumpOriginalFrame(data, w, h)

        synchronized(this) {
            // 上一帧尚未取走、新帧又到 → 旧帧被单槽覆盖，记一次丢帧
            if (mProcessing && mPending != null) mPerfDropped++
            mPending = PendingFrame(data, w, h)
            if (!mProcessing) {
                mProcessing = true
                mExecutor.submit { processLoop() }
            }
        }
    }

    private fun processLoop() {
        while (true) {
            try {
                val p: PendingFrame
                synchronized(this) {
                    p = mPending ?: run { mProcessing = false; return }
                    mPending = null
                }

                // 取消裁切：全图 UYVY → RGB888，直接原图推理。
                // 坐标天然为原图空间，无需裁剪偏移修正（offset 置 0）。
                val tConvert0 = System.currentTimeMillis()
                val rgb = convertUyvyToRgb888(p.data, p.w, p.h, mRgbBuf)
                mRgbBuf = rgb
                val tInfer0 = System.currentTimeMillis()

                mAlgorithm.setCropOffset(0, 0)
                val result = mAlgorithm.processFrame(rgb, p.w, p.h, 0)
                val tDone = System.currentTimeMillis()

                val convertMs = tInfer0 - tConvert0
                val inferMs = tDone - tInfer0
                collectPerfStats(convertMs, inferMs, tDone)

                logger.fine("full ${p.w}x${p.h} convert=${convertMs}ms infer=${inferMs}ms face=${result.faceId}")

                try { mCallback(result) } catch (e: Exception) { logger.log(Level.SEVERE, "cb error", e) }
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "loop error", e)
                synchronized(this) { mProcessing = false }; return
            }
        }
    }

    /** 累加性能指标，每 [PERF_REPORT_INTERVAL] 帧输出一次 [PERF] 摘要。 */
    private fun collectPerfStats(convertMs: Long, inferMs: Long, doneMs: Long) {
        mPerfCount++
        mPerfConvertSumMs += convertMs
        mPerfInferSumMs += inferMs
        if (convertMs > mPerfMaxConvertMs) mPerfMaxConvertMs = convertMs
        if (inferMs > mPerfMaxInferMs) mPerfMaxInferMs = inferMs
        if (mPerfLastDoneMs > 0) {
            mPerfIntervalSumMs += doneMs - mPerfLastDoneMs
            mPerfIntervalCount++
        }
        mPerfLastDoneMs = doneMs

        if (mPerfCount >= PERF_REPORT_INTERVAL) {
            val avgConvert = mPerfConvertSumMs.toFloat() / mPerfCount
            val avgInfer = mPerfInferSumMs.toFloat() / mPerfCount
            val avgInterval = if (mPerfIntervalCount > 0) mPerfIntervalSumMs.toFloat() / mPerfIntervalCount else 0f
            val fps = if (avgInterval > 0) 1000f / avgInterval else 0f
            logger.info(
                "[PERF] ${PERF_REPORT_INTERVAL}帧汇总: 转换 avg=${"%.1f".format(avgConvert)}ms max=${mPerfMaxConvertMs}ms | " +
                        "推理 avg=${"%.1f".format(avgInfer)}ms max=${mPerfMaxInferMs}ms | " +
                        "帧间隔 avg=${"%.1f".format(avgInterval)}ms (~${"%.1f".format(fps)}fps) | 累计丢帧=${mPerfDropped}"
            )
            mPerfCount = 0
            mPerfConvertSumMs = 0; mPerfInferSumMs = 0
            mPerfMaxConvertMs = 0; mPerfMaxInferMs = 0
            mPerfIntervalSumMs = 0; mPerfIntervalCount = 0
        }
    }
}

/**
 * 全图 UYVY → RGB888（无裁剪，转换整个原图）。
 *
 * 提取为顶层函数以便单元测试直接测量转换吞吐。
 *
 * @param data UYVY 原始帧（每像素 2 字节，Y0 U Y1 V）
 * @param imgW 帧宽
 * @param imgH 帧高
 * @param reuse 可复用的 RGB 缓冲（尺寸匹配时直接复用，否则新分配），可为 null
 * @return RGB888 缓冲（当 [reuse] 尺寸不匹配时返回新分配缓冲）
 */
fun convertUyvyToRgb888(data: ByteArray, imgW: Int, imgH: Int, reuse: ByteArray? = null): ByteArray {
    val need = imgW * imgH * 3
    val rgb = if (reuse != null && reuse.size == need) reuse else ByteArray(need)
    var dstIdx = 0
    for (row in 0 until imgH) {
        var srcCol = 0
        for (col in 0 until imgW step 2) {
            val srcPos = row * imgW * 2 + srcCol * 2
            val u = data[srcPos].toInt() and 0xFF
            val y0 = data[srcPos + 1].toInt() and 0xFF
            val v = data[srcPos + 2].toInt() and 0xFF
            val y1 = data[srcPos + 3].toInt() and 0xFF
            srcCol += 2

            fun clamp(v: Int): Byte = when { v < 0 -> 0; v > 255 -> 255; else -> v }.toByte()
            val c0 = y0 - 16; val d = u - 128; val e = v - 128
            rgb[dstIdx++] = clamp((298 * c0 + 409 * e + 128) shr 8)
            rgb[dstIdx++] = clamp((298 * c0 - 100 * d - 208 * e + 128) shr 8)
            rgb[dstIdx++] = clamp((298 * c0 + 516 * d + 128) shr 8)

            val c1 = y1 - 16
            rgb[dstIdx++] = clamp((298 * c1 + 409 * e + 128) shr 8)
            rgb[dstIdx++] = clamp((298 * c1 - 100 * d - 208 * e + 128) shr 8)
            rgb[dstIdx++] = clamp((298 * c1 + 516 * d + 128) shr 8)
        }
    }
    return rgb
}
