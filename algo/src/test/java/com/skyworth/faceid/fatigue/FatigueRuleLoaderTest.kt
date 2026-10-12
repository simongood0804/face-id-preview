package com.skyworth.faceid.fatigue

import com.skyworth.faceid.fatigue.FatigueRule.Condition
import com.skyworth.faceid.fatigue.FatigueRule.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FatigueRuleLoader 解析正确性测试（FACEP-015，隐患 E 修复）。
 *
 * 使用 org.json:json（JVM 版，与 Android 内置 org.json API 一致），
 * 在纯 JVM 下验证"fatigue_rules.json 配置真正生效"，覆盖：
 * - 对应用户给定规则（资产 JSON）的完整解析；
 * - 缺失/损坏/空白 JSON 回退默认规则；
 * - 未知条件类型跳过；
 * - 条件字段映射正确。
 */
class FatigueRuleLoaderTest {

    @Test
    fun `解析用户规则-资产JSON完整映射`() {
        val json = """
            {
              "schema_version": 1,
              "yawn": { "enabled": true, "mouth_open_ratio": 0.3, "min_duration_ms": 2000 },
              "eye_close": { "enabled": true },
              "levels": [
                {
                  "level": "LIGHT",
                  "enter": [
                    { "type": "eye_close_count", "window_ms": 60000, "count_min": 9, "min_duration_ms": 200 },
                    { "type": "yawn_count", "window_ms": 60000, "count_min": 2 }
                  ],
                  "exit": { "type": "clean_clear", "window_ms": 20000, "clear_duration_ms": 750 }
                },
                {
                  "level": "MODERATE",
                  "enter": [
                    { "type": "eye_close_duration", "min_ms": 1500, "max_ms": 2400 },
                    { "type": "yawn_count", "window_ms": 60000, "count_min": 3 }
                  ],
                  "exit": { "type": "clean_clear", "window_ms": 20000, "clear_duration_ms": 750 }
                },
                {
                  "level": "SEVERE",
                  "enter": [ { "type": "eye_close_duration", "min_ms": 2400 } ],
                  "exit": { "type": "clean_clear", "window_ms": 20000, "clear_duration_ms": 750 }
                }
              ],
              "no_face": { "enabled": true, "reset_ms": 3000 }
            }
        """.trimIndent()

        val rule = FatigueRuleLoader.load(json)

        // 打哈欠基础
        assertEquals(0.3f, rule.yawn.mouthOpenRatio, 1e-4f)
        assertEquals(2000L, rule.yawn.minDurationMs)

        // 三级
        assertEquals(3, rule.levels.size)
        assertEquals(Level.LIGHT, rule.levels[0].level)
        assertEquals(Level.MODERATE, rule.levels[1].level)
        assertEquals(Level.SEVERE, rule.levels[2].level)

        // 轻度进入条件（或关系）：闭眼计数 + 哈欠计数
        val lightEnter = rule.levels[0].enter
        assertEquals(2, lightEnter.size)
        val c0 = lightEnter[0] as Condition.EyeCloseCount
        assertEquals(60_000L, c0.windowMs)
        assertEquals(9, c0.countMin)
        assertEquals(200L, c0.minDurationMs)
        val c1 = lightEnter[1] as Condition.YawnCount
        assertEquals(2, c1.countMin)

        // 中度连续闭眼区间
        val modEnter = rule.levels[1].enter
        val durCond = modEnter[0] as Condition.EyeCloseDuration
        assertEquals(1_500L, durCond.minMs)
        assertEquals(2_400L, durCond.maxMs)

        // 重度连续闭眼（maxMs 缺省=null）
        val sevEnter = rule.levels[2].enter
        val sevDur = sevEnter[0] as Condition.EyeCloseDuration
        assertEquals(2_400L, sevDur.minMs)
        assertEquals(null, sevDur.maxMs)

        // 退出条件
        val exit = rule.levels[0].exit as Condition.CleanClear
        assertEquals(20_000L, exit.windowMs)
        assertEquals(750L, exit.clearDurationMs)

        // 无人脸
        assertEquals(3000L, rule.noFace.resetMs)
    }

    @Test
    fun `未知条件类型跳过`() {
        val json = """
            {
              "levels": [
                {
                  "level": "LIGHT",
                  "enter": [
                    { "type": "unknown_condition", "foo": 1 },
                    { "type": "yawn_count", "window_ms": 60000, "count_min": 2 }
                  ],
                  "exit": { "type": "clean_clear", "window_ms": 20000, "clear_duration_ms": 750 }
                }
              ]
            }
        """.trimIndent()

        val rule = FatigueRuleLoader.load(json)
        val lightEnter = rule.levels[0].enter
        // 未知 type 被跳过，只剩哈欠计数条件
        assertEquals(1, lightEnter.size)
        assertTrue(lightEnter[0] is Condition.YawnCount)
    }

    @Test
    fun `空白JSON回退默认规则`() {
        val rule = FatigueRuleLoader.load("")
        // 默认三级 + 默认打哈欠阈值 0.3
        assertEquals(3, rule.levels.size)
        assertEquals(0.3f, rule.yawn.mouthOpenRatio, 1e-4f)
    }

    @Test
    fun `损坏JSON回退默认规则`() {
        val rule = FatigueRuleLoader.load("{ not valid json ")
        assertEquals(3, rule.levels.size)
        assertEquals(2000L, rule.yawn.minDurationMs)
    }

    @Test
    fun `null回退默认规则`() {
        assertEquals(3, FatigueRuleLoader.load(null).levels.size)
    }

    @Test
    fun `缺失levels回退默认三级`() {
        val json = """{ "schema_version": 1, "no_face": { "reset_ms": 5000 } }"""
        val rule = FatigueRuleLoader.load(json)
        // 未提供 levels → 用默认三级
        assertEquals(3, rule.levels.size)
        // 但 no_face 自定义生效
        assertEquals(5000L, rule.noFace.resetMs)
    }
}
