package com.skyworth.faceid.fatigue

import com.skyworth.faceid.fatigue.FatigueRule.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FatigueStateMachine 单元测试（FACEP-015）。
 *
 * 使用用户给定规则（FatigueRule 默认值）验证：
 * - 轻度：60s 闭眼≥9次(≥0.2s) 或 60s 哈欠≥2次 → LIGHT
 * - 中度：60s 闭眼≥10次(≥0.2s) / 20s 闭眼≥2次(≥0.75s) / 连续闭眼1.5~2.4s / 60s 哈欠≥3次
 * - 重度：20s 闭眼≥2次(≥1.2s) / 连续闭眼≥2.4s
 * - 逐级退出：20s 无超0.75s 闭眼/哈欠 → 逐级降
 * - 无人脸复位、打哈欠(嘴开合≥0.3 持续≥2s)
 */
class FatigueStateMachineTest {

    private val rule = FatigueRule()

    private fun machine() = FatigueStateMachine(rule)

    /** 模拟一次闭眼事件：闭眼持续 [durationMs] 后睁开。 */
    private fun driveEyeClose(m: FatigueStateMachine, startMs: Long, durationMs: Long, eyeRatioClosed: Float = 0.0f) {
        // 闭眼开始
        m.update(1.0f, 0f, true, startMs)
        m.update(eyeRatioClosed, 0f, true, startMs + 1)
        // 保持闭眼 durationMs
        m.update(eyeRatioClosed, 0f, true, startMs + durationMs)
        // 睁开（事件结束）
        m.update(1.0f, 0f, true, startMs + durationMs + 1)
    }

    /** 模拟一次哈欠事件：张嘴持续 [durationMs]（开口占比高）后闭合 ≥1s 让窗口结束判定。 */
    private fun driveYawn(m: FatigueStateMachine, startMs: Long, durationMs: Long) {
        m.update(1.0f, 0.0f, true, startMs)                        // 闭嘴
        m.update(1.0f, 0.9f, true, startMs + 1)                    // 张嘴开始
        m.update(1.0f, 0.9f, true, startMs + durationMs)           // 持续张嘴（≥minDurationMs）
        m.update(1.0f, 0.0f, true, startMs + durationMs + 1)       // 闭嘴
        m.update(1.0f, 0.0f, true, startMs + durationMs + 1200)    // 闭嘴持续 ≥1s，结束窗口判定
    }

    /** 模拟"说话"：嘴部开合度快速波动（频繁穿越阈值 0.3），张嘴窗口内开口占比低。 */
    private fun driveTalking(m: FatigueStateMachine, startMs: Long, totalMs: Long) {
        m.update(1.0f, 0.0f, true, startMs)
        var t = startMs + 100
        while (t < startMs + totalMs) {
            // 每个"音节"：张 100ms（开合度 0.5 超过 0.3） + 闭 200ms
            m.update(1.0f, 0.5f, true, t)
            m.update(1.0f, 0.5f, true, t + 100)
            m.update(1.0f, 0.0f, true, t + 150)
            m.update(1.0f, 0.0f, true, t + 250)
            t += 350
        }
        // 说话结束，闭嘴 1s 让窗口结束判定
        m.update(1.0f, 0.0f, true, startMs + totalMs + 1200)
    }

    @Test
    fun `轻度-60秒内闭眼9次触发`() {
        val m = machine()
        var t = 0L
        // 9 次闭眼，每次持续 0.3s，间隔 1s
        repeat(9) {
            driveEyeClose(m, t, 300)
            t += 1000
        }
        val out = m.update(1.0f, 0f, true, t)
        assertEquals(Level.LIGHT, out.level)
    }

    @Test
    fun `轻度-不足9次不触发`() {
        val m = machine()
        var t = 0L
        repeat(8) {
            driveEyeClose(m, t, 300)
            t += 1000
        }
        val out = m.update(1.0f, 0f, true, t)
        assertEquals(Level.NONE, out.level)
    }

    @Test
    fun `轻度-60秒内哈欠2次触发`() {
        val m = machine()
        var t = 0L
        repeat(2) {
            driveYawn(m, t, 2500)
            t += 10_000
        }
        val out = m.update(1.0f, 0f, true, t)
        assertEquals(Level.LIGHT, out.level)
    }

    @Test
    fun `中度-连续闭眼1秒5到2秒4之间`() {
        val m = machine()
        // 连续闭眼 1.8s（≥1.5s 且 <2.4s）→ 中度
        m.update(1.0f, 0f, true, 0)
        m.update(0.0f, 0f, true, 100)
        m.update(0.0f, 0f, true, 1900) // 持续 1.8s
        val out = m.update(0.0f, 0f, true, 2000)
        assertEquals(Level.MODERATE, out.level)
    }

    @Test
    fun `中度-连续闭眼超过2秒4升级重度`() {
        val m = machine()
        m.update(1.0f, 0f, true, 0)
        m.update(0.0f, 0f, true, 100)
        m.update(0.0f, 0f, true, 2600) // 持续 2.5s
        val out = m.update(0.0f, 0f, true, 2700)
        assertEquals(Level.SEVERE, out.level)
    }

    @Test
    fun `重度-20秒内闭眼2次各1点2秒以上`() {
        val m = machine()
        var t = 0L
        repeat(2) {
            driveEyeClose(m, t, 1300)
            t += 5000
        }
        val out = m.update(1.0f, 0f, true, t)
        assertEquals(Level.SEVERE, out.level)
    }

    @Test
    fun `中度-20秒内闭眼2次各0点75秒以上`() {
        val m = machine()
        var t = 0L
        repeat(2) {
            driveEyeClose(m, t, 900)
            t += 5000
        }
        val out = m.update(1.0f, 0f, true, t)
        assertEquals(Level.MODERATE, out.level)
    }

    @Test
    fun `直接退出-重度直接回正常`() {
        val m = machine()
        // 触发重度：连续闭眼 2.5s
        m.update(1.0f, 0f, true, 0)
        m.update(0.0f, 0f, true, 100)
        m.update(0.0f, 0f, true, 2600)
        assertEquals(Level.SEVERE, m.update(0.0f, 0f, true, 2700).level)

        // 睁眼，20s 无超 0.75s 闭眼/哈欠 → 直接回 NONE（不再逐级降）
        var t = 2700L
        // 满一个退出窗口（>20s）后应直接回正常
        repeat(25) {
            t += 1000
            m.update(1.0f, 0f, true, t)
        }
        val out = m.update(1.0f, 0f, true, t)
        assertEquals("退出后应直接回正常（不逐级降）", Level.NONE, out.level)
    }

    @Test
    fun `无人脸复位`() {
        val m = machine()
        // 先触发轻度
        var t = 0L
        repeat(9) { driveEyeClose(m, t, 300); t += 1000 }
        assertEquals(Level.LIGHT, m.update(1.0f, 0f, true, t).level)

        // 无人脸持续 3s → 复位
        m.update(1.0f, 0f, false, t + 100)
        m.update(1.0f, 0f, false, t + 3100)
        val out = m.update(1.0f, 0f, false, t + 3200)
        assertEquals(Level.NONE, out.level)
    }

    @Test
    fun `打哈欠判定-嘴开合0点9持续2秒5记一次`() {
        val m = machine()
        // 哈欠事件：嘴开合 0.9 持续 2.5s → 一次哈欠
        driveYawn(m, 0, 2500)
        val out = m.update(1.0f, 0f, true, 3000)
        assertTrue("哈欠计数应为1", out.yawnCount60s >= 1)
    }

    @Test
    fun `连续闭眼时长诊断`() {
        val m = machine()
        m.update(1.0f, 0f, true, 0)
        m.update(0.0f, 0f, true, 100)
        m.update(0.0f, 0f, true, 1100) // 持续 1s
        val out = m.update(0.0f, 0f, true, 1200)
        assertTrue("连续闭眼时长应≥1s", out.curEyeCloseMs >= 1000)
    }

    @Test
    fun `说话不误判哈欠-频繁开合`() {
        // 方案 C：说话时嘴部开合度快速波动（开口占比低），不应记哈欠
        val m = machine()
        var t = 0L
        // 模拟连续说话 8s（音节式：张100ms闭200ms）
        while (t < 8000) {
            m.update(1.0f, 0.0f, true, t)          // 闭嘴（音节间）
            m.update(1.0f, 0.5f, true, t + 100)    // 开口（开合度0.5>0.3）
            m.update(1.0f, 0.0f, true, t + 150)    // 闭口（<1s，不结束窗口）
            m.update(1.0f, 0.0f, true, t + 250)    // 继续闭嘴
            t += 350
        }
        // 说话结束，闭嘴 ≥1s 让窗口结束判定
        m.update(1.0f, 0.0f, true, t + 1200)
        val out = m.update(1.0f, 0.0f, true, t + 1300)
        // 说话不应记为哈欠
        assertEquals("说话不应计哈欠", 0, out.yawnCount60s)
        // 说话也不应触发"哈欠2次"的轻度疲劳
        assertEquals(Level.NONE, out.level)
    }

    @Test
    fun `正常哈欠-平稳张嘴记一次`() {
        // 方案 C 对照组：平稳张嘴（开口占比高）应正常记哈欠
        val m = machine()
        // 张嘴 2.5s（开口段），开合度平稳 0.9
        m.update(1.0f, 0.0f, true, 0)
        m.update(1.0f, 0.9f, true, 100)
        m.update(1.0f, 0.9f, true, 1100)
        m.update(1.0f, 0.9f, true, 2100)
        m.update(1.0f, 0.9f, true, 2600)  // 张嘴结束前最后开口帧
        m.update(1.0f, 0.0f, true, 2700)  // 闭嘴
        m.update(1.0f, 0.0f, true, 3900)  // 闭嘴≥1s，结束窗口
        val out = m.update(1.0f, 0.0f, true, 4000)
        assertTrue("平稳哈欠应记1次", out.yawnCount60s >= 1)
    }

    @Test
    fun `眨眼不计入闭眼统计`() {
        // 诊断统计应过滤时长：眨眼（<200ms）不计入闭眼次数，避免"每次眨眼都被记录成闭眼"
        val m = machine()
        var t = 0L
        // 5 次眨眼，每次约 80ms（< min_count_duration_ms=200）
        repeat(5) {
            driveEyeClose(m, t, 80)
            t += 500
        }
        val out = m.update(1.0f, 0f, true, t)
        assertEquals("眨眼(80ms)不应计入闭眼统计", 0, out.eyeCloseCount60s)
    }

    @Test
    fun `有效闭眼计入闭眼统计`() {
        // 闭眼 ≥200ms 才计入闭眼统计
        val m = machine()
        var t = 0L
        repeat(3) {
            driveEyeClose(m, t, 300)   // 300ms ≥ 200ms，有效闭眼
            t += 1000
        }
        val out = m.update(1.0f, 0f, true, t)
        assertEquals("闭眼(300ms)应计入闭眼统计", 3, out.eyeCloseCount60s)
    }

    @Test
    fun `闭嘴时持续张嘴为0`() {
        // 张嘴后闭口（开合度<0.3），curYawnMs 应显示 0（即使张嘴窗口为统计占比仍活跃）
        val m = machine()
        m.update(1.0f, 0.0f, true, 0)      // 闭嘴
        m.update(1.0f, 0.9f, true, 100)    // 张嘴
        m.update(1.0f, 0.9f, true, 2100)   // 张嘴持续 2s
        // 闭口（开合度 0.08 < 0.3）
        val out = m.update(1.0f, 0.08f, true, 2200)
        assertEquals("闭口(开合度0.08)不应显示持续张嘴", 0L, out.curYawnMs)
    }
}
