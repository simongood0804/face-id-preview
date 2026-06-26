package com.skyworth.faceid.algorithm;

import android.graphics.PointF;
import android.graphics.RectF;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * IFaceIDAlgorithm 接口及 FaceIDResult 单元测试
 *
 * 测试算法接口的实现行为，包括：
 *   1. FaceIDResult 数据类的正确性
 *   2. 算法初始化/处理/释放的生命周期
 *   3. 边界条件（空帧、无检测、异常等）
 */
public class IFaceIDAlgorithmTest {

    private MockFaceIDAlgorithm mAlgorithm;

    @Before
    public void setUp() {
        mAlgorithm = new MockFaceIDAlgorithm();
    }

    // ========== FaceIDResult 测试 ==========

    @Test
    public void testFaceIDResultCreation() {
        RectF faceRect = new RectF(10, 20, 100, 200);
        byte[] data = new byte[]{1, 2, 3, 4};
        List<PointF> landmarks = new ArrayList<>();
        landmarks.add(new PointF(50, 60));

        IFaceIDAlgorithm.FaceIDResult result = new IFaceIDAlgorithm.FaceIDResult(
                "face_001", 0.85f, faceRect, data, landmarks);

        assertEquals("face_001", result.getFaceId());
        assertEquals(0.85f, result.getConfidence(), 0.001f);
        assertEquals(faceRect, result.getFaceRect());
        assertArrayEquals(data, result.getProcessedData());
        assertEquals(landmarks, result.getLandmarks());
    }

    @Test
    public void testFaceIDResultWithNullFields() {
        // 允许 faceRect 和 landmarks 为 null（未检测到人脸时）
        IFaceIDAlgorithm.FaceIDResult result = new IFaceIDAlgorithm.FaceIDResult(
                "", 0f, null, new byte[0], null);

        assertEquals("", result.getFaceId());
        assertEquals(0f, result.getConfidence(), 0.001f);
        assertNull(result.getFaceRect());
        assertNull(result.getLandmarks());
    }

    @Test
    public void testFaceIDResultEmptyData() {
        IFaceIDAlgorithm.FaceIDResult result = new IFaceIDAlgorithm.FaceIDResult(
                "face_002", 0.5f, null, new byte[0], null);

        assertNotNull(result.getProcessedData());
        assertEquals(0, result.getProcessedData().length);
    }

    // ========== 算法生命周期测试 ==========

    @Test
    public void testInitialize() {
        Map<String, Object> config = new HashMap<>();
        config.put("model_path", "/data/model/faceid.bin");
        config.put("threshold", 0.7f);

        boolean result = mAlgorithm.initialize(null, config);
        assertTrue("初始化应返回 true", result);
        assertTrue("算法应处于已初始化状态", mAlgorithm.isInitialized());
        assertEquals("initialize 应被调用 1 次", 1, mAlgorithm.getInitializeCallCount());
    }

    @Test
    public void testInitializeWithEmptyConfig() {
        boolean result = mAlgorithm.initialize(null, new HashMap<>());
        assertTrue(result);
        assertTrue(mAlgorithm.isInitialized());
    }

    @Test
    public void testProcessFrameWithFaceDetected() {
        mAlgorithm.initialize(null, new HashMap<>());
        byte[] frameData = new byte[640 * 480 * 3 / 2];
        IFaceIDAlgorithm.FaceIDResult result = mAlgorithm.processFrame(frameData, 640, 480, 0);

        assertNotNull("处理结果不应为 null", result);
        assertEquals("应返回预设的 faceId", "test_face_001", result.getFaceId());
        assertEquals("应返回预设的置信度", 0.95f, result.getConfidence(), 0.001f);
        assertNotNull("检测到人脸时应返回 faceRect", result.getFaceRect());
        assertNotNull("处理后的数据不应为 null", result.getProcessedData());
        assertEquals("处理后的数据应与输入大小一致", frameData.length, result.getProcessedData().length);
        assertEquals("processFrame 应被调用 1 次", 1, mAlgorithm.getProcessCallCount());
    }

    @Test
    public void testProcessFrameWithoutFace() {
        mAlgorithm.setDetectFace(false);
        mAlgorithm.initialize(null, new HashMap<>());

        byte[] frameData = new byte[320 * 240 * 3 / 2];
        IFaceIDAlgorithm.FaceIDResult result = mAlgorithm.processFrame(frameData, 320, 240, 0);

        assertNotNull(result);
        assertEquals("未检测到人脸时应返回空 faceId", "", result.getFaceId());
        assertEquals("未检测到人脸时置信度应为 0", 0f, result.getConfidence(), 0.001f);
        assertNull("未检测到人脸时 faceRect 应为 null", result.getFaceRect());
    }

    @Test
    public void testProcessFrameNullFrameData() {
        mAlgorithm.initialize(null, new HashMap<>());
        // 空帧时算法应能容错
        IFaceIDAlgorithm.FaceIDResult result = mAlgorithm.processFrame(null, 640, 480, 0);
        assertNotNull(result);
        assertNotNull("空帧输入时 processedData 应自动创建", result.getProcessedData());
    }

    @Test(expected = IllegalStateException.class)
    public void testProcessFrameBeforeInitialize() {
        // 未初始化就调用 processFrame 应抛出异常
        mAlgorithm.processFrame(new byte[100], 10, 10, 0);
    }

    @Test
    public void testRelease() {
        mAlgorithm.initialize(null, new HashMap<>());
        assertTrue(mAlgorithm.isInitialized());

        mAlgorithm.release();
        assertFalse("释放后算法应处于未初始化状态", mAlgorithm.isInitialized());
        assertEquals("release 应被调用 1 次", 1, mAlgorithm.getReleaseCallCount());
    }

    @Test
    public void testDoubleRelease() {
        mAlgorithm.initialize(null, new HashMap<>());
        mAlgorithm.release();
        mAlgorithm.release();

        assertEquals("多次 release 应安全", 2, mAlgorithm.getReleaseCallCount());
        assertFalse(mAlgorithm.isInitialized());
    }

    @Test
    public void testProcessFrameWithDelay() {
        mAlgorithm.setProcessDelayMs(50);
        mAlgorithm.initialize(null, new HashMap<>());

        long startTime = System.currentTimeMillis();
        IFaceIDAlgorithm.FaceIDResult result = mAlgorithm.processFrame(
                new byte[100], 10, 10, 0);
        long elapsed = System.currentTimeMillis() - startTime;

        assertNotNull(result);
        assertTrue("处理耗时应 >= 50ms", elapsed >= 50);
    }

    @Test
    public void testProcessFrameWithException() {
        mAlgorithm.setShouldThrowException(true);
        mAlgorithm.initialize(null, new HashMap<>());

        // 算法抛异常时，调用方能正确处理
        try {
            mAlgorithm.processFrame(new byte[100], 10, 10, 0);
            fail("应抛出 RuntimeException");
        } catch (RuntimeException e) {
            assertEquals("Simulated algorithm error", e.getMessage());
        }
    }

    @Test
    public void testFullLifecycle() {
        // 完整的生命周期：初始化 → 多次处理 → 释放
        Map<String, Object> config = new HashMap<>();
        config.put("threshold", 0.8f);
        mAlgorithm.initialize(null, config);

        for (int i = 0; i < 10; i++) {
            IFaceIDAlgorithm.FaceIDResult result = mAlgorithm.processFrame(
                    new byte[100], 10, 10, 0);
            assertNotNull(result);
        }

        assertEquals("processFrame 应被调用 10 次", 10, mAlgorithm.getProcessCallCount());

        mAlgorithm.release();
        assertFalse(mAlgorithm.isInitialized());
    }

    @Test
    public void testMultipleInstances() {
        // 多个算法实例独立运作
        MockFaceIDAlgorithm algo1 = new MockFaceIDAlgorithm();
        MockFaceIDAlgorithm algo2 = new MockFaceIDAlgorithm();

        algo2.setFaceId("face_002");

        algo1.initialize(null, new HashMap<>());
        algo2.initialize(null, new HashMap<>());

        IFaceIDAlgorithm.FaceIDResult result1 = algo1.processFrame(new byte[100], 10, 10, 0);
        IFaceIDAlgorithm.FaceIDResult result2 = algo2.processFrame(new byte[100], 10, 10, 0);

        assertEquals("test_face_001", result1.getFaceId());
        assertEquals("face_002", result2.getFaceId());
        assertEquals(1, algo1.getProcessCallCount());
        assertEquals(1, algo2.getProcessCallCount());
    }

    @Test
    public void testFaceIDResultImmutability() {
        // FaceIDResult 应不可变（通过 final 字段保证）
        byte[] data = new byte[]{1, 2, 3};
        List<PointF> landmarks = new ArrayList<>();
        landmarks.add(new PointF(10, 20));

        IFaceIDAlgorithm.FaceIDResult result = new IFaceIDAlgorithm.FaceIDResult(
                "face_003", 0.9f, new RectF(0, 0, 100, 100), data, landmarks);

        // 修改原始数据不应影响 result
        data[0] = 99;
        assertNotEquals("修改原始数组不应影响 result 内的数据",
                data[0], result.getProcessedData()[0]);
    }
}
