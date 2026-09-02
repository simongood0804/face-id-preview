package com.skyworth.faceid.core

import android.content.Context
import android.util.Log
import com.android.car.evs.CameraIds

/**
 * 摄像头选择偏好（进程级）。
 *
 * 保存用户从主页选择的取流摄像头（AVMF/AVMR/AVMB/AVML/RVC/DMS 等）。
 * - 持久化到 SharedPreferences（重启保持）；
 * - 同时维护静态字段 [selectedCameraId]，供无 Context 的 [FrameSession.open] 读取。
 */
object CameraPreference {

    private const val TAG = "CameraPreference"
    private const val PREFS = "faceid_prefs"
    private const val KEY_CAMERA = "selected_camera"

    /** 当前选择的摄像头 ID（默认 DMS）。 */
    @Volatile
    var selectedCameraId: String = CameraIds.DMS

    /** 支持在主页选择的摄像头（AVMF/AVMR/AVMB/AVML/RVC/DMS）。 */
    val selectableCameraIds: List<String> = listOf(
        CameraIds.AVMF, CameraIds.AVMR, CameraIds.AVMB, CameraIds.AVML,
        CameraIds.RVC, CameraIds.DMS
    )

    /** 摄像头 ID → 展示名称。 */
    val cameraDisplayName: Map<String, String> = mapOf(
        CameraIds.AVMF to "AVMF 前视",
        CameraIds.AVMR to "AVMR 右视",
        CameraIds.AVMB to "AVMB 后视",
        CameraIds.AVML to "AVML 左视",
        CameraIds.RVC to "RVC 倒车",
        CameraIds.DMS to "DMS 驾驶员"
    )

    /** 从持久化加载（应用启动/主页 onCreate 调用）。 */
    fun init(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            selectedCameraId = prefs.getString(KEY_CAMERA, CameraIds.DMS) ?: CameraIds.DMS
            Log.i(TAG, "init: loaded camera=$selectedCameraId")
        } catch (e: Exception) {
            Log.w(TAG, "init: failed", e)
        }
    }

    /** 保存选择的摄像头（主页选择时调用）。 */
    fun setSelected(context: Context, cameraId: String) {
        selectedCameraId = cameraId
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_CAMERA, cameraId).apply()
            Log.i(TAG, "setSelected: camera=$cameraId")
        } catch (e: Exception) {
            Log.w(TAG, "setSelected: save failed", e)
        }
    }
}
