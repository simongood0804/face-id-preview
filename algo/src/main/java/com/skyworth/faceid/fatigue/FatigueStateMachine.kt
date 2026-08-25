/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.fatigue

import com.skyworth.faceid.fatigue.FatigueRule.Condition

/**
 * 疲劳判定状态机（FACEP-015）。
 *
 * 多级疲劳（轻度/中度/重度）+ 覆盖升级 + 直接退出 + 时间窗统计 + 无人脸复位。
 *
 * 状态机：`NONE → LIGHT → MODERATE → SEVERE`
 * - **升级覆盖**：当前等级及更高级的 enter 条件（或关系）任一满足，即切换到（更高）该等级；
 * - **直接退出**：当前等级的 exit 条件满足，**直接回 NONE**（不再逐级降）——三级是覆盖关系，退出即恢复正常；
 * - **窗口统计**：60s/20s 内闭眼次数、哈欠次数，基于结束事件统计（超窗惰性清理）；
 * - **无人脸复位**：持续无人脸 ≥ reset_ms → 复位 NONE 并清空统计。
 *
 * 纯 JVM（时钟由外部注入，便于测试）。规则经 [FatigueRule] 注入。
 */
class FatigueStateMachine(
    private val rule: FatigueRule = FatigueRule()
) {
    private val detector = FatigueEventDetector(rule)

    /** 所有已记录事件（含结束时刻与时长），按时刻惰性清理。 */
    private val events = mutableListOf<Pair<Long, FatigueEvent>>() // (endMs, event)

    private var level: FatigueRule.Level = FatigueRule.Level.NONE
    private var levelEnterMs = 0L
    private var lastNoFaceStartMs = -1L

    /** 诊断：命中的进入条件描述。 */
    var lastMatchedCondition: String? = null
        private set

    /**
     * 更新一帧，返回当前疲劳等级与诊断信息。
     *
     * @param eyeOpenRatio 眼睛连续开合度（0~1，1=全睁）
     * @param mouthOpenRatio 嘴部连续开合度（0~1，1=全张）
     * @param hasFace 是否检测到人脸
     * @param nowMs 当前时刻（ms，单调递增）
     */
    fun update(eyeOpenRatio: Float, mouthOpenRatio: Float, hasFace: Boolean, nowMs: Long): FatigueOutput {
        // 1. 事件检测：新结束的闭眼/哈欠事件入池
        val newEvents = detector.onFrame(eyeOpenRatio, mouthOpenRatio, hasFace, nowMs)
        for (e in newEvents) events.add(nowMs to e)

        // 2. 无人脸复位
        if (!hasFace) {
            if (lastNoFaceStartMs < 0) lastNoFaceStartMs = nowMs
            val noFaceMs = nowMs - lastNoFaceStartMs
            if (rule.noFace.enabled && noFaceMs >= rule.noFace.resetMs) {
                level = FatigueRule.Level.NONE
                levelEnterMs = nowMs
                lastMatchedCondition = null
                events.clear()
            }
            lastNoFaceMs = noFaceMs
            return output(eyeOpenRatio, mouthOpenRatio, nowMs)
        } else {
            lastNoFaceStartMs = -1L
            lastNoFaceMs = 0L
        }

        // 3. 过期事件清理
        cleanExpired(nowMs)

        // 4. 升级判定：从当前等级往更高等级检查（或关系）
        val targetLevel = findHigherLevel(nowMs)
        if (targetLevel != null) {
            level = targetLevel
            levelEnterMs = nowMs
        } else {
            // 5. 退出判定：当前等级 exit 满足 → 降级
            maybeDowngrade(nowMs)
        }

        return output(eyeOpenRatio, mouthOpenRatio, nowMs)
    }

    /** 从当前等级往更高等级找**最高** enter 满足的等级（返回 null 表示不升级）。
     *  覆盖语义：若同时满足中度/重度条件，直接升到**最高**满足等级（重度），
     *  而非第一个遍历到的——符合"高等级覆盖低等级"（隐患 C 修复）。 */
    private fun findHigherLevel(nowMs: Long): FatigueRule.Level? {
        var highest: FatigueRule.Level? = null
        var highestDesc: String? = null
        for (lr in rule.levels) {
            if (!lr.enabled) continue
            // 只检查严格高于当前等级的（NONE 时可进入最低级）
            if (level.ordinal >= lr.level.ordinal) continue
            val matched = lr.enter.firstOrNull { condMatches(it, nowMs) }
            if (matched != null) {
                if (highest == null || lr.level.ordinal > highest.ordinal) {
                    highest = lr.level
                    highestDesc = describe(matched)
                }
            }
        }
        if (highest != null) lastMatchedCondition = highestDesc
        return highest
    }

    /** 当前等级 exit 满足 → **直接退出疲劳（回 NONE）**，不再逐级降（重度→轻度→…）。
     *  语义：三级是"覆盖"关系（重替换轻），退出即回到正常状态。
     *  滞回：等级进入后需持续 ≥ exit 窗口时长，且窗口内仍无显著事件，才允许退出——
     *  避免"刚触发(如 9 次短闭眼进入轻度)就因无长事件立即退出"。 */
    private fun maybeDowngrade(nowMs: Long) {
        val cur = rule.levels.firstOrNull { it.level == level && it.enabled } ?: return
        val exitWindow = (cur.exit as? Condition.CleanClear)?.windowMs ?: 20_000L
        if (nowMs - levelEnterMs < exitWindow) return // 滞回：未满一个退出窗口不退出
        if (exitSatisfied(cur.exit, nowMs)) {
            level = FatigueRule.Level.NONE
            levelEnterMs = nowMs
            lastMatchedCondition = null
        }
    }

    /** 单个进入条件是否满足。 */
    private fun condMatches(c: Condition, nowMs: Long): Boolean = when (c) {
        is Condition.EyeCloseCount -> {
            eventsInWindow(nowMs, c.windowMs).count { it.type == FatigueEvent.Type.EYE_CLOSE && it.durationMs >= c.minDurationMs } >= c.countMin
        }
        is Condition.YawnCount -> {
            eventsInWindow(nowMs, c.windowMs).count { it.type == FatigueEvent.Type.YAWN && it.durationMs >= rule.yawn.minDurationMs } >= c.countMin
        }
        is Condition.EyeCloseDuration -> {
            val d = detector.curEyeCloseMs
            d >= c.minMs && (c.maxMs == null || d < c.maxMs)
        }
        is Condition.CleanClear -> true // 不作为进入条件
    }

    /** 退出条件是否满足（CleanClear：窗口内无超过 clear_duration 的闭眼/哈欠事件）。 */
    private fun exitSatisfied(c: Condition, nowMs: Long): Boolean {
        val clear = c as? Condition.CleanClear ?: return false
        val maxDur = eventsInWindow(nowMs, clear.windowMs).maxOfOrNull { it.durationMs } ?: 0L
        return maxDur < clear.clearDurationMs
    }

    /** 窗口内事件列表（惰性清理在此处由调用方保证过期移除）。 */
    private fun eventsInWindow(nowMs: Long, windowMs: Long): List<FatigueEvent> {
        val cutoff = nowMs - windowMs
        return events.filter { it.first >= cutoff }.map { it.second }
    }

    private fun cleanExpired(nowMs: Long) {
        val cutoff = nowMs - 60_000L // 最大窗口 60s，统一按最长时间窗清理
        events.removeAll { it.first < cutoff }
    }

    private fun describe(c: Condition): String = when (c) {
        is Condition.EyeCloseCount -> "${c.windowMs / 1000}s内闭眼${c.countMin}次(≥${c.minDurationMs}ms)"
        is Condition.YawnCount -> "${c.windowMs / 1000}s内哈欠${c.countMin}次"
        is Condition.EyeCloseDuration -> "连续闭眼${c.minMs}ms" + (c.maxMs?.let { "~${it}ms" } ?: "以上")
        is Condition.CleanClear -> "清空"
    }

    private fun output(eyeOpenRatio: Float, mouthOpenRatio: Float, nowMs: Long): FatigueOutput {
        return FatigueOutput(
            level = level,
            active = level != FatigueRule.Level.NONE,
            matchedCondition = lastMatchedCondition,
            eyeOpenRatio = eyeOpenRatio,
            mouthOpenRatio = mouthOpenRatio,
            eyeCloseCount60s = countTypeInWindow(nowMs, 60_000L, FatigueEvent.Type.EYE_CLOSE, rule.eyeClose.minCountDurationMs),
            eyeCloseCount20s = countTypeInWindow(nowMs, 20_000L, FatigueEvent.Type.EYE_CLOSE, rule.eyeClose.minCountDurationMs),
            yawnCount60s = countTypeInWindow(nowMs, 60_000L, FatigueEvent.Type.YAWN, rule.yawn.minDurationMs),
            curEyeCloseMs = detector.curEyeCloseMs,
            curYawnMs = detector.curYawnMs,
            curNoFaceMs = lastNoFaceMs
        )
    }

    /** 窗口内某类事件的次数（带最小时长过滤：只统计持续 ≥ minDurationMs 的"有效"事件，
     *  眨眼/短张嘴不计入，诊断统计与疲劳判定语义一致）。 */
    private fun countTypeInWindow(nowMs: Long, windowMs: Long, type: FatigueEvent.Type, minDurationMs: Long): Int {
        return eventsInWindow(nowMs, windowMs).count { it.type == type && it.durationMs >= minDurationMs }
    }

    /** 当前无人脸持续时长（诊断用）。 */
    var lastNoFaceMs: Long = 0L
        private set

    /** 疲劳判定输出（含诊断信息，供渲染展示）。 */
    data class FatigueOutput(
        val level: FatigueRule.Level,
        val active: Boolean,
        val matchedCondition: String?,
        /** 当前眼睛开合度（实时，0~1，1=全睁）。 */
        val eyeOpenRatio: Float,
        /** 当前嘴部开合度（实时，0~1，1=全张）。 */
        val mouthOpenRatio: Float,
        val eyeCloseCount60s: Int,
        val eyeCloseCount20s: Int,
        val yawnCount60s: Int,
        val curEyeCloseMs: Long,
        val curYawnMs: Long,
        val curNoFaceMs: Long
    )
}
