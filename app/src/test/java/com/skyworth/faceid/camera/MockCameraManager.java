package com.skyworth.faceid.camera;

/**
 * 用于单元测试的 Mock CameraManager
 *
 * 模拟 CameraManager 行为，不依赖真实 EvsSDK/HAL。
 */
public class MockCameraManager extends CameraManager {

    private boolean mIsActive = false;

    public MockCameraManager() {
        super(null);
    }

    @Override
    public void returnBuffer(Object buffer) {
        if (buffer instanceof MockEvsBufferDesc) {
            ((MockEvsBufferDesc) buffer).markRecycled();
        }
    }

    @Override
    public void openCamera() {
        mIsActive = true;
    }

    @Override
    public void stopCamera() {
        mIsActive = false;
    }

    @Override
    public boolean isActive() {
        return mIsActive;
    }
}
