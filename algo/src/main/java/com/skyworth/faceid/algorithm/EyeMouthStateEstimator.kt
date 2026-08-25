/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.algorithm

import kotlin.math.abs

/**
 * 单帧几何判定器：基于 68 点地标计算眼睛开合度（睑距/脸宽）与嘴巴开合度（MAR）。
 *
 * 职责（阶段二）：**只做单帧几何计算，输出连续开合度**，不做多帧时序/防抖。
 * 多帧稳定状态由后续的 `EyeMouthStateMachine`（阶段三）负责。
 *
 * - 输入：68 点坐标，**展平 FloatArray（长度 136，`[x0,y0,x1,y1,...]`）**。
 *   使用原始 float 数组而非 Android `PointF`，以保证在 JVM 单元测试中可实例化。
 * - 索引：经 [LandmarkIndexMapping] 将语义区域映射到 68 点索引，不写死任何索引。
 * - 输出：眼睛开合度（睑距/脸宽）、MAR，以及归一化开合度 `eyeOpenRatio`/`mouthOpenRatio`（0~1）。
 *
 * ## 为什么用"睑距/脸宽"而非经典 EAR
 *
 * 经典 EAR = 上下睑纵向距离均值 / 单眼角距。它理论上尺度不变，但分母（单眼角距）很小，
 * 远距离人脸变小时地标绝对误差占比显著增大，实测会出现"近距离判睁眼、远距离判闭眼"的漂移。
 *
 * 本实现将分母替换为**脸部大小代理（双眼外眼角距离）**：
 * - 分母远大于单眼角距，远处小脸时对地标噪声更鲁棒；
 * - 分子（睑距）与分母（脸宽）随距离同比例缩放，开合度与距离解耦。
 *
 * 归一化基准（[referenceEyeAperture]/[closedEyeAperture]）基于"睑距/脸宽"标定，
 * 设备端实测后经 [EyeMouthCalibrator] 动态校准。
 *
 * 线程安全：本类无内部可变状态，可跨线程复用。
 */
class EyeMouthStateEstimator @JvmOverloads constructor(
    /** 语义区域 → 68 索引映射（可注入自定义映射）。 */
    private val mapping: LandmarkIndexMapping = LandmarkIndexMapping(),
    /** 完全睁眼参考开合度（睑距/脸宽，用于开合度归一化，可校准）。 */
    private val referenceEyeAperture: Float = 0.10f,
    /** 闭眼残差基线开合度（睑距/脸宽，上下睑不完全闭合的最小值）。 */
    private val closedEyeAperture: Float = 0.02f,
    /** 完全张嘴参考 MAR（= 当前标定的张嘴幅度，大于此幅度判张嘴）。 */
    private val referenceMouthMar: Float = 0.62f,
    /** 闭嘴残差基线 MAR（= 当前标定的闭嘴基准）。 */
    private val closedMouthMar: Float = 0.35f
) {

    /**
     * 单帧判定结果。
     *
     * @param aperture 眼睛开合度（睑距/脸宽，未归一化）。回退场景（缺外眼角）下等于 EAR。
     * @param ear 双眼 EAR 均值（原始几何量，诊断用；与 aperture 的分子相同、分母为单眼角距）。
     * @param faceWidth 双眼外眼角距离（脸部大小代理，像素）；0 表示外眼角缺失、已回退 EAR。
     * @param mar 嘴巴 MAR（原始几何量，未归一化）。
     * @param eyeOpenRatio 眼睛开合度，0.0=闭眼 ~ 1.0=睁眼。
     * @param mouthOpenRatio 嘴巴开合度，0.0=闭嘴 ~ 1.0=张嘴。
     * @param valid 是否计算有效（所需区域齐全且几何量可计算）。无效时开合度取默认值。
     */
    data class EyeMouthEstimate(
        val aperture: Float,
        val ear: Float,
        val faceWidth: Float,
        val mar: Float,
        val eyeOpenRatio: Float,
        val mouthOpenRatio: Float,
        val valid: Boolean
    ) {
        companion object {
            /** 无效结果：开合度取中性默认值（睁眼=1.0、闭嘴=0.0）。 */
            @JvmField
            val INVALID = EyeMouthEstimate(
                aperture = 0f, ear = 0f, faceWidth = 0f, mar = 0f,
                eyeOpenRatio = 1f, mouthOpenRatio = 0f,
                valid = false
            )
        }
    }

    /**
     * 计算单帧开合度。
     *
     * @param landmarks 68 点展平坐标（长度 ≥ 136）。长度不足或非偶则视为无效。
     * @return [EyeMouthEstimate]。
     */
    fun estimate(landmarks: FloatArray): EyeMouthEstimate {
        if (landmarks.size < 2 * 68) return EyeMouthEstimate.INVALID

        val eye = eyeMetrics(landmarks)
        if (eye == null) return EyeMouthEstimate.INVALID
        return buildEstimate(eye, marOf(landmarks))
    }

    // ============================================================
    // 内部：眼睛开合度（睑距/脸宽）
    // ============================================================

    /**
     * 眼睛几何量（主路径：睑距/脸宽；回退：单眼角距 EAR）。
     *
     * @param aperture 归一化用开合度（睑距/脸宽；外眼角缺失时回退为 EAR）。
     * @param ear 诊断用 EAR（睑距/单眼角距），0 表示不可算。
     * @param faceWidth 双眼外眼角距离（像素）；0 表示外眼角缺失（已回退）。
     */
    private data class EyeMetrics(
        val aperture: Float,
        val ear: Float,
        val faceWidth: Float
    )

    /**
     * 计算眼睛开合几何量。
     *
     * 优先使用"睑距均值 / 双眼外眼角距离"（与脸部大小相关，抗距离漂移）；
     * 若外眼角缺失（如自定义映射只配单眼），回退为经典 EAR（睑距/单眼角距）。
     *
     * @return null 表示上下睑区域均缺失或几何无效。
     */
    private fun eyeMetrics(landmarks: FloatArray): EyeMetrics? {
        val leftGap = lidGap(landmarks,
            LandmarkRegion.LEFT_EYE_UPPER_LID, LandmarkRegion.LEFT_EYE_LOWER_LID)
        val rightGap = lidGap(landmarks,
            LandmarkRegion.RIGHT_EYE_UPPER_LID, LandmarkRegion.RIGHT_EYE_LOWER_LID)
        if (leftGap == null && rightGap == null) return null

        // 睑距均值（单眼缺失时用另一只）
        val verticalAvg = when {
            leftGap != null && rightGap != null -> (leftGap + rightGap) / 2f
            leftGap != null -> leftGap
            else -> rightGap!!
        }

        // 诊断用 EAR（睑距/单眼角距）
        val leftEar = eyeAspectRatio(landmarks,
            LandmarkRegion.LEFT_EYE_UPPER_LID, LandmarkRegion.LEFT_EYE_LOWER_LID,
            LandmarkRegion.LEFT_EYE_OUTER_CANTHUS, LandmarkRegion.LEFT_EYE_INNER_CANTHUS)
        val rightEar = eyeAspectRatio(landmarks,
            LandmarkRegion.RIGHT_EYE_UPPER_LID, LandmarkRegion.RIGHT_EYE_LOWER_LID,
            LandmarkRegion.RIGHT_EYE_OUTER_CANTHUS, LandmarkRegion.RIGHT_EYE_INNER_CANTHUS)
        val ear = when {
            leftEar != null && rightEar != null -> (leftEar + rightEar) / 2f
            leftEar != null -> leftEar
            else -> rightEar ?: 0f
        }

        // 脸宽代理：双眼外眼角距离
        val faceWidth = faceWidthOf(landmarks)
        return if (faceWidth != null) {
            EyeMetrics(verticalAvg / faceWidth, ear, faceWidth)
        } else {
            // 外眼角缺失：回退经典 EAR（睑距/单眼角距）
            EyeMetrics(if (ear > 0f) ear else verticalAvg, ear, 0f)
        }
    }

    /** 上下睑对应点纵向距离均值；区域未配置或上下睑点对数不一致时返回 null。 */
    private fun lidGap(landmarks: FloatArray, upper: LandmarkRegion, lower: LandmarkRegion): Float? {
        val upperIdx = mapping.indices(upper)
        val lowerIdx = mapping.indices(lower)
        if (upperIdx.isEmpty() || lowerIdx.isEmpty()) return null
        if (upperIdx.size != lowerIdx.size) return null

        var verticalSum = 0f
        for (i in upperIdx.indices) {
            verticalSum += abs(yOf(landmarks, upperIdx[i]) - yOf(landmarks, lowerIdx[i]))
        }
        return verticalSum / upperIdx.size
    }

    /** 双眼外眼角水平距离（脸部大小代理）；区域缺失或距离过近时返回 null。 */
    private fun faceWidthOf(landmarks: FloatArray): Float? {
        val leftOuter = mapping.indices(LandmarkRegion.LEFT_EYE_OUTER_CANTHUS)
        val rightOuter = mapping.indices(LandmarkRegion.RIGHT_EYE_OUTER_CANTHUS)
        if (leftOuter.isEmpty() || rightOuter.isEmpty()) return null
        val width = abs(xOf(landmarks, rightOuter[0]) - xOf(landmarks, leftOuter[0]))
        return if (width >= MIN_WIDTH_EPS) width else null
    }

    /**
     * 计算单眼 EAR（Eye Aspect Ratio，诊断用）。
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
     * 将原始开合度归一化为 0~1，并组装结果。
     *
     * @param eye 眼睛几何量（aperture 用于归一化，ear/faceWidth 供诊断）。
     * @param mar 嘴巴 MAR（可为 null，表示不可用，取中性值）。
     */
    private fun buildEstimate(eye: EyeMetrics, mar: Float?): EyeMouthEstimate {
        val eyeRatio = normalize(eye.aperture, closedEyeAperture, referenceEyeAperture)
        val mouthRatio = if (mar != null) {
            normalize(mar, closedMouthMar, referenceMouthMar)
        } else {
            0f  // 嘴巴数据缺失时默认闭嘴
        }
        return EyeMouthEstimate(
            aperture = eye.aperture,
            ear = eye.ear,
            faceWidth = eye.faceWidth,
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
        /** 眼角/嘴角/外眼角水平距离的最小阈值（像素），小于此值视为几何无效，避免除零。 */
        private const val MIN_WIDTH_EPS = 1e-3f
    }
}
