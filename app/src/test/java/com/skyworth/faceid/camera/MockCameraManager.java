package com.skyworth.faceid.camera;

/**
 * 用于单元测试的 Mock CameraManager。
 */
public class MockCameraManager extends CameraManager {

    private boolean mIsActive;

    public MockCameraManager() {
        super(new StateCallback() {
            @Override
            public void onCameraOpened() {}
            @Override
            public void onCameraClosed() {}
            @Override
            public void onHalDied() {}
            @Override
            public void onError(String message) {}
        });
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
