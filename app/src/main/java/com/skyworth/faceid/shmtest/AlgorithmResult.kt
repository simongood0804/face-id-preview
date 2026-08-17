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
 * - 头姿：pitch/yaw/roll（单位度）；
 * - 视线：gazeValid/gazeYaw/gazePitch；
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
    val headposePitch: Float,
    val headposeYaw: Float,
    val headposeRoll: Float,
    val gazeValid: Float,
    val gazeYaw: Float,
    val gazePitch: Float,
    val zoneId: Float,
    val distracted: Boolean,
    val distractionBand: String,
    val distractionThresholdMs: Long,
    val speedKmh: Float
) {
    /** 固定序列化长度（字节，不含版本头）。 */
    fun encodedSize(): Int = SERIALIZED_BYTES

    /** 序列化为定长字节（头部含 magic + 长度，供 decode 校验版本与完整性）。 */
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(TOTAL_BYTES).order(ByteOrder.nativeOrder())
        buf.putInt(MAGIC)                 // 版本/魔数
        buf.putInt(SERIALIZED_BYTES)      // 负载长度
        buf.putInt(frameW)
        buf.putInt(frameH)
        buf.put(if (hasFace) 1.toByte() else 0.toByte())
        buf.putFloat(faceLeft)
        buf.putFloat(faceTop)
        buf.putFloat(faceRight)
        buf.putFloat(faceBottom)
        buf.putFloat(faceConfidence)
        buf.putFloat(headposePitch)
        buf.putFloat(headposeYaw)
        buf.putFloat(headposeRoll)
        buf.putFloat(gazeValid)
        buf.putFloat(gazeYaw)
        buf.putFloat(gazePitch)
        buf.putFloat(zoneId)
        buf.put(if (distracted) 1.toByte() else 0.toByte())
        // 档位：fast=0, slow=1
        buf.put(if (distractionBand == "slow") 1.toByte() else 0.toByte())
        buf.putLong(distractionThresholdMs)
        buf.putFloat(speedKmh)
        return buf.array()
    }

    companion object {
        /** 序列化魔数（版本标识，变更格式时递增）。 */
        private const val MAGIC = 0x41524131 // "ARA1"

        /** 负载长度（不含 8 字节版本头）。 */
        private const val SERIALIZED_BYTES = 4 + 4 + 1 + 4 * 5 + 4 * 7 + 1 + 1 + 8 + 4

        /** 总长度（8 字节版本头 + 负载）。 */
        private const val TOTAL_BYTES = 8 + SERIALIZED_BYTES

        /**
         * 从字节反序列化。校验 magic 与长度，不匹配（版本不一致/损坏）返回 null。
         */
        fun decode(data: ByteArray): AlgorithmResult? {
            if (data.size < 8) return null
            val header = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder())
            if (header.int != MAGIC) return null
            if (header.int != SERIALIZED_BYTES) return null
            val buf = ByteBuffer.wrap(data, 8, data.size - 8).order(ByteOrder.nativeOrder())
            val frameW = buf.int
            val frameH = buf.int
            val hasFace = buf.get().toInt() != 0
            val faceLeft = buf.float
            val faceTop = buf.float
            val faceRight = buf.float
            val faceBottom = buf.float
            val faceConfidence = buf.float
            val headposePitch = buf.float
            val headposeYaw = buf.float
            val headposeRoll = buf.float
            val gazeValid = buf.float
            val gazeYaw = buf.float
            val gazePitch = buf.float
            val zoneId = buf.float
            val distracted = buf.get().toInt() != 0
            val band = if (buf.get().toInt() != 0) "slow" else "fast"
            val threshold = buf.long
            val speed = buf.float
            return AlgorithmResult(
                frameW, frameH, hasFace, faceLeft, faceTop, faceRight, faceBottom,
                faceConfidence, headposePitch, headposeYaw, headposeRoll,
                gazeValid, gazeYaw, gazePitch, zoneId, distracted, band, threshold, speed
            )
        }

        /** 空结果（无人脸）。 */
        val EMPTY = AlgorithmResult(0, 0, false, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, false, "fast", 0L, -1f)
    }
}
