/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.fatigue

import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 疲劳检测规则加载器（FACEP-015）。
 *
 * 从 `fatigue_rules.json` 文本解析 [FatigueRule]，所有字段用 `optXxx(默认值)` 容错：
 * 字段缺失 / 类型错误 / JSON 损坏时回退默认值，**不崩溃**，并记录日志便于排查"配置未生效"。
 *
 * 解析逻辑依赖 `org.json`（Android 内置 / JVM 版 `org.json:json` API 一致），
 * 纯 JVM 单测可测解析正确性（隐患 E 修复）。
 */
object FatigueRuleLoader {

    private val logger = Logger.getLogger("FatigueRuleLoader")

    /** 从 JSON 文本解析；null / 损坏 / 解析异常时返回默认规则并记日志。 */
    fun load(jsonText: String?): FatigueRule {
        if (jsonText.isNullOrBlank()) {
            logger.warning("fatigue_rules json is null/blank, use default rule")
            return FatigueRule()
        }
        return try {
            parse(JSONObject(jsonText))
        } catch (e: Exception) {
            logger.log(Level.WARNING, "fatigue_rules json parse failed, use default rule", e)
            FatigueRule()
        }
    }

    /** 从文件解析；文件不存在 / 读取失败 / 解析异常时返回默认规则。 */
    fun loadFromFile(file: File): FatigueRule {
        if (!file.exists()) {
            logger.warning("fatigue_rules file not exists: ${file.path}, use default rule")
            return FatigueRule()
        }
        return try {
            load(file.readText())
        } catch (e: Exception) {
            logger.log(Level.WARNING, "fatigue_rules file read failed, use default rule", e)
            FatigueRule()
        }
    }

    /** 从 Android assets 的 `fatigue_rules.json` 解析；读取失败 / 解析异常时返回默认规则。 */
    fun loadFromAssets(context: android.content.Context): FatigueRule {
        return try {
            val text = context.assets.open("fatigue_rules.json").bufferedReader().use { it.readText() }
            load(text)
        } catch (e: Exception) {
            FatigueRule()
        }
    }

    private fun parse(root: JSONObject): FatigueRule {
        val schemaVersion = root.optInt("schema_version", 1)

        // 打哈欠基础条件
        val yawn = parseYawn(root.optJSONObject("yawn"))
        // 闭眼基础条件
        val eyeClose = parseEyeClose(root.optJSONObject("eye_close"))
        // 三级规则
        val levels = parseLevels(root.optJSONArray("levels"))
        // 无人脸复位
        val noFace = parseNoFace(root.optJSONObject("no_face"))

        return FatigueRule(
            schemaVersion = schemaVersion,
            yawn = yawn,
            eyeClose = eyeClose,
            levels = if (levels.isNotEmpty()) levels else FatigueRule.DEFAULT_LEVELS,
            noFace = noFace
        )
    }

    private fun parseYawn(o: JSONObject?): FatigueRule.YawnRule {
        if (o == null) return FatigueRule.YawnRule()
        return FatigueRule.YawnRule(
            enabled = o.optBoolean("enabled", true),
            mouthOpenRatio = o.optDouble("mouth_open_ratio", FatigueRule.YawnRule.DEFAULT_MOUTH_OPEN_RATIO.toDouble())
                .toFloat(),
            minDurationMs = o.optLong("min_duration_ms", FatigueRule.YawnRule.DEFAULT_MIN_DURATION_MS),
            minOpenRatio = o.optDouble("min_open_ratio", FatigueRule.YawnRule.DEFAULT_MIN_OPEN_RATIO.toDouble())
                .toFloat()
        )
    }

    private fun parseEyeClose(o: JSONObject?): FatigueRule.EyeCloseRule {
        if (o == null) return FatigueRule.EyeCloseRule()
        return FatigueRule.EyeCloseRule(
            enabled = o.optBoolean("enabled", true),
            minCountDurationMs = o.optLong(
                "min_count_duration_ms",
                FatigueRule.EyeCloseRule.DEFAULT_MIN_COUNT_DURATION_MS
            )
        )
    }

    private fun parseLevels(a: JSONArray?): List<FatigueRule.LevelRule> {
        if (a == null || a.length() == 0) return emptyList()
        val result = mutableListOf<FatigueRule.LevelRule>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            result.add(parseLevel(o))
        }
        return result
    }

    private fun parseLevel(o: JSONObject): FatigueRule.LevelRule {
        val levelName = o.optString("level")
        val level = runCatching { FatigueRule.Level.valueOf(levelName) }.getOrNull()
            ?: return FatigueRule.LevelRule(level = FatigueRule.Level.LIGHT, enter = emptyList(),
                exit = FatigueRule.Condition.CleanClear(20_000L, 750L))
        val enter = parseEnter(o.optJSONArray("enter"))
        val exit = parseExit(o.optJSONObject("exit"))
        return FatigueRule.LevelRule(
            level = level,
            enabled = o.optBoolean("enabled", true),
            enter = enter,
            exit = exit
        )
    }

    private fun parseEnter(a: JSONArray?): List<FatigueRule.Condition> {
        if (a == null) return emptyList()
        val result = mutableListOf<FatigueRule.Condition>()
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            val cond = parseCondition(c) ?: continue
            result.add(cond)
        }
        return result
    }

    /** 解析单个条件；未知 type 返回 null（跳过）。 */
    private fun parseCondition(c: JSONObject): FatigueRule.Condition? {
        return when (c.optString("type")) {
            "eye_close_count" -> FatigueRule.Condition.EyeCloseCount(
                windowMs = c.optLong("window_ms", 60_000L),
                countMin = c.optInt("count_min", 1),
                minDurationMs = c.optLong("min_duration_ms", 200L)
            )
            "yawn_count" -> FatigueRule.Condition.YawnCount(
                windowMs = c.optLong("window_ms", 60_000L),
                countMin = c.optInt("count_min", 1)
            )
            "eye_close_duration" -> FatigueRule.Condition.EyeCloseDuration(
                minMs = c.optLong("min_ms", 0L),
                maxMs = if (c.has("max_ms")) c.optLong("max_ms") else null
            )
            "clean_clear" -> FatigueRule.Condition.CleanClear(
                windowMs = c.optLong("window_ms", 20_000L),
                clearDurationMs = c.optLong("clear_duration_ms", 750L)
            )
            else -> null
        }
    }

    private fun parseExit(o: JSONObject?): FatigueRule.Condition {
        return parseCondition(o ?: JSONObject()) ?: FatigueRule.Condition.CleanClear(20_000L, 750L)
    }

    private fun parseNoFace(o: JSONObject?): FatigueRule.NoFaceRule {
        if (o == null) return FatigueRule.NoFaceRule()
        return FatigueRule.NoFaceRule(
            enabled = o.optBoolean("enabled", true),
            resetMs = o.optLong("reset_ms", FatigueRule.NoFaceRule.DEFAULT_RESET_MS)
        )
    }
}
