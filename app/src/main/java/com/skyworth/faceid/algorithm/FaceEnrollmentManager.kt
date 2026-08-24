package com.skyworth.faceid.algorithm

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 人脸录入与识别管理器（FACEP-012 细化）。
 *
 * - **手动录入**：由 UI 按钮触发录入模式，采集连续稳定帧后由用户命名保存
 * - **识别**：仅与已录入的人比对，返回最匹配的名字；未命中返回 null（"未录入"）
 * - **管理**：支持按名称删除已录入的人脸
 * - **存储**：JSON 文件持久化到应用内部存储
 * - **并发安全**：mGallery 读写均加锁（录入/删除在 UI 线程、识别在算法线程）
 */
class FaceEnrollmentManager(
    context: Context,
    private val mAlgorithm: FaceIDAlgorithmImpl
) {

    private val TAG = "FaceEnroll"

    private val mDbFile = File(context.filesDir, "face_enrollments.json")

    /** 已录入的人脸库，按录入顺序排列。 */
    private val mGallery = linkedMapOf<String, FloatArray>()

    /** 匹配阈值（余弦相似度）。 */
    private val MATCH_THRESHOLD = 0.40f

    /** 最佳匹配与次优匹配的最小间隔，防止身份来回跳。 */
    private val MATCH_MARGIN = 0.05f

    /** 录入所需的最低检测置信度。 */
    private val ENROLL_CONFIDENCE = 0.65f

    /** 录入前需要连续检测到稳定人脸的帧数。 */
    private val ENROLL_CONSECUTIVE_FRAMES = 10

    /** 默认命名建议池（山海经异兽名），供命名对话框兜底。 */
    private val SHENHAI_NAMES = listOf(
        "饕餮", "混沌", "穷奇", "梼杌", "夔牛", "獬豸",
        "白泽", "麒麟", "重明鸟", "毕方", "九尾狐", "应龙"
    )

    /** 连续检测到稳定人脸的帧数计数器。 */
    private var mStableFrames = 0

    /** 是否处于手动录入模式。 */
    @Volatile
    var isEnrolling: Boolean = false
        private set

    /** 手动录入采集到、待用户命名的特征向量。 */
    @Volatile
    private var mPendingEmb: FloatArray? = null

    init {
        load()
    }

    // ============================================================
    // 识别
    // ============================================================

    data class RecognitionResult(
        val name: String?,
        val isNewEnroll: Boolean
    )

    /**
     * 识别（FACEP-012：**仅匹配，不再自动录入**）。
     *
     * - 命中已录入人脸 → 返回 `(name, false)`；
     * - 未命中（库外人脸）→ 返回 `(null, false)`，由上层置为"未录入"；
     * - 录入模式下 → 返回 `(null, false)`（不干扰录入采集）。
     */
    fun recognize(emb: FloatArray, @Suppress("UNUSED_PARAMETER") score: Float,
                  @Suppress("UNUSED_PARAMETER") liveness: Float): RecognitionResult {
        if (emb.size != 512) return RecognitionResult(null, false)
        // 录入模式下不识别，避免干扰采集
        if (isEnrolling) return RecognitionResult(null, false)

        val name = synchronized(this) { matchBest(emb) }
        return if (name != null) RecognitionResult(name, false) else RecognitionResult(null, false)
    }

    /** 在已录入库中找最佳匹配；命中返回名称，否则返回 null。 */
    private fun matchBest(emb: FloatArray): String? {
        if (mGallery.isEmpty()) return null
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
            if (mGallery.size <= 1 || bestSim - secondSim >= MATCH_MARGIN) {
                return bestName
            }
            Log.d(TAG, "reject: best=$bestName sim=${String.format("%.3f", bestSim)} " +
                    "second=${String.format("%.3f", secondSim)} margin=${String.format("%.3f", bestSim - secondSim)}")
        }
        return null
    }

    // ============================================================
    // 手动录入
    // ============================================================

    /** 开始手动录入模式。 */
    fun startManualEnrollment() {
        synchronized(this) {
            isEnrolling = true
            mStableFrames = 0
            mPendingEmb = null
        }
        Log.i(TAG, "startManualEnrollment: enrolling")
    }

    /** 结束手动录入模式（成功保存或取消时调用）。 */
    fun stopManualEnrollment() {
        synchronized(this) {
            isEnrolling = false
            mStableFrames = 0
            mPendingEmb = null
        }
        Log.i(TAG, "stopManualEnrollment")
    }

    /**
     * 录入模式下采集稳定人脸帧。
     *
     * @return `true` 表示已采集到足够稳定帧，可弹框命名（此时 [pendingEmbedding] 可用）
     */
    fun onEnrollmentFrame(emb: FloatArray, score: Float): Boolean {
        synchronized(this) {
            if (!isEnrolling) return false
            if (emb.size != 512) return false
            if (score >= ENROLL_CONFIDENCE) {
                mStableFrames++
                if (mStableFrames >= ENROLL_CONSECUTIVE_FRAMES) {
                    mPendingEmb = emb.copyOf()
                    Log.i(TAG, "onEnrollmentFrame: captured, ready to name")
                    return true
                }
            } else {
                mStableFrames = 0
            }
            return false
        }
    }

    /** 采集到待命名的特征向量（[onEnrollmentFrame] 返回 true 后有效）。 */
    fun pendingEmbedding(): FloatArray? = mPendingEmb

    // ============================================================
    // 录入保存 / 删除
    // ============================================================

    /**
     * 保存一个已命名的录入人脸。
     *
     * @return 成功返回 true；名称为空/空白、或名称已存在返回 false
     */
    fun addEnrolledFace(name: String, emb: FloatArray): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || emb.size != 512) {
            Log.w(TAG, "addEnrolledFace: invalid name or embedding")
            return false
        }
        synchronized(this) {
            if (mGallery.containsKey(trimmed)) {
                Log.w(TAG, "addEnrolledFace: name exists: $trimmed")
                return false
            }
            mGallery[trimmed] = emb.copyOf()
        }
        save()
        Log.i(TAG, "addEnrolledFace: $trimmed (total=${getCount()})")
        return true
    }

    /** 删除一个已录入的人脸。返回是否成功删除。 */
    fun deleteFace(name: String): Boolean {
        val removed = synchronized(this) { mGallery.remove(name) != null }
        if (removed) {
            save()
            Log.i(TAG, "deleteFace: $name (total=${getCount()})")
        } else {
            Log.w(TAG, "deleteFace: not found: $name")
        }
        return removed
    }

    fun getEnrolledNames(): Set<String> = synchronized(this) { mGallery.keys.toSet() }

    fun getCount(): Int = synchronized(this) { mGallery.size }

    /** 默认命名建议（未使用的异兽名），供命名对话框兜底。 */
    fun defaultNameCandidates(): List<String> = synchronized(this) {
        SHENHAI_NAMES.filter { it !in mGallery.keys }
    }

    // ============================================================
    // 存储
    // ============================================================

    private fun load() {
        if (!mDbFile.exists()) return
        try {
            val text = mDbFile.readText()
            val arr = JSONArray(text)
            synchronized(this) {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val name = obj.getString("name")
                    val embArr = obj.getJSONArray("emb")
                    val emb = FloatArray(embArr.length()) { embArr.getDouble(it).toFloat() }
                    mGallery[name] = emb
                }
            }
            Log.i(TAG, "loaded ${getCount()} enrollments")
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
        }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            synchronized(this) {
                for ((name, emb) in mGallery) {
                    val obj = JSONObject()
                    obj.put("name", name)
                    val embArr = JSONArray()
                    for (v in emb) embArr.put(v.toDouble())
                    obj.put("emb", embArr)
                    arr.put(obj)
                }
            }
            mDbFile.writeText(arr.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
        }
    }
}
