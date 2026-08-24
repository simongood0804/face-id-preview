package com.skyworth.faceid.algorithm;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * EyeMouthStateEstimator（单帧几何判定器）单元测试（提案 FACEP-010 §3.5/§3.6）。
 *
 * 验证：
 * - 睁眼：eyeOpenRatio ≈ 1.0
 * - 闭眼（含上下睑残差）：eyeOpenRatio ≈ 0.0
 * - 张嘴：mouthOpenRatio ≈ 1.0
 * - 闭嘴：mouthOpenRatio ≈ 0.0
 * - 阈值边界 / 无效输入 / 数据缺失回退
 */
public class EyeMouthStateEstimatorTest {

    private EyeMouthStateEstimator mEstimator;

    /** 默认参考量：睁眼 aperture（睑距/脸宽）=0.10、闭眼残差=0.02；张嘴 MAR=0.5、闭嘴残差 MAR=0.05。 */
    @Before
    public void setUp() {
        mEstimator = new EyeMouthStateEstimator();
    }

    /** 构造 68 点坐标（展平 FloatArray）。仅设置眼睛/嘴巴相关点。 */
    private float[] landmarks(int eyeGap, int mouthGap) {
        float[] lm = new float[136];
        // 眼睛：眼宽 100（外眼角 x=0、内眼角 x=100），上睑 y=10，下睑 y=10+eyeGap
        // 左眼（观察者左侧，300W 36~41）：眼尾 36、眼角 39；上睑 37/38、下睑 41/40
        set(lm, 36, 0f, 10f); set(lm, 39, 100f, 10f);
        set(lm, 37, 25f, 10f); set(lm, 38, 75f, 10f);
        set(lm, 41, 25f, 10f + eyeGap); set(lm, 40, 75f, 10f + eyeGap);
        // 右眼（观察者右侧，300W 42~47）：眼尾 45、眼角 42；上睑 43/44、下睑 47/46
        set(lm, 45, 100f, 10f); set(lm, 42, 0f, 10f);
        set(lm, 43, 25f, 10f); set(lm, 44, 75f, 10f);
        set(lm, 47, 25f, 10f + eyeGap); set(lm, 46, 75f, 10f + eyeGap);
        // 嘴巴：嘴角 x=0/80，上唇中央 y=20，下唇中央 y=20+mouthGap
        set(lm, 48, 0f, 20f); set(lm, 54, 80f, 20f);
        set(lm, 51, 40f, 20f);
        set(lm, 57, 40f, 20f + mouthGap);
        return lm;
    }

    private void set(float[] lm, int idx, float x, float y) {
        lm[idx * 2] = x;
        lm[idx * 2 + 1] = y;
    }

    private static final float EPS = 0.01f;

    // ---- 睁眼 / 闭眼（眼睛） ----

    @Test
    public void testEyeOpen() {
        // 睁眼：上下睑纵向 30 → aperture=30/脸宽100=0.30 ≥ 参考 0.10 → ratio=1.0
        EyeMouthStateEstimator.EyeMouthEstimate r =
                mEstimator.estimate(landmarks(30, 2));
        assertTrue(r.getValid());
        assertEquals("睁眼开合度应≈1.0", 1.0f, r.getEyeOpenRatio(), EPS);
    }

    @Test
    public void testEyeClosedWithResidual() {
        // 闭眼：上下睑纵向 2（残差不完全闭合）→ aperture=0.02 ≤ 闭眼基线 0.02 → ratio=0.0
        EyeMouthStateEstimator.EyeMouthEstimate r =
                mEstimator.estimate(landmarks(2, 2));
        assertTrue(r.getValid());
        assertEquals("闭眼开合度应≈0.0（含残差）", 0.0f, r.getEyeOpenRatio(), EPS);
    }

    // ---- 张嘴 / 闭嘴（嘴巴） ----

    @Test
    public void testMouthOpen() {
        // 张嘴：上下唇纵向 50 → MAR=0.625 ≥ 张嘴基准 0.62 → ratio≈1.0
        EyeMouthStateEstimator.EyeMouthEstimate r =
                mEstimator.estimate(landmarks(30, 50));
        assertTrue(r.getValid());
        assertEquals("张嘴开合度应≈1.0", 1.0f, r.getMouthOpenRatio(), EPS);
    }

    @Test
    public void testMouthClosed() {
        // 闭嘴：上下唇纵向 2 → MAR=0.025 ≤ 闭嘴基线 0.05 → ratio=0.0
        EyeMouthStateEstimator.EyeMouthEstimate r =
                mEstimator.estimate(landmarks(30, 2));
        assertTrue(r.getValid());
        assertEquals("闭嘴开合度应≈0.0", 0.0f, r.getMouthOpenRatio(), EPS);
    }

    // ---- 组合四态 ----

    @Test
    public void testEyeOpenMouthOpen() {
        EyeMouthStateEstimator.EyeMouthEstimate r =
                mEstimator.estimate(landmarks(30, 50));
        assertEquals(1.0f, r.getEyeOpenRatio(), EPS);
        assertEquals(1.0f, r.getMouthOpenRatio(), EPS);
    }

    @Test
    public void testEyeClosedMouthClosed() {
        EyeMouthStateEstimator.EyeMouthEstimate r =
                mEstimator.estimate(landmarks(2, 2));
        assertEquals(0.0f, r.getEyeOpenRatio(), EPS);
        assertEquals(0.0f, r.getMouthOpenRatio(), EPS);
    }

    // ---- 阈值边界：睁闭眼中间态（开合度应落在 0~1 之间） ----

    @Test
    public void testEyeSemiOpenRatioBetween() {
        // 上下睑纵向 6 → aperture=6/脸宽100=0.06
        // ratio = (0.06-0.02)/(0.10-0.02) = 0.5
        EyeMouthStateEstimator.EyeMouthEstimate r =
                mEstimator.estimate(landmarks(6, 2));
        assertEquals("半睁眼开合度应≈0.5", 0.5f, r.getEyeOpenRatio(), 0.03f);
    }

    @Test
    public void testEyeRatioClamped() {
        // 超睁眼：纵向 100 → aperture=1.0 >> 参考，应截断为 1.0
        EyeMouthStateEstimator.EyeMouthEstimate r =
                mEstimator.estimate(landmarks(100, 2));
        assertEquals("开合度应截断到 1.0", 1.0f, r.getEyeOpenRatio(), EPS);
    }

    // ---- 无效输入 ----

    @Test
    public void testTooShortInputInvalid() {
        float[] shortLm = new float[100];
        EyeMouthStateEstimator.EyeMouthEstimate r = mEstimator.estimate(shortLm);
        assertFalse("长度不足 136 应无效", r.getValid());
        assertEquals("无效时睁眼默认 1.0", 1.0f, r.getEyeOpenRatio(), EPS);
        assertEquals("无效时闭嘴默认 0.0", 0.0f, r.getMouthOpenRatio(), EPS);
    }

    @Test
    public void testNullMappingRegionInvalid() {
        // 自定义映射：仅左眼，缺右眼 → 应回退到左眼 EAR，仍有效
        java.util.Map<LandmarkRegion, int[]> custom = new java.util.HashMap<>();
        custom.put(LandmarkRegion.LEFT_EYE_UPPER_LID, new int[]{37, 38});
        custom.put(LandmarkRegion.LEFT_EYE_LOWER_LID, new int[]{41, 40});
        custom.put(LandmarkRegion.LEFT_EYE_OUTER_CANTHUS, new int[]{36});
        custom.put(LandmarkRegion.LEFT_EYE_INNER_CANTHUS, new int[]{39});
        custom.put(LandmarkRegion.MOUTH_UPPER_LIP, new int[]{51});
        custom.put(LandmarkRegion.MOUTH_LOWER_LIP, new int[]{57});
        custom.put(LandmarkRegion.MOUTH_LEFT_CORNER, new int[]{48});
        custom.put(LandmarkRegion.MOUTH_RIGHT_CORNER, new int[]{54});
        EyeMouthStateEstimator est = new EyeMouthStateEstimator(
                new LandmarkIndexMapping(custom));
        EyeMouthStateEstimator.EyeMouthEstimate r = est.estimate(landmarks(30, 40));
        assertTrue("缺右眼应回退左眼并有效", r.getValid());
        assertEquals("仅左眼睁眼开合度应≈1.0", 1.0f, r.getEyeOpenRatio(), EPS);
    }

    // ---- 自定义参考量 ----

    @Test
    public void testCustomReferences() {
        // 参考睁眼 aperture=0.3、闭眼残差 0.1：纵向 30 → aperture=30/100=0.3 → ratio=1.0
        EyeMouthStateEstimator est = new EyeMouthStateEstimator(
                new LandmarkIndexMapping(), 0.3f, 0.1f, 0.5f, 0.05f);
        EyeMouthStateEstimator.EyeMouthEstimate r = est.estimate(landmarks(30, 2));
        assertEquals("自定义参考下睁眼开合度应≈1.0", 1.0f, r.getEyeOpenRatio(), EPS);
    }
}
