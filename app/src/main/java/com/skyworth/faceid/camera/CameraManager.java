/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.camera;

import android.hardware.HardwareBuffer;
import android.util.Log;

import com.android.car.evs.CameraIds;
import com.android.car.evs.EvsBufferDesc;
import com.android.car.evs.EvsCameraController;
import com.android.car.evs.EvsFrameRate;
import com.android.car.evs.EvsHalWrapper;

/**
 * 摄像头管理器。
 *
 * <p>封装 EvsSDK，管理摄像头枚举、打开/关闭、取流等操作。
 *
 * <p>核心组件：
 * <ul>
 *   <li>{@link EvsCameraController} — 核心控制器，实现 EvsBufferProvider 接口</li>
 *   <li>{@link EvsHalWrapper} / EvsHalWrapperImpl — HAL 层封装</li>
 *   <li>{@link EvsBufferDesc} — Buffer 描述（id, width, height, HardwareBuffer, state）</li>
 *   <li>{@link EvsBufferDesc.State} — NONE, QUEUE, DEQUEUE, RECYCLE</li>
 * </ul>
 *
 * <p>线程安全：方法非线程安全，需在单线程（主线程）中调用。
 */
public class CameraManager {

    private static final String TAG = "CameraManager";

    /** 默认使用 DMS（驾驶员监控摄像头）。 */
    private static final String DEFAULT_CAMERA_ID = CameraIds.DMS;

    /** EvsSDK 摄像头控制器。 */
    private EvsCameraController mCameraController;

    /** 状态回调。 */
    private StateCallback mStateCallback;

    /** 摄像头是否已启动。 */
    private volatile boolean mIsActive;

    /** HAL 层回调。 */
    private final EvsHalWrapper.HalEventCallback mHalCallback =
            new EvsHalWrapper.HalEventCallback() {
                @Override
                public void onFrameEvent(int bufferId, HardwareBuffer buffer) {
                    Log.d(TAG, "onFrameEvent: bufferId=" + bufferId);
                }

                @Override
                public void onHalDeath() {
                    Log.e(TAG, "onHalDeath: HAL service disconnected");
                    if (mStateCallback != null) {
                        mStateCallback.onHalDied();
                    }
                }
            };

    /**
     * 摄像头状态回调。
     */
    public interface StateCallback {

        /** 摄像头已成功打开。 */
        void onCameraOpened();

        /** 摄像头已关闭。 */
        void onCameraClosed();

        /** HAL 服务异常断开。 */
        void onHalDied();

        /**
         * 发生错误。
         *
         * @param message 错误描述
         */
        void onError(String message);
    }

    /**
     * 构造摄像头管理器。
     *
     * @param callback 状态回调，不可为 null
     */
    public CameraManager(StateCallback callback) {
        mStateCallback = callback;
    }

    /**
     * 打开并启动摄像头。
     *
     * <p>流程：
     * <ol>
     *   <li>创建 EvsCameraController</li>
     *   <li>调用 startCamera(cameraId) 启动</li>
     *   <li>设置帧率统计</li>
     * </ol>
     */
    public void openCamera() {
        if (mIsActive) {
            Log.w(TAG, "openCamera: already active");
            return;
        }

        try {
            mCameraController = new EvsCameraController();
            mCameraController.startCamera(DEFAULT_CAMERA_ID);

            EvsFrameRate frameRate = new EvsFrameRate();
            mCameraController.track(frameRate);

            mIsActive = true;
            Log.i(TAG, "openCamera: success, cameraId=" + DEFAULT_CAMERA_ID);

            if (mStateCallback != null) {
                mStateCallback.onCameraOpened();
            }
        } catch (Exception e) {
            Log.e(TAG, "openCamera: failed, cameraId=" + DEFAULT_CAMERA_ID, e);
            if (mStateCallback != null) {
                mStateCallback.onError("Failed to open camera: " + e.getMessage());
            }
        }
    }

    /**
     * 获取新的一帧。
     *
     * @return EvsBufferDesc 包含帧数据，或 null 表示无新帧
     */
    public EvsBufferDesc getNewFrame() {
        if (!mIsActive || mCameraController == null) {
            return null;
        }
        return mCameraController.getNewFrame();
    }

    /**
     * 归还 Buffer（由渲染完成后调用，避免阻塞）。
     *
     * @param buffer 待归还的 Buffer
     */
    public void returnBuffer(Object buffer) {
        if (buffer == null) {
            return;
        }
        if (buffer instanceof EvsBufferDesc) {
            EvsBufferDesc.recycle((EvsBufferDesc) buffer);
        }
    }

    /**
     * 停止摄像头。
     */
    public void stopCamera() {
        if (!mIsActive || mCameraController == null) {
            return;
        }

        try {
            mCameraController.stopCamera();
            mCameraController.release();
        } catch (Exception e) {
            Log.e(TAG, "stopCamera: failed", e);
        } finally {
            mIsActive = false;
            mCameraController = null;
            Log.i(TAG, "stopCamera: done");
            if (mStateCallback != null) {
                mStateCallback.onCameraClosed();
            }
        }
    }

    /**
     * 摄像头是否活跃中。
     */
    public boolean isActive() {
        return mIsActive;
    }
}
