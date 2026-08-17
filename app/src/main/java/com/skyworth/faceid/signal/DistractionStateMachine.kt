package com.skyworth.faceid.signal

/**
 * 分心判定状态机（从 [PreviewActivity.updateDistraction] 提取，逻辑保持一致）。
 *
 * 算法输出的 gazeDistracted 为单帧结果，存在误检抖动。这里按"持续时间"累计判定
 * （而非帧数，因单槽替换+跳帧导致帧率不稳定）：
 *  - 连续分心达到当前车速对应的触发阈值 → 触发分心；
 *  - 已触发后，连续非分心达到解除阈值 → 解除分心。
 *
 * 触发阈值按车速分档（GSR ADDW (EU) 2023/2590）：
 *  - 无车速数据 或 ≥50km/h → 快速档（1.5s）
 *  - <50km/h → 慢速档（3.0s）
 *
 * 本类为纯逻辑，不依赖 Android 系统库；单调时钟通过 [clockMs] 注入便于测试。
 *
 * 线程安全：非线程安全，需在单一线程（如信号分发线程）内调用。
 */
class DistractionStateMachine(
    /** 单调时钟（ms），默认 elapsedRealtime；可注入便于测试。 */
    private val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {

    companion object {
        /** 分心触发-快速档（≥50km/h 或无车速数据）：1.5s。 */
        const val TRIGGER_MS_FAST = 1500L

        /** 分心触发-慢速档（<50km/h）：3.0s。 */
        const val TRIGGER_MS_SLOW = 3000L

        /** 分心解除阈值：0.5s。 */
        const val CLEAR_MS = 500L

        /** 分档车速阈值（km/h）。 */
        const val SPEED_FAST_THRESHOLD_KMH = 50f
    }

    /** 分心是否已确认触发。 */
    private var distractActive = false

    /** 是否正在累计计时（避免以时间戳为 0 作为哨兵带来的边界问题）。 */
    private var timingActive = false

    /** 最近一次状态累积起始时间戳。 */
    private var accumStart = 0L

    /** 当前生效的触发阈值（ms）。 */
    private var triggerMs = TRIGGER_MS_FAST

    /** 最近一次车速分档。 */
    private var speedBand = "fast"

    /**
     * 更新分心状态。
     *
     * @param hasFace 是否检测到人脸
     * @param gazeDistracted 单帧分心标志（>0 表示分心）
     * @param vehicleSpeedKmh 当前车速（km/h）；负数表示无数据
     * @return 防抖后是否判定为分心
     */
    fun update(
        hasFace: Boolean,
        gazeDistracted: Float,
        vehicleSpeedKmh: Float
    ): Boolean {
        val now = clockMs()
        val distracted = gazeDistracted > 0f

        // 无车速数据(<0)或高速(≥50)用快速档，低速用慢速档
        triggerMs = if (vehicleSpeedKmh >= 0f && vehicleSpeedKmh < SPEED_FAST_THRESHOLD_KMH) {
            speedBand = "slow"
            TRIGGER_MS_SLOW
        } else {
            speedBand = "fast"
            TRIGGER_MS_FAST
        }

        if (distractActive) {
            // 已触发：连续非分心达到解除阈值才解除
            if (!distracted) {
                if (!timingActive) {
                    timingActive = true
                    accumStart = now
                } else if (now - accumStart >= CLEAR_MS) {
                    distractActive = false
                    resetTiming()
                }
            } else {
                resetTiming()  // 仍分心，重置解除计时
            }
        } else {
            // 未触发：连续分心达到触发阈值才触发
            if (distracted) {
                if (!timingActive) {
                    timingActive = true
                    accumStart = now
                } else if (now - accumStart >= triggerMs) {
                    distractActive = true
                    resetTiming()
                }
            } else {
                resetTiming()  // 非分心，重置触发计时
            }
        }
        return distractActive
    }

    /**
     * 重置分心状态（无人脸时调用）。
     */
    fun reset() {
        distractActive = false
        resetTiming()
    }

    /** 清除计时状态。 */
    private fun resetTiming() {
        timingActive = false
        accumStart = 0L
    }

    /** 当前是否判定为分心。 */
    fun isDistracted(): Boolean = distractActive

    /** 当前生效的触发阈值（ms）。 */
    fun currentTriggerMs(): Long = triggerMs

    /** 当前车速分档（"fast"/"slow"）。 */
    fun currentSpeedBand(): String = speedBand
}
