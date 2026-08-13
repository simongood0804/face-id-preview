package com.skyworth.faceid.bus

/**
 * 总线消息容器。
 *
 * 参照 openpilot cereal Event 的 tagged union 思路：所有消息都携带 topic 标识（tag），
 * 消费者只关心自己订阅的 topic。数据载荷以 [Any] 存放不可变值对象，避免跨层共享可变状态。
 *
 * @property topic 消息所属 topic
 * @property payload 载荷（应为不可变值对象）
 * @property sequence 单调递增序号（可用于判断 freshness / 丢帧）
 * @property timestampNanos 发布时间戳（System.nanoTime 单调时钟）
 */
data class BusMessage(
    val topic: ServiceRegistry.Topic,
    val payload: Any,
    val sequence: Long,
    val timestampNanos: Long
)
