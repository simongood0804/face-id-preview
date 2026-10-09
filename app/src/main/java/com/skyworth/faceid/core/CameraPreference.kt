package com.skyworth.faceid.core

import android.content.Context
import android.util.Log
import com.android.car.evs.CameraIds
import com.skyworth.faceid.camera.EvsCameraCatalog

/**
 * 摄像头选择偏好（进程级）。
 *
 * 保存用户从主页选择的取流摄像头（清单由固件声明动态解析，见 [EvsCameraCatalog]）。
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

    /**
     * 支持在主页选择的摄像头 —— **由固件声明动态解析**，见 [EvsCameraCatalog]。
     *
     * 不同车型/固件的相机名不同（van233 环视四路叫 `FVC/RBS/RVC/LBS`，minibus 就叫
     * `AVMF/AVMR/AVMB/AVML`），写死任一套换车即"没流、预览黑"——HAL 对固件**未声明**的
     * 名字会 `openCamera` 返回成功却一帧不出，且没有任何报错，故不再写死名字。
     */
    val selectableCameraIds: List<String> = EvsCameraCatalog.ids

    /** 摄像头 ID → 展示名称：**直接用 ID**（不加中文，与观看端按钮同一套名字）。 */
    val cameraDisplayName: Map<String, String> = EvsCameraCatalog.displayNames

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
