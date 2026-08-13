package com.skyworth.faceid.bus

import java.util.concurrent.ConcurrentHashMap

/**
 * 消息订阅端 API（参照 openpilot SubMaster）。
 *
 * 订阅若干 topic，轮询所有订阅队列读取新消息并缓存最新值；
 * 通过 per-topic 健康检查判定 alive 状态——某个 topic 断流/变慢只影响它自己的 alive，
 * **不影响其他 topic 的读取**，这正是"单层故障不拖垮其他层"的核心机制。
 *
 * 用法：
 * ```
 * val sub = BusSubscriber(bus, setOf(Topic.ALGO_RESULT, Topic.VEHICLE_SPEED))
 * sub.update()        // 轮询拉取新消息
 * if (sub.alive(Topic.ALGO_RESULT)) {
 *     val r = sub.latest<T>(Topic.ALGO_RESULT)  // 最新消息
 * }
 * ```
 *
 * 线程安全：latest/health 使用 ConcurrentHashMap，update() 建议在单一线程调用。
 */
class BusSubscriber(
    private val bus: BusHub,
    private val topics: Set<ServiceRegistry.Topic>
) {
    /** reader id -> topic 映射（每个订阅的 topic 一个独立 reader 槽位）。 */
    private val readerIds = ConcurrentHashMap<ServiceRegistry.Topic, Int>()

    /** 各 topic 最新消息。 */
    private val latestMessages = ConcurrentHashMap<ServiceRegistry.Topic, BusMessage>()

    /** 各 topic 最后一次轮询时收到消息的单调时钟纳秒（即"最后存活确认时刻"）。 */
    private val lastUpdateNanos = ConcurrentHashMap<ServiceRegistry.Topic, Long>()

    /** 各 topic alive 状态。 */
    private val aliveFlags = ConcurrentHashMap<ServiceRegistry.Topic, Boolean>()

    /** 各 topic 上一轮 [update] 是否有新消息。 */
    private val freshFlags = ConcurrentHashMap<ServiceRegistry.Topic, Boolean>()

    init {
        for (topic in topics) {
            val readerId = bus.registerReader(topic)
            if (readerId >= 0) {
                readerIds[topic] = readerId
                aliveFlags[topic] = false
            }
        }
    }

    /**
     * 轮询所有订阅 topic，拉取新消息并更新缓存与健康状态。
     * 应周期性调用（如在每层自己的线程循环中）。
     *
     * 健康检查语义：以每次 [update] 的 [nowNanos] 为"当前时刻"，若某 topic 距上次
     * **收到消息时的 [nowNanos]** 已超过健康超时仍未更新，则判定失活。
     * 某个 topic 断流只影响它自己的 alive，不影响其他 topic。
     *
     * @param nowNanos 当前单调时钟（默认 [System.nanoTime]），便于测试注入
     */
    @JvmOverloads
    fun update(nowNanos: Long = System.nanoTime()) {
        for (topic in topics) {
            val readerId = readerIds[topic] ?: continue
            // 拉取该 reader 下所有新消息，保留最新一条
            var latest: BusMessage? = null
            while (bus.hasNext(topic, readerId)) {
                val msg = bus.readNext(topic, readerId) ?: break
                latest = msg
            }
            if (latest != null) {
                latestMessages[topic] = latest
                lastUpdateNanos[topic] = nowNanos
                aliveFlags[topic] = true
                freshFlags[topic] = true
            } else {
                freshFlags[topic] = false
                // 健康检查：距上次收到消息的轮询时刻是否已超过超时
                val lastTs = lastUpdateNanos[topic]
                val timeout = ServiceRegistry.healthTimeoutMs(topic) * 1_000_000L
                if (lastTs == null || (nowNanos - lastTs) > timeout) {
                    aliveFlags[topic] = false
                }
            }
        }
    }

    /**
     * 该 topic 是否存活（在健康超时内收到过数据）。
     * topic 未订阅时返回 false。
     */
    fun alive(topic: ServiceRegistry.Topic): Boolean = aliveFlags[topic] ?: false

    /** 该 topic 在上一轮 [update] 中是否有新消息。 */
    fun updated(topic: ServiceRegistry.Topic): Boolean = freshFlags[topic] ?: false

    /** 该 topic 的最新消息；无消息返回 null。 */
    fun latest(topic: ServiceRegistry.Topic): BusMessage? = latestMessages[topic]

    /** 该 topic 的最新消息载荷（类型安全），无消息或类型不符返回 null。 */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> latestPayload(topic: ServiceRegistry.Topic, clazz: Class<T>): T? {
        val msg = latestMessages[topic] ?: return null
        return if (clazz.isInstance(msg.payload)) msg.payload as T else null
    }

    /** 注销所有订阅 reader，释放槽位。 */
    fun close() {
        readerIds.forEach { (topic, readerId) -> bus.unregisterReader(topic, readerId) }
        readerIds.clear()
        latestMessages.clear()
        aliveFlags.clear()
    }
}
