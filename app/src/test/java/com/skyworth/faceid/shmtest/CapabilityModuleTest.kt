package com.skyworth.faceid.shmtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FACEP-011 阶段 A：能力模块枚举与 topic 映射测试。
 *
 * 验证：
 * - 每个能力模块有唯一 topic id；
 * - [CapabilityModule.fromTopic] 正/反向映射正确；
 * - 订阅解析（topic 列表 → 模块集合）不遗漏、非法 topic 被拒。
 */
class CapabilityModuleTest {

    @Test
    fun `每个能力模块 topic 唯一`() {
        val topics = CapabilityModule.values().map { it.topic }
        assertEquals("topic id 不应重复", topics.size, topics.toSet().size)
    }

    @Test
    fun `所有模块 topic 均为正数且互不冲突`() {
        val topics = CapabilityModule.values().map { it.topic }
        assertTrue("topic 应为正数", topics.all { it > 0 })
        assertEquals("不应有冲突", topics.size, topics.toSet().size)
    }

    @Test
    fun `fromTopic 正向映射`() {
        assertEquals(CapabilityModule.FACE_DETECT, CapabilityModule.fromTopic(0x01))
        assertEquals(CapabilityModule.HEADPOSE, CapabilityModule.fromTopic(0x02))
        assertEquals(CapabilityModule.GAZE, CapabilityModule.fromTopic(0x03))
        assertEquals(CapabilityModule.DISTRACTION, CapabilityModule.fromTopic(0x04))
        assertEquals(CapabilityModule.VEHICLE_SPEED, CapabilityModule.fromTopic(0x05))
    }

    @Test
    fun `fromTopic 反向一致`() {
        for (module in CapabilityModule.values()) {
            val back = CapabilityModule.fromTopic(module.topic)
            assertEquals("fromTopic 应与枚举一致", module, back)
        }
    }

    @Test
    fun `fromTopic 未知 topic 返回 null`() {
        assertNull(CapabilityModule.fromTopic(0))
        assertNull(CapabilityModule.fromTopic(0x10))
        assertNull(CapabilityModule.fromTopic(-1))
    }

    @Test
    fun `valid 校验合法模块集合`() {
        val all = CapabilityModule.values().toList()
        assertTrue("全量模块应合法", CapabilityModule.valid(all))
        assertTrue("空集应合法", CapabilityModule.valid(emptyList()))
        assertTrue("部分模块应合法", CapabilityModule.valid(listOf(CapabilityModule.FACE_DETECT)))
    }

    @Test
    fun `subscribe 解析 topic 列表不遗漏`() {
        // 模拟订阅 FACE_DETECT + VEHICLE_SPEED 两个 topic
        val topics = intArrayOf(
            CapabilityModule.FACE_DETECT.topic,
            CapabilityModule.VEHICLE_SPEED.topic
        )
        val modules = ArrayList<CapabilityModule>(topics.size)
        for (topic in topics) {
            CapabilityModule.fromTopic(topic)?.let { modules.add(it) }
        }
        assertEquals(2, modules.size)
        assertTrue(modules.contains(CapabilityModule.FACE_DETECT))
        assertTrue(modules.contains(CapabilityModule.VEHICLE_SPEED))
        assertFalse(modules.contains(CapabilityModule.HEADPOSE))
    }

    @Test
    fun `非法 topic 在订阅中应被拒绝`() {
        val topics = intArrayOf(CapabilityModule.FACE_DETECT.topic, 0x7fff)
        // fromTopic 对非法 topic 返回 null，视为不合法
        val parsed = ArrayList<CapabilityModule>(topics.size)
        for (topic in topics) {
            CapabilityModule.fromTopic(topic)?.let { parsed.add(it) }
        }
        assertEquals("非法 topic 应被过滤", 1, parsed.size)
        assertTrue(parsed.contains(CapabilityModule.FACE_DETECT))
    }
}
