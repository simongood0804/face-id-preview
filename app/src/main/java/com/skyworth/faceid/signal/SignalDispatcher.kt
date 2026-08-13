package com.skyworth.faceid.signal

import com.skyworth.faceid.algorithm.IFaceIDAlgorithm
import com.skyworth.faceid.bus.BusHub
import com.skyworth.faceid.bus.BusPublisher
import com.skyworth.faceid.bus.BusSubscriber
import com.skyworth.faceid.bus.ServiceRegistry

/**
 * 信号转发层核心：信号分发器。
 *
 * 作为消息总线的消费者，轮询 [ServiceRegistry.Topic.VEHICLE_SPEED]（车机车速）
 * 与 [ServiceRegistry.Topic.ALGO_RESULT]（算法结果），完成：
 * 1. 车速缓存更新（供分心分档使用）；
 * 2. 分心判定状态机（防抖 + 车速分档）；
 * 3. 将防抖后的 overlay 数据发布到 [ServiceRegistry.Topic.FRAME_OVERLAY]。
 *
 * 设计目标（参照 openpilot SubMaster）：
 * - **解耦**：只依赖消息总线与纯逻辑状态机，不直接持有算法/相机/UI 引用；
 * - **故障隔离**：某个 topic 断流只影响自身 alive，不拖垮其他信号；
 *   车速不可用 → 按最严格档处理，不崩溃。
 *
 * 使用方式：由各层独立线程周期性调用 [poll]，或在收到新消息时调用 [processMessage]。
 * 线程安全：非线程安全，应在单一线程内调用。
 */
class SignalDispatcher(
    private val hub: BusHub,
    private val publisher: BusPublisher,
    /** 算法结果 → 分心输入 的提取器（保持信号层与算法包解耦）。 */
    private val distractionExtractor: (IFaceIDAlgorithm.FaceIDResult) -> SignalTypes.AlgoDistractionInput =
        { r -> SignalTypes.AlgoDistractionInput(r.faceId.isNotEmpty(), r.gazeDistracted) },
    /** 分心状态机实例（可注入便于测试）。 */
    private val stateMachine: DistractionStateMachine = DistractionStateMachine()
) {
    private val TAG = "SignalDispatcher"

    /** 当前缓存的车速。 */
    @Volatile
    var currentSpeedKmh = -1f
        private set

    /** 订阅的 topic 集合。 */
    private val subscribedTopics = setOf(
        ServiceRegistry.Topic.VEHICLE_SPEED,
        ServiceRegistry.Topic.ALGO_RESULT
    )

    /** 底层订阅器（执行 per-topic 健康检查）。 */
    private val subscriber: BusSubscriber = BusSubscriber(hub, subscribedTopics)

    /** 最近一次分心输出。 */
    @Volatile
    var lastDistraction: SignalTypes.DistractionOutput = SignalTypes.DistractionOutput.IDLE
        private set

    /** 最近一次故障事件（无故障时为 null）。 */
    @Volatile
    var lastFault: SignalTypes.FaultEvent? = null
        private set

    /** 车速信号是否健康（在健康超时内收到过数据）。 */
    @Volatile
    var vehicleSpeedHealthy = false
        private set

    /** 算法结果是否健康。 */
    @Volatile
    var algoHealthy = false
        private set

    /**
     * 轮询所有订阅 topic，处理新消息。
     * 应周期性调用。
     */
    @JvmOverloads
    fun poll(nowNanos: Long = System.nanoTime()) {
        subscriber.update(nowNanos)
        // 健康检查：车速 topic 失活 → 上报 SIGNAL_ERROR（但算法处理不受影响）
        vehicleSpeedHealthy = subscriber.alive(ServiceRegistry.Topic.VEHICLE_SPEED)
        algoHealthy = subscriber.alive(ServiceRegistry.Topic.ALGO_RESULT)
        if (!vehicleSpeedHealthy) {
            reportFault(SignalTypes.FaultEvent.CODE_VEHICLE_SPEED_UNAVAILABLE,
                "vehicle_speed",
                "vehicle speed topic unavailable, using fast threshold")
        }
        // 处理车速
        subscriber.latest(ServiceRegistry.Topic.VEHICLE_SPEED)?.let { msg ->
            val speed = msg.payload as? SignalTypes.VehicleSpeed ?: return@let
            currentSpeedKmh = speed.speedKmh
        }
        // 处理算法结果
        subscriber.latest(ServiceRegistry.Topic.ALGO_RESULT)?.let { msg ->
            val result = msg.payload as? IFaceIDAlgorithm.FaceIDResult ?: return@let
            processAlgorithmResult(result)
        }
    }

    /** 上报故障：记录 + 发布到 SIGNAL_ERROR topic。 */
    private fun reportFault(code: String, source: String, message: String) {
        val fault = SignalTypes.FaultEvent(code, source, message)
        lastFault = fault
        publisher.publish(ServiceRegistry.Topic.SIGNAL_ERROR, fault)
    }

    /**
     * 直接处理一条算法结果消息（外部推送路径）。
     */
    fun processAlgorithmResult(result: IFaceIDAlgorithm.FaceIDResult) {
        val input = distractionExtractor(result)
        val distracted: Boolean
        if (input.hasFace) {
            distracted = stateMachine.update(input, currentSpeedKmh)
        } else {
            // 无人脸：重置分心状态
            stateMachine.reset()
            distracted = false
        }
        lastDistraction = SignalTypes.DistractionOutput(
            distracted = distracted,
            activeThresholdMs = stateMachine.currentTriggerMs(),
            speedBand = stateMachine.currentSpeedBand()
        )
        // 发布 overlay 数据供绘制层消费
        publisher.publish(ServiceRegistry.Topic.FRAME_OVERLAY, lastDistraction)
    }

    /**
     * 直接处理一条车速消息（外部推送路径）。
     */
    fun processVehicleSpeed(speed: SignalTypes.VehicleSpeed) {
        currentSpeedKmh = speed.speedKmh
        publisher.publish(ServiceRegistry.Topic.VEHICLE_SPEED, speed)
    }

    /** 释放订阅资源。 */
    fun close() {
        subscriber.close()
    }
}
