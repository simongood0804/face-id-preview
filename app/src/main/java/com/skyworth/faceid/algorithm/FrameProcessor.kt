package com.skyworth.faceid.algorithm

import android.graphics.Rect
import android.util.Log
import java.util.concurrent.ExecutorService

/**
 * 帧数据处理管理器（单槽替换 + ROI 裁剪）。
 *
 * GL 线程读取 HardwareBuffer → ByteArray 后再传给此处理器，
 * 算法线程接收 ByteArray → 裁剪 ROI（900×900）→ 推理。
 * 裁剪窗口跟随人脸，人脸中心位于窗口上方 2/3 处。
 */
class FrameProcessor(
    private val mAlgorithm: FaceIDAlgorithmImpl,
    private val mExecutor: ExecutorService,
    private val mCallback: (IFaceIDAlgorithm.FaceIDResult) -> Unit
) {
    private val TAG = "FrameProcessor"

    /** 裁剪窗口边长。 */
    private val CROP_SIZE = 900

    /** 当前裁剪窗口左上角（原图坐标）。 */
    @Volatile var cropLeft: Int = (1600 - CROP_SIZE) / 2
    @Volatile var cropTop: Int = (1300 - CROP_SIZE) / 2

    /** 上次检测到的人脸中心（用于跟踪）。 */
    private var mLastFaceCX = 0f
    private var mLastFaceCY = 0f
    private var mNoFaceCount = 0

    private data class PendingFrame(
        val data: ByteArray,
        val w: Int, val h: Int
    )

    @Volatile private var mPending: PendingFrame? = null
    @Volatile private var mProcessing = false

    init {
        cropLeft = (1600 - CROP_SIZE) / 2
        cropTop = (1300 - CROP_SIZE) / 2
        Log.i(TAG, "FrameProcessor started, crop=$CROP_SIZE")
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

                // 裁剪 ROI 并设置偏移（算法内坐标会被修正回原图空间）
                val cropped = cropFrame(p.data, p.w, p.h)
                mAlgorithm.mCropOffsetX = cropLeft
                mAlgorithm.mCropOffsetY = cropTop

                val t0 = System.currentTimeMillis()
                val result = mAlgorithm.processFrame(cropped, CROP_SIZE, CROP_SIZE, 0)
                Log.d(TAG, "CROP ${CROP_SIZE}x${CROP_SIZE} → ${System.currentTimeMillis()-t0}ms, face=${result.faceId}")

                // 更新跟踪位置
                updateTracking(result)

                try { mCallback(result) } catch (e: Exception) { Log.e(TAG, "cb error", e) }
            } catch (e: Exception) {
                Log.e(TAG, "loop error", e)
                synchronized(this) { mProcessing = false }; return
            }
        }
    }

    // ============================================================
    // ROI 裁剪
    // ============================================================

    private fun cropFrame(data: ByteArray, imgW: Int, imgH: Int): ByteArray {
        val size = CROP_SIZE
        val left = cropLeft.coerceIn(0, imgW - size)
        val top = cropTop.coerceIn(0, imgH - size)
        cropLeft = left
        cropTop = top

        // 裁剪 UYVY → 同时转换为 RGB888（避免两次循环）
        val rgb = ByteArray(size * size * 3)
        var srcRow = top
        var dstIdx = 0
        for (row in 0 until size) {
            var srcCol = left
            for (col in 0 until size step 2) {
                val srcPos = srcRow * imgW * 2 + srcCol * 2
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
            srcRow++
        }
        return rgb
    }

    // ============================================================
    // 跟踪: 人脸在窗口上方 2/3 处
    // ============================================================

    private fun updateTracking(result: IFaceIDAlgorithm.FaceIDResult) {
        val imgW = 1600
        val imgH = 1300
        val size = CROP_SIZE

        if (result.faceRect != null && result.faceId.isNotEmpty()) {
            mNoFaceCount = 0
            val cx = (result.faceRect.left + result.faceRect.right) / 2f
            val cy = (result.faceRect.top + result.faceRect.bottom) / 2f
            mLastFaceCX = cx
            mLastFaceCY = cy

            // 目标：人脸中心在窗口黄金分割点 (0.382) → winCenterY = cy + size * 0.118
            val winCX = cx
            val winCY = cy + size * 0.118f

            cropLeft = (winCX - size / 2f).toInt().coerceIn(0, imgW - size)
            cropTop = (winCY - size / 2f).toInt().coerceIn(0, imgH - size)
        } else {
            mNoFaceCount++
            if (mNoFaceCount > 15) { // ~0.5s 无人脸 → 居中
                cropLeft = (imgW - size) / 2
                cropTop = (imgH - size) / 2
            }
        }
    }
}
