package com.skyworth.faceid.pipeline;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * PipelineConfig 单元测试
 *
 * 测试配置项的默认值、getter/setter 以及边界条件。
 */
public class PipelineConfigTest {

    private PipelineConfig mConfig;

    @Before
    public void setUp() {
        mConfig = new PipelineConfig();
    }

    @Test
    public void testDefaultValues() {
        assertEquals("默认最大缓存帧数应为 3",
                PipelineConfig.DEFAULT_MAX_PENDING_FRAMES, mConfig.getMaxPendingFrames());
        assertEquals("默认算法处理超时应为 500ms",
                PipelineConfig.DEFAULT_PROCESS_TIMEOUT_MS, mConfig.getProcessTimeoutMs());
        assertEquals("默认 Buffer 回收超时应为 1000ms",
                PipelineConfig.DEFAULT_BUFFER_RECYCLE_TIMEOUT_MS, mConfig.getBufferRecycleTimeoutMs());
        assertEquals("默认跳帧间隔应为 1",
                PipelineConfig.DEFAULT_FRAME_SKIP_INTERVAL, mConfig.getFrameSkipInterval());
    }

    @Test
    public void testSetMaxPendingFrames() {
        mConfig.setMaxPendingFrames(5);
        assertEquals(5, mConfig.getMaxPendingFrames());

        mConfig.setMaxPendingFrames(0);
        assertEquals(0, mConfig.getMaxPendingFrames());

        mConfig.setMaxPendingFrames(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, mConfig.getMaxPendingFrames());
    }

    @Test
    public void testSetProcessTimeoutMs() {
        mConfig.setProcessTimeoutMs(1000);
        assertEquals(1000, mConfig.getProcessTimeoutMs());

        mConfig.setProcessTimeoutMs(0);
        assertEquals(0, mConfig.getProcessTimeoutMs());
    }

    @Test
    public void testSetBufferRecycleTimeoutMs() {
        mConfig.setBufferRecycleTimeoutMs(2000);
        assertEquals(2000, mConfig.getBufferRecycleTimeoutMs());

        mConfig.setBufferRecycleTimeoutMs(500);
        assertEquals(500, mConfig.getBufferRecycleTimeoutMs());
    }

    @Test
    public void testSetFrameSkipInterval() {
        mConfig.setFrameSkipInterval(2);
        assertEquals(2, mConfig.getFrameSkipInterval());

        mConfig.setFrameSkipInterval(5);
        assertEquals(5, mConfig.getFrameSkipInterval());
    }

    @Test
    public void testFrameSkipIntervalMinimumIsOne() {
        // 设置为 0 时，应被 Math.max(1, ...) 限制为 1
        mConfig.setFrameSkipInterval(0);
        assertEquals(1, mConfig.getFrameSkipInterval());

        // 设置为负数时，应被限制为 1
        mConfig.setFrameSkipInterval(-1);
        assertEquals(1, mConfig.getFrameSkipInterval());
    }

    @Test
    public void testFrameSkipIntervalAfterValidValueRemainsUnchanged() {
        mConfig.setFrameSkipInterval(3);
        assertEquals(3, mConfig.getFrameSkipInterval());

        // 再次设置有效值，不应受影响
        mConfig.setFrameSkipInterval(1);
        assertEquals(1, mConfig.getFrameSkipInterval());
    }

    @Test
    public void testDefaultConstantsUnchanged() {
        // 验证默认常量没有被意外修改
        assertEquals(3, PipelineConfig.DEFAULT_MAX_PENDING_FRAMES);
        assertEquals(500L, PipelineConfig.DEFAULT_PROCESS_TIMEOUT_MS);
        assertEquals(1000L, PipelineConfig.DEFAULT_BUFFER_RECYCLE_TIMEOUT_MS);
        assertEquals(1, PipelineConfig.DEFAULT_FRAME_SKIP_INTERVAL);
    }

    @Test
    public void testMultipleConfigurations() {
        // 链式修改多个配置
        mConfig.setMaxPendingFrames(10);
        mConfig.setProcessTimeoutMs(200);
        mConfig.setBufferRecycleTimeoutMs(500);
        mConfig.setFrameSkipInterval(3);

        assertAll("多项配置应全部生效",
                () -> assertEquals(10, mConfig.getMaxPendingFrames()),
                () -> assertEquals(200, mConfig.getProcessTimeoutMs()),
                () -> assertEquals(500, mConfig.getBufferRecycleTimeoutMs()),
                () -> assertEquals(3, mConfig.getFrameSkipInterval())
        );
    }

    @Test
    public void testToString() {
        String str = mConfig.toString();
        assertTrue("toString 应包含 maxPendingFrames", str.contains("maxPendingFrames"));
        assertTrue("toString 应包含 processTimeoutMs", str.contains("processTimeoutMs"));
        assertTrue("toString 应包含 bufferRecycleTimeoutMs", str.contains("bufferRecycleTimeoutMs"));
        assertTrue("toString 应包含 frameSkipInterval", str.contains("frameSkipInterval"));
        assertTrue("toString 应包含默认值 3", str.contains("maxPendingFrames=3"));
        assertTrue("toString 应包含默认值 500", str.contains("processTimeoutMs=500"));
    }

    // JUnit 4 没有 assertAll，辅助方法
    private void assertAll(String message, Runnable... assertions) {
        for (Runnable assertion : assertions) {
            try {
                assertion.run();
            } catch (AssertionError e) {
                throw new AssertionError(message + ": " + e.getMessage());
            }
        }
    }
}
