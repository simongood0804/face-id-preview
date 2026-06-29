package com.skyworth.faceid.camera;

/**
 * 用于单元测试的 Mock CameraManager。
 */
public class MockCameraManager extends CameraManager {

    private boolean mIsActive;

    public MockCameraManager() {
        super(new FaceIDCameraController(), "test_camera");
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
