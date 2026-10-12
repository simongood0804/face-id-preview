/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.zone

/**
 * xz 平面上的一个点（mm），用于定义区域四边形顶点。
 *
 * 世界坐标系（算法方约定）：`X`=右、`Y`=前、`Z`=上。`(x, z)` 是视线落点在
 * xz 平面（y=0）上的坐标。
 */
data class Point2D(
    val x: Float,
    val z: Float
)

/**
 * 可配置的 xz 平面四边形区域（FACEP-016）。
 *
 * 算法方标准：每个区域由 **4 个点绘制一个四边形** 定义，判断视线落点 `(x, z)`
 * 是否落在四边形内。区域是驾驶舱内驾驶员头朝向可能落向的目标区域，用
 * [isDistraction] 标记是否为分心区。
 *
 * 纯 JVM 数据类，无 Android 依赖，便于单测。
 */
data class RegionConfig(
    /** 区域唯一标识（0 通常为前向/专注区）。 */
    val id: Int,
    /** 区域名称（如 forward / addw_drv_left_knee）。 */
    val name: String,
    /** 四边形 4 个顶点 (x, z)（mm），按顺序（建议逆时针）围成四边形。 */
    val points: List<Point2D>,
    /** 是否分心区。 */
    val isDistraction: Boolean
) {
    /**
     * 判断落点 (x, z) 是否落在四边形内（射线法 point-in-polygon）。
     *
     * 支持任意凸/凹四边形，射线从点向右延伸，与边相交次数为奇数则在内部。
     * 四边形顶点建议按逆时针（或统一顺时针）给出，避免自交导致判定异常。
     */
    fun contains(x: Float, z: Float): Boolean {
        val n = points.size
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val pi = points[i]
            val pj = points[j]
            if ((pi.z > z) != (pj.z > z) &&
                x < (pj.x - pi.x) * (z - pi.z) / (pj.z - pi.z) + pi.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    companion object {
        /**
         * 默认区域定义（FACEP-016）。
         *
         * ⚠️ 坐标为**示意值**（xz 平面，mm），真实区域坐标需算法方提供（提案 §6）。
         * 共 15 个区域：1 个前向专注区（forward）+ 14 个 ADDW 分心区。
         * 四边形顶点按逆时针给出。
         */
        val DEFAULT_REGIONS: List<RegionConfig> = listOf(
            // 0 前向（专注）—— xz 平面中央
            RegionConfig(0, "forward",
                listOf(Point2D(-300f, 900f), Point2D(300f, 900f),
                    Point2D(300f, 1300f), Point2D(-300f, 1300f)), false),
            // 1 驾驶员左膝 —— 左下
            RegionConfig(1, "addw_drv_left_knee",
                listOf(Point2D(-500f, -100f), Point2D(-200f, -100f),
                    Point2D(-200f, 300f), Point2D(-500f, 300f)), true),
            // 2 驾驶员右膝 —— 右
            RegionConfig(2, "addw_drv_right_knee",
                listOf(Point2D(200f, -100f), Point2D(500f, -100f),
                    Point2D(500f, 300f), Point2D(200f, 300f)), true),
            // 3 驾驶员腰带 —— 中央偏下
            RegionConfig(3, "addw_drv_belt",
                listOf(Point2D(-200f, -100f), Point2D(200f, -100f),
                    Point2D(200f, 300f), Point2D(-200f, 300f)), true),
            // 4 乘客脚部空间 —— 左远下
            RegionConfig(4, "addw_pass_footwell",
                listOf(Point2D(-800f, -200f), Point2D(-500f, -200f),
                    Point2D(-500f, 200f), Point2D(-800f, 200f)), true),
            // 5 乘客座椅表面 —— 左远
            RegionConfig(5, "addw_pass_seat",
                listOf(Point2D(-800f, 400f), Point2D(-500f, 400f),
                    Point2D(-500f, 800f), Point2D(-800f, 800f)), true),
            // 6 手套箱 —— 左远偏上
            RegionConfig(6, "addw_glovebox",
                listOf(Point2D(-800f, 800f), Point2D(-500f, 800f),
                    Point2D(-500f, 1200f), Point2D(-800f, 1200f)), true),
            // 7 驾驶员左通风口 —— 左中
            RegionConfig(7, "addw_drv_left_vent",
                listOf(Point2D(-400f, 400f), Point2D(-100f, 400f),
                    Point2D(-100f, 700f), Point2D(-400f, 700f)), true),
            // 8 驾驶员右通风口 —— 右中
            RegionConfig(8, "addw_drv_right_vent",
                listOf(Point2D(100f, 400f), Point2D(400f, 400f),
                    Point2D(400f, 700f), Point2D(100f, 700f)), true),
            // 9 仪表盘 —— 中央
            RegionConfig(9, "addw_dashboard",
                listOf(Point2D(-200f, 300f), Point2D(200f, 300f),
                    Point2D(200f, 700f), Point2D(-200f, 700f)), true),
            // 10 方向盘 —— 中央偏下
            RegionConfig(10, "addw_steering_wheel",
                listOf(Point2D(-200f, -100f), Point2D(200f, -100f),
                    Point2D(200f, 400f), Point2D(-200f, 400f)), true),
            // 11 换挡杆 —— 右下
            RegionConfig(11, "addw_gear_selector",
                listOf(Point2D(300f, 300f), Point2D(600f, 300f),
                    Point2D(600f, 700f), Point2D(300f, 700f)), true),
            // 12 HVAC 控制 —— 右中偏上
            RegionConfig(12, "addw_hvac",
                listOf(Point2D(300f, 700f), Point2D(600f, 700f),
                    Point2D(600f, 1000f), Point2D(300f, 1000f)), true),
            // 13 信息娱乐屏 —— 右
            RegionConfig(13, "addw_infotainment",
                listOf(Point2D(300f, 400f), Point2D(700f, 400f),
                    Point2D(700f, 800f), Point2D(300f, 800f)), true),
            // 14 中控台 —— 右偏下
            RegionConfig(14, "addw_center_console",
                listOf(Point2D(200f, 300f), Point2D(500f, 300f),
                    Point2D(500f, 700f), Point2D(200f, 700f)), true)
        )
    }
}
