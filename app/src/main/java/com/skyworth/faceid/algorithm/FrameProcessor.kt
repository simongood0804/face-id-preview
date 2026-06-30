package com.skyworth.faceid.algorithm

import android.util.Log
import java.util.concurrent.ExecutorService

/**
 * 帧数据处理管理器（单槽替换）。
 *
 * GL 线程读取 byte[] 后提交，算法线程只做推理。
 */
class FrameProcessor(
    private val mAlgorithm: FaceIDAlgorithmImpl,
    private val mExecutor: ExecutorService,
    private val mCallback: (IFaceIDAlgorithm.FaceIDResult) -> Unit
) {
    private val TAG = "FrameProcessor"

    @Volatile private var mPendingData: ByteArray? = null
    @Volatile private var mPendingW = 0
    @Volatile private var mPendingH = 0
    @Volatile private var mProcessing = false

    init { Log.i(TAG, "FrameProcessor created") }

    fun submitFrame(data: ByteArray, frameW: Int, frameH: Int) {
        synchronized(this) {
            mPendingData = data
            mPendingW = frameW
            mPendingH = frameH
            if (!mProcessing) {
                mProcessing = true
                mExecutor.submit { processLoop() }
            }
        }
    }

    private fun processLoop() {
        while (true) {
            val data: ByteArray; val w: Int; val h: Int
            synchronized(this) {
                data = mPendingData ?: run { mProcessing = false; return }
                w = mPendingW; h = mPendingH; mPendingData = null
            }
            val t0 = System.currentTimeMillis()
            val result = mAlgorithm.processFrame(data, w, h, 0)
            val dt = System.currentTimeMillis() - t0
            Log.d(TAG, "FULL ${w}x${h} → ${dt}ms, face=${result.faceId}")
            try { mCallback(result) } catch (e: Exception) { Log.e(TAG, "cb error", e) }
        }
    }
}
