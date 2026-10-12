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
        /** 眼睛是否睁开（true=睁眼，false=闭眼）。基于 68 点 EAR + 时序防抖判定。 */
        val eyeOpen: Boolean = false,
        /** 嘴巴是否张开（true=张嘴，false=闭嘴）。基于 68 点 MAR + 时序防抖判定。 */
        val mouthOpen: Boolean = false,
        /** 眼睛连续开合度（0~1，1=全睁；FACEP-015 疲劳判定用，未检测人脸/地标缺失时=1）。 */
        val eyeOpenRatio: Float = 1f,
        /** 嘴部连续开合度（0~1，1=全张；FACEP-015 打哈欠判定用，未检测人脸/地标缺失时=0）。 */
        val mouthOpenRatio: Float = 0f,
        /** 活体得分（AAR FaceResult.liveness 透传；<0=不可用，>0.5=活体，否则疑似 spoof）。 */
        val liveness: Float = 0f,
        /** 结果包含哪些模型输出（AAR FaceResult.flags 位标志透传）。 */
        val flags: Int = 0,
        /** 头部姿态 6D 旋转（AAR FaceResult.headPose6d，3 或 6 元素）。 */
        val headPose6d: FloatArray? = null,
        /** 头部姿态有效性（AAR FaceResult.headValid，1=有效，0=无效）。 */
        val headValid: Int = 0,
        /** 球面视线-偏航角（AAR FaceResult.sphereYaw，单位度）。 */
        val sphereYaw: Float = 0f,
        /** 球面视线-俯仰角（AAR FaceResult.spherePitch，单位度）。 */
        val spherePitch: Float = 0f,
        /** 球面视线-3 区域命中标记（AAR FaceResult.area3Hit）。 */
        val area3Hit: Float = 0f,
        /** 球面视线-有效性（AAR FaceResult.sphereValid）。 */
        val sphereValid: Float = 0f,
        // ===== face-sdk 1.0.3 新增字段（当前仅透传备用，业务逻辑尚未消费）=====
        /**
         * MPIIGaze 每眼视线单位向量-相机系（AAR FaceResult.gazeCamDir）。
         * 形状 [2][3]，[0]=左眼、[1]=右眼；+x 右 / +y 下 / +z 前。
         * 有效性：flags&FACE_FLAG_GAZE 且 eyePatchValid 对应位为 1；
         * 模型失效时该眼全 0（无回退值）。
         */
        val gazeCamDir: Array<FloatArray>? = null,
        /**
         * 头朝向相对**正视基准**的偏差角 `[pitch, yaw, roll]`（度）。
         *
         * 依据文档：正视图时 `R_hw = R_wcᵀ·R_hc_fwd = I`，即"端正朝前"对应 F→W 旋转
         * 为单位阵（欧拉角 0）。故取 `head_hw_rot` 本身即为相对正前方的偏差。
         *
         * 语义（车辆系 X右/Y前/Z上）：
         * - `[0] pitch`：**抬头为正 / 低头为负**
         * - `[1] yaw`：**右转为正 / 左转为负**
         * - `[2] roll`：**头部向右侧倾为正**
         *
         * 供 UI 绘制"俯视小罗盘"（头朝向正向示意）。缺 F→W 头姿时为 null。
         */
        val headDeviation: FloatArray? = null,
        /**
         * MPIIGaze 每眼视线单位向量-世界（车辆）系（AAR FaceResult.gazeWorldDir）。
         * 形状 [2][3]，[0]=左眼、[1]=右眼；世界系 X 右 / Y 前 / Z 上。
         * 仅旋转无平移：v_w = R_wc^T · v_c。
         * 有效性：需 gazeWorldValid == 1（相机→世界外参已设置）。
         */
        val gazeWorldDir: Array<FloatArray>? = null,
        /** 相机→世界外参是否已设置（AAR FaceResult.gazeWorldValid，1=已设置，world 系字段才有效）。 */
        val gazeWorldValid: Int = 0,
        /**
         * MPIIGaze 每眼眼中心 3D 坐标-相机系（AAR FaceResult.eyeCenterCam），单位毫米。
         * 形状 [2][3]，[0]=左眼、[1]=右眼；XY 来自 68 点眼角中点反投影射线（含去畸变迭代），
         * Z 来自 solvePnP 深度。有效性同 eyePatchValid 对应位。
         */
        val eyeCenterCam: Array<FloatArray>? = null,
        /**
         * 每眼眼中心 3D 坐标-世界（车辆）系（AAR FaceResult.eyeCenterWorld），单位毫米。
         * 形状 [2][3]，P_w = R_wc^T·(P_c - t_wc)（含平移）。需 gazeWorldValid == 1。
         */
        val eyeCenterWorld: Array<FloatArray>? = null,
        /**
         * MPIIGaze 归一化眼部灰度 patch（AAR FaceResult.eyePatch），模型输入原样（含直方图均衡，
         * 不镜像、不放大）。每眼 36×60 = 2160 字节；[0]=左眼、[1]=右眼。
         * 有效性：flags&FACE_FLAG_GAZE 且 eyePatchValid 对应位为 1。
         */
        val eyePatch: Array<ByteArray>? = null,
        /**
         * 眼图有效性位标志（AAR FaceResult.eyePatchValid）：bit0=左眼有效，bit1=右眼有效。
         * 注意是位标志而非单一开关，判断单眼需按位与。
         */
        val eyePatchValid: Int = 0,
        /**
         * 标准 3D 人脸模型（MEANSHAPE_68）经 solvePnP 位姿投影回原图的像素坐标
         * （AAR FaceResult.facialPointsProj），形状 [68][2]。
         * 与 facialPoints（检测所得 68 点）同像素域，供叠加对比展示。
         * 有效性：flags&FACE_FLAG_LANDMARK 且相机内参已设置且 solvePnP 成功；否则全 0。
         */
        val facialPointsProj: Array<FloatArray>? = null,
        /**
         * 同上，但姿态仅保留 roll（yaw=pitch=0），平移/深度不变，
         * 即"纯 roll 摆正"的标准模型（AAR FaceResult.facialPointsProjRoll），形状 [68][2]。
         * 有效性同 facialPointsProj。
         */
        val facialPointsProjRoll: Array<FloatArray>? = null,
        // ===== face-sdk 1.0.3 新增字段结束 =====
        /** 头姿-头心旋转矩阵（AAR FaceResult.headHcRot）。 */
        val headHcRot: FloatArray? = null,
        /** 头姿-头心平移向量（AAR FaceResult.headHcT）。 */
        val headHcT: FloatArray? = null,
        /** 头姿-头旋旋转矩阵（AAR FaceResult.headHwRot）。 */
        val headHwRot: FloatArray? = null,
        /** 头姿-头旋平移向量（AAR FaceResult.headHwT）。 */
        val headHwT: FloatArray? = null,
        /** 车辆系头朝向单位向量 xyz（AAR FaceResult.headDir，需 flags&HEADFRAME 且 headDirValid==1）。 */
        val headDir: FloatArray? = null,
        /** 车辆系头朝向偏航角（AAR FaceResult.headDirYaw，度；0=正前，右转+）。 */
        val headDirYaw: Float = 0f,
        /** 车辆系头朝向俯仰角（AAR FaceResult.headDirPitch，度；0=水平，低头-）。 */
        val headDirPitch: Float = 0f,
        /** 头朝向有效性（AAR FaceResult.headDirValid，1=有效，需底层 cam_transform_enabled）。 */
        val headDirValid: Float = 0f,
        /** 行为类别（AAR FaceResult.behaviorClass=reserved[19]，需 flags&BEHAVIOR；算法定义：0=normal、1=smoking、2=phone）。 */
        val behaviorClass: Float = 0f,
        /** 行为类别概率分布（AAR FaceResult.behaviorProbs=reserved[20..22]：P(normal)/P(smoking)/P(phone)）。 */
        val behaviorProbs: FloatArray? = null
    ) {
        /** 头部姿态 6D 旋转（防御性拷贝，null 保持 null）。 */
        val headPose6dSafe: FloatArray? = headPose6d?.copyOf()
        // ===== face-sdk 1.0.3 新增字段的防御性拷贝（null 保持 null）=====
        /** 视线方向-相机系（防御性拷贝）。 */
        val gazeCamDirSafe: Array<FloatArray>? = gazeCamDir?.map { it.copyOf() }?.toTypedArray()
        /** 视线方向-世界系（防御性拷贝）。 */
        val gazeWorldDirSafe: Array<FloatArray>? = gazeWorldDir?.map { it.copyOf() }?.toTypedArray()
        /** 眼球中心-相机系（防御性拷贝）。 */
        val eyeCenterCamSafe: Array<FloatArray>? = eyeCenterCam?.map { it.copyOf() }?.toTypedArray()
        /** 眼球中心-世界系（防御性拷贝）。 */
        val eyeCenterWorldSafe: Array<FloatArray>? = eyeCenterWorld?.map { it.copyOf() }?.toTypedArray()
        /** 眼部图像 patch（防御性拷贝）。 */
        val eyePatchSafe: Array<ByteArray>? = eyePatch?.map { it.copyOf() }?.toTypedArray()
        /** 面部点-投影结果（防御性拷贝）。 */
        val facialPointsProjSafe: Array<FloatArray>? = facialPointsProj?.map { it.copyOf() }?.toTypedArray()
        /** 面部点-投影结果去 roll（防御性拷贝）。 */
        val facialPointsProjRollSafe: Array<FloatArray>? = facialPointsProjRoll?.map { it.copyOf() }?.toTypedArray()
        // ===== face-sdk 1.0.3 新增字段的防御性拷贝结束 =====

        // ===== 以下为依据 SDK 有效性规则计算的便捷判断（避免上层误用全 0 数据）=====
        /** 左眼有效（eyePatchValid bit0）。眼图/视线/眼中心三者共用此位。 */
        val leftEyeValid: Boolean = (eyePatchValid and 0x1) != 0
        /** 右眼有效（eyePatchValid bit1）。眼图/视线/眼中心三者共用此位。 */
        val rightEyeValid: Boolean = (eyePatchValid and 0x2) != 0
        /** 世界系视线可用：外参已设置且有任一眼有效（gazeWorldDir/eyeCenterWorld 才有意义）。 */
        val gazeWorldUsable: Boolean = gazeWorldValid == 1 && (leftEyeValid || rightEyeValid)
        /** 面部投影点可用：需相机内参且 solvePnP 成功（facialPointsProj 非空且为 68 点）。 */
        val facialPointsProjUsable: Boolean = (facialPointsProj?.size ?: 0) >= 68
        /** 头姿-头心旋转矩阵（防御性拷贝）。 */
        val headHcRotSafe: FloatArray? = headHcRot?.copyOf()
        /** 头姿-头心平移向量（防御性拷贝）。 */
        val headHcTSafe: FloatArray? = headHcT?.copyOf()
        /** 头姿-头旋旋转矩阵（防御性拷贝）。 */
        val headHwRotSafe: FloatArray? = headHwRot?.copyOf()
        /** 头姿-头旋平移向量（防御性拷贝）。 */
        val headHwTSafe: FloatArray? = headHwT?.copyOf()
        /** 车辆系头朝向单位向量（防御性拷贝）。 */
        val headDirSafe: FloatArray? = headDir?.copyOf()
        /** 行为类别概率分布（防御性拷贝）。 */
        val behaviorProbsSafe: FloatArray? = behaviorProbs?.copyOf()

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
     * 设置当前车速（km/h），供算法侧按法规分档设置分心判定时长阈值。
     *
     * 对齐 face-sdk `setDistractThresholdMs` 的法规口径：
     * ADDW（车速 ≥50 → 3500ms，≥20 → 6000ms）、GB/T 41797（头姿异常持续 3000ms）。
     * 负值表示车速未知。
     *
     * 默认实现为空操作；算法实现若需转发给 SDK 应重写此方法。
     */
    fun setVehicleSpeed(speedKmh: Float) {
        // 默认无操作
    }

    /**
     * 头姿 solvePnP 使用的固定对应点数（face-sdk `headposeCorrPairNum`，当前为 12）。
     *
     * 默认返回 0；算法实现应转发 SDK 的静态接口。供展示层凸显参与头姿解算的关键点。
     */
    fun headposeCorrPairNum(): Int = 0

    /**
     * 第 k 对头姿对应点的 2D landmark 索引（iBUG 68 点序，face-sdk
     * `headposeCorr2dIndex`）。k 取值 `0..headposeCorrPairNum()-1`。
     *
     * 默认返回 -1；算法实现应转发 SDK 的静态接口。
     */
    fun headposeCorr2dIndex(k: Int): Int = -1

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
