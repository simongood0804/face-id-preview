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
}
