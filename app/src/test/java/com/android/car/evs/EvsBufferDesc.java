package com.android.car.evs;

/**
 * 测试用的 Stub：模拟 EvsSDK 的 EvsBufferDesc
 *
 * 仅包含 BufferManager 测试所需的字段和方法。
 * BufferID + 宽高 + 回收标记 足矣。
 */
public class EvsBufferDesc {

    private int mId;
    private int mWidth;
    private int mHeight;
    private android.hardware.HardwareBuffer mHardwareBuffer;
    private State mState = State.NONE;

    public enum State {
        NONE, QUEUE, DEQUEUE, RECYCLE
    }

    public EvsBufferDesc() {
    }

    public int getId() {
        return mId;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public android.hardware.HardwareBuffer getHardwareBuffer() {
        return mHardwareBuffer;
    }

    public State getState() {
        return mState;
    }

    public boolean queue(int id, android.hardware.HardwareBuffer buffer, long timestamp) {
        this.mId = id;
        this.mHardwareBuffer = buffer;
        this.mState = State.QUEUE;
        return true;
    }

    public boolean dequeue() {
        this.mState = State.DEQUEUE;
        return true;
    }

    public void recovery() {
        this.mState = State.NONE;
    }

    /**
     * 静态回收方法（EvsSDK 原生 API）
     */
    public static void recycle(EvsBufferDesc buffer) {
        if (buffer != null) {
            buffer.recovery();
        }
    }
}
