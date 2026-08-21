package com.skyworth.faceid.algorithm;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * EyeMouthCalibrator（阈值动态校准器）单元测试（提案 FACEP-010 §3.7.6）。
 *
 * 验证：
 * - 样本不足时用默认阈值
 * - 样本足够后按分位数建立高低基准，阈值随数据自适应
 * - 高低阈值恒满足 low < high（滞回区间有效）
 * - reset() 复位回默认基准
 * - 不同样本分布下阈值收敛方向正确
 */
public class EyeMouthCalibratorTest {

    private static final float EPS = 0.01f;

    /** 默认阈值（对应 CalibratedThresholds.DEFAULT）。 */
    private static final float DEF_CLOSE = 0.18f;
    private static final float DEF_OPEN = 0.35f;

    // ---- 样本不足时用默认阈值 ----

    @Test
    public void testDefaultWhenInsufficientSamples() {
        EyeMouthCalibrator cal = new EyeMouthCalibrator();
        // 样本不足（<10 帧）时阈值应保持默认
        for (int i = 0; i < 5; i++) {
            cal.update(0.3f, 0.3f);
        }
        EyeMouthCalibrator.CalibratedThresholds t = cal.thresholds();
        assertEquals(DEF_CLOSE, t.getEyeCloseRatio(), EPS);
        assertEquals(DEF_OPEN, t.getEyeOpenRatio(), EPS);
        assertEquals(DEF_CLOSE, t.getMouthCloseRatio(), EPS);
        assertEquals(DEF_OPEN, t.getMouthOpenRatio(), EPS);
    }

    // ---- 样本足够后建立基准，阈值自适应 ----

    @Test
    public void testCalibratesToLowEyeRatios() {
        // 一直闭眼（开合度很小）：低基准应显著低于默认，阈值随之下降
        EyeMouthCalibrator cal = new EyeMouthCalibrator();
        for (int i = 0; i < 300; i++) {
            cal.update(0.05f, 0.1f);  // 眼睛近闭，嘴巴近闭
        }
        EyeMouthCalibrator.CalibratedThresholds t = cal.thresholds();
        // 高/低基准都应显著低于默认睁眼/闭眼阈值
        assertTrue("长期闭眼后睁眼阈值应低于默认 0.35",
                t.getEyeOpenRatio() < DEF_OPEN);
        assertTrue("长期闭眼后闭眼阈值应低于默认 0.18",
                t.getEyeCloseRatio() < DEF_CLOSE);
    }

    @Test
    public void testCalibratesToHighEyeRatios() {
        // 一直睁眼（开合度大）：高基准应接近样本高位
        EyeMouthCalibrator cal = new EyeMouthCalibrator();
        for (int i = 0; i < 300; i++) {
            cal.update(0.9f, 0.9f);  // 眼睛近睁，嘴巴近张
        }
        EyeMouthCalibrator.CalibratedThresholds t = cal.thresholds();
        assertTrue("长期睁眼后睁眼阈值应接近 0.9 附近的高位",
                t.getEyeOpenRatio() > 0.7f);
    }

    // ---- 高低阈值恒满足 low < high（滞回区间有效） ----

    @Test
    public void testHysteresisIntervalValid() {
        EyeMouthCalibrator cal = new EyeMouthCalibrator();
        // 混合数据：既有睁眼也有闭眼
        for (int i = 0; i < 300; i++) {
            float v = (i % 10 < 2) ? 0.05f : 0.9f;  // 20% 闭眼，80% 睁眼
            cal.update(v, v);
        }
        EyeMouthCalibrator.CalibratedThresholds t = cal.thresholds();
        assertTrue("眼睛滞回下界应 < 上界",
                t.getEyeCloseRatio() < t.getEyeOpenRatio());
        assertTrue("嘴巴滞回下界应 < 上界",
                t.getMouthCloseRatio() < t.getMouthOpenRatio());
    }

    // ---- reset() 复位回默认 ----

    @Test
    public void testResetRestoresDefault() {
        EyeMouthCalibrator cal = new EyeMouthCalibrator();
        // 先产生漂移
        for (int i = 0; i < 300; i++) {
            cal.update(0.05f, 0.1f);
        }
        EyeMouthCalibrator.CalibratedThresholds before = cal.thresholds();
        assertTrue(before.getEyeOpenRatio() < DEF_OPEN);

        // 复位后回默认
        cal.reset();
        EyeMouthCalibrator.CalibratedThresholds after = cal.thresholds();
        assertEquals(DEF_CLOSE, after.getEyeCloseRatio(), EPS);
        assertEquals(DEF_OPEN, after.getEyeOpenRatio(), EPS);
    }

    // ---- reset 后重新校准 ----

    @Test
    public void testRecalibrateAfterReset() {
        EyeMouthCalibrator cal = new EyeMouthCalibrator();
        // 第一个驾驶员：常闭眼
        for (int i = 0; i < 300; i++) cal.update(0.05f, 0.1f);
        EyeMouthCalibrator.CalibratedThresholds first = cal.thresholds();
        // 门信号复位（换驾驶员）
        cal.reset();
        // 第二个驾驶员：常睁眼
        for (int i = 0; i < 300; i++) cal.update(0.9f, 0.9f);
        EyeMouthCalibrator.CalibratedThresholds second = cal.thresholds();
        assertTrue("换人重校后阈值应显著高于原驾驶员",
                second.getEyeOpenRatio() > first.getEyeOpenRatio());
    }

    // ---- 自定义参数 ----

    @Test
    public void testCustomParams() {
        EyeMouthCalibrator cal = new EyeMouthCalibrator(
                100, 0.1f, 0.8f, 0.2f, 0.3f, 0.6f);
        for (int i = 0; i < 100; i++) {
            cal.update(0.9f, 0.9f);
        }
        EyeMouthCalibrator.CalibratedThresholds t = cal.thresholds();
        assertTrue(t.getEyeOpenRatio() > 0.7f);
    }
}
