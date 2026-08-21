package com.skyworth.faceid.algorithm

import android.util.Log
import java.util.concurrent.ExecutorService

/**
 * 帧数据处理管理器（单槽替换 + 全图推理）。
 *
 * GL 线程读取 HardwareBuffer → ByteArray（UYVY 原图）后传给此处理器，
 * 算法线程接收原图 → 全图 UYVY→RGB888 → 直接原图推理。
 *
 * 取消图像裁切（FACEP-011 迭代）：直接用原图（1600×1300）做算法推演，
 * 因此算法返回的人脸框/关键点/地标坐标天然为原图空间，**无需再做点位映射转换**
 * （不再需要裁剪偏移修正）。
 */
class FrameProcessor(
    private val mAlgorithm: IFaceIDAlgorithm,
    private val mExecutor: ExecutorService,
    private val mCallback: (IFaceIDAlgorithm.FaceIDResult) -> Unit
) {
    private val TAG = "FrameProcessor"

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
    private var mRgbBufSize = 0

    init {
        Log.i(TAG, "FrameProcessor started (full-frame inference, no crop)")
    }

    fun submitFrame(data: ByteArray, w: Int, h: Int) {
        // 原图 dump：收到完整 UYVY 帧时，dump 未裁剪的原始画面
        mAlgorithm.dumpOriginalFrame(data, w, h)

        synchronized(this) {
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
                val rgb = convertFullFrame(p.data, p.w, p.h)
                mAlgorithm.setCropOffset(0, 0)

                val t0 = System.currentTimeMillis()
                val result = mAlgorithm.processFrame(rgb, p.w, p.h, 0)
                Log.d(TAG, "full ${p.w}x${p.h} → ${System.currentTimeMillis()-t0}ms, face=${result.faceId}")

                try { mCallback(result) } catch (e: Exception) { Log.e(TAG, "cb error", e) }
            } catch (e: Exception) {
                Log.e(TAG, "loop error", e)
                synchronized(this) { mProcessing = false }; return
            }
        }
    }

    /**
     * 全图 UYVY → RGB888（无裁剪，转换整个原图）。
     */
    private fun convertFullFrame(data: ByteArray, imgW: Int, imgH: Int): ByteArray {
        // 复用 RGB 缓冲，仅尺寸变化时重新分配，避免每帧 6.24MB 分配引发的 GC 尖峰
        val need = imgW * imgH * 3
        val rgb = when {
            mRgbBuf == null || mRgbBufSize != need ->
                ByteArray(need).also { mRgbBuf = it; mRgbBufSize = need }
            else -> mRgbBuf!!
        }
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
}
