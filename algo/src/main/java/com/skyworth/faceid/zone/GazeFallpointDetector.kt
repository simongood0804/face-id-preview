/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.zone

/**
 * 视线落点判定结果（FACEP-016）。
 */
data class FallpointPrediction(
    /** 命中区域 id；-1 = 无区域命中（无效或落点不在任何区域）。 */
    val regionId: Int,
    /** 是否落在分心区（命中区域的 [RegionConfig.isDistraction]）。 */
    val isDistracted: Boolean
) {
    companion object {
        /** 无命中 / 无效的默认结果。 */
        val NONE = FallpointPrediction(-1, false)
    }
}

/**
 * 视线落点区域判定引擎（FACEP-016）。
 *
 * 算法方标准：用头部位置 [headPos]（headHwT，世界系 mm）+ 头姿法向量 [dir]
 * （headDir，单位向量）确定空间射线，射线与 xz 平面（y=[planeY]）的交点作为
 * **视线落点** `(x, z)`，再判断落点落在哪个 [RegionConfig]（四边形）内。
 *
 * 特性：
 * - **xz 平面可配置**（[planeY]）：默认 y=0，若算法方约定为 y=c 可注入，避免硬编码。
 * - **区域切换滞回**（[holdMs]）：落点在区域边界抖动时，holdMs 窗口内不切换区域，
 *   抑制误判（参照 faceid_sdk `PredictZone` 的 hold_ms）。
 *
 * 纯 JVM 类，无 Android 依赖，便于单测。每帧耗时微秒级（15 区域 × 4 点射线法）。
 */
class GazeFallpointDetector(
    /** 可配置的四边形区域列表。 */
    val regions: List<RegionConfig>,
    /** xz 平面的 y 值（世界系，mm）。默认 0（过世界原点的 xz 平面），可配置。 */
    val planeY: Float = 0f,
    /** 区域切换滞回窗口（ms），抑制落点抖动。0 表示不启用滞回。 */
    val holdMs: Long = 150,
    /** 单调时钟（ms），默认 JVM 单调时钟；可注入便于测试。 */
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    /** 当前稳定的区域 id（滞回生效后的输出）。 */
    private var stableRegionId = -1

    /** 滞回是否在进行中（候选区域 ≠ 稳定区域）。 */
    private var holdActive = false

    /** 滞回计时起始（ms）。 */
    private var holdStartMs = 0L

    /**
     * 由头位置 + 头姿法向量求 xz 平面落点，并判定所属区域。
     *
     * @param headPos  头部世界坐标 `[Px, Py, Pz]`（mm，headHwT）
     * @param dir      头姿单位法向量 `[dx, dy, dz]`（headDir）
     * @param valid    headDirValid==1 且 flags&HEADFRAME
     */
    fun update(headPos: FloatArray?, dir: FloatArray?, valid: Boolean): FallpointPrediction {
        if (!valid || headPos == null || headPos.size < 3 || dir == null || dir.size < 3) {
            // 无效帧：不更新稳定区域，返回无命中
            return FallpointPrediction.NONE
        }
        val px = headPos[0]; val py = headPos[1]; val pz = headPos[2]
        val dx = dir[0];    val dy = dir[1];    val dz = dir[2]
        // 射线 R(λ)=P+λ·d 与 y=planeY 平面交点
        if (dy == 0f) return FallpointPrediction.NONE   // 与平面平行，无交点
        val lambda = (planeY - py) / dy
        if (lambda < 0f) return FallpointPrediction.NONE // 落点在头后方（朝反方向）

        val fx = px + lambda * dx
        val fz = pz + lambda * dz

        // 候选区域：落点命中分心区 → 立即判分心；否则取第一个命中的非分心区。
        var focusHit: RegionConfig? = null
        var candidateId = -1
        var candidateDistracted = false
        for (r in regions) {
            if (r.contains(fx, fz)) {
                if (r.isDistraction) {
                    candidateId = r.id
                    candidateDistracted = true
                    break
                }
                if (focusHit == null) focusHit = r
            }
        }
        if (candidateId == -1 && focusHit != null) {
            candidateId = focusHit.id
        }

        // 区域切换滞回：候选区域 ≠ 稳定区域时，holdMs 窗口内保持稳定区域，抑制抖动。
        val now = clockMs()
        val resultId: Int
        if (candidateId == stableRegionId) {
            holdActive = false
            resultId = stableRegionId
        } else {
            if (!holdActive) {
                holdActive = true
                holdStartMs = now
            }
            if (now - holdStartMs >= holdMs) {
                // 超过滞回窗口，正式切换
                stableRegionId = candidateId
                holdActive = false
                resultId = stableRegionId
            } else {
                // 仍在滞回窗口内，保持原稳定区域
                resultId = stableRegionId
            }
        }

        if (resultId < 0) return FallpointPrediction.NONE
        val result = regions.firstOrNull { it.id == resultId }
            ?: return FallpointPrediction.NONE
        return FallpointPrediction(result.id, result.isDistraction)
    }
}
