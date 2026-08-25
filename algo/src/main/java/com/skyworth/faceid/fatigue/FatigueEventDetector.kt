/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.fatigue

/**
 * 疲劳事件（FACEP-015）。
 *
 * 由 [FatigueEventDetector] 产出，表示一次闭眼 / 哈欠的**结束**（含持续时长）。
 */
data class FatigueEvent(
    val type: Type,
    val endMs: Long,
    /** 本次事件持续时长（ms）。 */
    val durationMs: Long
) {
    enum class Type { EYE_CLOSE, YAWN }
}

/**
 * 疲劳事件检测器（FACEP-015）。
 *
 * 将逐帧连续量（`eyeOpenRatio` / `mouthOpenRatio`）转化为"闭眼/哈欠事件"，
 * 跟踪状态边沿与持续时长。纯 JVM（时钟由外部注入，便于测试）。
 *
 * 判定规则：
 * - 闭眼：`eyeOpenRatio ≤ FatigueRule.EYE_CLOSE_RATIO(0.10)`；
 * - 哈欠（方案 C，区分说话抖动）：维护一个"张嘴窗口"（从首次嘴开进入，到**持续闭合 ≥
 *   内部确认时长 [yawnCloseEndMs]** 退出）。窗口内用**时间**累计：
 *   - `openAccumMs`：开口（≥阈值）累计时长；
 *   - `activeMs`：活跃期（窗口开始 → 最后一次开口段结束）时长，说话中的停顿计入（稀释占比）。
 *   窗口结束时判定：`openAccumMs / activeMs ≥ minOpenRatio`（0.7，平稳大张）且 `activeMs ≥
 *   minDurationMs` → 记为**哈欠**；否则（说话：频繁停顿导致开口占比低）→ 丢弃，不记哈欠。
 *
 * 调用约定：每帧调用 [onFrame]，返回本帧**新结束**的事件（若有）。事件含持续时长，
 * 由 [FatigueStateMachine] 依据各条件阈值过滤计数。
 */
class FatigueEventDetector(
    private val rule: FatigueRule
) {
    /** 张嘴窗口结束的"闭嘴确认时长"（ms）：嘴闭合持续 ≥ 此值才认为一次张嘴动作结束并判定。
     *  说话中的短暂停顿（<此值）不结束窗口，持续累积以降低开口占比（区分说话）。 */
    private val yawnCloseEndMs = 1000L

    private var eyeClosed = false
    private var eyeCloseStartMs = 0L

    // ---- 哈欠张嘴窗口状态（方案 C）----
    private var yawnActive = false
    private var yawnStartMs = 0L
    private var yawnOpenAccumMs = 0L
    private var lastOpenStartMs = 0L
    private var activeEndMs = 0L
    private var lastCloseStartMs = -1L

    /** 最近一次闭眼/哈欠开始时刻（诊断用）。 */
    var lastEyeCloseStartMs: Long = -1L
        private set
    var lastYawnStartMs: Long = -1L
        private set

    /** 当前连续闭眼时长（ms），未闭眼为 0。 */
    var curEyeCloseMs: Long = 0L
        private set

    /** 当前"张嘴窗口"活跃期时长（ms）；无张嘴窗口为 0。 */
    var curYawnMs: Long = 0L
        private set

    /**
     * 重置所有边沿/窗口状态（疲劳退出或无人脸复位时调用）。
     * 避免退出后旧的闭眼/张嘴计时延续，导致 `eye_close_duration` 等条件立刻满足、
     * "恢复正常后立刻告警"（FACEP-015）。
     */
    fun reset() {
        eyeClosed = false
        eyeCloseStartMs = 0L
        yawnActive = false
        yawnStartMs = 0L
        yawnOpenAccumMs = 0L
        lastOpenStartMs = 0L
        activeEndMs = 0L
        lastCloseStartMs = -1L
        lastEyeCloseStartMs = -1L
        lastYawnStartMs = -1L
        curEyeCloseMs = 0L
        curYawnMs = 0L
    }

    /**
     * 输入一帧，产出本帧新结束的事件。
     *
     * @param nowMs 当前时刻（ms，单调递增）
     */
    fun onFrame(eyeOpenRatio: Float, mouthOpenRatio: Float, hasFace: Boolean, nowMs: Long): List<FatigueEvent> {
        val events = mutableListOf<FatigueEvent>()

        // 人脸缺失：不产生事件（无人脸时由状态机走复位逻辑）
        if (!hasFace) {
            if (eyeClosed) {
                events.add(FatigueEvent(FatigueEvent.Type.EYE_CLOSE, nowMs, nowMs - eyeCloseStartMs))
                eyeClosed = false
            }
            if (yawnActive) {
                val yawn = evaluateYawnWindow(nowMs)
                if (yawn != null) events.add(yawn)
            }
            curEyeCloseMs = 0L
            curYawnMs = 0L
            return events
        }

        // 眼睛：开合度 ≤ 阈值视为闭眼
        val eyeClosedNow = rule.eyeClose.enabled && eyeOpenRatio <= FatigueRule.EYE_CLOSE_RATIO
        if (eyeClosedNow && !eyeClosed) {
            eyeClosed = true
            eyeCloseStartMs = nowMs
            lastEyeCloseStartMs = nowMs
        } else if (!eyeClosedNow && eyeClosed) {
            events.add(FatigueEvent(FatigueEvent.Type.EYE_CLOSE, nowMs, nowMs - eyeCloseStartMs))
            eyeClosed = false
        }
        curEyeCloseMs = if (eyeClosed) nowMs - eyeCloseStartMs else 0L

        // 哈欠：张嘴窗口 + 开口占比（方案 C，区分说话抖动）
        val mouthOpenNow = rule.yawn.enabled && mouthOpenRatio >= rule.yawn.mouthOpenRatio
        if (mouthOpenNow) {
            if (!yawnActive) {
                // 进入张嘴窗口（首次开口）
                yawnActive = true
                yawnStartMs = nowMs
                lastOpenStartMs = nowMs
                yawnOpenAccumMs = 0L
                activeEndMs = nowMs
                lastCloseStartMs = -1L
                lastYawnStartMs = nowMs
            } else {
                // 开口中：活跃期延伸；若之前闭口则开启新开口段
                activeEndMs = nowMs
                if (lastCloseStartMs >= 0) {
                    lastOpenStartMs = nowMs
                    lastCloseStartMs = -1L
                }
            }
        } else {
            if (yawnActive) {
                if (lastCloseStartMs < 0) {
                    // 开口段结束：累积开口时长，活跃期终点 = 闭口开始
                    lastCloseStartMs = nowMs
                    activeEndMs = nowMs
                    yawnOpenAccumMs += lastCloseStartMs - lastOpenStartMs
                }
                // 闭嘴持续 ≥ 确认时长 → 结束窗口并判定
                if (nowMs - lastCloseStartMs >= yawnCloseEndMs) {
                    val yawn = evaluateYawnWindow(nowMs)
                    if (yawn != null) events.add(yawn)
                }
            }
        }
        // 持续张嘴时长仅在"当前正在张嘴"（开合度≥阈值）时显示当前开口段时长；
        // 闭口期间（开合度<阈值，如 0.08）显示 0——即使张嘴窗口为统计开口占比仍活跃，
        // UI 也不应显示"持续张嘴"（否则看到 0.08 还显示张嘴，与实际开合度矛盾）。
        curYawnMs = if (mouthOpenNow && yawnActive) nowMs - lastOpenStartMs else 0L

        return events
    }

    /** 结束张嘴窗口：满足"活跃期时长 + 开口占比"则记一次哈欠事件，否则丢弃（说话/抖动）。 */
    private fun evaluateYawnWindow(nowMs: Long): FatigueEvent? {
        val activeMs = (activeEndMs - yawnStartMs).coerceAtLeast(0L)
        val openRatio = if (activeMs > 0) {
            (yawnOpenAccumMs.toFloat() / activeMs.toFloat()).coerceIn(0f, 1f)
        } else 0f

        // 重置窗口状态（无论是否记为哈欠）
        yawnActive = false
        yawnStartMs = 0L
        yawnOpenAccumMs = 0L
        lastOpenStartMs = 0L
        activeEndMs = 0L
        lastCloseStartMs = -1L
        curYawnMs = 0L

        val isYawn = activeMs >= rule.yawn.minDurationMs && openRatio >= rule.yawn.minOpenRatio
        return if (isYawn) {
            FatigueEvent(FatigueEvent.Type.YAWN, nowMs, activeMs)
        } else {
            null
        }
    }
}
