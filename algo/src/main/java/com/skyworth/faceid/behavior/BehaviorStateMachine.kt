package com.skyworth.faceid.behavior

/**
 * 行为监测时序判定状态机（抽烟 / 打电话）。
 *
 * **背景**：face-sdk 输出的 `behavior_class` 是**逐帧结果**，头文件明确
 * "持续时长/报警由 app 侧做时序逻辑，SDK 不计时"。逐帧结果存在抖动
 * （同类行为断续出现、normal↔behavior 频繁切换），直接按帧判定会误报/闪烁。
 *
 * **判据（GB/T 标准，见 docs/behavior_roi_v7.md）**：
 * - **抽烟**：连续 ≥ [SMOKING_HOLD_MS]（2s）
 * - **打电话**：连续 ≥ [PHONE_HOLD_MS]（3s）
 *
 * **防抖方案（业界双阈值滞回 + 分阶段宽限，方案 D）**：
 * - **进入严格**（未确认阶段不给宽限）：需连续达阈值才确认，断续抖动进不来，
 *   严格保持 GB/T 的进入门槛（抽烟 2s / 电话 3s），法规符合性不变；
 * - **退出发宽限**（已确认阶段给宽限）：已确认后短暂非行为（< [EXIT_GRACE_MS]）
 *   仍保持该行为，避免 SDK 单帧漏检造成状态闪烁；持续超过宽限期才解除。
 *
 * 宽限期 500ms 远小于 GB/T 的 **≤1.5s 响应要求**，故不影响合规。
 *
 * **状态转移**：
 * ```
 * NORMAL    + 行为       → PENDING（开始计时）
 * PENDING   + 同类行为   → 累计，达阈值则 → CONFIRMED
 * PENDING   + 非同类     → 回 NORMAL（严格，未确认不给宽限）
 * CONFIRMED + 非同类     → 保持 CONFIRMED（宽限期内）
 * CONFIRMED + 非同类 ≥宽限 → 回 NORMAL（真正结束）
 * ```
 *
 * 本类为纯逻辑，不依赖 Android；单调时钟通过 [clockMs] 注入便于测试。
 *
 * 线程安全：非线程安全，需在单一线程内调用。
 */
class BehaviorStateMachine(
    /** 单调时钟（ms），默认 JVM 单调时钟；可注入便于测试。 */
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {

    companion object {
        /** 行为类别：无行为 / 正常。 */
        const val CLASS_NORMAL = 0

        /** 行为类别：抽烟。 */
        const val CLASS_SMOKING = 1

        /** 行为类别：打电话。 */
        const val CLASS_PHONE = 2

        /** 抽烟确认时长（ms）：GB/T 标准，持续 ≥2s。 */
        const val SMOKING_HOLD_MS = 2000L

        /** 打电话确认时长（ms）：GB/T 标准，持续 ≥3s。 */
        const val PHONE_HOLD_MS = 3000L

        /**
         * 退出宽限期（ms）：**已确认**的行为，短暂非行为不超过该时长时保持状态，
         * 避免 SDK 逐帧漏检导致界面闪烁。
         *
         * 取值 500ms：与 [com.skyworth.faceid.signal.DistractionStateMachine.CLEAR_MS] 一致；
         * 且远小于 GB/T 的 ≤1.5s 响应要求，不影响合规。
         */
        const val EXIT_GRACE_MS = 500L

        /** 宽限计时未启动的标记（[graceSinceMs] 的空值）。 */
        private const val GRACE_NONE = -1L

        /** 某类别的确认时长；非行为类返回 0。 */
        fun holdMsOf(cls: Int): Long = when (cls) {
            CLASS_SMOKING -> SMOKING_HOLD_MS
            CLASS_PHONE -> PHONE_HOLD_MS
            else -> 0L
        }
    }

    /** 当前正在累计的类别（逐帧原始类别）。 */
    private var pendingClass = CLASS_NORMAL

    /** 当前累计计时的起始时间戳（ms）。 */
    private var pendingSinceMs = 0L

    /** 已确认输出的行为类别（[CLASS_NORMAL] 表示无行为）。 */
    private var confirmedClass = CLASS_NORMAL

    /** 已确认类别的确认时间戳（ms），用于日志/时长统计。 */
    private var confirmedSinceMs = 0L

    /**
     * 已确认行为的"中断起始时间戳"（ms）；[GRACE_NONE] 表示当前未处于中断中。
     *
     * 用于退出宽限：已确认行为出现非同类帧时开始计时，累计达到 [EXIT_GRACE_MS]
     * 才真正解除；期间若同类帧回归则清零（视为漏检）。
     */
    private var graceSinceMs = GRACE_NONE

    /**
     * 输入一帧行为类别，返回**时序确认后**的输出类别（带进入严格 + 退出发宽限的防抖）。
     *
     * @param rawClass 逐帧行为类别（0=normal 1=smoking 2=phone）；非法值按 [CLASS_NORMAL]
     * @return 确认后的行为类别：确认达阈值后返回该类别，否则 [CLASS_NORMAL]
     */
    fun update(rawClass: Int): Int {
        val cls = if (rawClass in CLASS_NORMAL..CLASS_PHONE) rawClass else CLASS_NORMAL
        val now = clockMs()

        // ---- 1. 已确认状态下：退出发宽限 ----
        // 已确认行为若与当前帧同类，则中断清零（视为漏检回归），保持确认状态；
        // 若不同类，则开始/继续累计中断时长，达到宽限期才真正解除。
        if (confirmedClass != CLASS_NORMAL) {
            if (cls == confirmedClass) {
                graceSinceMs = GRACE_NONE                       // 同类回归，中断清零
                pendingClass = cls
                pendingSinceMs = now
                return confirmedClass
            }
            // 不同类：累计中断时长
            if (graceSinceMs == GRACE_NONE) {
                graceSinceMs = now
            }
            if (now - graceSinceMs < EXIT_GRACE_MS) {
                // 宽限期内：保持已确认行为（滤掉 SDK 单帧漏检造成的闪烁）
                return confirmedClass
            }
            // 超过宽限期：真正解除
            confirmedClass = CLASS_NORMAL
            confirmedSinceMs = now
            graceSinceMs = GRACE_NONE
            pendingClass = cls
            pendingSinceMs = now
            // 落到下方"未确认"分支处理本帧
        }

        // ---- 2. 未确认阶段：严格（不给宽限）----
        if (cls == CLASS_NORMAL) {
            // 无行为：直接输出正常（进不来就算正常，符合"抖动判为正常"）
            pendingClass = CLASS_NORMAL
            pendingSinceMs = now
            return CLASS_NORMAL
        }

        // 类别变化：重置累计计时
        if (cls != pendingClass) {
            pendingClass = cls
            pendingSinceMs = now
        }

        // 累计达到该行为的确认阈值 → 确认
        if (now - pendingSinceMs >= holdMsOf(cls)) {
            confirmedClass = cls
            confirmedSinceMs = now
            graceSinceMs = GRACE_NONE
            return cls
        }

        // 尚未满足持续时长：视为无行为（正常）。
        // 未确认阶段不给宽限，保证断续抖动无法被确认（严格保持 GB/T 进入门槛）。
        return CLASS_NORMAL
    }

    /** 当前已确认的行为类别（0=正常）。 */
    fun confirmed(): Int = confirmedClass

    /** 已确认行为的持续时长（ms）；无行为时为 0。 */
    fun confirmedDurationMs(): Long =
        if (confirmedClass == CLASS_NORMAL) 0L else clockMs() - confirmedSinceMs

    /** 复位（如人脸丢失/切页）。 */
    fun reset() {
        pendingClass = CLASS_NORMAL
        pendingSinceMs = 0L
        confirmedClass = CLASS_NORMAL
        confirmedSinceMs = 0L
        graceSinceMs = GRACE_NONE
    }
}
