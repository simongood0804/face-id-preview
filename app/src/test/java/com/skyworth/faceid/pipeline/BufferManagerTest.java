package com.skyworth.faceid.pipeline;

import com.skyworth.faceid.camera.MockCameraManager;
import com.skyworth.faceid.camera.MockEvsBufferDesc;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLog;

import static org.junit.Assert.*;

/**
 * BufferManager 单元测试
 *
 * 测试核心功能：
 *   1. Buffer 注册与归还
 *   2. 重复归还防护
 *   3. 空 Buffer 安全处理
 *   4. 超时强制回收
 *   5. shutdown 清理
 *   6. 并发安全性
 */
@RunWith(RobolectricTestRunner.class)
public class BufferManagerTest {

    private MockCameraManager mMockCameraManager;
    private BufferManager mBufferManager;
    private static final long TIMEOUT_MS = 500;

    @Before
    public void setUp() {
        // 启用 android.util.Log 输出（默认 Log 在 Robolectric 中被拦截）
        ShadowLog.stream = System.out;
        mMockCameraManager = new MockCameraManager();
        mBufferManager = new BufferManager(mMockCameraManager, TIMEOUT_MS);
    }

    @After
    public void tearDown() {
        mBufferManager.shutdown();
    }

    // ========== 基础功能测试 ==========

    @Test
    public void testRegisterAndRecycle() {
        MockEvsBufferDesc buffer = new MockEvsBufferDesc(1, 640, 480);
        mBufferManager.registerBuffer(buffer);

        assertEquals("注册后活跃 Buffer 数应为 1", 1, mBufferManager.getActiveBufferCount());
        assertEquals("总帧数应为 1", 1, mBufferManager.getTotalFrameCount());

        mBufferManager.recycleBuffer(buffer);
        assertTrue("Buffer 应被标记为已回收", buffer.isRecycled());
        assertEquals("回收后活跃 Buffer 数应为 0", 0, mBufferManager.getActiveBufferCount());
    }

    @Test
    public void testRegisterAndRecycleById() {
        MockEvsBufferDesc buffer = new MockEvsBufferDesc(5, 1280, 720);
        mBufferManager.registerBuffer(buffer);

        assertEquals(1, mBufferManager.getActiveBufferCount());

        mBufferManager.recycleBuffer(5);
        assertTrue(buffer.isRecycled());
        assertEquals(0, mBufferManager.getActiveBufferCount());
    }

    @Test
    public void testRegisterNullBuffer() {
        // null 注册应安全无异常
        mBufferManager.registerBuffer(null);
        assertEquals("null 注册不应增加活跃数", 0, mBufferManager.getActiveBufferCount());
        assertEquals("null 注册不应计入总帧数", 0, mBufferManager.getTotalFrameCount());
    }

    @Test
    public void testRecycleNullBuffer() {
        // null 归还应安全无异常
        mBufferManager.recycleBuffer((MockEvsBufferDesc) null);
        assertEquals(0, mBufferManager.getActiveBufferCount());
    }

    @Test
    public void testRecycleUnknownBuffer() {
        // 归还未注册的 Buffer ID 应安全
        mBufferManager.recycleBuffer(999);
        assertEquals(0, mBufferManager.getActiveBufferCount());
        assertEquals("泄漏次数不应增加", 0, mBufferManager.getLeakCount());
    }

    // ========== 重复归还防护测试 ==========

    @Test
    public void testDoubleRecycleProtection() {
        MockEvsBufferDesc buffer = new MockEvsBufferDesc(1, 640, 480);
        mBufferManager.registerBuffer(buffer);

        mBufferManager.recycleBuffer(buffer);
        assertTrue("第一次归还后应标记为已回收", buffer.isRecycled());
        assertEquals(0, mBufferManager.getActiveBufferCount());

        // 第二次归还不应报错
        mBufferManager.recycleBuffer(buffer);
        assertEquals("二次归还不应影响活跃数", 0, mBufferManager.getActiveBufferCount());
    }

    @Test
    public void testRecycleAfterShutdown() {
        MockEvsBufferDesc buffer = new MockEvsBufferDesc(1, 640, 480);
        mBufferManager.registerBuffer(buffer);

        mBufferManager.shutdown();
        assertTrue("shutdown 后 Buffer 应被回收", buffer.isRecycled());
        assertEquals("shutdown 后活跃数应为 0", 0, mBufferManager.getActiveBufferCount());

        // shutdown 后归还应安全
        mBufferManager.recycleBuffer(buffer);
        assertEquals("shutdown 后归还不应影响状态", 0, mBufferManager.getActiveBufferCount());
    }

    // ========== 超时回收测试 ==========

    @Test(timeout = 3000)
    public void testTimeoutRecycle() throws InterruptedException {
        // 使用 100ms 超时
        BufferManager quickTimeoutManager = new BufferManager(mMockCameraManager, 100);
        quickTimeoutManager.startTimeoutMonitor();

        MockEvsBufferDesc buffer = new MockEvsBufferDesc(1, 640, 480);
        quickTimeoutManager.registerBuffer(buffer);

        // 等待超时触发
        Thread.sleep(600);

        assertTrue("超时后 Buffer 应被强制回收", buffer.isRecycled());
        assertTrue("泄漏计数应增加", quickTimeoutManager.getLeakCount() >= 1);

        quickTimeoutManager.shutdown();
    }

    @Test(timeout = 3000)
    public void testNoTimeoutIfRecycledInTime() throws InterruptedException {
        BufferManager longTimeoutManager = new BufferManager(mMockCameraManager, 5000);
        longTimeoutManager.startTimeoutMonitor();

        MockEvsBufferDesc buffer = new MockEvsBufferDesc(1, 640, 480);
        longTimeoutManager.registerBuffer(buffer);

        // 在超时前归还
        Thread.sleep(200);
        longTimeoutManager.recycleBuffer(buffer);

        // 再等待一段时间，确认不会被超时回收
        Thread.sleep(300);

        assertEquals("泄漏计数应为 0", 0, longTimeoutManager.getLeakCount());
        assertTrue("Buffer 应已正常回收", buffer.isRecycled());

        longTimeoutManager.shutdown();
    }

    // ========== 多 Buffer 测试 ==========

    @Test
    public void testMultipleBuffersSequential() {
        for (int i = 0; i < 5; i++) {
            MockEvsBufferDesc buffer = new MockEvsBufferDesc(i, 640, 480);
            mBufferManager.registerBuffer(buffer);
        }
        assertEquals("注册 5 个 Buffer 后活跃数应为 5", 5, mBufferManager.getActiveBufferCount());
        assertEquals("总帧数应为 5", 5, mBufferManager.getTotalFrameCount());

        for (int i = 0; i < 5; i++) {
            mBufferManager.recycleBuffer(i);
        }
        assertEquals("全部回收后活跃数应为 0", 0, mBufferManager.getActiveBufferCount());
        assertEquals("总帧数应仍为 5", 5, mBufferManager.getTotalFrameCount());
    }

    @Test
    public void testMultipleBuffersOutOfOrderRecycle() {
        MockEvsBufferDesc[] buffers = new MockEvsBufferDesc[3];
        for (int i = 0; i < 3; i++) {
            buffers[i] = new MockEvsBufferDesc(i, 640, 480);
            mBufferManager.registerBuffer(buffers[i]);
        }

        // 乱序归还：先归还 2，再归还 0，最后归还 1
        mBufferManager.recycleBuffer(2);
        assertTrue(buffers[2].isRecycled());

        mBufferManager.recycleBuffer(0);
        assertTrue(buffers[0].isRecycled());

        assertEquals("部分回收后应有 1 个活跃", 1, mBufferManager.getActiveBufferCount());

        mBufferManager.recycleBuffer(1);
        assertTrue(buffers[1].isRecycled());
        assertEquals("全部回收后应为 0", 0, mBufferManager.getActiveBufferCount());
    }

    // ========== Shutdown 测试 ==========

    @Test
    public void testShutdownWithActiveBuffers() {
        MockEvsBufferDesc buffer1 = new MockEvsBufferDesc(1, 640, 480);
        MockEvsBufferDesc buffer2 = new MockEvsBufferDesc(2, 320, 240);

        mBufferManager.registerBuffer(buffer1);
        mBufferManager.registerBuffer(buffer2);
        // 不归还直接 shutdown

        mBufferManager.shutdown();
        assertTrue("shutdown 时应强制回收 buffer1", buffer1.isRecycled());
        assertTrue("shutdown 时应强制回收 buffer2", buffer2.isRecycled());
        assertEquals("shutdown 后活跃数应为 0", 0, mBufferManager.getActiveBufferCount());
    }

    @Test
    public void testShutdownIdempotent() {
        // 多次 shutdown 应安全
        mBufferManager.shutdown();
        mBufferManager.shutdown();
        mBufferManager.shutdown();
        // 不应抛出异常
    }

    @Test
    public void testStartMonitorTwice() {
        // 重复启动监控应安全
        mBufferManager.startTimeoutMonitor();
        mBufferManager.startTimeoutMonitor(); // 第二次不应创建新线程
        // 不应抛出异常
        mBufferManager.shutdown();
    }

    // ========== 并发安全测试 ==========

    @Test(timeout = 5000)
    public void testConcurrentRegisterAndRecycle() throws InterruptedException {
        final int threadCount = 10;
        final int buffersPerThread = 20;
        final int totalBuffers = threadCount * buffersPerThread;
        Thread[] threads = new Thread[threadCount];

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < buffersPerThread; i++) {
                    int bufferId = threadId * buffersPerThread + i;
                    MockEvsBufferDesc buffer = new MockEvsBufferDesc(bufferId, 640, 480);
                    mBufferManager.registerBuffer(buffer);
                    mBufferManager.recycleBuffer(bufferId);
                }
            });
            threads[t].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        // 等待所有 Buffer 完成回收（recycleBuffer 从 map 中移除是原子的）
        // 由于 ConcurrentHashMap 在 recycleBuffer 中的 remove 操作是原子的，
        // 最终活跃数应为 0
        int activeCount = mBufferManager.getActiveBufferCount();
        assertEquals("并发操作后活跃数应为 0，实际=" + activeCount,
                0, activeCount);
        assertEquals("总帧数应等于所有注册的 Buffer 数",
                totalBuffers, mBufferManager.getTotalFrameCount());
        assertEquals("不应有泄漏",
                0, mBufferManager.getLeakCount());
    }
}
