package com.skyworth.faceid.algorithm;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用于单元测试的 Mock Face ID 算法
 */
public class MockFaceIDAlgorithm implements IFaceIDAlgorithm {

    private boolean mInitialized = false;
    private boolean mShouldThrowException = false;
    private boolean mDetectFace = true;
    private long mProcessDelayMs = 0;
    private String mFaceId = "test_face_001";
    private float mConfidence = 0.95f;

    private int mInitializeCallCount = 0;
    private int mProcessCallCount = 0;
    private int mReleaseCallCount = 0;

    @Override
    public boolean initialize(Context context, Map<String, Object> config) {
        mInitializeCallCount++;
        mInitialized = true;
        return true;
    }

    @Override
    public FaceIDResult processFrame(byte[] frameData, int width, int height, int format) {
        mProcessCallCount++;
        if (!mInitialized) {
            throw new IllegalStateException("Algorithm not initialized");
        }
        if (mProcessDelayMs > 0) {
            try {
                Thread.sleep(mProcessDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (mShouldThrowException) {
            throw new RuntimeException("Simulated algorithm error");
        }
        byte[] processedData = (frameData != null) ? frameData.clone()
                : new byte[width * height * 3 / 2];

        if (mDetectFace) {
            RectF faceRect = new RectF(
                    width * 0.1f, height * 0.1f,
                    width * 0.6f, height * 0.7f
            );
            List<PointF> landmarks = new ArrayList<>();
            landmarks.add(new PointF(width * 0.3f, height * 0.3f));
            landmarks.add(new PointF(width * 0.5f, height * 0.3f));
            return new FaceIDResult(mFaceId, mConfidence, faceRect, processedData, landmarks);
        } else {
            return new FaceIDResult("", 0f, null, processedData, null);
        }
    }

    @Override
    public void release() {
        mReleaseCallCount++;
        mInitialized = false;
    }

    public boolean isInitialized() { return mInitialized; }
    public void setShouldThrowException(boolean shouldThrow) { mShouldThrowException = shouldThrow; }
    public void setDetectFace(boolean detectFace) { mDetectFace = detectFace; }
    public void setProcessDelayMs(long delayMs) { mProcessDelayMs = delayMs; }
    public void setFaceId(String faceId) { mFaceId = faceId; }
    public void setConfidence(float confidence) { mConfidence = confidence; }
    public int getInitializeCallCount() { return mInitializeCallCount; }
    public int getProcessCallCount() { return mProcessCallCount; }
    public int getReleaseCallCount() { return mReleaseCallCount; }
}
