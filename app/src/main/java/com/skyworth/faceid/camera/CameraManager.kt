/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.camera

import android.util.Log
import com.android.car.evs.CameraIds
import com.android.car.evs.EvsFrameRate

/**
 * 摄像头管理器。
 *
 * 封装 [FaceIDCameraController]，管理摄像头打开/关闭。
 * 实际取帧和渲染由 [EvsGL20CameraRenderer] 配合 [FaceIDCameraController] 完成。
 *
 * 线程安全：方法非线程安全，需在单线程中调用。
 */
open class CameraManager(
    /** 外部传入的 FaceIDCameraController（与渲染器共享同一个实例）。 */
    val controller: FaceIDCameraController,
    /** 默认摄像头 ID；可在 [openCamera] 前通过 [cameraId] 动态覆盖（主页选择）。 */
    cameraId: String = DEFAULT_CAMERA_ID
) {
    private val TAG = "CameraManager"

    /** 摄像头帧率跟踪器，供外部观察 LiveData 更新 UI。 */
    val frameRate: EvsFrameRate = EvsFrameRate()

    /** 摄像头 ID（可动态设置，供主页选择切换取流摄像头）。 */
    @Volatile
    var cameraId: String = cameraId

    /** 摄像头是否已启动。 */
    @Volatile
    open var isActive: Boolean = false

    /**
     * 打开并启动摄像头。
     */
    open fun openCamera() {
        if (isActive) {
            Log.w(TAG, "openCamera: already active")
            return
        }
        try {
            controller.track(frameRate)
            controller.startCamera(cameraId)
            isActive = true
            Log.i(TAG, "openCamera: success, cameraId=$cameraId")
        } catch (e: Exception) {
            Log.e(TAG, "openCamera: failed, cameraId=$cameraId", e)
            throw e
        }
    }

    /**
     * 停止摄像头。
     */
    open fun stopCamera() {
        if (!isActive) return
        try {
            controller.stopCamera()
            controller.release()
        } catch (e: Exception) {
            Log.e(TAG, "stopCamera: failed", e)
        } finally {
            isActive = false
            Log.i(TAG, "stopCamera: done")
        }
    }

    companion object {
        private const val DEFAULT_CAMERA_ID = CameraIds.DMS
    }
}
