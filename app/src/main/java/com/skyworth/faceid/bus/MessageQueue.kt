package com.skyworth.faceid.bus

/**
 * 消息队列统一抽象（阶段 C）。
 *
 * 让上层 [BusHub] 按需选择**进程内**（[BusQueue]，`BusMessage` 对象引用）
 * 或**跨进程**（[ShmQueue] 适配，负载序列化为字节）实现，实现"同 API、多载体"。
 *
 * 消息以 [BusMessage] 为统一类型：
 * - [BusQueue] 直接承载对象引用（单进程，零拷贝）；
 * - [ShmMessageQueue] 把 `payload` 序列化/反序列化为字节（跨进程）。
 *
 * 线程模型：多读者-单写者，[publish] 单写者调用；[readNext]/[hasNext]
 * 每 reader 独立调用（建议单消费线程）。
 */
interface MessageQueue {

    /**
     * 发布一条消息（单写者路径）。
     *
     * 跨进程实现要求 `payload` 可序列化（如 [ByteArray]），否则抛异常。
     *
     * @return 写入的序号
     */
    fun publish(msg: BusMessage): Long

    /** 读取某 reader 的下一条消息；无新消息或 reader 无效返回 null。 */
    fun readNext(readerId: Int): BusMessage?

    /** 某 reader 是否仍有未读消息。 */
    fun hasNext(readerId: Int): Boolean

    /** 注册一个 reader，返回 id；超过上限返回 -1。 */
    fun registerReader(): Int

    /** 注销 reader。 */
    fun unregisterReader(readerId: Int)

    /** 清空队列（重置所有读指针与消息）。 */
    fun reset()
}
