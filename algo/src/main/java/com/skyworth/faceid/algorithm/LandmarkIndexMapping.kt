/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.algorithm

/**
 * 语义区域 → 模型点位索引的映射表。
 *
 * 这是**唯一接触具体模型索引**的地方。判定逻辑（睁闭眼 / 开合嘴）只依赖
 * [LandmarkRegion]，不直接引用任何索引；后续更换算法 / 点位定义（如换 106 点、
 * 5 点或其它模型）时，**仅需修改本映射**，判定逻辑与渲染层零改动。
 *
 * 默认映射基于 PIPNet 68 点定义（300W 标准，见提案 FACEP-010 §2.4）：
 * - 上下眼睑索引数组**按"同一列"逐点对应**（`UPPER[i] ↔ LOWER[i]`），供 EAR 计算。
 * - 方向以观察者视角描述；镜像不影响判定。
 */
class LandmarkIndexMapping(
    /** 语义区域 → 该区域在 68 点中的索引数组。 */
    private val regions: Map<LandmarkRegion, IntArray> = default68Mapping()
) {
    /**
     * 返回某语义区域在 68 点中的索引数组。
     * 若未配置返回空数组（调用方应容错，避免越界）。
     */
    fun indices(region: LandmarkRegion): IntArray = regions[region] ?: IntArray(0)

    /**
     * 判断某语义区域是否已配置索引。
     */
    fun hasRegion(region: LandmarkRegion): Boolean = regions.containsKey(region)

    companion object {
        /**
         * 基于 PIPNet 68 点（300W 标准顺序）的默认映射（提案 FACEP-010 §2.4）。
         *
         * 上下眼睑对应（观察者视角）：
         * - 左眼（观察者左侧 = 300W right eye 36~41）：
         *   上眼睑 37/38，下眼睑 41/40，眼尾 36、眼角 39；
         *   EAR 对应列：37↔41、38↔40。
         * - 右眼（观察者右侧 = 300W left eye 42~47）：
         *   上眼睑 43/44，下眼睑 47/46，眼尾 45、眼角 42；
         *   EAR 对应列：43↔47、44↔46。
         *
         * 嘴巴（外边缘）：左嘴角 48、右嘴角 54，其中 51=上唇中央（人中）、57=下唇中央。
         */
        fun default68Mapping(): Map<LandmarkRegion, IntArray> = mapOf(
            // 左眼（观察者视角）
            LandmarkRegion.LEFT_EYE_UPPER_LID to intArrayOf(37, 38),
            LandmarkRegion.LEFT_EYE_LOWER_LID to intArrayOf(41, 40),
            LandmarkRegion.LEFT_EYE_OUTER_CANTHUS to intArrayOf(36),
            LandmarkRegion.LEFT_EYE_INNER_CANTHUS to intArrayOf(39),
            // 右眼（观察者视角）
            LandmarkRegion.RIGHT_EYE_UPPER_LID to intArrayOf(43, 44),
            LandmarkRegion.RIGHT_EYE_LOWER_LID to intArrayOf(47, 46),
            LandmarkRegion.RIGHT_EYE_OUTER_CANTHUS to intArrayOf(45),
            LandmarkRegion.RIGHT_EYE_INNER_CANTHUS to intArrayOf(42),
            // 嘴巴（外边缘）
            LandmarkRegion.MOUTH_UPPER_LIP to intArrayOf(51),
            LandmarkRegion.MOUTH_LOWER_LIP to intArrayOf(57),
            LandmarkRegion.MOUTH_LEFT_CORNER to intArrayOf(48),
            LandmarkRegion.MOUTH_RIGHT_CORNER to intArrayOf(54)
        )
    }
}
