package com.skyworth.faceid.bus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * [ShmQueue] 核心逻辑单测（纯 JVM，不依赖真机 SharedMemory）。
 *
 * 通过 [ByteBuffer.allocateDirect] 构造队列，验证 publish/readNext 的
 * 序号连续性、环形回绕、多读者独立读指针、慢读者失效等核心行为。
 * 跨进程映射（[ShmQueue.create]/[attach]）需真机/阶段 B 验证。
 */
class ShmQueueTest {

    private fun newQueue(capacity: Int = 4, maxReaders: Int = 3): ShmQueue {
        val size = ShmQueue.totalSize(capacity, maxReaders)
        val buf = ByteBuffer.allocateDirect(size)
        val q = ShmQueue(buf, capacity, maxReaders)
        q.initializeHeader() // 模拟 create() 的初始化
        return q
    }

    @Test
    fun `basic publish and read single reader`() {
        val q = newQueue(capacity = 4, maxReaders = 3)
        val r0 = q.registerReader()
        assertEquals(0, r0)

        q.publish(1, byteArrayOf(10, 20))
        q.publish(2, byteArrayOf(30))
        q.publish(3, byteArrayOf(40, 50, 60))

        assertTrue(q.hasNext(r0))
        val m1 = q.readNext(r0)
        assertNotNull(m1)
        assertEquals(1, m1!!.topic)
        assertTrue(m1.payload.contentEquals(byteArrayOf(10, 20)))

        val m2 = q.readNext(r0)
        assertEquals(2, m2!!.topic)
        val m3 = q.readNext(r0)
        assertEquals(3, m3!!.topic)
        // 读尽后返回 null
        assertNull(q.readNext(r0))
        assertTrue(!q.hasNext(r0))
    }

    @Test
    fun `multiple readers independent pointers`() {
        val q = newQueue(capacity = 4, maxReaders = 3)
        val r0 = q.registerReader()
        val r1 = q.registerReader()
        assertEquals(0, r0)
        assertEquals(1, r1)

        q.publish(1, byteArrayOf(1))
        q.publish(2, byteArrayOf(2))

        // r0 只读第一条，r1 读两条
        assertEquals(1, q.readNext(r0)!!.topic)
        assertTrue(q.hasNext(r0)) // r0 还有第 2 条
        assertEquals(1, q.readNext(r1)!!.topic)
        assertEquals(2, q.readNext(r1)!!.topic)
        assertNull(q.readNext(r1))
        // r0 仍可读到第 2 条
        assertEquals(2, q.readNext(r0)!!.topic)
        assertNull(q.readNext(r0))
    }

    @Test
    fun `sequence wraps around ring buffer`() {
        val q = newQueue(capacity = 4, maxReaders = 3)
        val r0 = q.registerReader()
        // 持续发布并读取（reader 及时消费，不被标记失效），验证回绕后序号仍单调正确
        for (i in 0 until 10) {
            q.publish(i, byteArrayOf(i.toByte()))
            val m = q.readNext(r0)
            assertNotNull(m)
            assertEquals(i.toLong(), m!!.sequence)
        }
        assertNull(q.readNext(r0))
    }

    @Test
    fun `slow reader becomes invalid when overwritten`() {
        val q = newQueue(capacity = 4, maxReaders = 3)
        val r0 = q.registerReader()  // 慢读者，从不读
        // 发布 5 条（seq 0-4），第 5 条回绕覆盖 slot0
        for (i in 1..5) {
            q.publish(i, byteArrayOf(i.toByte()))
        }
        // r0 读指针(0)停留在 slot0，已被 seq4 覆盖 → r0 失效
        assertNull(q.readNext(r0))
        assertTrue(!q.hasNext(r0))
    }

    @Test
    fun `unregister reader then cannot read`() {
        val q = newQueue(capacity = 4, maxReaders = 3)
        val r0 = q.registerReader()
        q.publish(1, byteArrayOf(1))
        q.unregisterReader(r0)
        assertNull(q.readNext(r0))
        assertTrue(!q.hasNext(r0))
    }

    @Test
    fun `re-register same reader slot becomes valid again`() {
        val q = newQueue(capacity = 4, maxReaders = 2)
        val r0 = q.registerReader()
        q.unregisterReader(r0)
        // 注销后重注册同一槽位（id 应为 0），readerValid 需复位，否则无法读
        val r0b = q.registerReader()
        assertEquals(0, r0b)
        q.publish(1, byteArrayOf(42))
        val m = q.readNext(r0b)
        assertNotNull(m)
        assert(m!!.payload.contentEquals(byteArrayOf(42)))
    }

    @Test
    fun `serializer round trip`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = ShmMessageSerializer.encode(0x12, payload)
        assertEquals(0x12, ShmMessageSerializer.decodeTopic(encoded))
        assertTrue(ShmMessageSerializer.decodePayload(encoded).contentEquals(payload))
    }

    @Test
    fun `publish beyond max readers returns -1`() {
        val q = newQueue(capacity = 4, maxReaders = 2)
        assertEquals(0, q.registerReader())
        assertEquals(1, q.registerReader())
        assertEquals(-1, q.registerReader())
    }
}
