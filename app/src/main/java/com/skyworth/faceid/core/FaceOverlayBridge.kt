package com.skyworth.faceid.core

import com.skyworth.faceid.algorithm.FrameProcessor
import com.skyworth.faceid.algorithm.IFaceIDAlgorithm
import com.skyworth.faceid.render.FaceOverlayView

/**
 * 算法结果 → 渲染桥接器（按模块分区）。
 *
 * 提案 FACEP-011 §4.6-C：把 [IFaceIDAlgorithm.FaceIDResult] 组装成
 * [FaceOverlayView.FaceBox]，并按模块只填充对应字段，避免跨模块数据污染
 * （识别 label 不污染疲劳显示、分心 zoneId 不污染识别显示）。
 *
 * 分区（[Module]）：
 * - [Module.RECOGNITION]：只填充人脸框/置信度/识别 label/5关键点；
 * - [Module.FATIGUE]：只填充人脸框 + 眼睛/嘴巴开合；
 * - [Module.DISTRACTION]：只填充人脸框 + 头姿/视线/分区/分心状态。
 *
 * 本类为**纯数据桥接**，不依赖 UI 控件（getString/Toast 等由调用方处理）；
 * 裁剪框/分心提示/人脸框设置经由持有的 [FaceOverlayView]。
 *
 * 线程安全：须在 UI 线程调用（操作 View）。
 */
class FaceOverlayBridge(
    private val overlayView: FaceOverlayView
) {

    private val TAG = "FaceOverlayBridge"

    /** 功能模块分区。 */
    enum class Module { RECOGNITION, FATIGUE, DISTRACTION, BEHAVIOR }

    /**
     * 更新裁剪窗口（黄色采样框）。
     * @param frameProcessor 算法帧处理器（提供裁剪偏移）。
     * @param size 裁剪尺寸（默认 900，与算法对齐）。
     */
    fun updateCropRect(frameProcessor: FrameProcessor, size: Int = 900) {
        val crop = android.graphics.RectF(
            frameProcessor.cropLeft.toFloat(),
            frameProcessor.cropTop.toFloat(),
            (frameProcessor.cropLeft + size).toFloat(),
            (frameProcessor.cropTop + size).toFloat()
        )
        overlayView.setCropRect(crop)
    }

    /**
     * 设置分心提示（固定位置，不随人脸移动）。
     */
    fun setDistracted(active: Boolean) {
        overlayView.setDistracted(active)
    }

    /**
     * 按模块分区组装并设置人脸框。
     *
     * @param result 算法结果。
     * @param distractActive 当前分心状态（DISTRACTION 模块使用）。
     * @param module 功能模块分区，决定填充哪些字段（数据隔离）。
     * @param frameW 帧宽（算法结果对应帧尺寸）。
     * @param frameH 帧高。
     */
    fun setFaces(
        result: IFaceIDAlgorithm.FaceIDResult,
        distractActive: Boolean,
        module: Module,
        frameW: Int,
        frameH: Int
    ) {
        val rect = result.faceRect
        if (rect == null) {
            overlayView.clearFaces()
            return
        }

        // 按模块设置绘制模式（FACEP-011 功能划分：只画本模块关注内容）
        overlayView.drawMode = when (module) {
            Module.RECOGNITION -> FaceOverlayView.DRAW_MODE_RECOGNITION
            Module.FATIGUE -> FaceOverlayView.DRAW_MODE_FATIGUE
            Module.DISTRACTION -> FaceOverlayView.DRAW_MODE_DISTRACTION
            Module.BEHAVIOR -> FaceOverlayView.DRAW_MODE_BEHAVIOR
        }

        val box = buildFaceBox(rect, result, distractActive, module)
        overlayView.setFaces(listOf(box), frameW, frameH)
        overlayView.visibility = android.view.View.VISIBLE
    }

    /**
     * 清空人脸框（无人脸防抖隐藏）。
     */
    fun clearFaces() {
        overlayView.clearFaces()
    }

    /**
     * 按模块组装 FaceBox（只填充对应字段，其余用默认值 → 数据隔离）。
     *
     * @param rect 非空人脸框（由调用方解包）。
     */
    private fun buildFaceBox(
        rect: android.graphics.RectF,
        result: IFaceIDAlgorithm.FaceIDResult,
        distractActive: Boolean,
        module: Module
    ): FaceOverlayView.FaceBox {
        val faceId = result.faceId
        // FACEP-012：unregistered（未录入）不显示名字标签，且按绿色 detected 框处理（有效人脸）
        val isNamed = faceId.isNotEmpty() && faceId != "detected" &&
            faceId != "spoof" && faceId != "unregistered"
        val overlayType = if (isNamed || faceId == "detected" || faceId == "unregistered")
            FaceOverlayView.FaceType.DETECTED
        else FaceOverlayView.FaceType.SPOOF

        return when (module) {
            Module.RECOGNITION -> FaceOverlayView.FaceBox(
                rect = rect,
                type = overlayType,
                confidence = result.confidence,
                label = if (isNamed) faceId else null
            )

            Module.FATIGUE -> FaceOverlayView.FaceBox(
                rect = rect,
                type = overlayType,
                confidence = result.confidence,
                eyeOpen = result.eyeOpen,
                mouthOpen = result.mouthOpen,
                denseLandmarks = result.landmarks
            )

            Module.DISTRACTION -> FaceOverlayView.FaceBox(
                rect = rect,
                type = overlayType,
                confidence = result.confidence,
                // 5 关键点：分心需要绘制（头姿/视线箭头起点 + 紫色关键点）
                keypoints = result.keypoints,
                pitch = result.headposePitch,
                yaw = result.headposeYaw,
                roll = result.headposeRoll,
                gazeValid = result.gazeValid,
                gazeYaw = result.gazeYaw,
                gazePitch = result.gazePitch,
                gazeCalibrated = result.gazeCalibrated,
                gazeDistracted = if (distractActive) 1f else 0f,
                zoneId = result.zoneId
            )

            // 行为监测：仅画框，只需 rect/type/confidence，其余默认
            Module.BEHAVIOR -> FaceOverlayView.FaceBox(
                rect = rect,
                type = overlayType,
                confidence = result.confidence
            )
        }
    }
}
