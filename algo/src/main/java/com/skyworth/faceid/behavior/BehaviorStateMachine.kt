package com.skyworth.faceid.behavior

/**
 * 行为监测时序判定状态机（抽烟 / 打电话），采用**方案 D：双阈值滞回 + 分阶段宽限**。
 *
 * SDK 只给逐帧行为类别，抖动（单帧误检/漏检）会导致状态频繁跳变，需要时序确认：
 * - **进入阶段（严格）**：连续满足持续时长才确认，避免单帧误检；
 * - **退出阶段（宽限）**：已确认后给一段宽限，避免零星丢帧导致状态闪烁。
 *
 * 判定口径（默认值，符合 GB/T 41797 对行为响应的要求）：
 * - 抽烟：连续 ≥ [smokingEnterMs]（默认 2000ms）确认；确认后中断 ≤ [smokingExitMs] 不清除；
 * - 打电话：连续 ≥ [phoneEnterMs]（默认 3000ms）确认；确认后中断 ≤ [phoneExitMs] 不清除。
 *
 * 四个延时均**可运行时调整**（0 ~ [MAX_DELAY_MS]），用于调试与现场标定：
 * - 进入延时为 0 表示**不延时**，当帧即确认（等价于不做时序确认，退回 SDK 逐帧结果）；
 * - 退出延时为 0 表示**不宽限**，一旦类别不再匹配立即回落。
 *
 * 时间基准：调用方传入 `clockMs`（单调时钟，推荐 `System.nanoTime()/1_000_000`），
 * 按**真实经过时间**累计而非帧数——帧率不稳定时按帧计数会漂移。
 *
 * 线程安全：非线程安全。共享层（[com.skyworth.faceid.core.AlgoSession]）在单线程
 * `AlgoProcessor` 内调用，配置变更亦须在该线程或加锁语境下进行。
 */
class BehaviorStateMachine(
    /** 抽烟进入延时（ms）：连续满足该时长才确认。0 = 不延时。 */
    initialSmokingEnterMs: Long = DEFAULT_SMOKING_ENTER_MS,
    /** 抽烟退出延时（ms）：确认后中断不超过该时长则保持。0 = 不宽限。 */
    initialSmokingExitMs: Long = DEFAULT_EXIT_MS,
    /** 打电话进入延时（ms）。0 = 不延时。 */
    initialPhoneEnterMs: Long = DEFAULT_PHONE_ENTER_MS,
    /** 打电话退出延时（ms）。0 = 不宽限。 */
    initialPhoneExitMs: Long = DEFAULT_EXIT_MS
) {

    /** 延时上限：10 秒（UI 滑块行程与此一致）。 */
    companion object {
        /** 正常（无行为）。 */
        const val CLASS_NORMAL = 0

        /** 抽烟。 */
        const val CLASS_SMOKING = 1

        /** 打电话。 */
        const val CLASS_PHONE = 2

        /** 抽烟进入延时默认值（GB/T 41797：抽烟 ≥2s）。 */
        const val DEFAULT_SMOKING_ENTER_MS = 2000L

        /** 打电话进入延时默认值（GB/T 41797：打电话 ≥3s）。 */
        const val DEFAULT_PHONE_ENTER_MS = 3000L

        /** 退出宽限默认值（与 DistractionStateMachine.CLEAR_MS 一致，远小于 GB/T 1.5s 响应要求）。 */
        const val DEFAULT_EXIT_MS = 500L

        /** 延时上限：10 秒。 */
        const val MAX_DELAY_MS = 10_000L

        /** 宽限期无暂存类别的哨兵值。 */
        private const val GRACE_NONE = -1

        /** 取某类别的**默认**进入延时（供日志/UI 初始展示）。 */
        fun defaultEnterMsOf(cls: Int): Long = when (cls) {
            CLASS_SMOKING -> DEFAULT_SMOKING_ENTER_MS
            CLASS_PHONE -> DEFAULT_PHONE_ENTER_MS
            else -> 0L
        }

        /** 取某类别的**默认**退出延时（供日志/UI 初始展示）。 */
        fun defaultExitMsOf(cls: Int): Long = when (cls) {
            CLASS_SMOKING, CLASS_PHONE -> DEFAULT_EXIT_MS
            else -> 0L
        }
    }

    // ---- 可运行时调整的四个延时（外部经 setter 修改，做范围钳制）----
    // 注意：初始值必须引用**构造参数**（initialXxxMs），不能写成 `clamp(smokingEnterMs)`——
    // 在属性初始化表达式中引用同名属性会被解析为属性自身 getter（此时尚未赋值，读到 0），
    // 从而把默认延时全部覆盖成 0（曾导致行为状态疯狂翻转）。

    /** 抽烟进入延时（ms）。 */
    var smokingEnterMs: Long = clamp(initialSmokingEnterMs)
        internal set(v) { field = clamp(v); onDelayChanged() }

    /** 抽烟退出延时（ms）。 */
    var smokingExitMs: Long = clamp(initialSmokingExitMs)
        internal set(v) { field = clamp(v); onDelayChanged() }

    /** 打电话进入延时（ms）。 */
    var phoneEnterMs: Long = clamp(initialPhoneEnterMs)
        internal set(v) { field = clamp(v); onDelayChanged() }

    /** 打电话退出延时（ms）。 */
    var phoneExitMs: Long = clamp(initialPhoneExitMs)
        internal set(v) { field = clamp(v); onDelayChanged() }

    /** 当前确认后的类别（0=正常 / 1=抽烟 / 2=打电话）。 */
    private var mConfirmed: Int = CLASS_NORMAL

    /** 进入阶段：当前连续命中的候选类别与其起始时刻。 */
    private var mCandidate: Int = CLASS_NORMAL
    private var mCandidateSinceMs: Long = 0L

    /** 退出阶段：宽限期内暂存的上一个已确认类别（[GRACE_NONE] 表示无）。 */
    private var mGraceClass: Int = GRACE_NONE
    private var mGraceSinceMs: Long = 0L

    /**
     * 喂入一帧行为类别，返回**确认后**的类别。
     *
     * @param rawClass SDK 逐帧类别（0=正常 / 1=抽烟 / 2=打电话）。
     * @param clockMs  单调时钟（ms）。默认取 `System.nanoTime()/1_000_000`。
     * @return 确认后的类别（[CLASS_NORMAL] / [CLASS_SMOKING] / [CLASS_PHONE]）。
     */
    fun update(
        rawClass: Int,
        clockMs: Long = System.nanoTime() / 1_000_000
    ): Int {
        val raw = normalize(rawClass)

        // ---- 正常类别：走退出逻辑（宽限期） ----
        if (raw == CLASS_NORMAL) {
            if (mConfirmed == CLASS_NORMAL) {
                // 本来就没行为：清掉进入中的候选计时
                mCandidate = CLASS_NORMAL
                return CLASS_NORMAL
            }
            // 退出延时为 0 → 不宽限，立即回落。
            // ★ 必须在此**提前返回**：否则下面的宽限逻辑会先"维持一帧旧状态"
            //   （grace_since 记为当帧，clock-grace_since==0 <= 0 成立），
            //   使上一帧的偶发命中被多显示一帧；配合进入延时也为 0 时，
            //   raw 的每一次瞬时脉冲都会被放大成一次可见的状态跳变 → 界面闪烁。
            val exitMs = exitMsOf(mConfirmed)
            if (exitMs <= 0L) {
                clearAll()
                return CLASS_NORMAL
            }
            // 已确认行为，当前帧不再匹配 → 起（或延续）宽限计时
            if (mGraceClass == GRACE_NONE) {
                mGraceClass = mConfirmed
                mGraceSinceMs = clockMs
                // 首帧进入宽限：维持原状态（宽限期本身即"允许中断"的语义）
                return mConfirmed
            }
            // 宽限期仍未过 → 维持原确认类别（防闪烁）
            if (clockMs - mGraceSinceMs <= exitMs) {
                return mConfirmed
            }
            // 宽限已过 → 真正回落
            clearAll()
            return CLASS_NORMAL
        }

        // ---- 行为类别：走进入逻辑（严格累计） ----
        // 若与当前已确认类别一致，直接维持（无需重新计时）
        if (raw == mConfirmed) {
            // 命中即清宽限（避免"确认后短暂中断又恢复"被误判为退出流程）
            mGraceClass = GRACE_NONE
            return mConfirmed
        }

        // 与已确认类别不同（含从正常进入、或行为间切换）→ 重新累计候选
        val enterMs = enterMsOf(raw)
        if (enterMs <= 0L) {
            // 进入延时为 0：不延时，当帧即确认
            mConfirmed = raw
            mCandidate = raw
            mGraceClass = GRACE_NONE
            return mConfirmed
        }
        if (mCandidate != raw) {
            // 新的候选类别，从本帧开始计时
            mCandidate = raw
            mCandidateSinceMs = clockMs
            // 若此前有已确认行为处于宽限中，此时直接放弃宽限（切换到新行为）
            mGraceClass = GRACE_NONE
            return mConfirmed
        }
        // 候选类别连续命中，检查是否达到进入延时
        if (clockMs - mCandidateSinceMs >= enterMs) {
            mConfirmed = raw
            mGraceClass = GRACE_NONE
            return mConfirmed
        }
        return mConfirmed
    }

    /**
     * 复位：清空所有计时与已确认状态（无人脸 / 换驾驶员 / 切页时调用），
     * 避免残留计时导致后续误判。
     *
     * 注：已确认类别由 [clearAll] 一并复位（含 `mConfirmed`），此处无需重复赋值。
     */
    fun reset() {
        clearAll()
    }

    /** 当前确认后的类别（只读）。 */
    fun confirmedClass(): Int = mConfirmed

    // ============================================================
    // 内部
    // ============================================================

    private fun clearAll() {
        // ★ 必须一并复位 mConfirmed：
        //   update() 的退出分支会调用本方法后 return CLASS_NORMAL，若此处不复位，
        //   内部 mConfirmed 仍停留在旧行为（如打电话=2），下一帧 raw=0 进来时
        //   `if (mConfirmed == CLASS_NORMAL)` 判断失败 → 再次走退出分支 →
        //   输出与输入不一致并逐帧翻转（表现为"没打电话却闪打电话中"）。
        mConfirmed = CLASS_NORMAL
        mCandidate = CLASS_NORMAL
        mCandidateSinceMs = 0L
        mGraceClass = GRACE_NONE
        mGraceSinceMs = 0L
    }

    /**
     * 延时变化时复位全部计时，避免"改大延时后仍按旧起点立即触发"的反直觉行为。
     *
     * 必须**整体复位**（含 [mConfirmed]）而不是只清候选/宽限：
     * 若保留 `mConfirmed` 而清掉 `mGraceClass`，处于"已确认 + 宽限中"的状态会被
     * 打断——下一帧 `raw==正常` 时因 `mGraceClass == GRACE_NONE` 而**重新起算宽限**，
     * 相当于把宽限期延长一轮（本该已回落却继续维持）。整体复位后语义明确：
     * 改延时即重新开始判定，不再沿用任何旧状态。
     */
    private fun onDelayChanged() {
        clearAll()
    }

    private fun enterMsOf(cls: Int): Long = when (cls) {
        CLASS_SMOKING -> smokingEnterMs
        CLASS_PHONE -> phoneEnterMs
        else -> 0L
    }

    private fun exitMsOf(cls: Int): Long = when (cls) {
        CLASS_SMOKING -> smokingExitMs
        CLASS_PHONE -> phoneExitMs
        else -> 0L
    }

    /** 归一化 SDK 类别：非法值一律当正常。 */
    private fun normalize(cls: Int): Int = when (cls) {
        CLASS_SMOKING, CLASS_PHONE -> cls
        else -> CLASS_NORMAL
    }

    /** 延时范围钳制到 [0, MAX_DELAY_MS]。 */
    private fun clamp(v: Long): Long = v.coerceIn(0L, MAX_DELAY_MS)
}
