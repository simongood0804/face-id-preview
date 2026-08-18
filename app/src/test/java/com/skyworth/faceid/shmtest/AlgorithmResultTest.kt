package com.skyworth.faceid.shmtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AlgorithmResult] 序列化往返单测（纯 JVM）。
 *
 * 验证 encode/decode 对称性（含版本头 magic + 长度校验）：
 * - 正常往返：所有字段一致；
 * - 版本不匹配：decode 返回 null（容错）。
 */
class AlgorithmResultTest {

    @Test
    fun `encode decode round trip preserves all fields`() {
        val src = AlgorithmResult(
            frameW = 1600, frameH = 1300, hasFace = true,
            faceLeft = 100f, faceTop = 200f, faceRight = 300f, faceBottom = 400f,
            faceConfidence = 0.98f,
            headposePitch = 1.5f, headposeYaw = 12.0f, headposeRoll = -3.5f,
            gazeValid = 1f, gazeYaw = -20f, gazePitch = 15f,
            zoneId = 7f,
            distracted = true, distractionBand = "slow", distractionThresholdMs = 3000L,
            speedKmh = 60f
        )
        val decoded = AlgorithmResult.decode(src.encode())
        assertNotNull(decoded)
        assertEquals(src, decoded)
    }

    @Test
    fun `serialized length matches declared encodedSize`() {
        // 确保 SERIALIZED_BYTES 与实际 encode 写入字节数一致，防止字段/长度错位
        val src = AlgorithmResult(
            frameW = 1600, frameH = 1300, hasFace = true,
            faceLeft = 100f, faceTop = 200f, faceRight = 300f, faceBottom = 400f,
            faceConfidence = 0.98f,
            headposePitch = 1.5f, headposeYaw = 12.0f, headposeRoll = -3.5f,
            gazeValid = 1f, gazeYaw = -20f, gazePitch = 15f,
            zoneId = 7f, gazeCalibrated = 1f,
            distracted = true, distractionBand = "slow", distractionThresholdMs = 3000L,
            speedKmh = 60f,
            keypoints = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f)
        )
        val encoded = src.encode()
        // encodedSize() = SERIALIZED_BYTES（不含 8 字节头），encode 总长 = 8 + SERIALIZED_BYTES
        assertEquals("序列化长度应一致", 8 + src.encodedSize(), encoded.size)
    }

    @Test
    fun `decode rejects mismatched magic version`() {
        // 构造一个 magic 错误的字节（模仿旧版本/损坏数据）
        val src = AlgorithmResult.EMPTY
        val bytes = src.encode()
        bytes[0] = bytes[0].inc()  // 篡改 magic
        assertNull(AlgorithmResult.decode(bytes))
    }

    @Test
    fun `decode rejects truncated data`() {
        val src = AlgorithmResult.EMPTY
        val bytes = src.encode()
        // 截断到头部以内
        val truncated = bytes.copyOf(6)
        assertNull(AlgorithmResult.decode(truncated))
    }

    @Test
    fun `empty result encodes and decodes`() {
        val decoded = AlgorithmResult.decode(AlgorithmResult.EMPTY.encode())
        assertNotNull(decoded)
        assertEquals(AlgorithmResult.EMPTY, decoded)
    }

    @Test
    fun `keypoints round trip preserved`() {
        // 5 关键点扁平为 10 个 float：左眼、右眼、鼻尖、左嘴角、右嘴角
        val kp = floatArrayOf(
            100f, 150f, 200f, 150f, 150f, 220f, 120f, 300f, 180f, 300f
        )
        val src = AlgorithmResult(
            frameW = 1600, frameH = 1300, hasFace = true,
            faceLeft = 100f, faceTop = 200f, faceRight = 300f, faceBottom = 400f,
            faceConfidence = 0.98f,
            headposePitch = 1.5f, headposeYaw = 12.0f, headposeRoll = -3.5f,
            gazeValid = 1f, gazeYaw = -20f, gazePitch = 15f,
            zoneId = 7f, gazeCalibrated = 1f,
            distracted = true, distractionBand = "slow", distractionThresholdMs = 3000L,
            speedKmh = 60f,
            keypoints = kp
        )
        val decoded = AlgorithmResult.decode(src.encode())
        assertNotNull(decoded)
        decoded?.let {
            assertEquals("关键点数应为 10 个 float", 10, it.keypoints?.size)
            val dkp = it.keypoints!!
            assertEquals(100f, dkp[0], 1e-4f)
            assertEquals(150f, dkp[1], 1e-4f)
            assertEquals(180f, dkp[8], 1e-4f)
            assertEquals(300f, dkp[9], 1e-4f)
            assertEquals("gazeCalibrated 应保留", 1f, it.gazeCalibrated, 1e-4f)
        }
    }

    @Test
    fun `null keypoints round trip yields null`() {
        val src = AlgorithmResult(
            frameW = 1600, frameH = 1300, hasFace = true,
            faceLeft = 100f, faceTop = 200f, faceRight = 300f, faceBottom = 400f,
            faceConfidence = 0.98f,
            headposePitch = 1.5f, headposeYaw = 12.0f, headposeRoll = -3.5f,
            gazeValid = 1f, gazeYaw = -20f, gazePitch = 15f,
            zoneId = 7f,
            distracted = true, distractionBand = "slow", distractionThresholdMs = 3000L,
            speedKmh = 60f,
            keypoints = null
        )
        val decoded = AlgorithmResult.decode(src.encode())
        assertNotNull(decoded)
        assertEquals("null 关键点应保持 null", null, decoded!!.keypoints)
    }
}
