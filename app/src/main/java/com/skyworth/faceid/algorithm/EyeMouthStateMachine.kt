/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.algorithm

/**
 * 眼睛/嘴巴基础状态防抖器（多帧时序）。
 *
 * 职责（阶段三）：对 `EyeMouthStateEstimator` 输出的**连续开合度**做多帧时序稳定，
 * 输出稳定的基础状态 `eyeClosed` / `mouthOpen`。**不含任何业务判定**（疲劳/哈欠等
 * 上层业务不在本类）。
 *
 * 对齐项目现有 `DistractionStateMachine` 的成熟模式：
 * - **单调时钟**按持续时间累计（非帧数），天然抗跳帧导致的计数失真；
 * - **双阈值滞回**：解决闭眼时上下睑残差不完全闭合导致的阈值附近闪变；
 * - 时钟 `clockMs` 与阈值可注入，便于单元测试。
 *
 * 方向约定（开合度均为 `EyeMouthStateEstimator` 输出）：
 * - `eyeOpenRatio`：0.0=闭眼 ~ 1.0=睁眼；
 * - `mouthOpenRatio`：0.0=闭嘴 ~ 1.0=张嘴。
 *
 * 滞回说明（**不对称防抖**：进入严格、退出即时）：
 * - 眼睛：`eyeOpenRatio ≤ [eyeCloseRatio]` → 闭眼候选，持续 [confirmMs] 确认 `eyeClosed=true`；
 *          `eyeOpenRatio ≥ [eyeOpenRatio]` → 睁眼候选，**满足即解除** `eyeClosed=false`；
 * - 嘴巴：`mouthOpenRatio ≥ [mouthOpenRatio]` → 张嘴候选，持续 [mouthConfirmMs] 确认 `mouthOpen=true`；
 *          `mouthOpenRatio ≤ [mouthCloseRatio]` → 闭嘴候选，**满足即解除** `mouthOpen=false`；
 * - 滞回区间（介于两阈值之间）→ 维持上一状态，计时重置（吸收抖动）。
 *
 * 不对称理由：进入"关闭"状态（闭眼/张嘴）需要确认时长，防止阈值附近抖动误报；
 * 退出方向对应**真实状态的恢复**（睁开眼/闭上嘴），应立即反映，若也要求连续确认，
 * 会因单帧抖动反复重置计时，出现"明明睁眼了仍判闭眼"的延迟。
 *
 * 线程安全：非线程安全，需在单一线程内调用（如算法处理线程）。
 */
class EyeMouthStateMachine @JvmOverloads constructor(
    /** 单调时钟（ms），默认 elapsedRealtime；可注入便于测试。 */
    private val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    /** 眼睛闭眼候选阈值（eyeOpenRatio ≤ 此值进入闭眼候选；数值越小判定越严格）。 */
    private val eyeCloseRatio: Float = 0.10f,
    /** 眼睛睁眼候选阈值（eyeOpenRatio ≥ 此值进入睁眼候选；数值越小退出闭眼越容易）。 */
    private val eyeOpenRatio: Float = 0.30f,
    /** 嘴巴张嘴候选阈值（mouthOpenRatio ≥ 此值进入张嘴候选）。 */
    private val mouthOpenRatio: Float = 0.60f,
    /** 嘴巴闭嘴候选阈值（mouthOpenRatio ≤ 此值进入闭嘴候选）。 */
    private val mouthCloseRatio: Float = 0.35f,
    /** 状态确认时长（ms），对称短确认（约 2~3 帧）。 */
    private val confirmMs: Long = 80L,
    /** 嘴巴状态确认时长（ms），更长以过滤说话时的快速开合抖动。 */
    private val mouthConfirmMs: Long = 200L
) {

    companion object {
        /** 眼睛闭眼候选默认阈值（数值越小判定越严格）。 */
        const val DEFAULT_EYE_CLOSE_RATIO = 0.10f
        /** 眼睛睁眼候选默认阈值（退出闭眼的开合度门槛）。 */
        const val DEFAULT_EYE_OPEN_RATIO = 0.30f
        /** 嘴巴张嘴候选默认阈值（基于手动标定张嘴基准，高于此判张嘴）。 */
        const val DEFAULT_MOUTH_OPEN_RATIO = 0.60f
        /** 嘴巴闭嘴候选默认阈值（基于手动标定闭嘴基准，低于此判闭嘴）。 */
        const val DEFAULT_MOUTH_CLOSE_RATIO = 0.35f
        /** 默认眼睛确认时长（ms）。 */
        const val DEFAULT_CONFIRM_MS = 80L
        /** 默认嘴巴确认时长（ms），更长以过滤快速开合抖动。 */
        const val DEFAULT_MOUTH_CONFIRM_MS = 200L
    }

    /** 眼睛状态（内部状态机）。 */
    private val eyeState = AxisState(confirmMs, clockMs)

    /** 嘴巴状态（内部状态机，独立更长确认时长）。 */
    private val mouthState = AxisState(mouthConfirmMs, clockMs)

    /**
     * 更新一帧状态。
     *
     * @param hasFace 本帧是否检测到人脸；false 时重置，不产生判定。
     * @param eyeOpenRatio 本帧眼睛开合度 0~1（`EyeMouthStateEstimator` 输出）。
     * @param mouthOpenRatio 本帧嘴巴开合度 0~1。
     */
    fun update(hasFace: Boolean, eyeOpenRatio: Float, mouthOpenRatio: Float) {
        update(hasFace, eyeOpenRatio, mouthOpenRatio, null)
    }

    /**
     * 更新一帧状态（支持动态阈值）。
     *
     * @param hasFace 本帧是否检测到人脸；false 时重置，不产生判定。
     * @param eyeOpenRatio 本帧眼睛开合度 0~1（`EyeMouthStateEstimator` 输出）。
     * @param mouthOpenRatio 本帧嘴巴开合度 0~1。
     * @param thresholds 动态阈值（`EyeMouthCalibrator` 输出）；null 时用构造参数的固定阈值。
     */
    fun update(
        hasFace: Boolean,
        eyeOpenRatio: Float,
        mouthOpenRatio: Float,
        thresholds: EyeMouthCalibrator.CalibratedThresholds?
    ) {
        if (!hasFace) {
            reset()
            return
        }

        // 眼睛：开合度小 = 闭眼候选；开合度大 = 睁眼候选
        val eyeClose = thresholds?.eyeCloseRatio ?: this.eyeCloseRatio
        val eyeOpen = thresholds?.eyeOpenRatio ?: this.eyeOpenRatio
        val eyeCloseCandidate = eyeOpenRatio <= eyeClose
        val eyeOpenCandidate = eyeOpenRatio >= eyeOpen
        eyeState.update(eyeCloseCandidate, eyeOpenCandidate)

        // 嘴巴：开合度大 = 张嘴候选；开合度小 = 闭嘴候选
        // 嘴巴阈值采用固定值（基于手动标定的闭嘴/张嘴基准），不随动态校准漂移，
        // 以保证张嘴/闭嘴判定的稳定性与防抖。
        val mouthOpenCandidate = mouthOpenRatio >= this.mouthOpenRatio
        val mouthCloseCandidate = mouthOpenRatio <= this.mouthCloseRatio
        mouthState.update(mouthOpenCandidate, mouthCloseCandidate)
    }

    /** 当前是否判定为闭眼（稳定状态）。 */
    fun isEyeClosed(): Boolean = eyeState.closed

    /** 当前是否判定为张嘴（稳定状态）。 */
    fun isMouthOpen(): Boolean = mouthState.closed

    /** 重置状态（无人脸时调用）。 */
    fun reset() {
        eyeState.reset()
        mouthState.reset()
    }

    /**
     * 单轴滞回确认器（内部）。
     *
     * 维护一个"关闭"状态（眼睛=闭眼、嘴巴=张嘴），通过候选方向 + 持续确认。
     * 进入/退出**不对称**：
     * - **进入关闭**（闭眼/张嘴）：需 [confirmMs] 连续候选确认，防阈值附近抖动误报；
     * - **退出关闭**（睁眼/闭嘴）：候选满足即**立即解除**，不设确认时长——
     *   退出对应真实状态恢复（睁开眼/闭上嘴），应即时反映；若也要求连续确认，
     *   真实值贴着阈值轻微抖动时计时会被反复重置，造成"已恢复仍保持关闭"的延迟。
     * 滞回区间（两个候选方向均为 false）时保持上一状态并重置计时，吸收抖动。
     */
    private class AxisState(
        private val confirmMs: Long,
        private val clockMs: () -> Long
    ) {
        /** 当前确认的"关闭"状态（眼睛=闭眼、嘴巴=张嘴）。 */
        var closed = false
            private set

        /** 是否正在累计计时。 */
        private var timing = false

        /** 最近一次累计起始时间戳。 */
        private var accumStart = 0L

        /**
         * 更新本轴状态。
         *
         * @param closeCandidate 是否进入"关闭候选"（如闭眼/张嘴）。
         * @param openCandidate 是否进入"打开候选"（如睁眼/闭嘴）。
         */
        fun update(closeCandidate: Boolean, openCandidate: Boolean) {
            val now = clockMs()

            if (closed) {
                // 已确认关闭：打开候选满足即立即解除（不对称防抖，退出不设确认时长）
                if (openCandidate) {
                    closed = false
                    resetTiming()
                } else {
                    resetTiming()  // 仍关闭或滞回区间，保持关闭
                }
            } else {
                // 未关闭（打开）：只有"关闭候选"持续确认才进入
                if (closeCandidate) {
                    if (!timing) {
                        timing = true
                        accumStart = now
                    } else if (now - accumStart >= confirmMs) {
                        closed = true
                        resetTiming()
                    }
                } else {
                    resetTiming()  // 仍打开或滞回区间，保持打开
                }
            }
        }

        /** 重置状态与计时。 */
        fun reset() {
            closed = false
            resetTiming()
        }

        private fun resetTiming() {
            timing = false
            accumStart = 0L
        }
    }
}
