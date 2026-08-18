package com.skyworth.faceid.shmtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FACEP-011 阶段 B：各能力模块 payload 序列化往返测试。
 *
 * 验证：
 * - 每个模块 encode→decode 往返一致；
 * - 损坏数据（magic/长度不匹配）返回 null；
 * - 与 [CapabilityModule] topic 的对应关系。
 */
class ModuleDataTest {

    @Test
    fun `FaceBoxData 序列化往返`() {
        val data = FaceBoxData(1600, 1300, true, 10f, 20f, 30f, 40f, 0.85f, 3f)
        val decoded = FaceBoxData.decode(data.encode())
        assertNotNull(decoded)
        decoded?.let {
            assertEquals(1600, it.frameW)
            assertEquals(1300, it.frameH)
            assertTrue(it.hasFace)
            assertEquals(10f, it.faceLeft, 1e-4f)
            assertEquals(20f, it.faceTop, 1e-4f)
            assertEquals(30f, it.faceRight, 1e-4f)
            assertEquals(40f, it.faceBottom, 1e-4f)
            assertEquals(0.85f, it.faceConfidence, 1e-4f)
            assertEquals(3f, it.zoneId, 1e-4f)
        }
    }

    @Test
    fun `FaceBoxData 无人脸时 hasFace=false`() {
        val data = FaceBoxData(1600, 1300, false, 0f, 0f, 0f, 0f, 0f, 0f)
        val decoded = FaceBoxData.decode(data.encode())
        assertNotNull(decoded)
        assertTrue(!decoded!!.hasFace)
    }

    @Test
    fun `FaceBoxData 含 5 关键点序列化往返`() {
        // 5 关键点扁平为 10 个 float：左眼、右眼、鼻尖、左嘴角、右嘴角
        val kp = floatArrayOf(
            100f, 150f, 200f, 150f, 150f, 220f, 120f, 300f, 180f, 300f
        )
        val data = FaceBoxData(1600, 1300, true, 10f, 20f, 30f, 40f, 0.85f, 3f, kp)
        val decoded = FaceBoxData.decode(data.encode())
        assertNotNull(decoded)
        decoded?.let {
            assertEquals("关键点数应为 10 个 float", 10, it.keypoints?.size)
            val dkp = it.keypoints!!
            assertEquals(100f, dkp[0], 1e-4f)
            assertEquals(150f, dkp[1], 1e-4f)
            assertEquals(200f, dkp[2], 1e-4f)
            assertEquals(220f, dkp[5], 1e-4f)
            assertEquals(300f, dkp[9], 1e-4f)
        }
    }

    @Test
    fun `FaceBoxData 无关键点时 decode 返回 null keypoints`() {
        val data = FaceBoxData(1600, 1300, true, 10f, 20f, 30f, 40f, 0.85f, 3f, null)
        val decoded = FaceBoxData.decode(data.encode())
        assertNotNull(decoded)
        assertEquals("无关键点应返回 null", null, decoded!!.keypoints)
    }

    @Test
    fun `HeadposeData 序列化往返`() {
        val data = HeadposeData(10f, 20f, -30f)
        val decoded = HeadposeData.decode(data.encode())
        assertNotNull(decoded)
        decoded?.let {
            assertEquals(10f, it.pitch, 1e-4f)
            assertEquals(20f, it.yaw, 1e-4f)
            assertEquals(-30f, it.roll, 1e-4f)
        }
    }

    @Test
    fun `GazeData 序列化往返`() {
        val data = GazeData(1f, 5f, -8f, calibrated = 1f)
        val decoded = GazeData.decode(data.encode())
        assertNotNull(decoded)
        decoded?.let {
            assertEquals(1f, it.valid, 1e-4f)
            assertEquals(5f, it.yaw, 1e-4f)
            assertEquals(-8f, it.pitch, 1e-4f)
            assertEquals("calibrated 应保留", 1f, it.calibrated, 1e-4f)
        }
    }

    @Test
    fun `DistractData 序列化往返 fast`() {
        val data = DistractData(true, "fast", 1500L, score = 0.8f, hpScore = 0.6f, gazeScore = 0.9f)
        val decoded = DistractData.decode(data.encode())
        assertNotNull(decoded)
        decoded?.let {
            assertTrue(it.distracted)
            assertEquals("fast", it.band)
            assertEquals(1500L, it.thresholdMs)
            assertEquals("score 应保留", 0.8f, it.score, 1e-4f)
            assertEquals("hpScore 应保留", 0.6f, it.hpScore, 1e-4f)
            assertEquals("gazeScore 应保留", 0.9f, it.gazeScore, 1e-4f)
        }
    }

    @Test
    fun `DistractData 序列化往返 slow`() {
        val data = DistractData(false, "slow", 3000L)
        val decoded = DistractData.decode(data.encode())
        assertNotNull(decoded)
        decoded?.let {
            assertTrue(!it.distracted)
            assertEquals("slow", it.band)
            assertEquals(3000L, it.thresholdMs)
        }
    }

    @Test
    fun `SpeedData 序列化往返`() {
        val data = SpeedData(62.5f)
        val decoded = SpeedData.decode(data.encode())
        assertNotNull(decoded)
        decoded?.let { assertEquals(62.5f, it.speedKmh, 1e-4f) }
    }

    @Test
    fun `SpeedData 无数据返回负数`() {
        val data = SpeedData(-1f)
        val decoded = SpeedData.decode(data.encode())
        assertNotNull(decoded)
        decoded?.let { assertEquals(-1f, it.speedKmh, 1e-4f) }
    }

    @Test
    fun `损坏数据返回 null`() {
        // 空字节
        assertNull(FaceBoxData.decode(ByteArray(0)))
        assertNull(DistractData.decode(ByteArray(0)))
        // 长度不足
        assertNull(FaceBoxData.decode(ByteArray(4)))
        // magic 不匹配（长度对但内容错）
        val bogus = ByteArray(20)
        assertNull(HeadposeData.decode(bogus))
    }

    @Test
    fun `模块 topic 与 CapabilityModule 一致`() {
        assertEquals(CapabilityModule.FACE_DETECT.topic, 0x01)
        assertEquals(CapabilityModule.HEADPOSE.topic, 0x02)
        assertEquals(CapabilityModule.GAZE.topic, 0x03)
        assertEquals(CapabilityModule.DISTRACTION.topic, 0x04)
        assertEquals(CapabilityModule.VEHICLE_SPEED.topic, 0x05)
    }
}
