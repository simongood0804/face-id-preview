package com.skyworth.faceid.bus

import java.util.concurrent.ConcurrentHashMap

/**
 * 消息总线枢纽。
 *
 * 管理所有 topic 对应的 [BusQueue]，并提供 reader 注册 / 发布 / 读取的底层操作。
 * [BusPublisher] 与 [BusSubscriber] 均通过本类与底层队列交互。
 *
 * 设计目标：
 * - **解耦**：各层只通过 topic 通信，不持有彼此对象引用；
 * - **隔离**：每个 topic 独立队列，一个 topic 的故障/拥塞不影响其他 topic；
 * - **进程内承载**：阶段一为纯 JVM 内存实现，后续可替换为共享内存载体。
 *
 * 线程安全：队列操作基于原子变量；集合使用 ConcurrentHashMap。
 *
 * 阶段 C：队列抽象为 [MessageQueue]，默认进程内 [BusQueue]；
 * 可通过 [queueFactory] 注入跨进程 [ShmMessageQueue]（基于 [ShmQueue]），
 * 实现同 API、进程内/跨进程可切换。
 */
class BusHub(
    private val queueFactory: () -> MessageQueue = { BusQueue() }
) {

    private val queues = ConcurrentHashMap<ServiceRegistry.Topic, MessageQueue>()

    /**
     * 注册一个订阅者 reader 到指定 topic，返回 reader id；失败返回 -1。
     */
    fun registerReader(topic: ServiceRegistry.Topic): Int {
        return queueFor(topic).registerReader()
    }

    /**
     * 注销指定 topic 的 reader。
     */
    fun unregisterReader(topic: ServiceRegistry.Topic, readerId: Int) {
        queueFor(topic).unregisterReader(readerId)
    }

    /**
     * 发布一条消息到指定 topic 的队列。
     */
    fun publishTo(topic: ServiceRegistry.Topic, msg: BusMessage) {
        queueFor(topic).publish(msg)
    }

    /**
     * 读取指定 topic 队列中某 reader 的下一条消息。
     */
    fun readNext(topic: ServiceRegistry.Topic, readerId: Int): BusMessage? {
        return queueFor(topic).readNext(readerId)
    }

    /**
     * 判断指定 topic 队列中某 reader 是否仍有未读消息。
     */
    fun hasNext(topic: ServiceRegistry.Topic, readerId: Int): Boolean {
        return queueFor(topic).hasNext(readerId)
    }

    /** 获取（或创建）某 topic 对应的队列。 */
    private fun queueFor(topic: ServiceRegistry.Topic): MessageQueue =
        queues.computeIfAbsent(topic) { queueFactory() }

    /** 当前已创建队列的 topic 数（调试用）。 */
    fun topicCount(): Int = queues.size

    /** 清空所有队列。 */
    fun reset() {
        queues.values.forEach { it.reset() }
    }
}
