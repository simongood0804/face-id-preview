package com.skyworth.faceid.algorithm;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * LandmarkIndexMapping（语义区域 → 106 点索引映射）单元测试。
 *
 * 阶段一目标：验证映射定义的正确性与一致性（提案 FACEP-010 §2.4）。
 * - 所有语义区域均已配置
 * - 索引均在 106 点有效范围 [0, 105]
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

    // ---- 索引均在有效范围 [0, 105] ----

    @Test
    public void testAllIndicesWithinRange() {
        for (LandmarkRegion r : LandmarkRegion.values()) {
            for (int i : idx(r)) {
                assertTrue(r + " 索引 " + i + " 超出范围",
                        i >= 0 && i <= 105);
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
        // 左眼（观察者视角）：眼尾 35、眼角 39
        assertArrayEquals("左眼眼尾", new int[]{35},
                idx(LandmarkRegion.LEFT_EYE_OUTER_CANTHUS));
        assertArrayEquals("左眼眼角", new int[]{39},
                idx(LandmarkRegion.LEFT_EYE_INNER_CANTHUS));
    }

    @Test
    public void testRightEyeCanthus() {
        // 右眼（观察者视角）：眼尾 93、眼角 89
        assertArrayEquals("右眼眼尾", new int[]{93},
                idx(LandmarkRegion.RIGHT_EYE_OUTER_CANTHUS));
        assertArrayEquals("右眼眼角", new int[]{89},
                idx(LandmarkRegion.RIGHT_EYE_INNER_CANTHUS));
    }

    @Test
    public void testLeftEyeLidPoints() {
        // 左眼：上眼睑 42/40/41，下眼睑 36/33/37
        assertArrayEquals("左眼上眼睑", new int[]{42, 40, 41},
                idx(LandmarkRegion.LEFT_EYE_UPPER_LID));
        assertArrayEquals("左眼下眼睑", new int[]{36, 33, 37},
                idx(LandmarkRegion.LEFT_EYE_LOWER_LID));
    }

    @Test
    public void testRightEyeLidPoints() {
        // 右眼：上眼睑 95/94/96，下眼睑 91/87/90
        assertArrayEquals("右眼上眼睑", new int[]{95, 94, 96},
                idx(LandmarkRegion.RIGHT_EYE_UPPER_LID));
        assertArrayEquals("右眼下眼睑", new int[]{91, 87, 90},
                idx(LandmarkRegion.RIGHT_EYE_LOWER_LID));
    }

    // ---- 嘴巴关键语义点 ----

    @Test
    public void testMouthCorners() {
        assertArrayEquals("左嘴角", new int[]{61},
                idx(LandmarkRegion.MOUTH_LEFT_CORNER));
        assertArrayEquals("右嘴角", new int[]{52},
                idx(LandmarkRegion.MOUTH_RIGHT_CORNER));
    }

    @Test
    public void testMouthLipCenters() {
        // 上唇中央 71（人中）、下唇中央 53
        assertTrue("上唇应包含人中 71",
                contains(idx(LandmarkRegion.MOUTH_UPPER_LIP), 71));
        assertTrue("下唇应包含中央 53",
                contains(idx(LandmarkRegion.MOUTH_LOWER_LIP), 53));
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
                .default106Mapping();
        assertEquals("默认映射应覆盖全部语义区域",
                LandmarkRegion.values().length, def.size());
    }
}
