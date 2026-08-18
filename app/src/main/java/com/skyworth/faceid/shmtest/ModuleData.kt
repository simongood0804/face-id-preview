package com.skyworth.faceid.shmtest

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 各能力模块的独立 payload（FACEP-011 阶段 B）。
 *
 * 把 [AlgorithmResult] 拆分为按 [CapabilityModule] topic 独立发布的模块数据，
 * 每个模块一个定长序列化结构（沿用 AlgorithmResult 的 ByteBuffer + MAGIC 风格）。
 * 消费者按订阅的 topic 只反序列化所需模块，实现「按需订阅/发布」。
 */

/** 帧元信息（并入 [FaceBoxData]，不单独发布）。 */

/**
 * FACE_DETECT 模块：人脸检测结果（含帧尺寸 + 5 关键点）。
 */
data class FaceBoxData(
    val frameW: Int,
    val frameH: Int,
    val hasFace: Boolean,
    val faceLeft: Float,
    val faceTop: Float,
    val faceRight: Float,
    val faceBottom: Float,
    val faceConfidence: Float,
    val zoneId: Float,
    /**
     * 5 个面部关键点（左眼、右眼、鼻尖、左嘴角、右嘴角），扁平为 10 个 float
     * [x0,y0,x1,y1,...]；null 表示无。用 FloatArray 便于序列化与 JVM 单测。
     */
    val keypoints: FloatArray? = null
) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(TOTAL_BYTES).order(ByteOrder.nativeOrder())
        buf.putInt(MAGIC); buf.putInt(SERIALIZED_BYTES)
        buf.putInt(frameW); buf.putInt(frameH)
        buf.put(if (hasFace) 1.toByte() else 0.toByte())
        buf.putFloat(faceLeft); buf.putFloat(faceTop)
        buf.putFloat(faceRight); buf.putFloat(faceBottom)
        buf.putFloat(faceConfidence); buf.putFloat(zoneId)
        // 5 关键点（10 个 float），null/不足填 0
        for (i in 0 until 10) {
            buf.putFloat(keypoints?.getOrNull(i) ?: 0f)
        }
        return buf.array()
    }

    companion object {
        private const val MAGIC = 0x46423131 // "FB11"
        /** 负载长度：帧尺寸/人脸框/置信度/zone + 5 关键点(10 float)。 */
        private const val SERIALIZED_BYTES = 4 + 4 + 1 + 4 * 6 + 5 * 2 * 4
        private const val TOTAL_BYTES = 8 + SERIALIZED_BYTES

        fun decode(data: ByteArray): FaceBoxData? {
            if (data.size < 8) return null
            val header = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder())
            if (header.int != MAGIC) return null
            if (header.int != SERIALIZED_BYTES) return null
            val buf = ByteBuffer.wrap(data, 8, data.size - 8).order(ByteOrder.nativeOrder())
            val frameW = buf.int; val frameH = buf.int
            val hasFace = buf.get().toInt() != 0
            val l = buf.float; val t = buf.float; val r = buf.float; val b = buf.float
            val conf = buf.float; val zone = buf.float
            // 5 关键点（10 个 float）
            val kp = FloatArray(10)
            var hasAny = false
            for (i in 0 until 10) {
                val v = buf.float
                if (v != 0f) hasAny = true
                kp[i] = v
            }
            return FaceBoxData(
                frameW, frameH, hasFace, l, t, r, b, conf, zone,
                keypoints = if (hasAny) kp else null
            )
        }
    }
}

/**
 * HEADPOSE 模块：头姿。
 */
data class HeadposeData(
    val pitch: Float,
    val yaw: Float,
    val roll: Float
) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(TOTAL_BYTES).order(ByteOrder.nativeOrder())
        buf.putInt(MAGIC); buf.putInt(SERIALIZED_BYTES)
        buf.putFloat(pitch); buf.putFloat(yaw); buf.putFloat(roll)
        return buf.array()
    }

    companion object {
        private const val MAGIC = 0x48423131 // "HB11"
        private const val SERIALIZED_BYTES = 4 * 3
        private const val TOTAL_BYTES = 8 + SERIALIZED_BYTES

        fun decode(data: ByteArray): HeadposeData? {
            if (data.size < 8) return null
            val header = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder())
            if (header.int != MAGIC) return null
            if (header.int != SERIALIZED_BYTES) return null
            val buf = ByteBuffer.wrap(data, 8, data.size - 8).order(ByteOrder.nativeOrder())
            return HeadposeData(buf.float, buf.float, buf.float)
        }
    }
}

/**
 * GAZE 模块：视线。
 */
data class GazeData(
    val valid: Float,
    val yaw: Float,
    val pitch: Float,
    /** 视线是否已标定（1=已标定）。 */
    val calibrated: Float = 0f
) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(TOTAL_BYTES).order(ByteOrder.nativeOrder())
        buf.putInt(MAGIC); buf.putInt(SERIALIZED_BYTES)
        buf.putFloat(valid); buf.putFloat(yaw); buf.putFloat(pitch); buf.putFloat(calibrated)
        return buf.array()
    }

    companion object {
        private const val MAGIC = 0x475A3131 // "GZ11"
        private const val SERIALIZED_BYTES = 4 * 4
        private const val TOTAL_BYTES = 8 + SERIALIZED_BYTES

        fun decode(data: ByteArray): GazeData? {
            if (data.size < 8) return null
            val header = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder())
            if (header.int != MAGIC) return null
            if (header.int != SERIALIZED_BYTES) return null
            val buf = ByteBuffer.wrap(data, 8, data.size - 8).order(ByteOrder.nativeOrder())
            return GazeData(buf.float, buf.float, buf.float, buf.float)
        }
    }
}

/**
 * DISTRACTION 模块：分心判定结果（含分心分数）。
 */
data class DistractData(
    val distracted: Boolean,
    val band: String,
    val thresholdMs: Long,
    /** 分心综合分数（0.0~1.0）。 */
    val score: Float = 0f,
    /** 分心-头部姿态分数。 */
    val hpScore: Float = 0f,
    /** 分心-视线分数。 */
    val gazeScore: Float = 0f
) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(TOTAL_BYTES).order(ByteOrder.nativeOrder())
        buf.putInt(MAGIC); buf.putInt(SERIALIZED_BYTES)
        buf.put(if (distracted) 1.toByte() else 0.toByte())
        buf.put(if (band == "slow") 1.toByte() else 0.toByte())
        buf.putLong(thresholdMs)
        buf.putFloat(score); buf.putFloat(hpScore); buf.putFloat(gazeScore)
        return buf.array()
    }

    companion object {
        private const val MAGIC = 0x44523131 // "DR11"
        private const val SERIALIZED_BYTES = 1 + 1 + 8 + 4 * 3
        private const val TOTAL_BYTES = 8 + SERIALIZED_BYTES

        fun decode(data: ByteArray): DistractData? {
            if (data.size < 8) return null
            val header = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder())
            if (header.int != MAGIC) return null
            if (header.int != SERIALIZED_BYTES) return null
            val buf = ByteBuffer.wrap(data, 8, data.size - 8).order(ByteOrder.nativeOrder())
            val distracted = buf.get().toInt() != 0
            val band = if (buf.get().toInt() != 0) "slow" else "fast"
            val threshold = buf.long
            val score = buf.float; val hp = buf.float; val gs = buf.float
            return DistractData(distracted, band, threshold, score, hp, gs)
        }
    }
}

/**
 * VEHICLE_SPEED 模块：车速。
 */
data class SpeedData(
    val speedKmh: Float
) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(TOTAL_BYTES).order(ByteOrder.nativeOrder())
        buf.putInt(MAGIC); buf.putInt(SERIALIZED_BYTES)
        buf.putFloat(speedKmh)
        return buf.array()
    }

    companion object {
        private const val MAGIC = 0x53503131 // "SP11"
        private const val SERIALIZED_BYTES = 4
        private const val TOTAL_BYTES = 8 + SERIALIZED_BYTES

        fun decode(data: ByteArray): SpeedData? {
            if (data.size < 8) return null
            val header = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder())
            if (header.int != MAGIC) return null
            if (header.int != SERIALIZED_BYTES) return null
            val buf = ByteBuffer.wrap(data, 8, data.size - 8).order(ByteOrder.nativeOrder())
            return SpeedData(buf.float)
        }
    }
}
