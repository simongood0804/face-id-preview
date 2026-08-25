package com.skyworth.faceid.algorithm;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * EyeMouthStateMachine（眼睛/嘴巴基础状态防抖器）单元测试（提案 FACEP-010 §3.7）。
 *
 * 验证：
 * - 闭眼：持续确认时长后 eyeClosed=true；不足时长不触发
 * - 睁眼解除：闭眼后睁眼候选满足即解除（不对称防抖，退出不设确认时长）
 * - 张嘴/闭嘴：mouthOpen 的确认与解除
 * - 滞回区间防闪变：介于 close/open 阈值之间时状态保持不变
 * - hasFace=false 重置
 * - 时钟推进/确认时长边界
 */
public class EyeMouthStateMachineTest {

    private AtomicLong mClock;
    private EyeMouthStateMachine mState;

    /** 默认阈值：eyeClose=0.10、eyeOpen=0.30；确认时长 80ms。 */
    @Before
    public void setUp() {
        mClock = new AtomicLong(0L);
        mState = new EyeMouthStateMachine(() -> mClock.get());
    }

    private void advance(long ms) {
        mClock.addAndGet(ms);
    }

    // ---- 闭眼确认 ----

    @Test
    public void testEyeCloseConfirmedAfterConfirmMs() {
        // 开合度 0.05 ≤ closeRatio 0.10 → 闭眼候选
        mState.update(true, 0.05f, 0.05f);
        assertFalse("未确认前不应闭眼", mState.isEyeClosed());
        advance(79);
        mState.update(true, 0.05f, 0.05f);
        assertFalse("不足 80ms 不应确认闭眼", mState.isEyeClosed());
        advance(1);
        mState.update(true, 0.05f, 0.05f);
        assertTrue("达到 80ms 应确认闭眼", mState.isEyeClosed());
    }

    @Test
    public void testEyeNotConfirmedBelowMs() {
        mState.update(true, 0.05f, 0.05f);
        advance(80);
        // 中间有一帧回到睁眼（0.8），打断计时
        mState.update(true, 0.8f, 0.1f);
        advance(79);
        mState.update(true, 0.05f, 0.05f);
        assertFalse("中断后重新计时，未达 80ms 不应闭眼", mState.isEyeClosed());
    }

    // ---- 睁眼解除 ----

    @Test
    public void testEyeOpenClearsImmediately() {
        // 先确认闭眼
        mState.update(true, 0.05f, 0.05f);
        advance(80);
        mState.update(true, 0.05f, 0.05f);
        assertTrue(mState.isEyeClosed());

        // 进入睁眼候选（0.8 ≥ openRatio 0.30）单帧即解除（不对称防抖：退出不设确认时长）
        mState.update(true, 0.8f, 0.1f);
        assertFalse("睁眼候选满足应立即解除闭眼", mState.isEyeClosed());
    }

    @Test
    public void testEyeOpenNotClearedInHysteresis() {
        // 先确认闭眼
        mState.update(true, 0.05f, 0.05f);
        advance(80);
        mState.update(true, 0.05f, 0.05f);
        assertTrue(mState.isEyeClosed());

        // 滞回区间（0.25：0.10 < 0.25 < 0.30）不满足睁眼候选 → 应保持闭眼
        mState.update(true, 0.25f, 0.1f);
        assertTrue("滞回区间应保持闭眼", mState.isEyeClosed());
    }

    // ---- 滞回区间防闪变 ----

    @Test
    public void testHysteresisKeepsState() {
        // 初始睁眼，开合度在滞回区间（0.25：0.10 < 0.25 < 0.30）反复跳动不应误判闭眼
        for (int i = 0; i < 10; i++) {
            advance(10);
            mState.update(true, 0.25f, 0.1f);
            assertFalse("滞回区间不应误判闭眼", mState.isEyeClosed());
        }
    }

    @Test
    public void testHysteresisKeepsClosedAfterClosed() {
        // 已确认闭眼，开合度在滞回区间不应被误判为睁眼
        mState.update(true, 0.05f, 0.05f);
        advance(80);
        mState.update(true, 0.05f, 0.05f);
        assertTrue(mState.isEyeClosed());

        for (int i = 0; i < 10; i++) {
            advance(10);
            mState.update(true, 0.25f, 0.1f);
            assertTrue("滞回区间应保持闭眼", mState.isEyeClosed());
        }
    }

    // ---- 张嘴 / 闭嘴 ----

    @Test
    public void testMouthOpenConfirmed() {
        // mouthOpenRatio 0.8 ≥ 0.60 → 张嘴候选（嘴巴确认时长 200ms）
        mState.update(true, 0.8f, 0.8f);
        assertFalse(mState.isMouthOpen());
        advance(200);
        mState.update(true, 0.8f, 0.8f);
        assertTrue("达到确认时长应张嘴", mState.isMouthOpen());
    }

    @Test
    public void testMouthCloseClearsImmediately() {
        // 先确认张嘴（嘴巴确认时长 200ms）
        mState.update(true, 0.8f, 0.8f);
        advance(200);
        mState.update(true, 0.8f, 0.8f);
        assertTrue(mState.isMouthOpen());

        // 闭嘴候选（mouthOpenRatio 0.05 ≤ 0.35）单帧即解除（不对称防抖：退出不设确认时长）
        mState.update(true, 0.8f, 0.05f);
        assertFalse("闭嘴候选满足应立即解除张嘴", mState.isMouthOpen());
    }

    // ---- hasFace=false 重置 ----

    @Test
    public void testResetWhenNoFace() {
        // 眼睛确认 80ms，嘴巴确认 200ms
        mState.update(true, 0.05f, 0.8f);
        advance(200);
        mState.update(true, 0.05f, 0.8f);
        assertTrue(mState.isEyeClosed());
        assertTrue(mState.isMouthOpen());

        mState.reset();
        assertFalse("reset 后应非闭眼", mState.isEyeClosed());
        assertFalse("reset 后应非张嘴", mState.isMouthOpen());
    }

    @Test
    public void testUpdateNoFaceResets() {
        mState.update(true, 0.05f, 0.8f);
        advance(80);
        mState.update(true, 0.05f, 0.8f);
        assertTrue(mState.isEyeClosed());

        // hasFace=false 的 update 应重置
        mState.update(false, 0.1f, 0.8f);
        assertFalse("无人脸应重置闭眼", mState.isEyeClosed());
        assertFalse("无人脸应重置张嘴", mState.isMouthOpen());
    }

    // ---- 确认时长边界 ----

    @Test
    public void testConfirmExactlyAtMs() {
        mState.update(true, 0.05f, 0.05f);
        advance(80);
        mState.update(true, 0.05f, 0.05f);
        assertTrue("恰好在 80ms 处应确认", mState.isEyeClosed());
    }

    // ---- 自定义阈值 / 确认时长 ----

    @Test
    public void testCustomConfirmMs() {
        EyeMouthStateMachine custom = new EyeMouthStateMachine(
                () -> mClock.get(), 0.1f, 0.3f, 0.3f, 0.1f, 200L);
        custom.update(true, 0.05f, 0.1f);  // 0.05 ≤ closeRatio 0.1
        advance(199);
        custom.update(true, 0.05f, 0.1f);
        assertFalse("自定义 200ms 确认，未到不触发", custom.isEyeClosed());
        advance(1);
        custom.update(true, 0.05f, 0.1f);
        assertTrue("自定义 200ms 确认，到时触发", custom.isEyeClosed());
    }
}
