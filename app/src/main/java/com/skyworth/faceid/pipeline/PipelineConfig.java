/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.pipeline;

/**
 * 帧处理流水线配置。
 *
 * <p>所有可调参数集中管理，方便调优。创建时使用默认值，可通过 setter 按需调整。
 *
 * <p>线程安全：配置类，非线程安全。应在流水线启动前完成配置。
 */
public class PipelineConfig {

    /** 默认：最多缓存的帧数（防止内存堆积）。 */
    public static final int DEFAULT_MAX_PENDING_FRAMES = 3;

    /** 默认：算法处理超时时间（毫秒），超时强制回收 Buffer。 */
    public static final long DEFAULT_PROCESS_TIMEOUT_MS = 500L;

    /** 默认：Buffer 回收超时（毫秒），超时不再等待直接回收。 */
    public static final long DEFAULT_BUFFER_RECYCLE_TIMEOUT_MS = 1000L;

    /** 默认：跳帧处理间隔（1 = 每帧都处理，2 = 隔一帧处理一次）。 */
    public static final int DEFAULT_FRAME_SKIP_INTERVAL = 1;

    /** 最大缓存的帧数。 */
    private int mMaxPendingFrames;

    /** 算法处理超时（毫秒）。 */
    private long mProcessTimeoutMs;

    /** Buffer 回收超时（毫秒）。 */
    private long mBufferRecycleTimeoutMs;

    /** 跳帧处理间隔。 */
    private int mFrameSkipInterval;

    /** 使用默认值构造配置。 */
    public PipelineConfig() {
        mMaxPendingFrames = DEFAULT_MAX_PENDING_FRAMES;
        mProcessTimeoutMs = DEFAULT_PROCESS_TIMEOUT_MS;
        mBufferRecycleTimeoutMs = DEFAULT_BUFFER_RECYCLE_TIMEOUT_MS;
        mFrameSkipInterval = DEFAULT_FRAME_SKIP_INTERVAL;
    }

    public int getMaxPendingFrames() {
        return mMaxPendingFrames;
    }

    public void setMaxPendingFrames(int maxPendingFrames) {
        this.mMaxPendingFrames = maxPendingFrames;
    }

    public long getProcessTimeoutMs() {
        return mProcessTimeoutMs;
    }

    public void setProcessTimeoutMs(long processTimeoutMs) {
        this.mProcessTimeoutMs = processTimeoutMs;
    }

    public long getBufferRecycleTimeoutMs() {
        return mBufferRecycleTimeoutMs;
    }

    public void setBufferRecycleTimeoutMs(long bufferRecycleTimeoutMs) {
        this.mBufferRecycleTimeoutMs = bufferRecycleTimeoutMs;
    }

    public int getFrameSkipInterval() {
        return mFrameSkipInterval;
    }

    public void setFrameSkipInterval(int frameSkipInterval) {
        mFrameSkipInterval = Math.max(1, frameSkipInterval);
    }

    @Override
    public String toString() {
        return "PipelineConfig{"
                + "maxPendingFrames=" + mMaxPendingFrames
                + ", processTimeoutMs=" + mProcessTimeoutMs
                + ", bufferRecycleTimeoutMs=" + mBufferRecycleTimeoutMs
                + ", frameSkipInterval=" + mFrameSkipInterval
                + '}';
    }
}
