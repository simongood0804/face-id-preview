package com.android.car.evs;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * 测试用的 Stub：模拟 EvsSDK 的 EvsFrameRate
 */
public class EvsFrameRate {

    private final MutableLiveData<Integer> value = new MutableLiveData<>(0);
    private volatile boolean active = false;

    public EvsFrameRate() {
    }

    public LiveData<Integer> getValue() {
        return value;
    }

    public void post() {
    }

    public void start() {
        active = true;
    }

    public void stop() {
        active = false;
    }
}
