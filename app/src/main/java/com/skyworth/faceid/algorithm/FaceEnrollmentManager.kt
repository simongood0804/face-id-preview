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
    private val MATCH_THRESHOLD = 0.40f

    /** 最佳匹配与次优匹配的最小间隔，防止身份来回跳。 */
    private val MATCH_MARGIN = 0.05f

    /** 自动录入所需的最低检测置信度。 */
    private val ENROLL_CONFIDENCE = 0.65f

    /** 自动录入前需要连续检测到人脸的帧数。 */
    private val ENROLL_CONSECUTIVE_FRAMES = 10

    /** 录入冷却帧数（录入成功后暂停自动录入）。 */
    private val ENROLL_COOLDOWN_FRAMES = 60

    /** 连续检测到稳定人脸的帧数计数器。 */
    private var mStableFrames = 0

    /** 录入冷却计数器。 */
    private var mCooldown = 0

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
     *
     * @param emb       512-D 特征向量
     * @param score     检测置信度
     * @param liveness  活体分数
     */
    fun recognize(emb: FloatArray, score: Float, liveness: Float): RecognitionResult {
        if (emb.size != 512) return RecognitionResult(null, false)

        // 冷却计数器递减
        if (mCooldown > 0) mCooldown--

        // 1. 尝试匹配已录入的人（不受冷却和稳定帧约束）
        if (mGallery.isNotEmpty()) {
            var bestName: String? = null
            var bestSim = -1f
            var secondSim = -1f
            for ((name, stored) in mGallery) {
                val sim = mAlgorithm.compare(stored, emb)
                if (sim > bestSim) {
                    secondSim = bestSim
                    bestSim = sim
                    bestName = name
                } else if (sim > secondSim) {
                    secondSim = sim
                }
            }
            if (bestName != null && bestSim >= MATCH_THRESHOLD) {
                mStableFrames = 0
                if (mGallery.size <= 1 || bestSim - secondSim >= MATCH_MARGIN) {
                    return RecognitionResult(bestName, false)
                }
                Log.d(TAG, "reject: best=$bestName sim=${String.format("%.3f", bestSim)} " +
                        "second=${String.format("%.3f", secondSim)} margin=${String.format("%.3f", bestSim - secondSim)}")
            }
        }

        // 2. 自动录入前检查：冷却中
        if (mCooldown > 0) return RecognitionResult(null, false)

        // 3. 自动录入前检查：置信度、活体、稳定帧
        if (score >= ENROLL_CONFIDENCE && (liveness < 0f || liveness > 0.5f)) {
            mStableFrames++
            if (mStableFrames < ENROLL_CONSECUTIVE_FRAMES) {
                Log.d(TAG, "enroll pending: ${mStableFrames}/${ENROLL_CONSECUTIVE_FRAMES}")
                return RecognitionResult(null, false)
            }
            val name = pickName()
            enroll(name, emb)
            mStableFrames = 0
            mCooldown = ENROLL_COOLDOWN_FRAMES
            return RecognitionResult(name, true)
        } else {
            mStableFrames = 0
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
