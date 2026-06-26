/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.camera;

/**
 * 用于单元测试的 Mock CameraManager。
 *
 * <p>模拟 CameraManager 行为，不依赖真实 EvsSDK/HAL。
 * 通过 {@link MockEvsBufferDesc} 追踪 Buffer 是否被回收。
 */
public class MockCameraManager extends CameraManager {

    private boolean mIsActive;

    public MockCameraManager() {
        // 父类 CameraManager 需要非空 callback，传一个静默回调
        super(new StateCallback() {
            @Override
            public void onCameraOpened() {
                // no-op
            }

            @Override
            public void onCameraClosed() {
                // no-op
            }

            @Override
            public void onHalDied() {
                // no-op
            }

            @Override
            public void onError(String message) {
                // no-op
            }
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
