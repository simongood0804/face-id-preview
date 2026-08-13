package com.skyworth.faceid.bus

/**
 * 消息总线 - Topic 注册表。
 *
 * 参照 openpilot 的 services.py 设计：集中注册所有服务（topic）名及其基准频率。
 * 每个 topic 的基准频率既用于调度参考，也是订阅侧 per-topic 健康检查（alive）的判定基准。
 *
 * 本类为纯 JVM 实现，不依赖 Android 系统库，便于单元测试。
 */
object ServiceRegistry {

    /**
     * 消息总线的命名 Topic 及其基准频率（Hz）。
     *
     * @property freqHz 该 topic 的期望发布频率，用于健康检查超时判定
     * @property isCritical 是否关键 topic（健康检查超时后派发故障事件）
     */
    enum class Topic(
        val freqHz: Int,
        val isCritical: Boolean = true
    ) {
        /** 车机车速信号。 */
        VEHICLE_SPEED(10),

        /** 算法结果（人脸/分心/头姿/识别）。 */
        ALGO_RESULT(20),

        /** 算法健康状态。 */
        ALGO_STATE(1),

        /** 帧已就绪（元数据 + 共享内存句柄）。 */
        FRAME_READY(30),

        /** 绘制层可消费的 overlay 数据。 */
        FRAME_OVERLAY(20),

        /** 信号层故障上报。 */
        SIGNAL_ERROR(1),

        /** 帧层故障上报。 */
        FRAME_ERROR(1),

        /** 总线心跳（watchdog 基准）。 */
        BUS_HEARTBEAT(1)
    }

    /**
     * 健康检查超时倍数：超过 `k / freqHz` 秒未收到更新即视为该 topic 失活。
     * 与 openpilot 的 alive/freq_ok 判定思路一致，避免偶发掉帧误判。
     */
    const val HEALTH_CHECK_MULTIPLIER = 3

    /**
     * 根据 topic 基准频率计算健康超时毫秒数。
     * 超时 = (k / freqHz) 秒。频率越低，允许的失活窗口越长。
     *
     * @param topic 目标 topic
     * @return 健康超时毫秒数；若 freqHz <= 0 返回 Long.MAX_VALUE（不健康检查）
     */
    fun healthTimeoutMs(topic: Topic): Long {
        if (topic.freqHz <= 0) return Long.MAX_VALUE
        return (HEALTH_CHECK_MULTIPLIER * 1000L) / topic.freqHz
    }
}
