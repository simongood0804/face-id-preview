package com.skyworth.faceid.camera;

import com.android.car.evs.EvsBufferDesc;

/**
 * 用于单元测试的 Mock EvsBufferDesc
 *
 * 模拟 EvsSDK 的 EvsBufferDesc，无需真实 HardwareBuffer 即可测试。
 */
public class MockEvsBufferDesc extends EvsBufferDesc {

    private final int mId;
    private final int mWidth;
    private final int mHeight;
    private boolean mIsRecycled = false;

    public MockEvsBufferDesc(int id, int width, int height) {
        this.mId = id;
        this.mWidth = width;
        this.mHeight = height;
    }

    @Override
    public int getId() {
        return mId;
    }

    @Override
    public int getWidth() {
        return mWidth;
    }

    @Override
    public int getHeight() {
        return mHeight;
    }

    @Override
    public android.hardware.HardwareBuffer getHardwareBuffer() {
        return null;
    }

    public boolean isRecycled() {
        return mIsRecycled;
    }

    public void markRecycled() {
        mIsRecycled = true;
    }
}
