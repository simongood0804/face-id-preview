package com.skyworth.faceid.shmtest

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * FACEP-011 阶段 C：`FaceCapabilityClient` 接入示例模块。
 *
 * 演示一个**外部 App 模块**如何通过接口文档（docs/FaceCapability_接入说明.md）
 * 注册 + 初始化 + 订阅 + 取数，即「别的 App 模块依赖即可使用」。
 *
 * 实际业务模块可参考此生命周期：
 * - [start]：连接 → 注册 → 初始化 → 订阅所需模块，并启动轮询；
 * - [onPoll]：按需取数（示例取人脸框 + 车速 + 分心）；
 * - [stop]：停止轮询 → 注销消费者 → 断开服务。
 */
class FaceCapabilityClientSample(private val context: Context) {

    private val TAG = "FaceCapabilitySample"

    private val client = FaceCapabilityClient(context)

    /** 取数轮询线程池（示例用单线程定时轮询）。 */
    private var pollExecutor: ScheduledExecutorService? = null

    /** 轮询周期（ms）。 */
    private var pollIntervalMs = 100L

    /** 是否已启动。 */
    @Volatile private var running = false

    /** 启动：连接 + 注册 + 初始化 + 订阅。 */
    fun start(pollIntervalMs: Long = 100L): Boolean {
        this.pollIntervalMs = pollIntervalMs

        // 1. 连接算法能力服务
        if (!client.connect()) {
            Log.e(TAG, "start: connect failed")
            return false
        }
        // 2. 注册 + 初始化算法
        val code = client.init()
        if (code != 0) {
            Log.e(TAG, "start: init failed code=$code")
            client.disconnect()
            return false
        }
        // 3. 订阅所需能力模块（示例：人脸检测 + 分心 + 车速）
        client.subscribe(setOf(
            CapabilityModule.FACE_DETECT,
            CapabilityModule.DISTRACTION,
            CapabilityModule.VEHICLE_SPEED
        ))

        running = true
        pollExecutor = Executors.newSingleThreadScheduledExecutor().apply {
            scheduleWithFixedDelay({ onPoll() }, 0, this@FaceCapabilityClientSample.pollIntervalMs, TimeUnit.MILLISECONDS)
        }
        Log.i(TAG, "start: connected + subscribed")
        return true
    }

    /**
     * 取数回调（示例）：按需获取已订阅模块的最新数据。
     * 生产模块可在此把数据派发到自身业务逻辑。
     */
    private fun onPoll() {
        if (!running) return
        try {
            // 只取订阅的模块；未订阅模块返回 null
            val face = client.obtainFaceBox()
            val distract = client.obtainDistract()
            val speed = client.obtainSpeed()

            // 示例：打印关键信息（生产模块可替换为业务处理）
            val faceDesc = face?.let {
                if (it.hasFace) "face@(${it.faceLeft.toInt()},${it.faceTop.toInt()},${it.faceRight.toInt()},${it.faceBottom.toInt()})" else "no-face"
            } ?: "n/a"
            val speedDesc = speed?.let { if (it.speedKmh >= 0f) "${"%.1f".format(it.speedKmh)}km/h" else "n/a" } ?: "n/a"
            val distDesc = distract?.let { if (it.distracted) "DISTRACTED(${it.band},${it.thresholdMs}ms)" else "ok" } ?: "n/a"
            Log.d(TAG, "poll: $faceDesc | speed=$speedDesc | $distDesc")
        } catch (e: Exception) {
            Log.w(TAG, "onPoll error", e)
        }
    }

    /** 停止：停止轮询 → 注销消费者 → 断开服务。 */
    fun stop() {
        running = false
        pollExecutor?.shutdownNow()
        pollExecutor = null
        client.disconnect()
        Log.i(TAG, "stop: disconnected")
    }
}
