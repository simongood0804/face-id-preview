package com.skyworth.faceid.algorithm

import android.util.Log
import java.util.concurrent.ExecutorService

/**
 * 帧数据处理管理器（单槽替换）。
 *
 * GL 线程仅传递 HardwareBuffer 引用（~0ms，不阻塞渲染），
 * 算法线程读取 + 推理。JNI 有 AHardwareBuffer_acquire 保护。
 */
class FrameProcessor(
    private val mAlgorithm: FaceIDAlgorithmImpl,
    private val mExecutor: ExecutorService,
    private val mReadBuffer: (android.hardware.HardwareBuffer, Int, Int) -> ByteArray?,
    private val mCallback: (IFaceIDAlgorithm.FaceIDResult) -> Unit
) {
    private val TAG = "FrameProcessor"

    private data class PendingFrame(
        val buffer: android.hardware.HardwareBuffer,
        val w: Int, val h: Int
    )

    @Volatile private var mPending: PendingFrame? = null
    @Volatile private var mProcessing = false

    init { Log.i(TAG, "FrameProcessor started") }

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
                synchronized(this) { p = mPending ?: run { mProcessing = false; return }; mPending = null }

                // 算法线程读取 HardwareBuffer（不阻塞 GL 渲染管线）
                val data = mReadBuffer(p.buffer, p.w, p.h)
                if (data == null) { Log.w(TAG, "read null, skip"); continue }

                val t0 = System.currentTimeMillis()
                val result = mAlgorithm.processFrame(data, p.w, p.h, 0)
                Log.d(TAG, "FULL ${p.w}x${p.h} → ${System.currentTimeMillis()-t0}ms, face=${result.faceId}")
                try { mCallback(result) } catch (e: Exception) { Log.e(TAG, "cb error", e) }
            } catch (e: Exception) {
                Log.e(TAG, "loop error", e)
                synchronized(this) { mProcessing = false }; return
            }
        }
    }
}
