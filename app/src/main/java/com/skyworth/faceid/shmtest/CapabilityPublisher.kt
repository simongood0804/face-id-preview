package com.skyworth.faceid.shmtest

import android.util.Log
import com.skyworth.faceid.bus.ShmQueue

/**
 * 能力模块发布器（FACEP-011 阶段 B）。
 *
 * 把 [AlgoEngineService.onAlgorithmResult] 整合出的整体 [AlgorithmResult]，
 * **拆分为各 [CapabilityModule] 独立 payload**，并按订阅表**只发布有订阅者的模块**：
 * - 有消费者订阅 FACE_DETECT → 才发布人脸框数据；
 * - 无消费者订阅 HEADPOSE/GAZE → 不发布，节省共享内存带宽。
 *
 * 「单模块输出依赖」：模块间依赖（如 DISTRACTION 依赖 FACE_DETECT + VEHICLE_SPEED）
 * 在算法进程内已就绪，此处仅按订阅维度决定是否发布，依赖对消费者透明。
 *
 * @param queue 共享内存发布队列（多读者-单写者）。
 * @param subscribedTopicsProvider 提供「当前至少有一个消费者订阅的 topic 集合」，
 *        由 [AlgoEngineService] 传入（查其 mSubscriptions）。
 */
class CapabilityPublisher(
    private val queue: ShmQueue,
    private val subscribedTopicsProvider: () -> Set<Int>
) {
    private val TAG = "CapabilityPublisher"

    /**
     * 按模块发布 [AlgorithmResult]。仅发布有订阅者的模块。
     * 在算法线程（FrameProcessor 回调）调用。
     */
    fun publishModules(result: AlgorithmResult) {
        val subscribed = subscribedTopicsProvider()
        if (subscribed.isEmpty()) {
            Log.w(TAG, "publishModules: no subscriber, skip (hasFace=${result.hasFace})")
            return  // 无任何消费者订阅，整帧跳过发布
        }
        Log.d(TAG, "publishModules: publish ${subscribed.size} topics")

        try {
            if (CapabilityModule.FACE_DETECT.topic in subscribed) {
                queue.publish(
                    CapabilityModule.FACE_DETECT.topic,
                    result.toFaceBoxData().encode()
                )
            }
            if (CapabilityModule.HEADPOSE.topic in subscribed) {
                queue.publish(
                    CapabilityModule.HEADPOSE.topic,
                    HeadposeData(result.headposePitch, result.headposeYaw, result.headposeRoll).encode()
                )
            }
            if (CapabilityModule.GAZE.topic in subscribed) {
                queue.publish(
                    CapabilityModule.GAZE.topic,
                    GazeData(result.gazeValid, result.gazeYaw, result.gazePitch, result.gazeCalibrated).encode()
                )
            }
            if (CapabilityModule.DISTRACTION.topic in subscribed) {
                queue.publish(
                    CapabilityModule.DISTRACTION.topic,
                    DistractData(
                        result.distracted, result.distractionBand, result.distractionThresholdMs,
                        result.distractionScore, result.distractionHpScore, result.distractionGazeScore
                    ).encode()
                )
            }
            if (CapabilityModule.VEHICLE_SPEED.topic in subscribed) {
                queue.publish(
                    CapabilityModule.VEHICLE_SPEED.topic,
                    SpeedData(result.speedKmh).encode()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "publishModules failed", e)
        }
    }

    /** 把整体 [AlgorithmResult] 提取为 FACE_DETECT 模块数据。 */
    private fun AlgorithmResult.toFaceBoxData(): FaceBoxData =
        FaceBoxData(
            frameW = frameW,
            frameH = frameH,
            hasFace = hasFace,
            faceLeft = faceLeft,
            faceTop = faceTop,
            faceRight = faceRight,
            faceBottom = faceBottom,
            faceConfidence = faceConfidence,
            zoneId = zoneId,
            keypoints = keypoints
        )
}
