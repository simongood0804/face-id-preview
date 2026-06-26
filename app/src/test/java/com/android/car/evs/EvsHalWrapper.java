package com.android.car.evs;

import android.hardware.HardwareBuffer;

/**
 * 测试用的 Stub：模拟 EvsSDK 的 EvsHalWrapper
 */
public abstract class EvsHalWrapper {

    public interface HalEventCallback {
        void onFrameEvent(int bufferId, HardwareBuffer buffer);
        void onHalDeath();
    }

    public EvsHalWrapper() {
    }

    public boolean init() { return true; }
    public void release() {}
    public boolean isConnected() { return false; }
    public boolean connectToHalServiceIfNecessary() { return true; }
    public boolean openCamera(String cameraId) { return true; }
    public void closeCamera() {}
    public boolean requestToStartVideoStream() { return true; }
    public void requestToStopVideoStream() {}
    public void doneWithFrame(int bufferId) {}
    public long getExtendedInfo(long infoType) { return 0; }
}
