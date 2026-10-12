package com.skyworth.faceid.bus

import java.util.concurrent.ConcurrentHashMap

/**
 * 消息发布端 API（参照 openpilot PubMaster）。
 *
 * 按 topic 向对应 [BusQueue] 发布 [BusMessage]，发布端无需关心订阅者数量与状态。
 * 每个 topic 维护独立序号，自增保证 freshness 判定。
 *
 * 线程安全：使用 ConcurrentHashMap 保证多线程发布安全。
 */
class BusPublisher(private val bus: BusHub) {

    private val counters = ConcurrentHashMap<ServiceRegistry.Topic, Long>()

    /**
     * 发布一条消息到指定 topic。
     *
     * @param topic 目标 topic
     * @param payload 载荷（不可变值对象）
     * @return 该 topic 本次发布的序号
     */
    fun publish(topic: ServiceRegistry.Topic, payload: Any): Long {
        val seq = counters.merge(topic, 1L, Long::plus)!!
        val msg = BusMessage(
            topic = topic,
            payload = payload,
            sequence = seq,
            timestampNanos = System.nanoTime()
        )
        bus.publishTo(topic, msg)
        return seq
    }

    /** 当前某 topic 已发布消息数。 */
    fun publishedCount(topic: ServiceRegistry.Topic): Long = counters[topic] ?: 0L
}
