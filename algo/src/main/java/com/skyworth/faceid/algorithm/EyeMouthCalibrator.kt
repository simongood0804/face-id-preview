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
 * 差异会导致 aperture/MAR 绝对范围漂移。本校准器维护**每个驾驶员**的"睁/闭眼、张/闭嘴"
 * 基准，据此动态换算阈值，供单帧判定眼/嘴状态（防抖已去除），避免"此人总是睁眼/闭眼"
 * 误判。
 *
 * 输入量纲：本校准器跟踪**未归一化的原始几何量**（眼睛 aperture=睑距/双眼外眼角距离、
 * 嘴巴 MAR=嘴角距），而非 0~1 归一化开合度。理由：静态归一化基准（0.10/0.02）与实测
 * 范围不匹配时，开合度会饱和失真（完全睁眼只映射到 0.6~0.7），叠加动态阈值后出现
 * "睁大眼睛仍判闭眼"。改为在原始量纲上做分位数跟踪，用**个人实测的高/低基准**做归一化
 * 端点（[normalizeEye]/[normalizeMouth]），完全睁眼恒映射到 ≈1.0、完全闭眼 ≈0.0，
 * 消除基准错配与饱和。
 *
 * 两条校准路径：
 * - **运行中自适应（软漂移补偿）**：对 aperture/MAR 做滑动分位数跟踪（睁眼/张嘴取高位、
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
    /** 滞回下界比例（0~1）：**眼睛**低位基准到高位基准区间的下界位置（嘴巴阈值固定，见 [MOUTH_CLOSE_RATIO]）。 */
    private val closeRatioFactor: Float = DEFAULT_CLOSE_RATIO_FACTOR,
    /** 滞回上界比例（0~1）：**眼睛**低位基准到高位基准区间的上界位置（嘴巴阈值固定，见 [MOUTH_OPEN_RATIO]）。 */
    private val openRatioFactor: Float = DEFAULT_OPEN_RATIO_FACTOR,
    /** 眼睛归一化端点默认值：完全睁眼 aperture（睑距/脸宽），与 [EyeMouthStateEstimator] 默认一致。 */
    private val defaultEyeOpenAperture: Float = DEFAULT_EYE_OPEN_APERTURE,
    /** 眼睛归一化端点默认值：闭眼残差 aperture，与 [EyeMouthStateEstimator] 默认一致。 */
    private val defaultEyeCloseAperture: Float = DEFAULT_EYE_CLOSE_APERTURE,
    /** 嘴巴归一化端点默认值：完全张嘴 MAR，与 [EyeMouthStateEstimator] 默认一致。 */
    private val defaultMouthOpenMar: Float = DEFAULT_MOUTH_OPEN_MAR,
    /** 嘴巴归一化端点默认值：闭嘴残差 MAR，与 [EyeMouthStateEstimator] 默认一致。 */
    private val defaultMouthCloseMar: Float = DEFAULT_MOUTH_CLOSE_MAR
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
        /** 默认滞回下界比例（数值越小，闭眼候选阈值越贴近低位基准、判定越严格）。 */
        const val DEFAULT_CLOSE_RATIO_FACTOR = 0.10f
        /** 默认滞回上界比例。 */
        const val DEFAULT_OPEN_RATIO_FACTOR = 0.70f
        /** 建立基准所需的最少样本数（少于则用默认阈值）。 */
        const val MIN_SAMPLES = 10
        /** 单态判定阈值：窗口内（高位-低位）差小于默认高位的该比例时视为单态分布（持续闭眼/闭嘴）。 */
        const val SINGLE_STATE_RANGE_FACTOR = 0.2f
        /** 嘴巴闭嘴候选阈值（**固定**，不随动态校准漂移）。 */
        const val MOUTH_CLOSE_RATIO = 0.35f
        /** 嘴巴张嘴候选阈值（**固定**，不随动态校准漂移）。 */
        const val MOUTH_OPEN_RATIO = 0.60f

        /** 默认眼睛归一化端点：完全睁眼 aperture（睑距/脸宽），与 [EyeMouthStateEstimator.referenceEyeAperture] 默认一致。 */
        const val DEFAULT_EYE_OPEN_APERTURE = 0.10f
        /** 默认眼睛归一化端点：闭眼残差 aperture，与 [EyeMouthStateEstimator.closedEyeAperture] 默认一致。 */
        const val DEFAULT_EYE_CLOSE_APERTURE = 0.02f
        /** 默认嘴巴归一化端点：完全张嘴 MAR，与 [EyeMouthStateEstimator.referenceMouthMar] 默认一致。 */
        const val DEFAULT_MOUTH_OPEN_MAR = 0.62f
        /** 默认嘴巴归一化端点：闭嘴残差 MAR，与 [EyeMouthStateEstimator.closedMouthMar] 默认一致。 */
        const val DEFAULT_MOUTH_CLOSE_MAR = 0.35f
    }

    /** 动态换算后的阈值（供单帧判定眼/嘴状态，防抖已去除）。 */
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
            /** 静态默认阈值（v3.5 起校准器恒输出"眼睛因子 + 嘴巴固定值"，此处作未接入校准时的兜底参考）。 */
            @JvmField
            val DEFAULT = CalibratedThresholds(
                eyeCloseRatio = 0.10f,
                eyeOpenRatio = 0.30f,
                mouthCloseRatio = MOUTH_CLOSE_RATIO,
                mouthOpenRatio = MOUTH_OPEN_RATIO
            )
        }
    }

    /** 眼睛基准（aperture：值大=睁眼高位，小=闭眼低位）。 */
    private val eyeRef = AxisRef(defaultEyeOpenAperture, defaultEyeCloseAperture)

    /** 嘴巴基准（MAR：值大=张嘴高位，小=闭嘴低位）。 */
    private val mouthRef = AxisRef(defaultMouthOpenMar, defaultMouthCloseMar)

    /**
     * 更新一帧（眼睛/嘴巴都有效时），做滑动分位数跟踪与基准平滑，返回当前动态换算的阈值。
     *
     * @param eyeAperture 本帧眼睛原始开合量（aperture=睑距/双眼外眼角距离，未归一化，
     *                    **仅限主路径**；外眼角缺失回退的 EAR 量纲不同，需用 [updateEye] 前先排除）。
     * @param mouthMar 本帧嘴巴原始开合量（MAR，未归一化；区域缺失时估计器输出 0，会被忽略）。
     * @return 换算后的 [CalibratedThresholds]（ratio 量纲，供单帧判定眼/嘴状态）。
     */
    fun update(eyeAperture: Float, mouthMar: Float): CalibratedThresholds {
        updateEye(eyeAperture)
        updateMouth(mouthMar)
        return thresholds()
    }

    /**
     * 仅更新眼睛轴基准（v3.5 高危修复：调用方需自行保证 [aperture] 为**主路径 aperture**
     * （睑距/双眼外眼角距离），外眼角缺失回退的 EAR 量纲不同，混入窗口会拉高高位基准，
     * 导致主路径完全睁眼被归一化压低而误判闭眼）。
     */
    fun updateEye(aperture: Float) {
        eyeRef.update(aperture)
    }

    /**
     * 仅更新嘴巴轴基准（v3.5 高危修复：调用方需自行保证 [mar] 真实有效；
     * 区域缺失时估计器输出 0 为缺失哨兵，本方法内部会忽略）。
     */
    fun updateMouth(mar: Float) {
        mouthRef.update(mar)
    }

    /**
     * 用当前眼睛基准把原始 aperture 归一化为 0~1 开合度（完全睁眼 ≈1.0、完全闭眼 ≈0.0）。
     *
     * 归一化端点为该驾驶员实测的"睁眼高位/闭眼低位"基准（EWMA 平滑，随运行缓慢漂移），
     * 取代静态 0.10/0.02 端点，消除基准错配导致的饱和失真。基准未建立（样本不足）时
     * 退回默认端点，与 [EyeMouthStateEstimator] 静态归一化结果一致。
     */
    fun normalizeEye(aperture: Float): Float = eyeRef.normalize(aperture)

    /**
     * 用当前嘴巴基准把原始 MAR 归一化为 0~1 开合度（完全张嘴 ≈1.0、完全闭嘴 ≈0.0）。
     */
    fun normalizeMouth(mar: Float): Float = mouthRef.normalize(mar)

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
        // 基准建立后，归一化已融合个人实测范围（见 normalizeEye/normalizeMouth），
        // 阈值恒为"因子位置"（眼睛）或固定值（嘴巴），与归一化输入同量纲，基准建立与否不跳变：
        // - 眼睛：滞回阈值 = 个人开合区间的因子位置（下界 closeRatioFactor / 上界 openRatioFactor）。
        //   未校准阶段输入走静态端点归一化、输出同样是因子位置，避免旧实现"样本不足用静态默认
        //   （上界 0.30）、基准建立瞬间跳变到 0.70"造成状态机阈值突变（v3.5 中危修复）；
        // - 嘴巴：状态机设计为固定阈值（不随动态校准漂移），此处输出与状态机一致的固定值。
        return CalibratedThresholds(
            eyeCloseRatio = closeRatioFactor,
            eyeOpenRatio = openRatioFactor,
            mouthCloseRatio = MOUTH_CLOSE_RATIO,
            mouthOpenRatio = MOUTH_OPEN_RATIO
        )
    }

    // ============================================================
    // 内部：单轴基准（分位数 + EWMA 平滑 + 归一化/阈值换算）
    // ============================================================

    /**
     * 单轴（眼睛或嘴巴）的基准跟踪器。
     *
     * 维护样本滑动窗口，计算高位（睁眼/张嘴）与低位（闭眼/闭嘴）基准，
     * 用 EWMA 平滑。基准同时承担两个角色：
     * - **归一化端点**：[normalize] 把原始量纲值映射到 0~1（个人化，消除静态基准错配）；
     * - **阈值换算**：滞回上下界 = `low + (high - low) * factor`，经个人归一化后
     *   等价于因子位置（[EyeMouthCalibrator.thresholds] 直接输出因子）。
     */
    private inner class AxisRef(
        private val initHigh: Float,
        private val initLow: Float
    ) {
        private val values = RingBuffer(windowSize)
        private var high = initHigh
        private var low = initLow

        /** 是否已建立基准（样本足够）。 */
        val inited: Boolean get() = values.size >= MIN_SAMPLES

        /**
         * 用当前低/高基准把原始量纲值归一化到 0~1（越界截断）。
         * 区间塌缩（low≈high，如持续闭眼）时保护返回 1f。
         */
        fun normalize(value: Float): Float {
            val range = high - low
            if (range <= 0f) return 1f
            return ((value - low) / range).coerceIn(0f, 1f)
        }

        fun update(sample: Float) {
            // 数据真实性防护：缺失哨兵（≤0，估计器对区域缺失输出 0）与 NaN（几何异常）
            // 不进窗口。否则 0 会把低位基准拉向 0，使真实闭眼/闭嘴残差被归一化到中高位，
            // 闭眼/闭嘴候选永不成立（疲劳告警失效、张嘴无法解除）。正常量纲恒 >0：
            // aperture 闭眼残差 ≈0.02+、MAR 闭嘴残差 ≈0.35+。
            if (sample.isNaN() || sample <= 0f) return
            values.add(sample)
            if (values.size < MIN_SAMPLES) return  // 样本不足，用默认

            val sorted = values.sorted()
            val lo = quantile(sorted, closeQuantile)
            val hi = quantile(sorted, openQuantile)
            // 保证低基准 < 高基准，避免区间倒置
            val newLow = min(lo, hi * 0.9f)
            val newHigh = max(hi, newLow + 1e-3f)

            // 单态保护：窗口内高低位样本未分离（newHigh-newLow 远小于默认高位，如持续闭眼/闭嘴）
            // 时，若按正常 EWMA 让高/低基准同时收敛，区间会塌缩、把开合度"正常化"（持续闭眼
            // 反而映射到高位，疲劳告警失效）。改为只让"主导侧"基准跟随：
            // - 低位单态（lo 接近残差区，如持续闭眼/闭嘴）：低位基准跟随残差，高位保留"睁开/张开"记忆；
            // - 高位单态（lo 接近开度区，如持续睁眼/张嘴）：高位基准跟随开度，低位保留残差记忆。
            val midAnchor = (initLow + initHigh) / 2f
            if (newHigh - newLow < initHigh * SINGLE_STATE_RANGE_FACTOR) {
                if (lo < midAnchor) {
                    low = low * (1f - smoothAlpha) + lo * smoothAlpha
                } else {
                    high = high * (1f - smoothAlpha) + hi * smoothAlpha
                }
            } else {
                // 正常双态分布：EWMA 平滑，缓慢逼近分位数目标
                high = high * (1f - smoothAlpha) + newHigh * smoothAlpha
                low = low * (1f - smoothAlpha) + newLow * smoothAlpha
            }
        }

        fun reset() {
            values.clear()
            high = initHigh
            low = initLow
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
