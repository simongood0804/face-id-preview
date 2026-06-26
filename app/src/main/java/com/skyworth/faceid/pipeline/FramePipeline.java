/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.pipeline;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.hardware.HardwareBuffer;
import android.util.Log;

import com.android.car.evs.EvsBufferDesc;
import com.skyworth.faceid.algorithm.IFaceIDAlgorithm;
import com.skyworth.faceid.camera.CameraManager;
import com.skyworth.faceid.render.PreviewRenderer;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 帧处理流水线 — 核心协调层。
 *
 * <p>三线程模型：
 * <pre>
 *   Capture Thread → Process Thread → Render Thread → Buffer Recycle
 * </pre>
 *
 * <p>职责：
 * <ul>
 *   <li>从 CameraManager 获取原始帧</li>
 *   <li>调用 IFaceIDAlgorithm 处理帧（获取 faceId + 画框）</li>
 *   <li>将处理结果交给 PreviewRenderer 渲染</li>
 *   <li>通过 BufferManager 安全归还 Buffer</li>
 * </ul>
 *
 * <p>线程安全：状态通过 AtomicBoolean 保护，内部队列为线程安全实现。
 */
public class FramePipeline {

    private static final String TAG = "FramePipeline";

    /** 渲染队列无任务时线程休眠时间（毫秒）。 */
    private static final long RENDER_POLL_TIMEOUT_MS = 200L;

    /** 取流无帧时休眠时间（毫秒）。 */
    private static final long CAPTURE_SLEEP_MS = 1L;

    /** 队列满时 offer 超时（毫秒）。 */
    private static final long OFFER_TIMEOUT_MS = 100L;

    private final CameraManager mCameraManager;
    private final IFaceIDAlgorithm mAlgorithm;
    private final BufferManager mBufferManager;
    private final PreviewRenderer mPreviewRenderer;
    private final PipelineConfig mConfig;

    /** 帧处理结果队列（处理后待渲染）。 */
    private final LinkedBlockingQueue<FrameTask> mProcessedQueue;

    /** 取流线程。 */
    private Thread mCaptureThread;

    /** 处理线程池。 */
    private ExecutorService mProcessExecutor;

    /** 渲染线程。 */
    private Thread mRenderThread;

    /** 流水线是否运行中。 */
    private final AtomicBoolean mIsRunning = new AtomicBoolean(false);

    /** 帧计数器（用于跳帧）。 */
    private final AtomicInteger mFrameCounter = new AtomicInteger(0);

    /**
     * 帧处理任务。
     */
    private static class FrameTask {

        final EvsBufferDesc buffer;
        final byte[] frameData;
        final int width;
        final int height;
        final int format;
        IFaceIDAlgorithm.FaceIDResult result;
        volatile boolean processed;
        volatile boolean rendered;

        FrameTask(EvsBufferDesc buffer, byte[] frameData, int width, int height, int format) {
            this.buffer = buffer;
            this.frameData = frameData;
            this.width = width;
            this.height = height;
            this.format = format;
        }
    }

    /**
     * 构造帧处理流水线。
     *
     * @param cameraManager   摄像头管理器
     * @param algorithm       算法接口实现
     * @param bufferManager   Buffer 管理器
     * @param previewRenderer 预览渲染器
     * @param config          流水线配置
     */
    public FramePipeline(CameraManager cameraManager,
                         IFaceIDAlgorithm algorithm,
                         BufferManager bufferManager,
                         PreviewRenderer previewRenderer,
                         PipelineConfig config) {
        mCameraManager = cameraManager;
        mAlgorithm = algorithm;
        mBufferManager = bufferManager;
        mPreviewRenderer = previewRenderer;
        mConfig = config;

        mProcessedQueue = new LinkedBlockingQueue<>(config.getMaxPendingFrames());
    }

    /**
     * 启动流水线。
     *
     * <p>启动 Buffer 超时监控、取流线程、处理线程池和渲染线程。
     */
    public void start() {
        if (mIsRunning.getAndSet(true)) {
            Log.w(TAG, "start: already running");
            return;
        }

        Log.i(TAG, "start: config=" + mConfig);

        // 启动 Buffer 超时监控
        mBufferManager.startTimeoutMonitor();

        // 启动取流线程
        mCaptureThread = new Thread(this::captureLoop, "FrameCapture");
        mCaptureThread.setDaemon(true);
        mCaptureThread.start();

        // 启动处理线程池（单线程保证顺序，也可根据需要调整）
        mProcessExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "FrameProcessor");
            t.setDaemon(true);
            return t;
        });

        // 启动渲染线程
        mRenderThread = new Thread(this::renderLoop, "FrameRender");
        mRenderThread.setDaemon(true);
        mRenderThread.start();

        Log.i(TAG, "start: done");
    }

    /**
     * 取流循环 — 线程体。
     *
     * <p>从 CameraManager 不断获取新帧，提交到处理线程池。
     */
    private void captureLoop() {
        while (mIsRunning.get()) {
            try {
                // 1. 从 EvsSDK 获取新帧
                EvsBufferDesc buffer = mCameraManager.getNewFrame();
                if (buffer == null) {
                    Thread.sleep(CAPTURE_SLEEP_MS);
                    continue;
                }

                // 2. 注册到 BufferManager 跟踪
                mBufferManager.registerBuffer(buffer);

                // 3. 提取帧数据
                int width = buffer.getWidth();
                int height = buffer.getHeight();
                int bufferId = buffer.getId();

                byte[] frameData = readHardwareBuffer(buffer);
                if (frameData == null) {
                    Log.w(TAG, "captureLoop: failed to read buffer, id=" + bufferId);
                    mBufferManager.recycleBuffer(buffer);
                    continue;
                }

                // 4. 构建任务
                FrameTask task = new FrameTask(buffer, frameData, width, height, 0);

                // 5. 跳帧逻辑
                int frameCount = mFrameCounter.incrementAndGet();
                if (frameCount % mConfig.getFrameSkipInterval() != 0) {
                    // 跳过的帧直接渲染原始数据
                    task.processed = true;
                    task.result = new IFaceIDAlgorithm.FaceIDResult(
                            "", 0f, null, frameData, null);
                    mProcessedQueue.offer(task);
                } else {
                    // 提交到算法处理
                    mProcessExecutor.submit(() -> processTask(task));
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.e(TAG, "captureLoop: unexpected error", e);
            }
        }
        Log.i(TAG, "captureLoop: exited");
    }

    /**
     * 算法处理任务。
     *
     * @param task 待处理的帧任务
     */
    private void processTask(FrameTask task) {
        if (!mIsRunning.get()) {
            return;
        }

        try {
            IFaceIDAlgorithm.FaceIDResult result = mAlgorithm.processFrame(
                    task.frameData, task.width, task.height, task.format);

            task.result = result;
            task.processed = true;

            // 放入渲染队列（队列满时丢弃最旧帧）
            while (!mProcessedQueue.offer(task, OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                FrameTask oldest = mProcessedQueue.poll();
                if (oldest != null && !oldest.rendered) {
                    mBufferManager.recycleBuffer(oldest.buffer);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "processTask: algorithm error", e);
            // 异常时直接回退到原始数据渲染
            task.processed = true;
            task.result = new IFaceIDAlgorithm.FaceIDResult(
                    "", 0f, null, task.frameData, null);
            mProcessedQueue.offer(task);
        }
    }

    /**
     * 渲染循环 — 线程体。
     *
     * <p>从处理结果队列取出已完成的任务，交给 PreviewRenderer 渲染，
     * 渲染完成后归还 Buffer。
     */
    private void renderLoop() {
        while (mIsRunning.get()) {
            try {
                FrameTask task = mProcessedQueue.poll(RENDER_POLL_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS);
                if (task == null) {
                    continue;
                }

                // 渲染处理后的帧
                if (task.result != null) {
                    mPreviewRenderer.renderFrame(
                            task.result.getProcessedData(),
                            task.width,
                            task.height,
                            task.result.getFaceRect()
                    );
                }

                task.rendered = true;

                // 归还 Buffer（关键：避免阻塞）
                mBufferManager.recycleBuffer(task.buffer);

                // 回调 Face ID 结果（算法提供 faceId 时）
                if (task.result != null && !task.result.getFaceId().isEmpty()) {
                    onFaceIdDetected(task.result);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.e(TAG, "renderLoop: unexpected error", e);
            }
        }
        Log.i(TAG, "renderLoop: exited");
    }

    /**
     * Face ID 检测结果回调。子类可重写此方法获取 faceId 通知。
     *
     * @param result 算法处理结果
     */
    protected void onFaceIdDetected(IFaceIDAlgorithm.FaceIDResult result) {
        Log.i(TAG, "onFaceIdDetected: faceId=" + result.getFaceId()
                + ", confidence=" + result.getConfidence());
    }

    /**
     * 停止流水线。
     *
     * <p>停止所有线程、清空队列并归还残留 Buffer。
     */
    public void stop() {
        if (!mIsRunning.getAndSet(false)) {
            return;
        }

        Log.i(TAG, "stop: start");

        // 停止取流线程
        if (mCaptureThread != null) {
            mCaptureThread.interrupt();
            mCaptureThread = null;
        }

        // 停止处理线程池
        if (mProcessExecutor != null) {
            mProcessExecutor.shutdownNow();
            mProcessExecutor = null;
        }

        // 停止渲染线程
        if (mRenderThread != null) {
            mRenderThread.interrupt();
            mRenderThread = null;
        }

        // 清空队列并归还残留 Buffer
        FrameTask task;
        while ((task = mProcessedQueue.poll()) != null) {
            mBufferManager.recycleBuffer(task.buffer);
        }

        // 关闭 BufferManager
        mBufferManager.shutdown();

        Log.i(TAG, "stop: done, totalFrames=" + mFrameCounter.get());
    }

    /**
     * 流水线是否运行中。
     */
    public boolean isRunning() {
        return mIsRunning.get();
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /**
     * 从 EvsBufferDesc 的 HardwareBuffer 中读取帧数据。
     *
     * <p>通过 Bitmap.wrapHardwareBuffer 将 HardwareBuffer 包装为 Bitmap，
     * 然后读取像素数据。
     *
     * @param buffer EvsBufferDesc
     * @return 帧数据 byte[]（ARGB_8888 格式），null 表示读取失败
     */
    private byte[] readHardwareBuffer(EvsBufferDesc buffer) {
        try {
            HardwareBuffer hwBuffer = buffer.getHardwareBuffer();
            if (hwBuffer == null) {
                Log.w(TAG, "readHardwareBuffer: HardwareBuffer is null");
                return null;
            }

            // 使用 Bitmap.wrapHardwareBuffer 直接包装 HardwareBuffer
            Bitmap bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, null);
            if (bitmap == null) {
                Log.w(TAG, "readHardwareBuffer: Bitmap.wrapHardwareBuffer returned null");
                return null;
            }

            int byteCount = bitmap.getByteCount();
            ByteBuffer byteBuffer = ByteBuffer.allocate(byteCount);
            bitmap.copyPixelsToBuffer(byteBuffer);
            bitmap.recycle();

            Log.d(TAG, "readHardwareBuffer: " + buffer.getWidth() + "x" + buffer.getHeight()
                    + ", size=" + byteCount);
            return byteBuffer.array();

        } catch (Exception e) {
            Log.e(TAG, "readHardwareBuffer: failed", e);
            return null;
        }
    }

    /**
     * 估算 NV21 格式帧数据大小。
     *
     * @param width  图像宽度
     * @param height 图像高度
     * @param format 图像格式（预留）
     * @return 估算的字节数
     */
    public static int estimateFrameSize(int width, int height, int format) {
        // NV21: Y plane = width * height, UV plane = width * height / 2
        return width * height * 3 / 2;
    }
}
