/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.algorithm

import kotlin.math.abs

/**
 * 单帧几何判定器：基于 106 点地标计算眼睛开合度（EAR）与嘴巴开合度（MAR）。
 *
 * 职责（阶段二）：**只做单帧几何计算，输出连续开合度**，不做多帧时序/防抖。
 * 多帧稳定状态由后续的 `EyeMouthStateMachine`（阶段三）负责。
 *
 * - 输入：106 点坐标，**展平 FloatArray（长度 212，`[x0,y0,x1,y1,...]`）**。
 *   使用原始 float 数组而非 Android `PointF`，以保证在 JVM 单元测试中可实例化。
 * - 索引：经 [LandmarkIndexMapping] 将语义区域映射到 106 点索引，不写死任何索引。
 * - 输出：单眼 EAR 均值、MAR，以及归一化开合度 `eyeOpenRatio`/`mouthOpenRatio`（0~1）。
 *
 * 线程安全：本类无内部可变状态，可跨线程复用。
 */
class EyeMouthStateEstimator @JvmOverloads constructor(
    /** 语义区域 → 106 索引映射（可注入自定义映射）。 */
    private val mapping: LandmarkIndexMapping = LandmarkIndexMapping(),
    /** 完全睁眼参考 EAR（用于开合度归一化，可校准）。 */
    private val referenceEyeEar: Float = 0.25f,
    /** 闭眼残差基线 EAR（上下睑不完全闭合的最小 EAR）。 */
    private val closedEyeEar: Float = 0.05f,
    /** 完全张嘴参考 MAR（= 当前标定的张嘴幅度，大于此幅度判张嘴）。 */
    private val referenceMouthMar: Float = 0.62f,
    /** 闭嘴残差基线 MAR（= 当前标定的闭嘴基准）。 */
    private val closedMouthMar: Float = 0.35f
) {

    /**
     * 单帧判定结果。
     *
     * @param ear 双眼 EAR 均值（原始几何量，未归一化）。
     * @param mar 嘴巴 MAR（原始几何量，未归一化）。
     * @param eyeOpenRatio 眼睛开合度，0.0=闭眼 ~ 1.0=睁眼。
     * @param mouthOpenRatio 嘴巴开合度，0.0=闭嘴 ~ 1.0=张嘴。
     * @param valid 是否计算有效（所需区域齐全且几何量可计算）。无效时开合度取默认值。
     */
    data class EyeMouthEstimate(
        val ear: Float,
        val mar: Float,
        val eyeOpenRatio: Float,
        val mouthOpenRatio: Float,
        val valid: Boolean
    ) {
        companion object {
            /** 无效结果：开合度取中性默认值（睁眼=1.0、闭嘴=0.0）。 */
            @JvmField
            val INVALID = EyeMouthEstimate(
                ear = 0f, mar = 0f,
                eyeOpenRatio = 1f, mouthOpenRatio = 0f,
                valid = false
            )
        }
    }

    /**
     * 计算单帧开合度。
     *
     * @param landmarks 106 点展平坐标（长度 ≥ 212）。长度不足或非偶则视为无效。
     * @return [EyeMouthEstimate]。
     */
    fun estimate(landmarks: FloatArray): EyeMouthEstimate {
        if (landmarks.size < 2 * 106) return EyeMouthEstimate.INVALID

        // 眼睛：计算左右眼 EAR 的均值
        val leftEar = eyeAspectRatio(landmarks,
            LandmarkRegion.LEFT_EYE_UPPER_LID,
            LandmarkRegion.LEFT_EYE_LOWER_LID,
            LandmarkRegion.LEFT_EYE_OUTER_CANTHUS,
            LandmarkRegion.LEFT_EYE_INNER_CANTHUS)
        val rightEar = eyeAspectRatio(landmarks,
            LandmarkRegion.RIGHT_EYE_UPPER_LID,
            LandmarkRegion.RIGHT_EYE_LOWER_LID,
            LandmarkRegion.RIGHT_EYE_OUTER_CANTHUS,
            LandmarkRegion.RIGHT_EYE_INNER_CANTHUS)

        if (leftEar == null || rightEar == null) {
            // 单眼数据缺失时，回退到可用的一只眼
            val fallback = leftEar ?: rightEar ?: return EyeMouthEstimate.INVALID
            return buildEstimate(fallback, marOf(landmarks))
        }

        val ear = (leftEar + rightEar) / 2f
        return buildEstimate(ear, marOf(landmarks))
    }

    // ============================================================
    // 内部：单眼 EAR 计算
    // ============================================================

    /**
     * 计算单眼 EAR（Eye Aspect Ratio）。
     *
     * EAR = 上下眼睑对应点纵向距离均值 / 眼角水平距离
     *
     * @return EAR；区域未配置或几何无效（眼角水平距离≈0）时返回 null。
     */
    private fun eyeAspectRatio(
        landmarks: FloatArray,
        upper: LandmarkRegion,
        lower: LandmarkRegion,
        outer: LandmarkRegion,
        inner: LandmarkRegion
    ): Float? {
        val upperIdx = mapping.indices(upper)
        val lowerIdx = mapping.indices(lower)
        val outerIdx = mapping.indices(outer)
        val innerIdx = mapping.indices(inner)

        if (upperIdx.isEmpty() || lowerIdx.isEmpty() ||
                outerIdx.isEmpty() || innerIdx.isEmpty()) return null
        if (upperIdx.size != lowerIdx.size) return null

        val outerX = xOf(landmarks, outerIdx[0])
        val innerX = xOf(landmarks, innerIdx[0])
        val eyeWidth = abs(innerX - outerX)
        if (eyeWidth < MIN_WIDTH_EPS) return null  // 眼角距离过近，几何无效

        // 上下眼睑对应列纵向距离均值（EAR 只依赖纵向距离）
        var verticalSum = 0f
        for (i in upperIdx.indices) {
            val uy = yOf(landmarks, upperIdx[i])
            val ly = yOf(landmarks, lowerIdx[i])
            verticalSum += abs(uy - ly)
        }
        val verticalAvg = verticalSum / upperIdx.size

        return verticalAvg / eyeWidth
    }

    // ============================================================
    // 内部：嘴巴 MAR 计算
    // ============================================================

    /**
     * 计算嘴巴 MAR（Mouth Aspect Ratio）。
     *
     * MAR = |上唇中央.y − 下唇中央.y| / 嘴角水平距离
     *
     * 上唇中央取 `MOUTH_UPPER_LIP` 首点（人中），下唇中央取 `MOUTH_LOWER_LIP` 中点。
     * @return MAR；区域未配置或几何无效时返回 null。
     */
    private fun marOf(landmarks: FloatArray): Float? {
        val upper = mapping.indices(LandmarkRegion.MOUTH_UPPER_LIP)
        val lower = mapping.indices(LandmarkRegion.MOUTH_LOWER_LIP)
        val left = mapping.indices(LandmarkRegion.MOUTH_LEFT_CORNER)
        val right = mapping.indices(LandmarkRegion.MOUTH_RIGHT_CORNER)

        if (upper.isEmpty() || lower.isEmpty() || left.isEmpty() || right.isEmpty()) return null

        val upperCenterIdx = upper[0]
        val lowerCenterIdx = lower[lower.size / 2]
        val upperY = yOf(landmarks, upperCenterIdx)
        val lowerY = yOf(landmarks, lowerCenterIdx)

        val leftX = xOf(landmarks, left[0])
        val rightX = xOf(landmarks, right[0])
        val mouthWidth = abs(rightX - leftX)
        if (mouthWidth < MIN_WIDTH_EPS) return null

        return abs(lowerY - upperY) / mouthWidth
    }

    // ============================================================
    // 内部：归一化 + 结果组装
    // ============================================================

    /**
     * 将原始 EAR/MAR 归一化为开合度，并组装结果。
     *
     * @param ear 双眼 EAR 均值（可为 null，表示该侧数据不可用，取中性值）。
     * @param mar 嘴巴 MAR（可为 null，表示不可用，取中性值）。
     */
    private fun buildEstimate(ear: Float?, mar: Float?): EyeMouthEstimate {
        val eyeRatio = if (ear != null) {
            normalize(ear, closedEyeEar, referenceEyeEar)
        } else {
            1f  // 眼睛数据缺失时默认睁眼
        }
        val mouthRatio = if (mar != null) {
            normalize(mar, closedMouthMar, referenceMouthMar)
        } else {
            0f  // 嘴巴数据缺失时默认闭嘴
        }
        return EyeMouthEstimate(
            ear = ear ?: 0f,
            mar = mar ?: 0f,
            eyeOpenRatio = eyeRatio,
            mouthOpenRatio = mouthRatio,
            valid = true
        )
    }

    /**
     * 线性归一化到 [0,1]：值=closed 时为 0，值=reference 时为 1，中间线性，越界截断。
     */
    private fun normalize(value: Float, closed: Float, reference: Float): Float {
        val range = reference - closed
        if (range <= 0f) return 1f
        return ((value - closed) / range).coerceIn(0f, 1f)
    }

    /** 从展平数组取第 idx 点的 x。 */
    private fun xOf(landmarks: FloatArray, idx: Int): Float = landmarks[idx * 2]

    /** 从展平数组取第 idx 点的 y。 */
    private fun yOf(landmarks: FloatArray, idx: Int): Float = landmarks[idx * 2 + 1]

    companion object {
        /** 眼角/嘴角水平距离的最小阈值（像素），小于此值视为几何无效，避免除零。 */
        private const val MIN_WIDTH_EPS = 1e-3f
    }
}
