package com.skyworth.faceid.algorithm;

import android.graphics.PointF;
import android.graphics.RectF;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * IFaceIDAlgorithm 接口及 FaceIDResult 单元测试
 */
@RunWith(RobolectricTestRunner.class)
public class IFaceIDAlgorithmTest {

    private MockFaceIDAlgorithm mAlgorithm;

    @Before
    public void setUp() {
        mAlgorithm = new MockFaceIDAlgorithm();
    }

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

    @Test
    public void testInitialize() {
        Map<String, Object> config = new HashMap<>();
        config.put("model_path", "/data/model/faceid.bin");
        config.put("threshold", 0.7f);

        boolean result = mAlgorithm.initialize(null, config);
        assertTrue(result);
        assertTrue(mAlgorithm.isInitialized());
        assertEquals(1, mAlgorithm.getInitializeCallCount());
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

        assertNotNull(result);
        assertEquals("test_face_001", result.getFaceId());
        assertEquals(0.95f, result.getConfidence(), 0.001f);
        assertNotNull(result.getFaceRect());
        assertNotNull(result.getProcessedData());
        assertEquals(frameData.length, result.getProcessedData().length);
        assertEquals(1, mAlgorithm.getProcessCallCount());
    }

    @Test
    public void testProcessFrameWithoutFace() {
        mAlgorithm.setDetectFace(false);
        mAlgorithm.initialize(null, new HashMap<>());

        byte[] frameData = new byte[320 * 240 * 3 / 2];
        IFaceIDAlgorithm.FaceIDResult result = mAlgorithm.processFrame(frameData, 320, 240, 0);

        assertNotNull(result);
        assertEquals("", result.getFaceId());
        assertEquals(0f, result.getConfidence(), 0.001f);
        assertNull(result.getFaceRect());
    }

    @Test
    public void testProcessFrameNullFrameData() {
        mAlgorithm.initialize(null, new HashMap<>());
        IFaceIDAlgorithm.FaceIDResult result = mAlgorithm.processFrame(null, 640, 480, 0);
        assertNotNull(result);
        assertNotNull(result.getProcessedData());
    }

    @Test(expected = IllegalStateException.class)
    public void testProcessFrameBeforeInitialize() {
        mAlgorithm.processFrame(new byte[100], 10, 10, 0);
    }

    @Test
    public void testRelease() {
        mAlgorithm.initialize(null, new HashMap<>());
        assertTrue(mAlgorithm.isInitialized());
        mAlgorithm.release();
        assertFalse(mAlgorithm.isInitialized());
        assertEquals(1, mAlgorithm.getReleaseCallCount());
    }

    @Test
    public void testDoubleRelease() {
        mAlgorithm.initialize(null, new HashMap<>());
        mAlgorithm.release();
        mAlgorithm.release();
        assertEquals(2, mAlgorithm.getReleaseCallCount());
        assertFalse(mAlgorithm.isInitialized());
    }

    @Test
    public void testProcessFrameWithDelay() {
        mAlgorithm.setProcessDelayMs(50);
        mAlgorithm.initialize(null, new HashMap<>());

        long startTime = System.currentTimeMillis();
        IFaceIDAlgorithm.FaceIDResult result = mAlgorithm.processFrame(new byte[100], 10, 10, 0);
        long elapsed = System.currentTimeMillis() - startTime;

        assertNotNull(result);
        assertTrue(elapsed >= 50);
    }

    @Test
    public void testProcessFrameWithException() {
        mAlgorithm.setShouldThrowException(true);
        mAlgorithm.initialize(null, new HashMap<>());

        try {
            mAlgorithm.processFrame(new byte[100], 10, 10, 0);
            fail("应抛出 RuntimeException");
        } catch (RuntimeException e) {
            assertEquals("Simulated algorithm error", e.getMessage());
        }
    }

    @Test
    public void testFullLifecycle() {
        Map<String, Object> config = new HashMap<>();
        config.put("threshold", 0.8f);
        mAlgorithm.initialize(null, config);

        for (int i = 0; i < 10; i++) {
            IFaceIDAlgorithm.FaceIDResult result = mAlgorithm.processFrame(new byte[100], 10, 10, 0);
            assertNotNull(result);
        }
        assertEquals(10, mAlgorithm.getProcessCallCount());
        mAlgorithm.release();
        assertFalse(mAlgorithm.isInitialized());
    }

    @Test
    public void testMultipleInstances() {
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
        byte[] data = new byte[]{1, 2, 3};
        List<PointF> landmarks = new ArrayList<>();
        landmarks.add(new PointF(10, 20));

        IFaceIDAlgorithm.FaceIDResult result = new IFaceIDAlgorithm.FaceIDResult(
                "face_003", 0.9f, new RectF(0, 0, 100, 100), data, landmarks);

        data[0] = 99;
        assertNotEquals(data[0], result.getProcessedData()[0]);
    }
}
