package com.skyworth.faceid.algorithm;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * LandmarkIndexMapping（语义区域 → 68 点索引映射）单元测试。
 *
 * 阶段一目标：验证映射定义的正确性与一致性（提案 FACEP-010 §2.4，PIPNet 68 点 / 300W 标准）。
 * - 所有语义区域均已配置
 * - 索引均在 68 点有效范围 [0, 67]
 * - 上下眼睑对应列数量一致（供 EAR 计算）
 * - 索引无重复
 * - 关键语义点（眼尾/眼角/嘴角/上下唇中央）正确
 * - 自定义映射可注入
 */
public class LandmarkIndexMappingTest {

    private final LandmarkIndexMapping mMapping = new LandmarkIndexMapping();

    private int[] idx(LandmarkRegion r) {
        return mMapping.indices(r);
    }

    private boolean contains(int[] arr, int value) {
        for (int v : arr) {
            if (v == value) return true;
        }
        return false;
    }

    // ---- 所有语义区域均已配置 ----

    @Test
    public void testAllRegionsConfigured() {
        for (LandmarkRegion r : LandmarkRegion.values()) {
            assertTrue("区域 " + r + " 应已配置", mMapping.hasRegion(r));
            assertTrue("区域 " + r + " 索引不应为空", idx(r).length > 0);
        }
    }

    // ---- 索引均在有效范围 [0, 67] ----

    @Test
    public void testAllIndicesWithinRange() {
        for (LandmarkRegion r : LandmarkRegion.values()) {
            for (int i : idx(r)) {
                assertTrue(r + " 索引 " + i + " 超出范围",
                        i >= 0 && i <= 67);
            }
        }
    }

    // ---- 上下眼睑对应列数量一致 ----

    @Test
    public void testEyeLidColumnCountsMatch() {
        assertEquals("左眼上/下眼睑点数应一致",
                idx(LandmarkRegion.LEFT_EYE_UPPER_LID).length,
                idx(LandmarkRegion.LEFT_EYE_LOWER_LID).length);
        assertEquals("右眼上/下眼睑点数应一致",
                idx(LandmarkRegion.RIGHT_EYE_UPPER_LID).length,
                idx(LandmarkRegion.RIGHT_EYE_LOWER_LID).length);
    }

    // ---- 索引无重复 ----

    @Test
    public void testNoDuplicateIndices() {
        Set<Integer> seen = new HashSet<>();
        for (LandmarkRegion r : LandmarkRegion.values()) {
            for (int i : idx(r)) {
                assertTrue("索引 " + i + " 在区域 " + r + " 中重复出现",
                        seen.add(i));
            }
        }
    }

    // ---- 眼睛关键语义点 ----

    @Test
    public void testLeftEyeCanthus() {
        // 左眼（观察者视角，300W right eye 36~41）：眼尾 36、眼角 39
        assertArrayEquals("左眼眼尾", new int[]{36},
                idx(LandmarkRegion.LEFT_EYE_OUTER_CANTHUS));
        assertArrayEquals("左眼眼角", new int[]{39},
                idx(LandmarkRegion.LEFT_EYE_INNER_CANTHUS));
    }

    @Test
    public void testRightEyeCanthus() {
        // 右眼（观察者视角，300W left eye 42~47）：眼尾 45、眼角 42
        assertArrayEquals("右眼眼尾", new int[]{45},
                idx(LandmarkRegion.RIGHT_EYE_OUTER_CANTHUS));
        assertArrayEquals("右眼眼角", new int[]{42},
                idx(LandmarkRegion.RIGHT_EYE_INNER_CANTHUS));
    }

    @Test
    public void testLeftEyeLidPoints() {
        // 左眼：上眼睑 37/38，下眼睑 41/40（列对应 37↔41、38↔40）
        assertArrayEquals("左眼上眼睑", new int[]{37, 38},
                idx(LandmarkRegion.LEFT_EYE_UPPER_LID));
        assertArrayEquals("左眼下眼睑", new int[]{41, 40},
                idx(LandmarkRegion.LEFT_EYE_LOWER_LID));
    }

    @Test
    public void testRightEyeLidPoints() {
        // 右眼：上眼睑 43/44，下眼睑 47/46（列对应 43↔47、44↔46）
        assertArrayEquals("右眼上眼睑", new int[]{43, 44},
                idx(LandmarkRegion.RIGHT_EYE_UPPER_LID));
        assertArrayEquals("右眼下眼睑", new int[]{47, 46},
                idx(LandmarkRegion.RIGHT_EYE_LOWER_LID));
    }

    // ---- 嘴巴关键语义点 ----

    @Test
    public void testMouthCorners() {
        assertArrayEquals("左嘴角", new int[]{48},
                idx(LandmarkRegion.MOUTH_LEFT_CORNER));
        assertArrayEquals("右嘴角", new int[]{54},
                idx(LandmarkRegion.MOUTH_RIGHT_CORNER));
    }

    @Test
    public void testMouthLipCenters() {
        // 上唇中央 51（人中）、下唇中央 57
        assertTrue("上唇应包含人中 51",
                contains(idx(LandmarkRegion.MOUTH_UPPER_LIP), 51));
        assertTrue("下唇应包含中央 57",
                contains(idx(LandmarkRegion.MOUTH_LOWER_LIP), 57));
    }

    // ---- 自定义映射可注入 ----

    @Test
    public void testCustomMappingInjection() {
        // 模拟更换算法后自定义映射：仅重新定义左眼上眼睑
        java.util.Map<LandmarkRegion, int[]> custom = new java.util.HashMap<>();
        custom.put(LandmarkRegion.LEFT_EYE_UPPER_LID, new int[]{1, 2, 3});
        LandmarkIndexMapping customMapping = new LandmarkIndexMapping(custom);

        assertArrayEquals("自定义映射生效", new int[]{1, 2, 3},
                customMapping.indices(LandmarkRegion.LEFT_EYE_UPPER_LID));
        assertFalse("未配置区域应 hasRegion=false",
                customMapping.hasRegion(LandmarkRegion.RIGHT_EYE_LOWER_LID));
        assertArrayEquals("未配置区域返回空数组", new int[0],
                customMapping.indices(LandmarkRegion.RIGHT_EYE_LOWER_LID));
    }

    // ---- 默认映射完整性 ----

    @Test
    public void testDefaultMappingContainsAllRegions() {
        java.util.Map<LandmarkRegion, int[]> def = LandmarkIndexMapping.Companion
                .default68Mapping();
        assertEquals("默认映射应覆盖全部语义区域",
                LandmarkRegion.values().length, def.size());
    }
}
