/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.fatigue

/**
 * 疲劳检测规则（FACEP-015）。
 *
 * 从 `fatigue_rules.json` 解析，或使用内置默认值。规则包含：
 * - [yawn]：打哈欠基础条件（嘴部开合度阈值 + 最短持续时长）；
 * - [eyeClose]：闭眼计数基础条件（单次有效闭眼最短时长）；
 * - [levels]：三级疲劳（轻度/中度/重度），每级进入条件为**或关系**（任一满足即进入），
 *   每级有独立退出条件；高等级覆盖低等级（升级式）；
 * - [noFace]：无人脸复位条件。
 */
data class FatigueRule(
    val schemaVersion: Int = 1,
    val yawn: YawnRule = YawnRule(),
    val eyeClose: EyeCloseRule = EyeCloseRule(),
    val levels: List<LevelRule> = DEFAULT_LEVELS,
    val noFace: NoFaceRule = NoFaceRule()
) {
    /** 打哈欠基础条件。 */
    data class YawnRule(
        /** 打哈欠判定是否启用。 */
        val enabled: Boolean = true,
        /** 嘴部开合度阈值（mouthOpenRatio ≥ 此值视为"嘴开"，0~1）。 */
        val mouthOpenRatio: Float = DEFAULT_MOUTH_OPEN_RATIO,
        /** 嘴部持续打开 ≥ 此时长记一次"哈欠事件"（ms）。 */
        val minDurationMs: Long = DEFAULT_MIN_DURATION_MS,
        /**
         * 张嘴窗口内"开口占比"下限（0~1）：区分哈欠与说话（方案 C，FACEP-015）。
         * 哈欠是平稳持续张开（占比高，接近 1.0）；说话是快速开合（频繁闭合，占比低）。
         * 一次张嘴窗口结束时，若 `开口帧数/总帧数 ≥ minOpenRatio` 才记为哈欠，否则视为说话/抖动丢弃。
         */
        val minOpenRatio: Float = DEFAULT_MIN_OPEN_RATIO
    ) {
        companion object {
            const val DEFAULT_MOUTH_OPEN_RATIO = 0.3f
            const val DEFAULT_MIN_DURATION_MS = 2000L
            /** 默认开口占比下限：70% 帧处于开口状态才认为是平稳的哈欠。 */
            const val DEFAULT_MIN_OPEN_RATIO = 0.7f
        }
    }

    /** 闭眼判定开关。 */
    data class EyeCloseRule(
        /** 闭眼判定是否启用。 */
        val enabled: Boolean = true,
        /** **有效闭眼最短时长**（ms）：单次闭眼 ≥ 此值才计入"有效闭眼"统计（过滤眨眼）。
         *  疲劳判定各 `EyeCloseCount` 条件自带 `min_duration_ms`；此字段用于诊断统计的统一过滤。 */
        val minCountDurationMs: Long = DEFAULT_MIN_COUNT_DURATION_MS
    ) {
        companion object {
            /** 默认有效闭眼最短时长（过滤眨眼）。 */
            const val DEFAULT_MIN_COUNT_DURATION_MS = 200L
        }
    }

    /** 单个进入/退出条件。 */
    sealed class Condition {
        /** 时间窗内闭眼次数 ≥ countMin（单次闭眼 ≥ minDurationMs 才计数）。 */
        data class EyeCloseCount(
            val windowMs: Long,
            val countMin: Int,
            val minDurationMs: Long
        ) : Condition()

        /** 时间窗内哈欠次数 ≥ countMin。 */
        data class YawnCount(
            val windowMs: Long,
            val countMin: Int
        ) : Condition()

        /** 当前连续闭眼时长 ∈ [minMs, maxMs)；maxMs 缺省=无穷大。 */
        data class EyeCloseDuration(
            val minMs: Long,
            val maxMs: Long? = null
        ) : Condition()

        /** 时间窗内无超过 clearDurationMs 的闭眼/哈欠事件（退出用）。 */
        data class CleanClear(
            val windowMs: Long,
            val clearDurationMs: Long
        ) : Condition()
    }

    /** 单个疲劳等级规则。 */
    data class LevelRule(
        val level: Level,
        val enabled: Boolean = true,
        /** 进入条件数组，任一满足即进入（或关系）。 */
        val enter: List<Condition>,
        /** 退出条件（示例统一为 CleanClear）。 */
        val exit: Condition
    )

    /** 疲劳等级枚举（含正常）。 */
    enum class Level { NONE, LIGHT, MODERATE, SEVERE }

    /** 无人脸复位条件。 */
    data class NoFaceRule(
        val enabled: Boolean = true,
        /** 无人脸持续 ≥ 此时长复位疲劳等级（ms）。 */
        val resetMs: Long = DEFAULT_RESET_MS
    ) {
        companion object {
            const val DEFAULT_RESET_MS = 3000L
        }
    }

    companion object {
        /** 闭眼判定阈值（单帧连续开合度 ≤ 此值判闭眼，防抖已去除）。 */
        const val EYE_CLOSE_RATIO = 0.10f

        /** 用户给定规则（2026-08-25）对应的默认三级规则。 */
        val DEFAULT_LEVELS: List<LevelRule> = listOf(
            // 轻度：60s 内闭眼 ≥9 次（单次≥0.2s）或 60s 内哈欠 ≥2 次
            LevelRule(
                level = Level.LIGHT,
                enter = listOf(
                    Condition.EyeCloseCount(60_000L, 9, 200L),
                    Condition.YawnCount(60_000L, 2)
                ),
                exit = Condition.CleanClear(20_000L, 750L)
            ),
            // 中度：60s 闭眼≥10次(≥0.2s) / 20s 闭眼≥2次(≥0.75s) / 连续闭眼1.5~2.4s / 60s 哈欠≥3次
            LevelRule(
                level = Level.MODERATE,
                enter = listOf(
                    Condition.EyeCloseCount(60_000L, 10, 200L),
                    Condition.EyeCloseCount(20_000L, 2, 750L),
                    Condition.EyeCloseDuration(1_500L, 2_400L),
                    Condition.YawnCount(60_000L, 3)
                ),
                exit = Condition.CleanClear(20_000L, 750L)
            ),
            // 重度：20s 闭眼≥2次(≥1.2s) / 连续闭眼 ≥2.4s
            LevelRule(
                level = Level.SEVERE,
                enter = listOf(
                    Condition.EyeCloseCount(20_000L, 2, 1_200L),
                    Condition.EyeCloseDuration(2_400L)
                ),
                exit = Condition.CleanClear(20_000L, 750L)
            )
        )
    }
}
