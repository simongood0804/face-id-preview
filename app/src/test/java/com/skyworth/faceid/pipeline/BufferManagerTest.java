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
 */
@RunWith(RobolectricTestRunner.class)
public class BufferManagerTest {

    private MockCameraManager mMockCameraManager;
    private BufferManager mBufferManager;
    private static final long TIMEOUT_MS = 500;

    @Before
    public void setUp() {
        ShadowLog.stream = System.out;
        mMockCameraManager = new MockCameraManager();
        mBufferManager = new BufferManager(mMockCameraManager, TIMEOUT_MS);
    }

    @After
    public void tearDown() {
        mBufferManager.shutdown();
    }

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
        mBufferManager.registerBuffer(null);
        assertEquals(0, mBufferManager.getActiveBufferCount());
        assertEquals(0, mBufferManager.getTotalFrameCount());
    }

    @Test
    public void testRecycleNullBuffer() {
        mBufferManager.recycleBuffer((MockEvsBufferDesc) null);
        assertEquals(0, mBufferManager.getActiveBufferCount());
    }

    @Test
    public void testRecycleUnknownBuffer() {
        mBufferManager.recycleBuffer(999);
        assertEquals(0, mBufferManager.getActiveBufferCount());
        assertEquals(0, mBufferManager.getLeakCount());
    }

    @Test
    public void testDoubleRecycleProtection() {
        MockEvsBufferDesc buffer = new MockEvsBufferDesc(1, 640, 480);
        mBufferManager.registerBuffer(buffer);

        mBufferManager.recycleBuffer(buffer);
        assertTrue(buffer.isRecycled());
        assertEquals(0, mBufferManager.getActiveBufferCount());

        mBufferManager.recycleBuffer(buffer);
        assertEquals(0, mBufferManager.getActiveBufferCount());
    }

    @Test
    public void testRecycleAfterShutdown() {
        MockEvsBufferDesc buffer = new MockEvsBufferDesc(1, 640, 480);
        mBufferManager.registerBuffer(buffer);

        mBufferManager.shutdown();
        assertTrue(buffer.isRecycled());
        assertEquals(0, mBufferManager.getActiveBufferCount());

        mBufferManager.recycleBuffer(buffer);
        assertEquals(0, mBufferManager.getActiveBufferCount());
    }

    @Test(timeout = 3000)
    public void testTimeoutRecycle() throws InterruptedException {
        BufferManager quickTimeoutManager = new BufferManager(mMockCameraManager, 100);
        quickTimeoutManager.startTimeoutMonitor();

        MockEvsBufferDesc buffer = new MockEvsBufferDesc(1, 640, 480);
        quickTimeoutManager.registerBuffer(buffer);

        Thread.sleep(600);

        assertTrue("Buffer should be recycled after timeout", buffer.isRecycled());
        assertTrue("Leak count should be >= 1, but was " + quickTimeoutManager.getLeakCount(),
                quickTimeoutManager.getLeakCount() >= 1);

        quickTimeoutManager.shutdown();
    }

    @Test(timeout = 3000)
    public void testNoTimeoutIfRecycledInTime() throws InterruptedException {
        BufferManager longTimeoutManager = new BufferManager(mMockCameraManager, 5000);
        longTimeoutManager.startTimeoutMonitor();

        MockEvsBufferDesc buffer = new MockEvsBufferDesc(1, 640, 480);
        longTimeoutManager.registerBuffer(buffer);

        Thread.sleep(200);
        longTimeoutManager.recycleBuffer(buffer);

        Thread.sleep(300);

        assertEquals(0, longTimeoutManager.getLeakCount());
        assertTrue(buffer.isRecycled());

        longTimeoutManager.shutdown();
    }

    @Test
    public void testMultipleBuffersSequential() {
        for (int i = 0; i < 5; i++) {
            mBufferManager.registerBuffer(new MockEvsBufferDesc(i, 640, 480));
        }
        assertEquals(5, mBufferManager.getActiveBufferCount());
        assertEquals(5, mBufferManager.getTotalFrameCount());

        for (int i = 0; i < 5; i++) {
            mBufferManager.recycleBuffer(i);
        }
        assertEquals(0, mBufferManager.getActiveBufferCount());
        assertEquals(5, mBufferManager.getTotalFrameCount());
    }

    @Test
    public void testMultipleBuffersOutOfOrderRecycle() {
        MockEvsBufferDesc[] buffers = new MockEvsBufferDesc[3];
        for (int i = 0; i < 3; i++) {
            buffers[i] = new MockEvsBufferDesc(i, 640, 480);
            mBufferManager.registerBuffer(buffers[i]);
        }

        mBufferManager.recycleBuffer(2);
        assertTrue(buffers[2].isRecycled());

        mBufferManager.recycleBuffer(0);
        assertTrue(buffers[0].isRecycled());

        assertEquals(1, mBufferManager.getActiveBufferCount());

        mBufferManager.recycleBuffer(1);
        assertTrue(buffers[1].isRecycled());
        assertEquals(0, mBufferManager.getActiveBufferCount());
    }

    @Test
    public void testShutdownWithActiveBuffers() {
        MockEvsBufferDesc buffer1 = new MockEvsBufferDesc(1, 640, 480);
        MockEvsBufferDesc buffer2 = new MockEvsBufferDesc(2, 320, 240);

        mBufferManager.registerBuffer(buffer1);
        mBufferManager.registerBuffer(buffer2);

        mBufferManager.shutdown();
        assertTrue(buffer1.isRecycled());
        assertTrue(buffer2.isRecycled());
        assertEquals(0, mBufferManager.getActiveBufferCount());
    }

    @Test
    public void testShutdownIdempotent() {
        mBufferManager.shutdown();
        mBufferManager.shutdown();
        mBufferManager.shutdown();
    }

    @Test
    public void testStartMonitorTwice() {
        mBufferManager.startTimeoutMonitor();
        mBufferManager.startTimeoutMonitor();
        mBufferManager.shutdown();
    }

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

        assertEquals(0, mBufferManager.getActiveBufferCount());
        assertEquals(totalBuffers, mBufferManager.getTotalFrameCount());
        assertEquals(0, mBufferManager.getLeakCount());
    }
}
