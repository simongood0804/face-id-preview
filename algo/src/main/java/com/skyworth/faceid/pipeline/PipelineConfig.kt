/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.pipeline

/**
 * 帧处理流水线配置。
 *
 * 所有可调参数集中管理，方便调优。创建时使用默认值，可通过属性按需调整。
 * 应在流水线启动前完成配置（非线程安全）。
 */
class PipelineConfig {

    /** 最大缓存的帧数（防止内存堆积）。 */
    var maxPendingFrames: Int = DEFAULT_MAX_PENDING_FRAMES

    /** 算法处理超时（毫秒）。 */
    var processTimeoutMs: Long = DEFAULT_PROCESS_TIMEOUT_MS

    /** Buffer 回收超时（毫秒）。 */
    var bufferRecycleTimeoutMs: Long = DEFAULT_BUFFER_RECYCLE_TIMEOUT_MS

    /** 跳帧处理间隔（1 = 每帧都处理，2 = 隔一帧处理一次）。 */
    var frameSkipInterval: Int = DEFAULT_FRAME_SKIP_INTERVAL
        set(value) {
            field = value.coerceAtLeast(1)
        }

    override fun toString(): String = "PipelineConfig{" +
            "maxPendingFrames=$maxPendingFrames" +
            ", processTimeoutMs=$processTimeoutMs" +
            ", bufferRecycleTimeoutMs=$bufferRecycleTimeoutMs" +
            ", frameSkipInterval=$frameSkipInterval" +
            '}'

    companion object {
        /** 默认：最多缓存的帧数。 */
        const val DEFAULT_MAX_PENDING_FRAMES = 3

        /** 默认：算法处理超时时间（毫秒）。 */
        const val DEFAULT_PROCESS_TIMEOUT_MS = 500L

        /** 默认：Buffer 回收超时（毫秒）。 */
        const val DEFAULT_BUFFER_RECYCLE_TIMEOUT_MS = 1000L

        /** 默认：跳帧处理间隔。 */
        const val DEFAULT_FRAME_SKIP_INTERVAL = 1
    }
}
