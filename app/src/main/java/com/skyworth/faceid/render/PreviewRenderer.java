/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.nio.ByteBuffer;

/**
 * 预览渲染器。
 *
 * <p>将算法处理后的帧数据（带人脸框）渲染到 SurfaceView。
 *
 * <p>渲染模式：
 * <ul>
 *   <li>原始帧绘制 — 将 byte[] 帧数据转换为 Bitmap 绘制到 Canvas</li>
 *   <li>人脸框叠加 — 当算法返回 {@link RectF} 人脸框时，额外绘制绿色边框</li>
 *   <li>Face ID 信息 — 当检测到人脸时显示身份标识</li>
 * </ul>
 *
 * <p>线程安全：渲染循环在独立线程中运行，{{@link #renderFrame}} 由流水线线程调用。
 */
public class PreviewRenderer {

    private static final String TAG = "PreviewRenderer";

    private static final int RENDER_SLEEP_MS = 10;
    private static final int SURFACE_WAIT_MS = 5;
    private static final float FACE_RECT_STROKE_WIDTH = 4f;
    private static final float TEXT_SIZE_SP = 36f;
    private static final float TEXT_SHADOW_RADIUS = 2f;

    /** 目标 SurfaceView。 */
    private SurfaceView mSurfaceView;

    /** 人脸框画笔。 */
    private final Paint mFaceRectPaint;

    /** 人脸关键点画笔。 */
    private final Paint mLandmarkPaint;

    /** 文字画笔。 */
    private final Paint mTextPaint;

    /** 当前最新帧数据（用于渲染线程与 UI 线程同步）。 */
    private volatile FrameData mCurrentFrame;

    /** 渲染锁。 */
    private final Object mRenderLock = new Object();

    /** 渲染线程。 */
    private Thread mRenderThread;

    /** 是否运行中。 */
    private volatile boolean mIsRunning;

    /**
     * 帧数据包装。
     */
    private static class FrameData {

        final byte[] data;
        final int width;
        final int height;
        final RectF faceRect;
        final String faceId;
        final float confidence;

        FrameData(byte[] data, int width, int height, RectF faceRect,
                  String faceId, float confidence) {
            this.data = data;
            this.width = width;
            this.height = height;
            this.faceRect = faceRect;
            this.faceId = faceId;
            this.confidence = confidence;
        }
    }

    /** 构造预览渲染器。 */
    public PreviewRenderer() {
        mFaceRectPaint = new Paint();
        mFaceRectPaint.setColor(Color.GREEN);
        mFaceRectPaint.setStyle(Paint.Style.STROKE);
        mFaceRectPaint.setStrokeWidth(FACE_RECT_STROKE_WIDTH);
        mFaceRectPaint.setAntiAlias(true);

        mLandmarkPaint = new Paint();
        mLandmarkPaint.setColor(Color.BLUE);
        mLandmarkPaint.setStyle(Paint.Style.FILL);
        mLandmarkPaint.setStrokeWidth(8f);
        mLandmarkPaint.setAntiAlias(true);

        mTextPaint = new Paint();
        mTextPaint.setColor(Color.WHITE);
        mTextPaint.setTextSize(TEXT_SIZE_SP);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setShadowLayer(TEXT_SHADOW_RADIUS, 1f, 1f, Color.BLACK);
    }

    /**
     * 绑定 SurfaceView。
     *
     * @param surfaceView 预览用的 SurfaceView
     */
    public void setSurfaceView(SurfaceView surfaceView) {
        mSurfaceView = surfaceView;
    }

    /**
     * 开始渲染。
     */
    public void start() {
        mIsRunning = true;
        mRenderThread = new Thread(this::renderLoop, "PreviewRender");
        mRenderThread.setDaemon(true);
        mRenderThread.start();
        Log.i(TAG, "start: done");
    }

    /**
     * 渲染一帧（由 FramePipeline 调用）。
     *
     * @param data     处理后的帧数据
     * @param width    图像宽度
     * @param height   图像高度
     * @param faceRect 人脸框，null 表示未检测到人脸
     */
    public void renderFrame(byte[] data, int width, int height, RectF faceRect) {
        mCurrentFrame = new FrameData(data, width, height, faceRect, "", 0f);
    }

    /**
     * 带 Face ID 信息的渲染重载。
     *
     * @param data       处理后的帧数据
     * @param width      图像宽度
     * @param height     图像高度
     * @param faceRect   人脸框
     * @param faceId     Face ID 标识
     * @param confidence 置信度
     */
    public void renderFrame(byte[] data, int width, int height,
                            RectF faceRect, String faceId, float confidence) {
        mCurrentFrame = new FrameData(data, width, height, faceRect, faceId, confidence);
    }

    /**
     * 渲染循环 — 线程体。
     */
    private void renderLoop() {
        while (mIsRunning) {
            FrameData frame = mCurrentFrame;
            if (frame == null || mSurfaceView == null) {
                sleepQuietly(RENDER_SLEEP_MS);
                continue;
            }

            SurfaceHolder holder = mSurfaceView.getHolder();
            if (holder == null || holder.getSurface() == null
                    || !holder.getSurface().isValid()) {
                sleepQuietly(SURFACE_WAIT_MS);
                continue;
            }

            Canvas canvas = null;
            try {
                synchronized (mRenderLock) {
                    canvas = holder.lockCanvas();
                    if (canvas == null) {
                        continue;
                    }

                    canvas.drawColor(Color.BLACK);
                    drawFrame(canvas, frame);

                    if (frame.faceRect != null) {
                        drawFaceRect(canvas, frame);
                    }

                    if (frame.faceId != null && !frame.faceId.isEmpty()) {
                        drawFaceInfo(canvas, frame);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "renderLoop: draw error", e);
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas);
                    } catch (Exception e) {
                        Log.e(TAG, "renderLoop: unlockCanvas error", e);
                    }
                }
            }
        }
        Log.i(TAG, "renderLoop: exited");
    }

    /**
     * 绘制帧数据到 Canvas。
     *
     * <p>将 byte[] 帧数据（ARGB_8888）转换为 Bitmap 绘制到 Canvas，
     * 按宽高比适配 SurfaceView 尺寸。
     *
     * @param canvas 目标 Canvas
     * @param frame  帧数据
     */
    private void drawFrame(Canvas canvas, FrameData frame) {
        try {
            int expectedSize = frame.width * frame.height * 4; // ARGB_8888 = 4 bytes/pixel
            if (frame.data.length < expectedSize) {
                Log.w(TAG, "drawFrame: data too small, len=" + frame.data.length
                        + ", expected=" + expectedSize);
                canvas.drawColor(Color.DKGRAY);
                canvas.drawText("Frame format conversion...", 50,
                        canvas.getHeight() / 2f, mTextPaint);
                return;
            }

            Bitmap bitmap = Bitmap.createBitmap(
                    frame.width, frame.height, Bitmap.Config.ARGB_8888);

            ByteBuffer buffer = ByteBuffer.wrap(frame.data);
            bitmap.copyPixelsFromBuffer(buffer);

            // 适配 SurfaceView 缩放，保持宽高比
            int canvasWidth = canvas.getWidth();
            int canvasHeight = canvas.getHeight();

            float scaleX = (float) canvasWidth / frame.width;
            float scaleY = (float) canvasHeight / frame.height;
            float scale = Math.min(scaleX, scaleY);

            int drawWidth = (int) (frame.width * scale);
            int drawHeight = (int) (frame.height * scale);
            int left = (canvasWidth - drawWidth) / 2;
            int top = (canvasHeight - drawHeight) / 2;

            Rect srcRect = new Rect(0, 0, frame.width, frame.height);
            Rect dstRect = new Rect(left, top, left + drawWidth, top + drawHeight);

            canvas.drawBitmap(bitmap, srcRect, dstRect, null);
            bitmap.recycle();

        } catch (Exception e) {
            Log.e(TAG, "drawFrame: failed", e);
            try {
                canvas.drawColor(Color.DKGRAY);
                canvas.drawText("Frame render failed", 50, 100, mTextPaint);
            } catch (Exception ignored) {
                // 降级失败时不再处理
            }
        }
    }

    /**
     * 绘制人脸框。
     *
     * @param canvas 目标 Canvas
     * @param frame  帧数据（含人脸框坐标）
     */
    private void drawFaceRect(Canvas canvas, FrameData frame) {
        if (frame.faceRect == null) {
            return;
        }

        // 计算缩放比例
        float scaleX = (float) canvas.getWidth() / frame.width;
        float scaleY = (float) canvas.getHeight() / frame.height;
        float scale = Math.min(scaleX, scaleY);

        float offsetX = (canvas.getWidth() - frame.width * scale) / 2f;
        float offsetY = (canvas.getHeight() - frame.height * scale) / 2f;

        canvas.drawRect(
                frame.faceRect.left * scale + offsetX,
                frame.faceRect.top * scale + offsetY,
                frame.faceRect.right * scale + offsetX,
                frame.faceRect.bottom * scale + offsetY,
                mFaceRectPaint
        );
    }

    /**
     * 绘制 Face ID 信息。
     *
     * @param canvas 目标 Canvas
     * @param frame  帧数据（含 Face ID 信息）
     */
    private void drawFaceInfo(Canvas canvas, FrameData frame) {
        String info = "Face ID: " + frame.faceId
                + " (" + String.format("%.2f", frame.confidence * 100) + "%)";
        canvas.drawText(info, 20, 60, mTextPaint);
    }

    /**
     * 停止渲染器。
     */
    public void stop() {
        mIsRunning = false;
        if (mRenderThread != null) {
            mRenderThread.interrupt();
            mRenderThread = null;
        }
        mCurrentFrame = null;
        Log.i(TAG, "stop: done");
    }

    /**
     * 安静地休眠，忽略中断。
     */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
