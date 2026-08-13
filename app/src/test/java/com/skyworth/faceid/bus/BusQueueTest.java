package com.skyworth.faceid.bus;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * BusQueue（单写多读环形队列）单元测试。
 *
 * 验证 openpilot msgq 式的多读者-单写者、独立读指针、慢读者隔离等核心特性。
 */
public class BusQueueTest {

    private BusQueue mQueue;

    @Before
    public void setUp() {
        mQueue = new BusQueue(8, 15);
    }

    private BusMessage message(long seq) {
        return new BusMessage(ServiceRegistry.Topic.BUS_HEARTBEAT, seq, seq, System.nanoTime());
    }

    @Test
    public void testPublishAndReadSingleReader() {
        int r = mQueue.registerReader();
        assertEquals(0, r);

        mQueue.publish(message(1));
        mQueue.publish(message(2));

        assertTrue("应有未读消息", mQueue.hasNext(r));
        assertEquals(1L, mQueue.readNext(r).getSequence());
        assertEquals(2L, mQueue.readNext(r).getSequence());
        assertNull("读尽后应为 null", mQueue.readNext(r));
    }

    @Test
    public void testIndependentReaderPositions() {
        int r1 = mQueue.registerReader();
        int r2 = mQueue.registerReader();

        mQueue.publish(message(1));
        mQueue.publish(message(2));

        // r1 读取 1 条
        assertEquals(1L, mQueue.readNext(r1).getSequence());
        // r2 尚未读取，仍可读到第 1 条（独立读指针）
        assertEquals(1L, mQueue.readNext(r2).getSequence());
        assertEquals(2L, mQueue.readNext(r2).getSequence());
        // r1 继续读到第 2 条
        assertEquals(2L, mQueue.readNext(r1).getSequence());
        assertNull(mQueue.readNext(r1));
    }

    @Test
    public void testSlowReaderDoesNotBlockPublisher() {
        int r1 = mQueue.registerReader();
        mQueue.registerReader(); // r2 慢读者

        // 发布多条，超过容量（环形覆盖）
        for (long i = 1; i <= 30; i++) {
            mQueue.publish(message(i));
        }

        // 慢读者 r2 读到的位置早于发布量，但发布者不被阻塞
        assertEquals(30L, mQueue.publishedCount());
        // r1（一直读取）能追到最新
        for (long i = 1; i <= 30; i++) {
            mQueue.readNext(r1);
        }
        assertEquals(30L, mQueue.readerPosition(r1));
    }

    @Test
    public void testExceedMaxReadersReturnsMinusOne() {
        int r1 = mQueue.registerReader();
        int r2 = mQueue.registerReader();
        int r3 = mQueue.registerReader();
        int r4 = mQueue.registerReader();
        assertTrue(r1 >= 0 && r2 >= 0 && r3 >= 0 && r4 >= 0);

        // 容量 8，maxReaders 15 不触发；单独用小队列测试上限
        BusQueue small = new BusQueue(8, 2);
        assertEquals(0, small.registerReader());
        assertEquals(1, small.registerReader());
        assertEquals(-1, small.registerReader());
    }

    @Test
    public void testUnregisterReader() {
        int r = mQueue.registerReader();
        assertEquals(1, mQueue.readerCount());
        mQueue.unregisterReader(r);
        assertEquals(0, mQueue.readerCount());
        assertNull("注销后读不到", mQueue.readNext(r));
    }

    @Test
    public void testReset() {
        int r = mQueue.registerReader();
        mQueue.publish(message(1));
        mQueue.publish(message(2));
        mQueue.readNext(r);

        mQueue.reset();
        assertEquals(0L, mQueue.publishedCount());
        assertNull("重置后无消息", mQueue.readNext(r));
    }

    @Test
    public void testInvalidReaderReturnsNull() {
        assertNull("负 reader 返回 null", mQueue.readNext(-1));
        assertNull("越界 reader 返回 null", mQueue.readNext(99));
    }

    @Test
    public void testCapacityMustBePowerOfTwoEnforced() {
        // capacity 为 6 时内部提升为 8，仍可正常发布
        BusQueue q = new BusQueue(6, 4);
        int r = q.registerReader();
        for (long i = 1; i <= 10; i++) {
            q.publish(message(i));
        }
        assertEquals(10L, q.publishedCount());
        for (long i = 1; i <= 10; i++) {
            assertNotNull("应读到第 " + i + " 条", q.readNext(r));
        }
    }
}
