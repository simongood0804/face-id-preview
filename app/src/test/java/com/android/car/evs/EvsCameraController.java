package com.android.car.evs;

/**
 * 测试用的 Stub：模拟 EvsSDK 的 EvsCameraController
 *
 * CameraManager 测试用不到（MockCameraManager 已替代），
 * 仅用于编译通过。
 */
public class EvsCameraController implements EvsBufferProvider {

    @Override
    public EvsBufferDesc getNewFrame() {
        return null;
    }

    public void startCamera(String cameraId) {
    }

    public void stopCamera() {
    }

    public void release() {
    }

    public void track(EvsFrameRate frameRate) {
    }
}
