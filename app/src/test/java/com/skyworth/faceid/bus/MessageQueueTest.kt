package com.skyworth.faceid.bus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

/**
 * [MessageQueue] 统一抽象单测：
 * - [BusQueue]（进程内，对象引用）作为 [MessageQueue] 的行为；
 * - [ShmMessageQueue]（适配 [ShmQueue]，跨进程序列化字节）的行为。
 *
 * 均为纯 JVM 测试：用 [ByteBuffer.allocateDirect] 构造 [ShmQueue]，
 * 不依赖真机 [android.os.SharedMemory]。
 */
class MessageQueueTest {

    private fun newShmMessageQueue(capacity: Int = 4, maxReaders: Int = 3): ShmMessageQueue {
        val size = ShmQueue.totalSize(capacity, maxReaders)
        val buf = ByteBuffer.allocateDirect(size)
        val q = ShmQueue(buf, capacity, maxReaders)
        q.initializeHeader()
        return ShmMessageQueue(q)
    }

    private fun msg(topic: ServiceRegistry.Topic, payload: Any): BusMessage =
        BusMessage(topic, payload, 0L, 0L)

    // ---------------------------------------------------------------
    // BusQueue (MessageQueue)
    // ---------------------------------------------------------------

    @Test
    fun `BusQueue implements MessageQueue basic flow`() {
        val q: MessageQueue = BusQueue(capacity = 4, maxReaders = 2)
        val r0 = q.registerReader()
        assertEquals(0, r0)

        q.publish(msg(ServiceRegistry.Topic.ALGO_RESULT, byteArrayOf(1)))
        q.publish(msg(ServiceRegistry.Topic.VEHICLE_SPEED, byteArrayOf(2)))

        val m1 = q.readNext(r0)
        assertNotNull(m1)
        assertEquals(ServiceRegistry.Topic.ALGO_RESULT, m1!!.topic)
        // 第二条是 VEHICLE_SPEED
        val m2 = q.readNext(r0)
        assertEquals(ServiceRegistry.Topic.VEHICLE_SPEED, m2!!.topic)
        // 读完后无新消息
        assertNull(q.readNext(r0))
    }

    // ---------------------------------------------------------------
    // ShmMessageQueue (MessageQueue 适配 ShmQueue)
    // ---------------------------------------------------------------

    @Test
    fun `ShmMessageQueue publishes and reads BusMessage via byte payload`() {
        val q = newShmMessageQueue()
        val r0 = q.registerReader()
        assertEquals(0, r0)

        val payload = byteArrayOf(10, 20, 30)
        q.publish(msg(ServiceRegistry.Topic.ALGO_RESULT, payload))
        q.publish(msg(ServiceRegistry.Topic.FRAME_OVERLAY, byteArrayOf(1)))

        val m1 = q.readNext(r0)
        assertNotNull(m1)
        assertEquals(ServiceRegistry.Topic.ALGO_RESULT, m1!!.topic)
        // payload 经序列化往返后应保持一致
        assert(m1.payload is ByteArray)
        assert((m1.payload as ByteArray).contentEquals(payload))

        val m2 = q.readNext(r0)
        assertEquals(ServiceRegistry.Topic.FRAME_OVERLAY, m2!!.topic)
    }

    @Test
    fun `ShmMessageQueue rejects non ByteArray payload`() {
        val q = newShmMessageQueue()
        var threw = false
        try {
            q.publish(msg(ServiceRegistry.Topic.ALGO_RESULT, "not-bytes"))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assert(threw)
    }

    @Test
    fun `ShmMessageQueue independent reader pointers`() {
        val q = newShmMessageQueue()
        val r0 = q.registerReader()
        val r1 = q.registerReader()

        q.publish(msg(ServiceRegistry.Topic.ALGO_RESULT, byteArrayOf(1)))
        q.publish(msg(ServiceRegistry.Topic.ALGO_RESULT, byteArrayOf(2)))

        // r1 读两条，r0 只读一条
        assertEquals(1, (q.readNext(r1)!!.payload as ByteArray)[0].toInt())
        assertEquals(2, (q.readNext(r1)!!.payload as ByteArray)[0].toInt())
        assertNull(q.readNext(r1))

        assertEquals(1, (q.readNext(r0)!!.payload as ByteArray)[0].toInt())
    }
}
