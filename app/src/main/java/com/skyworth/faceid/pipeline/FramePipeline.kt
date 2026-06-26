/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.pipeline

import android.graphics.Bitmap
import android.util.Log
import com.android.car.evs.EvsBufferDesc
import com.skyworth.faceid.algorithm.IFaceIDAlgorithm
import com.skyworth.faceid.camera.CameraManager
import com.skyworth.faceid.render.PreviewRenderer
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 帧处理流水线 — 核心协调层。
 *
 * 三线程模型：
 * ```
 * Capture Thread → Process Thread → Render Thread → Buffer Recycle
 * ```
 *
 * 线程安全：状态通过 AtomicBoolean 保护，内部队列为线程安全实现。
 */
open class FramePipeline(
    private val mCameraManager: CameraManager,
    private val mAlgorithm: IFaceIDAlgorithm,
    private val mBufferManager: BufferManager,
    private val mPreviewRenderer: PreviewRenderer,
    private val mConfig: PipelineConfig
) {
    private val TAG = "FramePipeline"

    private val mProcessedQueue = LinkedBlockingQueue<FrameTask>(mConfig.maxPendingFrames)
    private var mCaptureThread: Thread? = null
    private var mProcessExecutor: java.util.concurrent.ExecutorService? = null
    private var mRenderThread: Thread? = null
    private val mIsRunning = AtomicBoolean(false)
    private val mFrameCounter = AtomicInteger(0)

    private class FrameTask(
        val buffer: EvsBufferDesc,
        val frameData: ByteArray,
        val width: Int,
        val height: Int,
        val format: Int,
        @Volatile var result: IFaceIDAlgorithm.FaceIDResult? = null,
        @Volatile var processed: Boolean = false,
        @Volatile var rendered: Boolean = false
    )

    fun start() {
        if (mIsRunning.getAndSet(true)) {
            Log.w(TAG, "start: already running")
            return
        }
        Log.i(TAG, "start: config=$mConfig")

        mBufferManager.startTimeoutMonitor()

        mCaptureThread = Thread({ captureLoop() }, "FrameCapture").apply { isDaemon = true }
        mCaptureThread?.start()

        mProcessExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "FrameProcessor").apply { isDaemon = true }
        }

        mRenderThread = Thread({ renderLoop() }, "FrameRender").apply { isDaemon = true }
        mRenderThread?.start()

        Log.i(TAG, "start: done")
    }

    private fun captureLoop() {
        while (mIsRunning.get()) {
            try {
                val buffer = mCameraManager.getNewFrame()
                if (buffer == null) {
                    Thread.sleep(CAPTURE_SLEEP_MS)
                    continue
                }

                mBufferManager.registerBuffer(buffer)

                val width = buffer.getWidth()
                val height = buffer.getHeight()
                val bufferId = buffer.getId()

                val frameData = readHardwareBuffer(buffer)
                if (frameData == null) {
                    Log.w(TAG, "captureLoop: failed to read buffer, id=$bufferId")
                    mBufferManager.recycleBuffer(buffer)
                    continue
                }

                val task = FrameTask(buffer, frameData, width, height, 0)

                val frameCount = mFrameCounter.incrementAndGet()
                if (frameCount % mConfig.frameSkipInterval != 0) {
                    task.processed = true
                    task.result = IFaceIDAlgorithm.FaceIDResult(processedData = frameData)
                    mProcessedQueue.offer(task)
                } else {
                    mProcessExecutor?.submit { processTask(task) }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Log.e(TAG, "captureLoop: unexpected error", e)
            }
        }
        Log.i(TAG, "captureLoop: exited")
    }

    private fun processTask(task: FrameTask) {
        if (!mIsRunning.get()) return
        try {
            val result = mAlgorithm.processFrame(task.frameData, task.width, task.height, task.format)
            task.result = result
            task.processed = true

            while (!mProcessedQueue.offer(task, OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                val oldest = mProcessedQueue.poll()
                if (oldest != null && !oldest.rendered) {
                    mBufferManager.recycleBuffer(oldest.buffer)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processTask: algorithm error", e)
            task.processed = true
            task.result = IFaceIDAlgorithm.FaceIDResult(processedData = task.frameData)
            mProcessedQueue.offer(task)
        }
    }

    private fun renderLoop() {
        while (mIsRunning.get()) {
            try {
                val task = mProcessedQueue.poll(RENDER_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    ?: continue

                if (task.result != null) {
                    mPreviewRenderer.renderFrame(
                        task.result!!.processedData,
                        task.width,
                        task.height,
                        task.result!!.faceRect
                    )
                }

                task.rendered = true
                mBufferManager.recycleBuffer(task.buffer)

                if (task.result != null && task.result!!.faceId.isNotEmpty()) {
                    onFaceIdDetected(task.result!!)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Log.e(TAG, "renderLoop: unexpected error", e)
            }
        }
        Log.i(TAG, "renderLoop: exited")
    }

    protected open fun onFaceIdDetected(result: IFaceIDAlgorithm.FaceIDResult) {
        Log.i(TAG, "onFaceIdDetected: faceId=${result.faceId}, confidence=${result.confidence}")
    }

    fun stop() {
        if (!mIsRunning.getAndSet(false)) return
        Log.i(TAG, "stop: start")

        mCaptureThread?.interrupt()
        mCaptureThread = null

        mProcessExecutor?.shutdownNow()
        mProcessExecutor = null

        mRenderThread?.interrupt()
        mRenderThread = null

        while (true) {
            val task = mProcessedQueue.poll() ?: break
            mBufferManager.recycleBuffer(task.buffer)
        }

        mBufferManager.shutdown()
        Log.i(TAG, "stop: done, totalFrames=${mFrameCounter.get()}")
    }

    fun isRunning(): Boolean = mIsRunning.get()

    private fun readHardwareBuffer(buffer: EvsBufferDesc): ByteArray? {
        return try {
            val hwBuffer = buffer.getHardwareBuffer()
            if (hwBuffer == null) {
                Log.w(TAG, "readHardwareBuffer: HardwareBuffer is null")
                return null
            }
            val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, null)
            if (bitmap == null) {
                Log.w(TAG, "readHardwareBuffer: Bitmap.wrapHardwareBuffer returned null")
                return null
            }
            val byteCount = bitmap.byteCount
            val byteBuffer = ByteBuffer.allocate(byteCount)
            bitmap.copyPixelsToBuffer(byteBuffer)
            bitmap.recycle()
            Log.d(TAG, "readHardwareBuffer: ${buffer.getWidth()}x${buffer.getHeight()}, size=$byteCount")
            byteBuffer.array()
        } catch (e: Exception) {
            Log.e(TAG, "readHardwareBuffer: failed", e)
            null
        }
    }

    companion object {
        private const val RENDER_POLL_TIMEOUT_MS = 200L
        private const val CAPTURE_SLEEP_MS = 1L
        private const val OFFER_TIMEOUT_MS = 100L

        @JvmStatic
        fun estimateFrameSize(width: Int, height: Int, format: Int): Int {
            return width * height * 3 / 2
        }
    }
}
