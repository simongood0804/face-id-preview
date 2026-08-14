package com.skyworth.faceid.shmtest

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 算法进程整合后的处理结果（跨进程，经 [ShmQueue] 序列化传输）。
 *
 * 由 `:algo` 进程产生（算法推理 + 车速信号 + 分心判定的整合），
 * 主进程消费后用于绘制。为定长结构，便于写入共享内存队列。
 *
 * 字段：
 * - 帧信息：frameW/frameH；
 * - 人脸：是否存在、人脸框（原图坐标）、置信度；
 * - 分心：分心标志、当前分心档位（fast/slow）、触发阈值；
 * - 车速：当前车速（km/h，负=无数据）。
 */
data class AlgorithmResult(
    val frameW: Int,
    val frameH: Int,
    val hasFace: Boolean,
    val faceLeft: Float,
    val faceTop: Float,
    val faceRight: Float,
    val faceBottom: Float,
    val faceConfidence: Float,
    val distracted: Boolean,
    val distractionBand: String,
    val distractionThresholdMs: Long,
    val speedKmh: Float
) {
    /** 固定序列化长度（字节）。 */
    fun encodedSize(): Int = SERIALIZED_BYTES

    /** 序列化为定长字节。 */
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(SERIALIZED_BYTES).order(ByteOrder.nativeOrder())
        buf.putInt(frameW)
        buf.putInt(frameH)
        buf.put(if (hasFace) 1.toByte() else 0.toByte())
        buf.putFloat(faceLeft)
        buf.putFloat(faceTop)
        buf.putFloat(faceRight)
        buf.putFloat(faceBottom)
        buf.putFloat(faceConfidence)
        buf.put(if (distracted) 1.toByte() else 0.toByte())
        // 档位：fast=0, slow=1
        buf.put(if (distractionBand == "slow") 1.toByte() else 0.toByte())
        buf.putLong(distractionThresholdMs)
        buf.putFloat(speedKmh)
        return buf.array()
    }

    companion object {
        private const val SERIALIZED_BYTES = 4 + 4 + 1 + 4 * 5 + 1 + 1 + 8 + 4

        /** 从字节反序列化。 */
        fun decode(data: ByteArray): AlgorithmResult {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder())
            val frameW = buf.int
            val frameH = buf.int
            val hasFace = buf.get().toInt() != 0
            val faceLeft = buf.float
            val faceTop = buf.float
            val faceRight = buf.float
            val faceBottom = buf.float
            val faceConfidence = buf.float
            val distracted = buf.get().toInt() != 0
            val band = if (buf.get().toInt() != 0) "slow" else "fast"
            val threshold = buf.long
            val speed = buf.float
            return AlgorithmResult(
                frameW, frameH, hasFace, faceLeft, faceTop, faceRight, faceBottom,
                faceConfidence, distracted, band, threshold, speed
            )
        }

        /** 空结果（无人脸）。 */
        val EMPTY = AlgorithmResult(0, 0, false, 0f, 0f, 0f, 0f, 0f, false, "fast", 0L, -1f)
    }
}
