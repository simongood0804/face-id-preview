package com.skyworth.faceid.bus

import java.util.concurrent.ConcurrentHashMap

/**
 * 总线健康监控器（Watchdog 角色）。
 *
 * 参照 openpilot 的健康检查思路，监控一组关键 topic 的 alive 状态：
 * - 周期性调用 [update] 检查各被监控 topic 是否仍在健康超时内收到数据；
 * - 检测到某 topic 从"健康"→"失活"的状态转变时，通过 [onFault] 回调上报故障事件；
 * - 单个 topic 失活只影响其自身与对应错误上报，**不影响其他 topic 与调用方**。
 *
 * 这是"某一层故障，其他层收到问题但不会崩溃"的核心机制：
 * - 健康检查本身不抛异常；
 * - 故障通过回调通知，由调用方决定降级策略；
 * - 监控器内部状态相互独立。
 *
 * 故障事件（[FaultEvent]）语义：
 * - [healthy]：受监控 topic 是否存活；
 * - [reason]：失活原因（健康超时）。
 *
 * 线程安全：状态使用 ConcurrentHashMap；[update] 建议在单一线程周期性调用。
 */
class HealthMonitor @JvmOverloads constructor(
    /** 受监控的关键 topic 及其故障码。 */
    private val monitored: Map<ServiceRegistry.Topic, String>,
    private val bus: BusHub,
    /** 故障上报回调（topic 失活时触发）。 */
    var onFault: ((topic: ServiceRegistry.Topic, event: FaultEvent) -> Unit)? = null
) {

    /** 受监控 topic 对应的错误 topic 码。 */
    data class FaultEvent(
        val code: String,
        val source: String,
        val message: String
    )

    /** 底层订阅器（复用 BusSubscriber 的 per-topic 健康检查）。 */
    private val subscriber: BusSubscriber = BusSubscriber(bus, monitored.keys)

    /** 各 topic 上一轮健康状态（用于检测状态转变）。 */
    private val prevHealthy = ConcurrentHashMap<ServiceRegistry.Topic, Boolean>()

    /** 各 topic 当前健康状态。 */
    private val healthyFlags = ConcurrentHashMap<ServiceRegistry.Topic, Boolean>()

    /** 各 topic 最近一次故障事件。 */
    private val lastFaults = ConcurrentHashMap<ServiceRegistry.Topic, FaultEvent>()

    init {
        for (topic in monitored.keys) {
            prevHealthy[topic] = false
            healthyFlags[topic] = false
        }
    }

    /**
     * 周期性健康检查。
     * 应周期性调用（如在监控线程循环中）。
     *
     * @param nowNanos 当前单调时钟（默认 [System.nanoTime]），便于测试注入
     */
    @JvmOverloads
    fun update(nowNanos: Long = System.nanoTime()) {
        subscriber.update(nowNanos)
        for ((topic, code) in monitored) {
            val wasHealthy = prevHealthy[topic] ?: false
            val isHealthy = subscriber.alive(topic)
            healthyFlags[topic] = isHealthy
            prevHealthy[topic] = isHealthy
            if (!isHealthy && wasHealthy) {
                // 状态转变：健康 → 失活，上报故障
                val event = FaultEvent(
                    code = code,
                    source = topic.name,
                    message = "topic '${topic.name}' heartbeat timeout (${ServiceRegistry.healthTimeoutMs(topic)}ms)"
                )
                lastFaults[topic] = event
                try {
                    onFault?.invoke(topic, event)
                } catch (e: Exception) {
                    // 故障回调不向外传播异常，避免拖垮监控线程
                }
            }
        }
    }

    /** 某受监控 topic 当前是否健康。 */
    fun healthy(topic: ServiceRegistry.Topic): Boolean = healthyFlags[topic] ?: false

    /** 某受监控 topic 是否处于失活状态。 */
    fun isFaulted(topic: ServiceRegistry.Topic): Boolean = !(healthyFlags[topic] ?: false)

    /** 某受监控 topic 最近一次的故障事件；无故障返回 null。 */
    fun lastFault(topic: ServiceRegistry.Topic): FaultEvent? = lastFaults[topic]

    /** 释放订阅资源。 */
    fun close() {
        subscriber.close()
        healthyFlags.clear()
        lastFaults.clear()
    }
}
