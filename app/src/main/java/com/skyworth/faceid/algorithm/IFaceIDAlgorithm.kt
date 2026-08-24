/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.algorithm

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF

/**
 * Face ID 算法抽象接口。
 *
 * 算法团队需实现此接口，业务层通过此接口与算法完全解耦。
 *
 * 数据流约定：
 * - 输入：原始帧数据 (YUV/NV21/RGBA) + 宽高 + 格式标识
 * - 输出：[FaceIDResult] 包含 faceId、置信度、人脸框、处理后帧数据
 *
 * 线程安全：实现类需保证 [processFrame] 的线程安全。
 */
interface IFaceIDAlgorithm {

    /**
     * 算法处理结果。
     *
     * 包含 Face ID 识别结果的全部信息，用于预览渲染和 UI 展示。
     * 不可变：构造函数对 [processedData] 做防御性拷贝。
     */
    class FaceIDResult @JvmOverloads constructor(
        /** Face ID 唯一标识（空字符串表示未检测到人脸）。 */
        faceId: String? = "",
        /** 置信度 (0.0 ~ 1.0)。 */
        confidence: Float = 0f,
        /** 人脸框坐标（用于画框），null 表示未检测到人脸。 */
        val faceRect: RectF? = null,
        /** 处理后的帧数据（算法绘制人脸框后的数据）。 */
        processedData: ByteArray? = null,
        /** 人脸密集地标（可选），null 表示未提供。 */
        val landmarks: List<PointF>? = null,
        /** 是否为新录入的人脸。 */
        val isNewEnrollment: Boolean = false,
        /** FACEP-012：手动录入模式下已采集到可命名的稳定人脸帧（可弹框命名）。 */
        val enrollmentReady: Boolean = false,
        /** 5 个面部关键点（左眼、右眼、鼻尖、左嘴角、右嘴角）。 */
        val keypoints: List<PointF>? = null,
        /** 头部姿态：俯仰角（Pitch），单位度。 */
        val headposePitch: Float = 0f,
        /** 头部姿态：偏航角（Yaw），单位度。 */
        val headposeYaw: Float = 0f,
        /** 头部姿态：翻滚角（Roll），单位度。 */
        val headposeRoll: Float = 0f,
        /** 视线是否有效（1=有效，0=无效）。 */
        val gazeValid: Float = 0f,
        /** 视线偏航角（Yaw），单位度。 */
        val gazeYaw: Float = 0f,
        /** 视线俯仰角（Pitch），单位度。 */
        val gazePitch: Float = 0f,
        /** 是否分心（1=分心，0=专注）。 */
        val gazeDistracted: Float = 0f,
        /** 是否已标定（1=已标定，0=未标定）。 */
        val gazeCalibrated: Float = 0f,
        /** 分心综合分数（0.0 ~ 1.0）。 */
        val distractionScore: Float = 0f,
        /** 分心-头部姿态分数。 */
        val distractionHpScore: Float = 0f,
        /** 分心-视线分数。 */
        val distractionGazeScore: Float = 0f,
        /** DMS 分区 ID。 */
        val zoneId: Float = 0f,
        /** DMS 分区置信度。 */
        val zoneConfidence: Float = 0f,
        /** 眼睛是否睁开（true=睁眼，false=闭眼）。基于 106 点 EAR + 时序防抖判定。 */
        val eyeOpen: Boolean = false,
        /** 嘴巴是否张开（true=张嘴，false=闭嘴）。基于 106 点 MAR + 时序防抖判定。 */
        val mouthOpen: Boolean = false
    ) {
        /** Face ID 唯一标识，不为 null。 */
        val faceId: String = faceId ?: ""

        /** 置信度，范围 0.0 ~ 1.0。 */
        val confidence: Float = confidence.coerceIn(0f, 1f)

        /** 处理后的帧数据（防御性拷贝）。 */
        val processedData: ByteArray = processedData?.clone() ?: ByteArray(0)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FaceIDResult) return false
            return faceId == other.faceId &&
                    confidence == other.confidence &&
                    faceRect == other.faceRect &&
                    processedData.contentEquals(other.processedData) &&
                    landmarks == other.landmarks
        }

        override fun hashCode(): Int {
            var result = faceId.hashCode()
            result = 31 * result + confidence.hashCode()
            result = 31 * result + (faceRect?.hashCode() ?: 0)
            result = 31 * result + processedData.contentHashCode()
            result = 31 * result + (landmarks?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * 初始化算法。
     *
     * 加载模型文件、初始化人脸检测器和特征提取器等资源。
     *
     * @param context Android 上下文
     * @param config  算法配置参数（模型路径、阈值等），不可为 null
     * @return 初始化成功返回 true，失败返回 false
     */
    fun initialize(context: Context?, config: MutableMap<String, Any>): Boolean

    /**
     * 处理单帧数据。
     *
     * @param frameData 原始帧数据 (YUV / NV21 / RGBA)
     * @param width     图像宽度（像素）
     * @param height    图像高度（像素）
     * @param format    图像格式标识（预留，暂传 0）
     * @return 算法处理结果，不会返回 null
     */
    fun processFrame(frameData: ByteArray?, width: Int, height: Int, format: Int): FaceIDResult

    /**
     * 设置裁剪偏移（ROI 左上角在原图坐标中的偏移）。
     *
     * 帧处理器在调用 [processFrame] 前设置，算法实现需在返回结果时
     * 将裁剪空间内的人脸框/关键点/地标坐标修正回原图空间。
     *
     * 默认实现为空操作；算法实现若需坐标修正应重写此方法。
     */
    fun setCropOffset(x: Int, y: Int) {
        // 默认无操作，由具体算法实现处理坐标修正
    }

    /**
     * 缓存一帧原始 UYVY 数据（供手动 dump 调试用）。
     *
     * 帧处理器在收到完整原始帧时调用。默认实现为空操作；
     * 支持 dump 调试的算法实现应重写此方法缓存最近一帧。
     */
    fun dumpOriginalFrame(uyvyData: ByteArray, width: Int, height: Int) {
        // 默认无操作
    }

    /**
     * 释放算法资源。
     */
    fun release()

    /**
     * 已导入（录入）的人脸数量，供识别模块 UI 展示。
     * 默认返回 0；支持人脸录入管理的实现应重写返回实际数量。
     */
    fun getEnrolledCount(): Int = 0

    // ============================================================
    // FACEP-012：手动录入 / 人脸管理（透传至 FaceEnrollmentManager）
    // 以下方法默认空实现；支持人脸录入管理的实现应重写。
    // ============================================================

    /** 是否处于手动录入模式。 */
    fun isEnrolling(): Boolean = false

    /** 开始手动录入模式。 */
    fun startManualEnrollment() {}

    /** 结束手动录入模式。 */
    fun stopManualEnrollment() {}

    /**
     * 录入模式下采集稳定人脸帧。
     * @return true 表示已采集足够稳定帧，可弹框命名
     */
    fun onEnrollmentFrame(emb: FloatArray, score: Float): Boolean = false

    /** 采集到待命名的特征向量（onEnrollmentFrame 返回 true 后有效）。 */
    fun pendingEmbedding(): FloatArray? = null

    /**
     * 保存一个已命名的录入人脸。
     * @return 成功返回 true；空名/重名返回 false
     */
    fun addEnrolledFace(name: String, emb: FloatArray): Boolean = false

    /** 删除一个已录入的人脸。返回是否成功删除。 */
    fun deleteFace(name: String): Boolean = false

    /** 已录入人脸名称列表。 */
    fun getEnrolledNames(): Set<String> = emptySet()

    /**
     * 录入已满可用的默认命名集合（供命名对话框兜底建议）。
     * 默认返回空集；实现可返回异兽名等候选。
     */
    fun defaultNameCandidates(): List<String> = emptyList()
}
