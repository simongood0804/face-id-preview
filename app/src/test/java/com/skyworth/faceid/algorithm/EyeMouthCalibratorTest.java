package com.skyworth.faceid.algorithm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * EyeMouthCalibrator 单元测试（v3.4 方案A：跟踪原始 aperture/MAR + 个人基准归一化）。
 *
 * 量纲约定（与 EyeMouthStateEstimator 默认端点一致）：
 * - 眼睛 aperture（睑距/双眼外眼角距离）：睁眼 ≈0.10、闭眼残差 ≈0.02~0.03；
 * - 嘴巴 MAR：张嘴 ≈0.60、闭嘴残差 ≈0.35~0.36。
 */
public class EyeMouthCalibratorTest {

    /** 精确断言容差（阈值/默认值）。 */
    private static final float EPS = 0.01f;
    /** 归一化断言容差（EWMA 平滑收敛余量）。 */
    private static final float NORM_EPS = 0.05f;

    /** 阈值语义（v3.5 中危修复后恒定，不随校准状态跳变）：眼睛=因子位置、嘴巴=固定值。 */
    private static final float DEF_EYE_CLOSE = 0.10f;   // 眼睛下界因子（默认）
    private static final float DEF_EYE_OPEN = 0.70f;    // 眼睛上界因子（默认）
    private static final float DEF_MOUTH_CLOSE = 0.35f; // 嘴巴闭嘴阈值（固定）
    private static final float DEF_MOUTH_OPEN = 0.60f;  // 嘴巴张嘴阈值（固定）

    /** 默认归一化端点（aperture/MAR 量纲）。 */
    private static final float DEF_EYE_OPEN_APERTURE = 0.10f;
    private static final float DEF_EYE_CLOSE_APERTURE = 0.02f;
    private static final float DEF_MOUTH_OPEN_MAR = 0.62f;
    private static final float DEF_MOUTH_CLOSE_MAR = 0.35f;

    // 实测量纲样本：睁眼/张嘴高位、闭眼/闭嘴残差低位
    private static final float EYE_OPEN = 0.10f;
    private static final float EYE_CLOSED = 0.03f;
    private static final float MOUTH_OPEN = 0.60f;
    private static final float MOUTH_CLOSED = 0.36f;

    /** 混合驾驶样本：80% 睁眼/张嘴、20% 闭眼/闭嘴。 */
    private static final class MixedSample {
        float eye;
        float mouth;
    }

    private static MixedSample[] mixedSamples(int n) {
        MixedSample[] s = new MixedSample[n];
        for (int i = 0; i < n; i++) {
            s[i] = new MixedSample();
            if (i % 10 < 2) {
                s[i].eye = EYE_CLOSED;
                s[i].mouth = MOUTH_CLOSED;
            } else {
                s[i].eye = EYE_OPEN;
                s[i].mouth = MOUTH_OPEN;
            }
        }
        return s;
    }

    private static void feed(EyeMouthCalibrator cal, MixedSample[] samples) {
        for (MixedSample s : samples) {
            cal.update(s.eye, s.mouth);
        }
    }

    @Test
    public void testThresholdsStableBeforeCalibration() {
        // v3.5 中危修复：阈值恒为"眼睛因子 + 嘴巴固定值"，校准激活（样本满 10 帧）
        // 前后不跳变——旧实现样本不足返回静态默认（眼睛上界 0.30），基准建立瞬间
        // 跳变到 0.70，可能造成状态机阈值突变/误判。
        EyeMouthCalibrator cal = new EyeMouthCalibrator();
        // 样本不足（<10 帧）
        for (int i = 0; i < 5; i++) {
            cal.update(0.05f, 0.50f);
        }
        EyeMouthCalibrator.CalibratedThresholds t = cal.thresholds();
        assertEquals(DEF_EYE_CLOSE, t.getEyeCloseRatio(), EPS);
        assertEquals(DEF_EYE_OPEN, t.getEyeOpenRatio(), EPS);
        assertEquals(DEF_MOUTH_CLOSE, t.getMouthCloseRatio(), EPS);
        assertEquals(DEF_MOUTH_OPEN, t.getMouthOpenRatio(), EPS);
        // 基准未建立：归一化用默认端点（与 Estimator 静态归一化一致）
        assertEquals(1.0f, cal.normalizeEye(DEF_EYE_OPEN_APERTURE), NORM_EPS);
        assertEquals(0.0f, cal.normalizeEye(DEF_EYE_CLOSE_APERTURE), NORM_EPS);
        assertEquals(1.0f, cal.normalizeMouth(DEF_MOUTH_OPEN_MAR), NORM_EPS);
        assertEquals(0.0f, cal.normalizeMouth(DEF_MOUTH_CLOSE_MAR), NORM_EPS);

        // 校准激活（混合样本建立个人基准）后阈值不变 → 无跳变
        feed(cal, mixedSamples(300));
        EyeMouthCalibrator.CalibratedThresholds after = cal.thresholds();
        assertEquals("校准激活后眼睛下界不跳变", DEF_EYE_CLOSE, after.getEyeCloseRatio(), EPS);
        assertEquals("校准激活后眼睛上界不跳变", DEF_EYE_OPEN, after.getEyeOpenRatio(), EPS);
        assertEquals("校准激活后嘴巴闭嘴阈值不跳变", DEF_MOUTH_CLOSE, after.getMouthCloseRatio(), EPS);
        assertEquals("校准激活后嘴巴张嘴阈值不跳变", DEF_MOUTH_OPEN, after.getMouthOpenRatio(), EPS);
    }

    @Test
    public void testCalibratesToMixedBaseline() {
        // 混合驾驶：80% 睁眼/张嘴、20% 闭眼/闭嘴
        EyeMouthCalibrator cal = new EyeMouthCalibrator();
        feed(cal, mixedSamples(300));
        EyeMouthCalibrator.CalibratedThresholds t = cal.thresholds();
        // 基准建立后阈值即滞回因子位置
        assertEquals("眼睛闭眼候选阈值应等于下界因子 0.10",
                0.10f, t.getEyeCloseRatio(), EPS);
        assertEquals("眼睛睁眼候选阈值应等于上界因子 0.70",
                0.70f, t.getEyeOpenRatio(), EPS);
        // 嘴巴阈值固定（不随动态校准漂移，与状态机默认一致）
        assertEquals("嘴巴闭嘴阈值固定为 0.35",
                DEF_MOUTH_CLOSE, t.getMouthCloseRatio(), EPS);
        assertEquals("嘴巴张嘴阈值固定为 0.60",
                DEF_MOUTH_OPEN, t.getMouthOpenRatio(), EPS);
        // 个人基准归一化：完全睁眼恒映射到 ≈1.0，完全闭眼 ≈0.0（消除静态基准错配）
        assertTrue("完全睁眼 aperture 应归一化到 ≈1.0",
                cal.normalizeEye(EYE_OPEN) >= 1.0f - NORM_EPS);
        assertTrue("闭眼残差 aperture 应归一化到 ≈0.0",
                cal.normalizeEye(EYE_CLOSED) <= NORM_EPS);
        // 半睁（区间中点）应落在滞回区间内
        float mid = cal.normalizeEye((EYE_OPEN + EYE_CLOSED) / 2f);
        assertTrue("半睁 aperture 应归一化到 0.4~0.6（滞回区间内）",
                mid >= 0.4f && mid <= 0.6f);
        // 嘴巴同样个人归一化
        assertTrue("完全张嘴 MAR 应归一化到 ≈1.0",
                cal.normalizeMouth(MOUTH_OPEN) >= 1.0f - NORM_EPS);
        assertTrue("闭嘴残差 MAR 应归一化到 ≈0.0",
                cal.normalizeMouth(MOUTH_CLOSED) <= NORM_EPS);
    }

    @Test
    public void testHysteresisIntervalValid() {
        EyeMouthCalibrator cal = new EyeMouthCalibrator();
        feed(cal, mixedSamples(300));
        EyeMouthCalibrator.CalibratedThresholds t = cal.thresholds();
        assertTrue("眼睛滞回下界应 < 上界",
                t.getEyeCloseRatio() < t.getEyeOpenRatio());
        assertTrue("嘴巴滞回下界应 < 上界",
                t.getMouthCloseRatio() < t.getMouthOpenRatio());
    }

    @Test
    public void testResetRestoresDefault() {
        EyeMouthCalibrator cal = new EyeMouthCalibrator();
        // 先产生漂移（混合样本建立个人基准）
        feed(cal, mixedSamples(300));
        assertTrue("漂移后完全睁眼应映射到高位",
                cal.normalizeEye(EYE_OPEN) >= 1.0f - NORM_EPS);

        // 复位后阈值不变（恒为因子/固定值），归一化端点回默认
        cal.reset();
        EyeMouthCalibrator.CalibratedThresholds after = cal.thresholds();
        assertEquals(DEF_EYE_CLOSE, after.getEyeCloseRatio(), EPS);
        assertEquals(DEF_EYE_OPEN, after.getEyeOpenRatio(), EPS);
        assertEquals(DEF_MOUTH_CLOSE, after.getMouthCloseRatio(), EPS);
        assertEquals(DEF_MOUTH_OPEN, after.getMouthOpenRatio(), EPS);
        assertEquals(1.0f, cal.normalizeEye(DEF_EYE_OPEN_APERTURE), NORM_EPS);
        assertEquals(0.0f, cal.normalizeEye(DEF_EYE_CLOSE_APERTURE), NORM_EPS);
    }

    @Test
    public void testRecalibrateAfterReset() {
        EyeMouthCalibrator cal = new EyeMouthCalibrator();
        // 第一个驾驶员：常闭眼/闭嘴（区间向低位塌缩）
        for (int i = 0; i < 300; i++) {
            cal.update(EYE_CLOSED, MOUTH_CLOSED);
        }
        // 门信号复位（换驾驶员）
        cal.reset();
        // 第二个驾驶员：常睁眼/张嘴，重校后其高位应能被映射到 ≈1.0
        for (int i = 0; i < 300; i++) {
            cal.update(EYE_OPEN, MOUTH_OPEN);
        }
        assertTrue("换人重校后新的睁眼 aperture 应归一化到 ≈1.0",
                cal.normalizeEye(EYE_OPEN) >= 1.0f - NORM_EPS);
        assertTrue("换人重校后新的张嘴 MAR 应归一化到 ≈1.0",
                cal.normalizeMouth(MOUTH_OPEN) >= 1.0f - NORM_EPS);
    }

    @Test
    public void testEyesAndMouthTrackIndependently() {
        // 眼睛混合分布、嘴巴持续闭嘴：两轴基准应互不影响
        EyeMouthCalibrator cal = new EyeMouthCalibrator();
        for (int i = 0; i < 300; i++) {
            float eye = (i % 10 < 2) ? EYE_CLOSED : EYE_OPEN;
            cal.update(eye, MOUTH_CLOSED);
        }
        assertTrue("眼睛基准不受嘴巴影响：睁眼应归一化到 ≈1.0",
                cal.normalizeEye(EYE_OPEN) >= 1.0f - NORM_EPS);
        assertTrue("嘴巴基准独立跟踪：闭嘴残差应归一化到 ≈0.0",
                cal.normalizeMouth(MOUTH_CLOSED) <= NORM_EPS);
    }

    @Test
    public void testZeroAndMissingSamplesIgnored() {
        // v3.5 高危修复：区域缺失时估计器对 mar/aperture 输出 0（缺失哨兵），
        // 不得进入分位数窗口（否则低位基准被拉向 0，真实闭眼/闭嘴残差被"正常化"，
        // 闭眼候选永不成立 → 疲劳告警失效 / 张嘴无法解除）。
        EyeMouthCalibrator cal = new EyeMouthCalibrator();
        feed(cal, mixedSamples(300));
        assertTrue(cal.normalizeEye(EYE_OPEN) >= 1.0f - NORM_EPS);

        // 模拟口罩/遮挡/地标缺失：连续大量 0 帧
        for (int i = 0; i < 300; i++) {
            cal.update(0f, 0f);
        }
        // 0 帧被忽略：基准不被拉向 0，睁/闭眼、张/闭嘴归一化语义不变
        assertTrue("缺失帧不得拉低眼睛基准：睁眼仍映射到高位",
                cal.normalizeEye(EYE_OPEN) >= 1.0f - NORM_EPS);
        assertTrue("缺失帧不得拉低眼睛基准：闭眼残差仍映射到低位",
                cal.normalizeEye(EYE_CLOSED) <= NORM_EPS);
        assertTrue("缺失帧不得拉低嘴巴基准：闭嘴残差仍映射到低位",
                cal.normalizeMouth(MOUTH_CLOSED) <= NORM_EPS);
        // 阈值仍为因子位置/固定值（校准未失效）
        EyeMouthCalibrator.CalibratedThresholds t = cal.thresholds();
        assertEquals(DEF_EYE_CLOSE, t.getEyeCloseRatio(), EPS);
        assertEquals(DEF_EYE_OPEN, t.getEyeOpenRatio(), EPS);
        assertEquals(DEF_MOUTH_CLOSE, t.getMouthCloseRatio(), EPS);
        assertEquals(DEF_MOUTH_OPEN, t.getMouthOpenRatio(), EPS);
    }

    @Test
    public void testNanSamplesIgnored() {
        // v3.5 高危修复：NaN（几何异常）不得进窗口，否则 EWMA 混合 NaN 恒为 NaN、
        // 基准永久损坏，状态机所有比较恒 false、状态卡死。
        EyeMouthCalibrator cal = new EyeMouthCalibrator();
        feed(cal, mixedSamples(300));
        assertTrue(cal.normalizeEye(EYE_OPEN) >= 1.0f - NORM_EPS);

        for (int i = 0; i < 100; i++) {
            cal.update(Float.NaN, Float.NaN);
        }
        assertTrue("NaN 帧不得污染眼睛基准",
                cal.normalizeEye(EYE_OPEN) >= 1.0f - NORM_EPS);
        assertTrue("NaN 帧不得污染嘴巴基准",
                cal.normalizeMouth(MOUTH_CLOSED) <= NORM_EPS);
        assertTrue("NaN 帧后阈值仍为因子位置",
                cal.thresholds().getEyeOpenRatio() > 0.5f);
    }

    @Test
    public void testFallbackFramesDoNotCorruptEyeAxis() {
        // v3.5 高危修复：外眼角缺失时估计器把 aperture 回退为经典 EAR（量纲不同，≈0.2~0.5），
        // 调用层用 faceWidth>0 拦截、不回退帧不喂眼睛轴（分轴更新）。这里验证：
        // 眼睛轴只吃主路径 aperture 帧时，嘴巴轴仍独立正常校准（两轴互不拖累）。
        EyeMouthCalibrator cal = new EyeMouthCalibrator();
        for (int i = 0; i < 300; i++) {
            boolean eyeFallback = (i % 10 < 3); // 30% 帧外眼角缺失
            if (eyeFallback) {
                // 眼睛回退：只喂嘴巴（嘴巴数据仍有效）
                cal.updateMouth((i % 2 == 0) ? MOUTH_OPEN : MOUTH_CLOSED);
            } else {
                cal.update(EYE_OPEN, MOUTH_OPEN);
            }
        }
        assertTrue("眼睛基准只由主路径 aperture 建立",
                cal.normalizeEye(EYE_OPEN) >= 1.0f - NORM_EPS);
        assertTrue("嘴巴轴不被回退帧拖累",
                cal.normalizeMouth(MOUTH_OPEN) >= 1.0f - NORM_EPS);
        assertTrue("嘴巴轴仍跟踪张嘴/闭嘴双态",
                cal.normalizeMouth(MOUTH_CLOSED) <= NORM_EPS);
    }

    @Test
    public void testCustomParams() {
        EyeMouthCalibrator cal = new EyeMouthCalibrator(
                100, 0.1f, 0.8f, 0.2f, 0.3f, 0.6f);
        feed(cal, mixedSamples(100));
        EyeMouthCalibrator.CalibratedThresholds t = cal.thresholds();
        // 自定义因子：下界 0.3、上界 0.6（仅影响眼睛轴）
        assertEquals("自定义下界因子应生效",
                0.3f, t.getEyeCloseRatio(), EPS);
        assertEquals("自定义上界因子应生效",
                0.6f, t.getEyeOpenRatio(), EPS);
        assertTrue(t.getEyeCloseRatio() < t.getEyeOpenRatio());
        // 嘴巴阈值恒为固定值，不随自定义因子变化
        assertEquals("嘴巴闭嘴阈值恒固定 0.35",
                DEF_MOUTH_CLOSE, t.getMouthCloseRatio(), EPS);
        assertEquals("嘴巴张嘴阈值恒固定 0.60",
                DEF_MOUTH_OPEN, t.getMouthOpenRatio(), EPS);
    }
}
