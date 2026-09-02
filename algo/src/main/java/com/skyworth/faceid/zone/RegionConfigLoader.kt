/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.zone

import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 区域（region）配置加载器（FACEP-016）。
 *
 * 从 JSON 文本解析 `regions` 数组 → [List]<[RegionConfig]>。参照 faceid_sdk 的
 * `LoadZonesFromJson` 与项目现有 `FatigueRuleLoader`（FACEP-015），用 `org.json`
 * 容错解析：字段缺失 / 类型错误 / JSON 损坏时回退默认区域，**不崩溃**，并记日志。
 *
 * 每个 region 由 **4 个点**（`points`，四边形）定义。points 缺失或 < 3 点 → 跳过该 region。
 */
object RegionConfigLoader {

    private val logger = Logger.getLogger("RegionConfigLoader")

    /** 从 JSON 文本解析 regions；null/损坏/解析异常时返回默认区域并记日志。 */
    fun load(jsonText: String?): List<RegionConfig> {
        if (jsonText.isNullOrBlank()) {
            logger.warning("regions json is null/blank, use default regions")
            return RegionConfig.DEFAULT_REGIONS
        }
        return try {
            val root = JSONObject(jsonText)
            val regions = parseRegions(root.optJSONArray("regions"))
            val result = regions.takeIf { it.isNotEmpty() } ?: RegionConfig.DEFAULT_REGIONS
            warnOnOverlap(result)   // 隐患修复：检测区域重叠，提示配置问题
            return result
        } catch (e: Exception) {
            logger.log(Level.WARNING, "regions json parse failed, use default regions", e)
            return RegionConfig.DEFAULT_REGIONS
        }
    }

    /**
     * 隐患修复（FACEP-016）：检测区域两两重叠并记警告日志。
     *
     * 区域重叠会导致 `contains` 分心优先误判。重叠常由**示意坐标未对齐**或配置错误引起，
     * 此处不自动改坐标（真实区域需算法方提供），仅记录警告便于排查。
     */
    private fun warnOnOverlap(regions: List<RegionConfig>) {
        for (i in regions.indices) {
            for (j in i + 1 until regions.size) {
                val a = regions[i]
                val b = regions[j]
                // 用顶点落在对方区域内近似判定重叠（足够提示配置问题）
                val overlap = a.points.any { b.contains(it.x, it.z) } ||
                    b.points.any { a.contains(it.x, it.z) }
                if (overlap) {
                    logger.warning("region overlap: '${a.name}'(id=${a.id}) overlaps '${b.name}'(id=${b.id})")
                }
            }
        }
    }

    /** 从文件解析；文件不存在/读取失败/解析异常时返回默认区域。 */
    fun loadFromFile(file: File): List<RegionConfig> {
        if (!file.exists()) {
            logger.warning("regions file not exists: ${file.path}, use default regions")
            return RegionConfig.DEFAULT_REGIONS
        }
        return try {
            load(file.readText())
        } catch (e: Exception) {
            logger.log(Level.WARNING, "regions file read failed, use default regions", e)
            RegionConfig.DEFAULT_REGIONS
        }
    }

    /** 从 Android assets 的指定路径解析；读取失败/解析异常时返回默认区域。 */
    fun loadFromAssets(
        context: android.content.Context,
        assetPath: String = "zone_regions.json"
    ): List<RegionConfig> {
        return try {
            val text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            load(text)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "regions assets read failed: $assetPath, use default regions", e)
            RegionConfig.DEFAULT_REGIONS
        }
    }

    private fun parseRegions(a: JSONArray?): List<RegionConfig> {
        if (a == null || a.length() == 0) return emptyList()
        val result = mutableListOf<RegionConfig>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val r = parseRegion(o) ?: continue
            result.add(r)
        }
        return result
    }

    /** 解析单个 region；字段缺失（无 id/name）或 points < 3 时返回 null（跳过）。 */
    private fun parseRegion(o: JSONObject): RegionConfig? {
        val id = o.optInt("id", -1)
        val name = o.optString("name")
        if (id < 0 || name.isEmpty()) return null

        val points = parsePoints(o.optJSONArray("points"))
        if (points.size < 3) return null

        val isDistraction = o.optBoolean("is_distraction", false)
        return RegionConfig(id = id, name = name, points = points, isDistraction = isDistraction)
    }

    /** 解析 points 数组（每个元素含 x/z）。 */
    private fun parsePoints(a: JSONArray?): List<Point2D> {
        if (a == null || a.length() == 0) return emptyList()
        val out = mutableListOf<Point2D>()
        for (i in 0 until a.length()) {
            val p = a.optJSONObject(i) ?: continue
            out.add(Point2D(
                x = p.optDouble("x", 0.0).toFloat(),
                z = p.optDouble("z", 0.0).toFloat()
            ))
        }
        return out
    }
}
