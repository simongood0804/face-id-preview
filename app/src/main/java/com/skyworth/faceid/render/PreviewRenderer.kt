/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.render

import android.graphics.*
import android.util.Log
import android.view.SurfaceView
import java.nio.ByteBuffer

/**
 * 预览渲染器。
 *
 * 将算法处理后的帧数据（带人脸框）渲染到 SurfaceView。
 *
 * 线程安全：渲染循环在独立线程中运行，[renderFrame] 由流水线线程安全调用。
 */
class PreviewRenderer {

    private val TAG = "PreviewRenderer"

    private var mSurfaceView: SurfaceView? = null

    @Volatile
    private var mCurrentFrame: FrameData? = null

    private var mRenderThread: Thread? = null

    @Volatile
    private var mIsRunning = false

    private val mFaceRectPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = FACE_RECT_STROKE_WIDTH
        isAntiAlias = true
    }

    private val mLandmarkPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.FILL
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val mTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = TEXT_SIZE_SP
        isAntiAlias = true
        setShadowLayer(TEXT_SHADOW_RADIUS, 1f, 1f, Color.BLACK)
    }

    private class FrameData(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val faceRect: RectF?,
        val faceId: String = "",
        val confidence: Float = 0f
    )

    fun setSurfaceView(surfaceView: SurfaceView?) {
        mSurfaceView = surfaceView
    }

    fun start() {
        mIsRunning = true
        mRenderThread = Thread({ renderLoop() }, "PreviewRender").apply { isDaemon = true }
        mRenderThread?.start()
        Log.i(TAG, "start: done")
    }

    fun renderFrame(data: ByteArray, width: Int, height: Int, faceRect: RectF?) {
        mCurrentFrame = FrameData(data, width, height, faceRect)
    }

    fun renderFrame(
        data: ByteArray,
        width: Int,
        height: Int,
        faceRect: RectF?,
        faceId: String,
        confidence: Float
    ) {
        mCurrentFrame = FrameData(data, width, height, faceRect, faceId, confidence)
    }

    private fun renderLoop() {
        while (mIsRunning) {
            val frame = mCurrentFrame
            if (frame == null) {
                sleepQuietly(RENDER_SLEEP_MS)
                continue
            }
            val surfaceView = mSurfaceView
            if (surfaceView == null) {
                sleepQuietly(RENDER_SLEEP_MS)
                continue
            }
            val holder = surfaceView.holder
            if (holder == null || !holder.surface.isValid) {
                sleepQuietly(SURFACE_WAIT_MS.toLong())
                continue
            }

            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas == null) continue

                canvas.drawColor(Color.BLACK)
                drawFrame(canvas, frame)
                if (frame.faceRect != null) {
                    drawFaceRect(canvas, frame)
                }
                if (frame.faceId.isNotEmpty()) {
                    drawFaceInfo(canvas, frame)
                }
            } catch (e: Exception) {
                Log.e(TAG, "renderLoop: draw error", e)
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        Log.e(TAG, "renderLoop: unlockCanvas error", e)
                    }
                }
            }
        }
        Log.i(TAG, "renderLoop: exited")
    }

    private fun drawFrame(canvas: Canvas, frame: FrameData) {
        try {
            val expectedSize = frame.width * frame.height * 4
            if (frame.data.size < expectedSize) {
                Log.w(TAG, "drawFrame: data too small, len=${frame.data.size}, expected=$expectedSize")
                canvas.drawColor(Color.DKGRAY)
                canvas.drawText("Frame format conversion...", 50f, canvas.height / 2f, mTextPaint)
                return
            }
            val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            val buffer = ByteBuffer.wrap(frame.data)
            bitmap.copyPixelsFromBuffer(buffer)

            val canvasWidth = canvas.width
            val canvasHeight = canvas.height
            val scale = minOf(canvasWidth.toFloat() / frame.width, canvasHeight.toFloat() / frame.height)
            val drawWidth = (frame.width * scale).toInt()
            val drawHeight = (frame.height * scale).toInt()
            val left = (canvasWidth - drawWidth) / 2
            val top = (canvasHeight - drawHeight) / 2

            canvas.drawBitmap(bitmap,
                Rect(0, 0, frame.width, frame.height),
                Rect(left, top, left + drawWidth, top + drawHeight), null)
            bitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "drawFrame: failed", e)
            try {
                canvas.drawColor(Color.DKGRAY)
                canvas.drawText("Frame render failed", 50f, 100f, mTextPaint)
            } catch (_: Exception) {
            }
        }
    }

    private fun drawFaceRect(canvas: Canvas, frame: FrameData) {
        val rect = frame.faceRect ?: return
        val scale = minOf(
            canvas.width.toFloat() / frame.width,
            canvas.height.toFloat() / frame.height
        )
        val offsetX = (canvas.width - frame.width * scale) / 2f
        val offsetY = (canvas.height - frame.height * scale) / 2f
        canvas.drawRect(
            rect.left * scale + offsetX,
            rect.top * scale + offsetY,
            rect.right * scale + offsetX,
            rect.bottom * scale + offsetY,
            mFaceRectPaint
        )
    }

    private fun drawFaceInfo(canvas: Canvas, frame: FrameData) {
        val info = "Face ID: ${frame.faceId} (${String.format("%.2f", frame.confidence * 100)}%)"
        canvas.drawText(info, 20f, 60f, mTextPaint)
    }

    fun stop() {
        mIsRunning = false
        mRenderThread?.interrupt()
        mRenderThread = null
        mCurrentFrame = null
        Log.i(TAG, "stop: done")
    }

    companion object {
        private const val RENDER_SLEEP_MS = 10L
        private const val SURFACE_WAIT_MS = 5
        private const val FACE_RECT_STROKE_WIDTH = 4f
        private const val TEXT_SIZE_SP = 36f
        private const val TEXT_SHADOW_RADIUS = 2f

        private fun sleepQuietly(millis: Long) {
            try {
                Thread.sleep(millis)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
