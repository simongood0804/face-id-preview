package com.skyworth.faceid.signal

/**
 * 信号转发层的数据值对象。
 *
 * 这些对象作为消息总线（[com.skyworth.faceid.bus.ServiceRegistry.Topic]）的载荷，
 * 在各层之间传递。均为不可变值对象，避免跨层共享可变状态。
 */
object SignalTypes {

    /**
     * 车机车速信号。
     *
     * @property speedKmh 车速（km/h）；负数表示无有效车速数据（此时按最严格档处理）
     * @property valid 车速数据是否有效（false 表示连接失败/断开/属性错误）
     */
    data class VehicleSpeed(
        val speedKmh: Float,
        val valid: Boolean
    ) {
        companion object {
            /** 无车速数据时的默认值（沿用现有 -1 语义）。 */
            val INVALID = VehicleSpeed(speedKmh = -1f, valid = false)
        }
    }

    /**
     * 驾驶门开关信号（作为眼/嘴阈值校准的复位触发，见 FACEP-010 §3.7.6）。
     *
     * 门信号**不参与眼嘴判定本身**，仅用于指示"可能换驾驶员"：门开时校准器
     * 清空当前基准，进入重校窗口，重新采集新驾驶员的睁/闭眼、张/闭嘴基准。
     *
     * @property isOpen 驾驶门是否打开（true=开，提示可能换人）
     * @property valid 门信号是否有效（false 表示连接失败/属性错误）
     */
    data class DoorState(
        val isOpen: Boolean,
        val valid: Boolean
    ) {
        companion object {
            /** 无门信号数据时的默认值。 */
            val INVALID = DoorState(isOpen = false, valid = false)
        }
    }

    /**
     * 分心判定所需的算法输入摘要（仅提取状态机需要的最小字段）。
     *
     * 通过 [fromAlgorithmResult] 从算法结果构建，避免信号层直接依赖算法包内部结构。
     *
     * @property hasFace 是否检测到人脸
     * @property gazeDistracted 单帧分心标志（>0 表示分心）
     */
    data class AlgoDistractionInput(
        val hasFace: Boolean,
        val gazeDistracted: Float
    ) {
        companion object {
            /** 无算法结果（无人脸）时的输入。 */
            val NO_FACE = AlgoDistractionInput(hasFace = false, gazeDistracted = 0f)
        }
    }

    /**
     * 分心判定后的输出（防抖后的最终状态）。
     *
     * @property distracted 防抖后是否判定为分心
     * @property activeThresholdMs 当前生效的触发阈值（ms），反映车速分档
     * @property speedBand 车速分档（"fast" = ≥50km/h 或无数据；"slow" = <50km/h）
     */
    data class DistractionOutput(
        val distracted: Boolean,
        val activeThresholdMs: Long,
        val speedBand: String
    ) {
        companion object {
            val IDLE = DistractionOutput(false, 0L, "fast")
        }
    }

    /**
     * 信号层故障事件。
     *
     * @property code 故障码
     * @property source 故障来源（如 "vehicle_speed"）
     * @property message 描述
     */
    data class FaultEvent(
        val code: String,
        val source: String,
        val message: String
    ) {
        companion object {
            const val CODE_VEHICLE_SPEED_UNAVAILABLE = "VEHICLE_SPEED_UNAVAILABLE"
        }
    }
}
