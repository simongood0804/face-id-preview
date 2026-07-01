package com.skyworth.faceid.algorithm

import android.graphics.Rect
import android.util.Log
import java.util.concurrent.ExecutorService

/**
 * 帧数据处理管理器（单槽替换 + ROI 裁剪）。
 *
 * GL 线程仅传递 HardwareBuffer 引用（~0ms），
 * 算法线程读取 → 裁剪 ROI（650×650）→ 推理。
 * 裁剪窗口跟随人脸，人脸中心位于窗口上方 2/3 处。
 */
class FrameProcessor(
    private val mAlgorithm: FaceIDAlgorithmImpl,
    private val mExecutor: ExecutorService,
    private val mReadBuffer: (android.hardware.HardwareBuffer, Int, Int) -> ByteArray?,
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
        val buffer: android.hardware.HardwareBuffer,
        val w: Int, val h: Int
    )

    @Volatile private var mPending: PendingFrame? = null
    @Volatile private var mProcessing = false

    init {
        cropLeft = (1600 - CROP_SIZE) / 2
        cropTop = (1300 - CROP_SIZE) / 2
        Log.i(TAG, "FrameProcessor started, crop=$CROP_SIZE")
    }

    fun submitFrame(hwBuffer: android.hardware.HardwareBuffer, w: Int, h: Int) {
        synchronized(this) {
            mPending = PendingFrame(hwBuffer, w, h)
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

                // 算法线程读取 HardwareBuffer
                val data = mReadBuffer(p.buffer, p.w, p.h)
                if (data == null) { Log.w(TAG, "read null, skip"); continue }

                // 裁剪 ROI 并设置偏移（算法内坐标会被修正回原图空间）
                val cropped = cropFrame(data, p.w, p.h)
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

        val out = ByteArray(size * size * 2) // UYVY: 2 bytes/pixel
        for (row in 0 until size) {
            val srcPos = (top + row) * imgW * 2 + left * 2
            val dstPos = row * size * 2
            System.arraycopy(data, srcPos, out, dstPos, size * 2)
        }
        return out
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
