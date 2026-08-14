package com.skyworth.faceid.bus

import android.os.SharedMemory

/**
 * [ShmQueue] 的 [MessageQueue] 适配器（阶段 C）。
 *
 * 把统一消息 [BusMessage] 与跨进程 [ShmQueue] 桥接：
 * - [publish]：要求 `payload` 为 [ByteArray]（跨进程共享原始字节），
 *   经 [ShmMessageSerializer] 编码（topic 头 + 负载）写入共享内存；
 * - [readNext]：从共享内存解出 topic + 负载，重建 [BusMessage]。
 *
 * 这样上层 [BusHub] 可通过本适配器在"进程内 [BusQueue] / 跨进程 [ShmQueue]"间
 * 无缝切换，而 [BusSubscriber]/[BusPublisher] 无需改动。
 */
class ShmMessageQueue(
    private val queue: ShmQueue
) : MessageQueue {

    override fun publish(msg: BusMessage): Long {
        // 跨进程负载必须是可序列化的原始字节
        val payload = msg.payload as? ByteArray
            ?: throw IllegalArgumentException(
                "cross-process ShmMessageQueue requires ByteArray payload, got ${msg.payload?.javaClass}")
        // topic 转 int（ServiceRegistry.Topic 的枚举 id）
        val topicId = topicToId(msg.topic)
        return queue.publish(topicId, payload)
    }

    override fun readNext(readerId: Int): BusMessage? {
        val m = queue.readNext(readerId) ?: return null
        val topic = idToTopic(m.topic)
        if (topic == null) {
            // 未知 topic id：丢弃，不阻塞
            return null
        }
        return BusMessage(
            topic = topic,
            payload = m.payload,
            sequence = m.sequence,
            timestampNanos = System.nanoTime()
        )
    }

    override fun hasNext(readerId: Int): Boolean = queue.hasNext(readerId)

    override fun registerReader(): Int = queue.registerReader()

    override fun unregisterReader(readerId: Int) = queue.unregisterReader(readerId)

    override fun reset() {
        // ShmQueue 无进程内 reset（跨进程队列头复位需写者配合），此处不支持
        throw UnsupportedOperationException("ShmQueue does not support reset()")
    }

    /** 底层 ShmQueue（供提供/消费方直接操作或挂载）。 */
    fun underlying(): ShmQueue = queue

    private fun topicToId(topic: ServiceRegistry.Topic): Int = topic.id

    private fun idToTopic(id: Int): ServiceRegistry.Topic? =
        ServiceRegistry.Topic.values().firstOrNull { it.id == id }
}
