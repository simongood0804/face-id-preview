package com.skyworth.faceid.algorithm

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * 人脸录入与识别管理器。
 *
 * - 自动录入：第一次见到的人脸自动分配山海经异兽名称
 * - 识别：与已录入的所有人比对，返回最匹配的名字
 * - 替换：所有名称用完后，替换最早录入的人
 * - 存储：JSON 文件持久化到应用内部存储
 */
class FaceEnrollmentManager(
    context: Context,
    private val mAlgorithm: FaceIDAlgorithmImpl
) {

    private val TAG = "FaceEnroll"

    private val mDbFile = File(context.filesDir, "face_enrollments.json")

    /** 已录入的人脸库，按录入顺序排列。 */
    private val mGallery = linkedMapOf<String, FloatArray>()

    /** 山海经异兽名称列表。 */
    private val SHENHAI_NAMES = listOf(
        "饕餮", "混沌", "穷奇", "梼杌", "麒麟",
        "白泽", "毕方", "重明鸟", "三足乌", "九尾狐",
        "乘黄", "当康", "讙", "狰", "蛊雕",
        "帝江", "朱雀", "玄武", "青龙", "白虎",
        "应龙", "烛龙", "夫诸", "冉遗鱼", "肥遗",
        "英招", "陆吾", "开明兽", "狴犴", "椒图",
        "嘲风", "螭吻", "囚牛", "睚眦", "霸下",
    )

    /** 匹配阈值（余弦相似度）。 */
    private val MATCH_THRESHOLD = 0.30f

    /** 自动录入所需的检测置信度。 */
    private val ENROLL_CONFIDENCE = 0.6f

    init {
        load()
    }

    // ============================================================
    // 公共 API
    // ============================================================

    data class RecognitionResult(
        val name: String?,
        val isNewEnroll: Boolean
    )

    /**
     * 识别或自动录入。
     * 名称用完时，替换最早录入的人。
     */
    fun recognize(emb: FloatArray, score: Float, liveness: Float): RecognitionResult {
        if (emb.size != 512) return RecognitionResult(null, false)

        // 1. 尝试匹配已录入的人
        if (mGallery.isNotEmpty()) {
            var bestName: String? = null
            var bestSim = -1f
            for ((name, stored) in mGallery) {
                val sim = mAlgorithm.compare(stored, emb)
                if (sim > bestSim) {
                    bestSim = sim
                    bestName = name
                }
            }
            if (bestName != null && bestSim >= MATCH_THRESHOLD) {
                return RecognitionResult(bestName, false)
            }
        }

        // 2. 没匹配到 → 自动录入（高置信度且非 spoof）
        if (score >= ENROLL_CONFIDENCE && (liveness < 0f || liveness > 0.5f)) {
            val name = pickName()
            enroll(name, emb)
            return RecognitionResult(name, true)
        }

        return RecognitionResult(null, false)
    }

    /** 录入（若名称已存在则覆盖）。 */
    fun enroll(name: String, emb: FloatArray) {
        mGallery.remove(name) // 移除旧位置
        mGallery[name] = emb.copyOf()
        save()
        Log.i(TAG, "enrolled: $name (total=${mGallery.size})")
    }

    fun getEnrolledNames(): Set<String> = mGallery.keys
    fun getCount(): Int = mGallery.size

    // ============================================================
    // 内部
    // ============================================================

    /** 选名：先用未使用的，用完后替换最早录入的。 */
    private fun pickName(): String {
        val used = mGallery.keys
        val available = SHENHAI_NAMES.firstOrNull { it !in used }
        if (available != null) return available
        // 全部用完了 → 替换最早录入的
        val oldest = mGallery.keys.first()
        Log.i(TAG, "all names used, replacing oldest: $oldest")
        return oldest
    }

    private fun load() {
        if (!mDbFile.exists()) return
        try {
            val text = mDbFile.readText()
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name")
                val embArr = obj.getJSONArray("emb")
                val emb = FloatArray(embArr.length()) { embArr.getDouble(it).toFloat() }
                mGallery[name] = emb
            }
            Log.i(TAG, "loaded ${mGallery.size} enrollments")
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
        }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            for ((name, emb) in mGallery) {
                val obj = JSONObject()
                obj.put("name", name)
                val embArr = JSONArray()
                for (v in emb) embArr.put(v.toDouble())
                obj.put("emb", embArr)
                arr.put(obj)
            }
            mDbFile.writeText(arr.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
        }
    }
}
