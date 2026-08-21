/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.algorithm

import kotlin.math.max
import kotlin.math.min

/**
 * 眼睛/嘴巴阈值动态校准器（提案 FACEP-010 §3.7.6）。
 *
 * 目的：静态阈值只对特定人脸有效，不同人眼/嘴大小、距摄像头距离、姿态、光照
 * 差异会导致 EAR/MAR 绝对范围漂移。本校准器维护**每个驾驶员**的"睁/闭眼、张/闭嘴"
 * 基准，据此动态换算滞回阈值，喂给 [EyeMouthStateMachine]，避免"此人总是睁眼/闭眼"
 * 误判。
 *
 * 两条校准路径：
 * - **运行中自适应（软漂移补偿）**：对 EAR/MAR 做滑动分位数跟踪（睁眼/张嘴取高位、
 *   闭眼/闭嘴取低位），用 EWMA 缓慢更新基准，适配同一人长时间的距离/光照漂移。
 * - **复位重校（硬复位，配合驾驶门开关信号）**：[reset] 清空基准进入"重校窗口"，
 *   在新驾驶员就位后（假定睁眼闭嘴）重新建立基准。由驾驶门开关信号触发。
 *
 * 线程安全：非线程安全，需在单一线程内调用（算法处理线程）。
 */
class EyeMouthCalibrator @JvmOverloads constructor(
    /** 滑动窗口帧数，用于分位数跟踪。 */
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    /** EWMA 平滑系数（0~1），越小越平滑。 */
    private val smoothAlpha: Float = DEFAULT_SMOOTH_ALPHA,
    /** 高位基准分位数（睁眼/张嘴候选）。 */
    private val openQuantile: Float = DEFAULT_OPEN_QUANTILE,
    /** 低位基准分位数（闭眼/闭嘴候选）。 */
    private val closeQuantile: Float = DEFAULT_CLOSE_QUANTILE,
    /** 滞回下界比例（0~1）：低位基准到高位基准区间的下界位置。 */
    private val closeRatioFactor: Float = DEFAULT_CLOSE_RATIO_FACTOR,
    /** 滞回上界比例（0~1）：低位基准到高位基准区间的上界位置。 */
    private val openRatioFactor: Float = DEFAULT_OPEN_RATIO_FACTOR
) {

    companion object {
        /** 默认滑动窗口帧数。 */
        const val DEFAULT_WINDOW_SIZE = 300
        /** 默认平滑系数。 */
        const val DEFAULT_SMOOTH_ALPHA = 0.02f
        /** 默认高位分位数。 */
        const val DEFAULT_OPEN_QUANTILE = 0.90f
        /** 默认低位分位数。 */
        const val DEFAULT_CLOSE_QUANTILE = 0.10f
        /** 默认滞回下界比例。 */
        const val DEFAULT_CLOSE_RATIO_FACTOR = 0.35f
        /** 默认滞回上界比例。 */
        const val DEFAULT_OPEN_RATIO_FACTOR = 0.70f
        /** 建立基准所需的最少样本数（少于则用默认阈值）。 */
        const val MIN_SAMPLES = 10
    }

    /** 动态换算后的防抖阈值（喂给 [EyeMouthStateMachine]）。 */
    data class CalibratedThresholds(
        /** 眼睛闭眼候选阈值（滞回下界）。 */
        val eyeCloseRatio: Float,
        /** 眼睛睁眼候选阈值（滞回上界）。 */
        val eyeOpenRatio: Float,
        /** 嘴巴闭嘴候选阈值（滞回下界）。 */
        val mouthCloseRatio: Float,
        /** 嘴巴张嘴候选阈值（滞回上界）。 */
        val mouthOpenRatio: Float
    ) {
        companion object {
            /** 默认阈值（未校准时使用，对应 StateMachine 默认值）。 */
            @JvmField
            val DEFAULT = CalibratedThresholds(
                eyeCloseRatio = 0.18f,
                eyeOpenRatio = 0.35f,
                mouthCloseRatio = 0.18f,
                mouthOpenRatio = 0.35f
            )
        }
    }

    /** 眼睛基准（EAR：开合度大=睁眼高位，小=闭眼低位）。 */
    private val eyeRef = AxisRef(CalibratedThresholds.DEFAULT.eyeOpenRatio,
        CalibratedThresholds.DEFAULT.eyeCloseRatio)

    /** 嘴巴基准（MAR：开合度大=张嘴高位，小=闭嘴低位）。 */
    private val mouthRef = AxisRef(CalibratedThresholds.DEFAULT.mouthOpenRatio,
        CalibratedThresholds.DEFAULT.mouthCloseRatio)

    /**
     * 更新一帧，做滑动分位数跟踪与基准平滑，返回当前动态换算的阈值。
     *
     * @param eyeOpenRatio 本帧眼睛开合度 0~1。
     * @param mouthOpenRatio 本帧嘴巴开合度 0~1。
     * @return 换算后的 [CalibratedThresholds]。
     */
    fun update(eyeOpenRatio: Float, mouthOpenRatio: Float): CalibratedThresholds {
        eyeRef.update(eyeOpenRatio)
        mouthRef.update(mouthOpenRatio)
        return thresholds()
    }

    /**
     * 复位重校：清空基准，进入重校窗口（由驾驶门开关信号触发）。
     * 重置后基准退回默认，随后新样本会重新建立基准。
     */
    fun reset() {
        eyeRef.reset()
        mouthRef.reset()
    }

    /** 当前换算阈值。 */
    fun thresholds(): CalibratedThresholds {
        return CalibratedThresholds(
            eyeCloseRatio = eyeRef.lowRatio(),
            eyeOpenRatio = eyeRef.highRatio(),
            mouthCloseRatio = mouthRef.lowRatio(),
            mouthOpenRatio = mouthRef.highRatio()
        )
    }

    // ============================================================
    // 内部：单轴基准（分位数 + EWMA 平滑 + 阈值换算）
    // ============================================================

    /**
     * 单轴（眼睛或嘴巴）的基准跟踪器。
     *
     * 维护样本滑动窗口，计算高位（睁眼/张嘴）与低位（闭眼/闭嘴）基准，
     * 用 EWMA 平滑，并按 [closeRatioFactor]/[openRatioFactor] 换算成滞回上下界阈值。
     */
    private inner class AxisRef(
        initHigh: Float,
        initLow: Float
    ) {
        private val values = RingBuffer(windowSize)
        private var high = initHigh
        private var low = initLow

        /** 是否已建立基准（样本足够）。 */
        private val inited: Boolean get() = values.size >= MIN_SAMPLES

        /** 当前滞回下界阈值（低位基准 + 因子位置）。样本不足时返回原始基准（默认值）。 */
        fun lowRatio(): Float =
            if (inited) low + (high - low) * closeRatioFactor else low

        /** 当前滞回上界阈值（高位基准 - 因子位置）。样本不足时返回原始基准（默认值）。 */
        fun highRatio(): Float =
            if (inited) low + (high - low) * openRatioFactor else high

        fun update(sample: Float) {
            values.add(sample.coerceIn(0f, 1f))
            if (values.size < MIN_SAMPLES) return  // 样本不足，用默认

            val sorted = values.sorted()
            val lo = quantile(sorted, closeQuantile)
            val hi = quantile(sorted, openQuantile)
            // 保证低基准 < 高基准，避免区间倒置
            val newLow = min(lo, hi * 0.9f)
            val newHigh = max(hi, newLow + 1e-3f)

            // EWMA 平滑：缓慢逼近分位数目标
            high = high * (1f - smoothAlpha) + newHigh * smoothAlpha
            low = low * (1f - smoothAlpha) + newLow * smoothAlpha
        }

        fun reset() {
            values.clear()
            high = CalibratedThresholds.DEFAULT.eyeOpenRatio
            low = CalibratedThresholds.DEFAULT.eyeCloseRatio
        }

        /** 计算排序数组的分位数（0~1）。 */
        private fun quantile(sorted: FloatArray, q: Float): Float {
            if (sorted.isEmpty()) return 0f
            val idx = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
            return sorted[idx]
        }
    }

    /**
     * 简单环形缓冲：维护固定窗口的样本，便于计算分位数。
     */
    private class RingBuffer(private val capacity: Int) {
        private val data = FloatArray(capacity)
        private var count = 0
        private var head = 0

        val size: Int get() = count

        fun add(value: Float) {
            data[head] = value
            head = (head + 1) % capacity
            if (count < capacity) count++
        }

        fun sorted(): FloatArray {
            val out = FloatArray(count)
            // 从最旧到最新复制（环形顺序）
            val start = (head - count + capacity) % capacity
            for (i in 0 until count) {
                out[i] = data[(start + i) % capacity]
            }
            out.sort()
            return out
        }

        fun clear() {
            count = 0
            head = 0
        }
    }
}
