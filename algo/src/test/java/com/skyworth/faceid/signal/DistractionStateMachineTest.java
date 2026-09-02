package com.skyworth.faceid.signal;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * DistractionStateMachine（分心判定状态机）单元测试。
 *
 * 验证从 PreviewActivity.updateDistraction 提取的逻辑与现有行为一致：
 * - 快速档（≥50km/h 或无数据）：1.5s 触发
 * - 慢速档（<50km/h）：3.0s 触发
 * - 解除阈值：0.5s
 * - 无人脸延时复位（防算法短暂漏检误消除信号）
 */
public class DistractionStateMachineTest {

    /** 可注入的单调时钟（模拟 elapsedRealtime）。 */
    private AtomicLong mClock;
    private DistractionStateMachine mState;

    @Before
    public void setUp() {
        mClock = new AtomicLong(0L);
        mState = new DistractionStateMachine(
                () -> mClock.get(), DistractionStateMachine.NO_FACE_RESET_MS);
    }

    private void advance(long ms) {
        mClock.addAndGet(ms);
    }

    // ---- 快速档（无车速数据） ----

    @Test
    public void testFastTriggerWhenNoSpeedData() {
        // 无车速数据（-1）→ 快速档 1.5s 触发
        mState.update(true, 1f, -1f);
        assertFalse(mState.isDistracted());
        advance(1499);
        assertFalse("未到 1.5s 不应触发", mState.update(true, 1f, -1f));
        advance(1);
        assertTrue("达到 1.5s 应触发", mState.update(true, 1f, -1f));
    }

    // ---- 快速档（≥50km/h） ----

    @Test
    public void testFastTriggerAtHighSpeed() {
        mState.update(true, 1f, 60f);  // 高速 → fast
        advance(1500);
        assertTrue(mState.update(true, 1f, 60f));
        assertEquals("fast 档阈值应为 1500ms",
                DistractionStateMachine.TRIGGER_MS_FAST, mState.currentTriggerMs());
        assertEquals("fast", mState.currentSpeedBand());
    }

    // ---- 慢速档（<50km/h） ----

    @Test
    public void testSlowTriggerAtLowSpeed() {
        mState.update(true, 1f, 30f);  // 低速 → slow
        advance(1500);
        assertFalse("低速 1.5s 不应触发（需 3s）", mState.update(true, 1f, 30f));
        advance(1500);
        assertTrue("低速达到 3s 应触发", mState.update(true, 1f, 30f));
        assertEquals("slow 档阈值应为 3000ms",
                DistractionStateMachine.TRIGGER_MS_SLOW, mState.currentTriggerMs());
        assertEquals("slow", mState.currentSpeedBand());
    }

    // ---- 边界：恰好 50km/h → 快速档 ----

    @Test
    public void testExactly50UsesFastBand() {
        mState.update(true, 1f, 50f);
        assertEquals("50km/h 应算高速档（fast）",
                DistractionStateMachine.TRIGGER_MS_FAST, mState.currentTriggerMs());
    }

    // ---- 非分心重置触发计时 ----

    @Test
    public void testInterruptResetsTriggerTimer() {
        mState.update(true, 1f, -1f);
        advance(1000);
        mState.update(true, 0f, -1f);  // 非分心 → 重置计时
        advance(1000);
        assertFalse("中断后重新计时，刚开始不应触发", mState.update(true, 1f, -1f));
        advance(1500);
        assertTrue("重置后累计 1.5s 触发", mState.update(true, 1f, -1f));
    }

    // ---- 解除逻辑 ----

    @Test
    public void testClearAfterClearMs() {
        mState.update(true, 1f, -1f);
        advance(1500);
        assertTrue(mState.update(true, 1f, -1f));  // 触发

        // 连续非分心 0.5s 解除
        mState.update(true, 0f, -1f);
        advance(499);
        assertTrue("未到 0.5s 保持触发", mState.update(true, 0f, -1f));
        advance(1);
        assertFalse("达到 0.5s 解除", mState.update(true, 0f, -1f));
    }

    @Test
    public void testClearResetWhenStillDistracted() {
        mState.update(true, 1f, -1f);
        advance(1500);
        assertTrue(mState.update(true, 1f, -1f));

        mState.update(true, 0f, -1f);  // 开始解除计时
        advance(300);
        mState.update(true, 1f, -1f);  // 又分心 → 重置解除计时
        advance(499);
        assertTrue("解除计时被重置，未到 0.5s", mState.update(true, 0f, -1f));
    }

    // ---- 无人脸延时复位 ----

    @Test
    public void testNoFaceDelayedReset() {
        // 先触发分心
        mState.update(true, 1f, -1f);
        advance(1500);
        assertTrue(mState.update(true, 1f, -1f));

        // 无人脸：未达确认时长 → 保持分心（防算法短暂漏检误消除）
        mState.update(false, 0f, -1f);
        assertTrue("无人脸未达确认时长应保持分心", mState.isDistracted());

        advance(DistractionStateMachine.NO_FACE_RESET_MS - 1);
        mState.update(false, 0f, -1f);
        assertTrue("无人脸差 1ms 不应复位", mState.isDistracted());

        advance(1);
        mState.update(false, 0f, -1f);
        assertFalse("连续无人脸达确认时长应复位", mState.isDistracted());
    }

    @Test
    public void testNoFaceInterruptedByFaceRestored() {
        mState.update(true, 1f, -1f);
        advance(1500);
        assertTrue(mState.update(true, 1f, -1f));

        // 短暂无人脸后又有人脸 → 无人脸确认计时清除，不复位
        mState.update(false, 0f, -1f);
        advance(2000);
        mState.update(true, 1f, -1f);  // 人脸恢复，清除无人脸确认计时
        advance(2000);
        mState.update(false, 0f, -1f);
        assertTrue("人脸恢复应中断无人脸确认计时", mState.isDistracted());
    }

    @Test
    public void testResetWhenNoFace() {
        mState.update(true, 1f, -1f);
        advance(1500);
        assertTrue(mState.update(true, 1f, -1f));

        mState.reset();
        assertFalse("reset 后应非分心", mState.isDistracted());
        // 重新累计：先 update 开始计时，再 advance 1.5s 触发
        mState.update(true, 1f, -1f);
        advance(1500);
        assertTrue(mState.update(true, 1f, -1f));
    }

    // ---- AlgoDistractionInput 重载 ----

    @Test
    public void testUpdateWithInputObject() {
        SignalTypes.AlgoDistractionInput input =
                new SignalTypes.AlgoDistractionInput(true, 1f);
        mState.update(input, -1f);
        advance(1500);
        assertTrue(mState.update(input, -1f));
    }

    @Test
    public void testUpdateWithNoFaceInput() {
        // hasFace=false：无人脸由状态机内部延时复位（未触发过告警时保持非分心）
        SignalTypes.AlgoDistractionInput noFace =
                new SignalTypes.AlgoDistractionInput(false, 0f);
        mState.update(noFace, -1f);
        // 无分心 → 不触发
        assertFalse(mState.isDistracted());
    }
}
