/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.camera

import android.util.Log
import com.android.car.evs.CameraIds
import com.android.car.evs.EvsBufferDesc
import com.android.car.evs.EvsCameraController
import com.android.car.evs.EvsFrameRate

/**
 * 摄像头管理器。
 *
 * 封装 EvsSDK，管理摄像头枚举、打开/关闭、取流等操作。
 *
 * 核心组件：
 * - [EvsCameraController] — 核心控制器
 * - [EvsBufferDesc] — Buffer 描述（id, width, height, HardwareBuffer, state）
 *
 * 线程安全：方法非线程安全，需在单线程（主线程）中调用。
 */
open class CameraManager(
    /** 状态回调。 */
    private val mStateCallback: StateCallback?
) {
    private val TAG = "CameraManager"

    /** EvsSDK 摄像头控制器。 */
    private var mCameraController: EvsCameraController? = null

    /** 摄像头是否已启动。 */
    @Volatile
    private var mIsActive = false

    /**
     * 摄像头状态回调。
     */
    interface StateCallback {
        fun onCameraOpened()
        fun onCameraClosed()
        fun onHalDied()
        fun onError(message: String)
    }

    /**
     * 打开并启动摄像头。
     */
    open fun openCamera() {
        if (mIsActive) {
            Log.w(TAG, "openCamera: already active")
            return
        }
        try {
            EvsCameraController().also { controller ->
                mCameraController = controller
                controller.startCamera(DEFAULT_CAMERA_ID)
                controller.track(EvsFrameRate())
            }
            mIsActive = true
            Log.i(TAG, "openCamera: success, cameraId=$DEFAULT_CAMERA_ID")
            mStateCallback?.onCameraOpened()
        } catch (e: Exception) {
            Log.e(TAG, "openCamera: failed, cameraId=$DEFAULT_CAMERA_ID", e)
            mStateCallback?.onError("Failed to open camera: ${e.message}")
        }
    }

    /**
     * 获取新的一帧。
     */
    fun getNewFrame(): EvsBufferDesc? {
        return if (mIsActive) mCameraController?.newFrame else null
    }

    /**
     * 归还 Buffer（由渲染完成后调用，避免阻塞）。
     */
    open fun returnBuffer(buffer: Any?) {
        if (buffer is EvsBufferDesc) {
            EvsBufferDesc.recycle(buffer)
        }
    }

    /**
     * 停止摄像头。
     */
    open fun stopCamera() {
        if (!mIsActive || mCameraController == null) return
        try {
            mCameraController?.apply {
                stopCamera()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopCamera: failed", e)
        } finally {
            mIsActive = false
            mCameraController = null
            Log.i(TAG, "stopCamera: done")
            mStateCallback?.onCameraClosed()
        }
    }

    /** 摄像头是否活跃中。 */
    open fun isActive(): Boolean = mIsActive

    companion object {
        private const val DEFAULT_CAMERA_ID = CameraIds.DMS
    }
}
