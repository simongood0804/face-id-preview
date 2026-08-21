/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.algorithm

/**
 * 语义区域 → 模型点位索引的映射表。
 *
 * 这是**唯一接触具体模型索引**的地方。判定逻辑（睁闭眼 / 开合嘴）只依赖
 * [LandmarkRegion]，不直接引用任何索引；后续更换算法 / 点位定义（如换 68 点、
 * 5 点或更换模型）时，**仅需修改本映射**，判定逻辑与渲染层零改动。
 *
 * 默认映射基于 insightFace `2d106det` 的 106 点定义（见提案 FACEP-010 §2.4）：
 * - 上下眼睑索引数组**按"同一列"逐点对应**（`UPPER[i] ↔ LOWER[i]`），供 EAR 计算。
 * - 方向以观察者视角描述；镜像不影响判定。
 */
class LandmarkIndexMapping(
    /** 语义区域 → 该区域在 106 点中的索引数组。 */
    private val regions: Map<LandmarkRegion, IntArray> = default106Mapping()
) {
    /**
     * 返回某语义区域在 106 点中的索引数组。
     * 若未配置返回空数组（调用方应容错，避免越界）。
     */
    fun indices(region: LandmarkRegion): IntArray = regions[region] ?: IntArray(0)

    /**
     * 判断某语义区域是否已配置索引。
     */
    fun hasRegion(region: LandmarkRegion): Boolean = regions.containsKey(region)

    companion object {
        /**
         * 基于 insightFace 2d106det 的默认 106 点映射（提案 FACEP-010 §2.4）。
         *
         * 上下眼睑对应（观察者视角）：
         * - 左眼（逆时针）：上眼睑 42/40/41，下眼睑 36/33/37，眼尾 35、眼角 39；
         *   EAR 对应列：42↔36、40↔33、41↔37。
         * - 右眼（顺时针）：上眼睑 95/94/96，下眼睑 91/87/90，眼尾 93、眼角 89；
         *   EAR 对应列：95↔91、94↔87、96↔90。
         *
         * 嘴巴（外边缘，人中起顺时针）：上唇 71/67/68/63/64，下唇 58/59/53/56/55，
         * 左嘴角 61、右嘴角 52。其中 71=人中（上唇中央）、53=下唇中央。
         */
        fun default106Mapping(): Map<LandmarkRegion, IntArray> = mapOf(
            // 左眼（观察者视角）
            LandmarkRegion.LEFT_EYE_UPPER_LID to intArrayOf(42, 40, 41),
            LandmarkRegion.LEFT_EYE_LOWER_LID to intArrayOf(36, 33, 37),
            LandmarkRegion.LEFT_EYE_OUTER_CANTHUS to intArrayOf(35),
            LandmarkRegion.LEFT_EYE_INNER_CANTHUS to intArrayOf(39),
            // 右眼（观察者视角）
            LandmarkRegion.RIGHT_EYE_UPPER_LID to intArrayOf(95, 94, 96),
            LandmarkRegion.RIGHT_EYE_LOWER_LID to intArrayOf(91, 87, 90),
            LandmarkRegion.RIGHT_EYE_OUTER_CANTHUS to intArrayOf(93),
            LandmarkRegion.RIGHT_EYE_INNER_CANTHUS to intArrayOf(89),
            // 嘴巴（外边缘）
            LandmarkRegion.MOUTH_UPPER_LIP to intArrayOf(71, 67, 68, 63, 64),
            LandmarkRegion.MOUTH_LOWER_LIP to intArrayOf(58, 59, 53, 56, 55),
            LandmarkRegion.MOUTH_LEFT_CORNER to intArrayOf(61),
            LandmarkRegion.MOUTH_RIGHT_CORNER to intArrayOf(52)
        )
    }
}
