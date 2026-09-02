package com.skyworth.faceid.ui

import android.hardware.HardwareBuffer
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.skyworth.faceid.algorithm.IFaceIDAlgorithm
import com.skyworth.faceid.core.AlgoSession
import com.skyworth.faceid.core.FaceOverlayBridge
import com.skyworth.faceid.core.FrameSession
import com.skyworth.faceid.core.NativeFrameReader
import com.skyworth.faceid.fatigue.FatigueRule
import com.skyworth.faceid.fatigue.FatigueRuleLoader
import com.skyworth.faceid.fatigue.FatigueStateMachine
import com.skyworth.faceid.R

/**
 * 融合监测页（FACEP-018）。
 *
 * 聚合展示算法完整功能：人脸识别 / 疲劳检测 / 分心监测 / 行为监测。
 * - 使用**融合 flag** 一次推理同时获得全部能力；
 * - 右侧预览只绘制 **68 点密集地标 + 头姿坐标轴 + 分心注意列表（zone 面板）**；
 * - 左侧面板分别展示识别 / 疲劳 / 行为 / 分心结果。
 */
class FusionMonitorActivity : AppCompatActivity() {

    private val TAG = "FusionMonitorActivity"

    private lateinit var mSurface: GLSurfaceView

    // 左侧信息面板
    private lateinit var mRecResult: TextView
    private lateinit var mRecConfidence: TextView
    private lateinit var mRecEnrolled: TextView
    private lateinit var mFatigueLevel: TextView
    private lateinit var mFatigueEye: TextView
    private lateinit var mFatigueMouth: TextView
    private lateinit var mDistractionZone: TextView
    private lateinit var mBehaviorResult: TextView

    private var mAlgoSession: AlgoSession? = null
    private var mFrameSession: FrameSession? = null
    private var mBridge: FaceOverlayBridge? = null

    /** 疲劳判定引擎（复用 :algo 外部状态机；规则从 assets/fatigue_rules.json 加载）。 */
    private var mFatigueMachine: FatigueStateMachine? = null

    private var mAlgorithmEnabled = true
    private var mRendererSet = false

    /** 最近一次行为类别（仅变化时刷新 UI，避免每帧刷）。 */
    private var mLastBehaviorClass = -1f

    /** 最近一次分心 zone（仅变化时刷新）。 */
    private var mLastZone = -1

    /** 最近一次已录入数量（仅数量变化时刷新，避免每帧刷 UI）。 */
    private var mLastEnrolledCount = -1

    /** 无人脸连续计数（用于识别区"未检测到"展示）。 */
    private var mNoFaceCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fusion_monitor)

        mSurface = findViewById(R.id.preview_surface)

        mRecResult = findViewById(R.id.tv_rec_result)
        mRecConfidence = findViewById(R.id.tv_rec_confidence)
        mRecEnrolled = findViewById(R.id.tv_rec_enrolled)
        mFatigueLevel = findViewById(R.id.tv_fatigue_level)
        mFatigueEye = findViewById(R.id.tv_fatigue_eye)
        mFatigueMouth = findViewById(R.id.tv_fatigue_mouth)
        mDistractionZone = findViewById(R.id.tv_distraction_zone)
        mBehaviorResult = findViewById(R.id.tv_behavior_result)

        findViewById<Button>(R.id.btn_back_home).setOnClickListener {
            finish()
        }

        // 复用外部疲劳判定引擎（FACEP-015）：规则从 assets/fatigue_rules.json 加载，
        // 与独立疲劳模块(FatigueActivity)一致，支持轻度/中度/重度三级判定。
        val rule = FatigueRuleLoader.loadFromAssets(this)
        mFatigueMachine = FatigueStateMachine(rule)
        Log.i(TAG, "onCreate: fatigue machine created (levels=${rule.levels.size})")

        Log.i(TAG, "onCreate: done")
    }

    override fun onStart() {
        super.onStart()
        startPreview()
    }

    override fun onStop() {
        stopPreview()
        super.onStop()
    }

    override fun onPause() {
        // 暂停 GLSurfaceView，停止 GLThread（避免渲染已释放的相机/算法资源导致 SIGSEGV）
        mSurface.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        mSurface.onResume()
    }

    override fun onDestroy() {
        stopPreview()
        super.onDestroy()
    }

    /** 装配并启动融合预览。 */
    private fun startPreview() {
        try {
            // 1. 算法会话：融合 flag（一次推理获得识别/疲劳/分心/行为全部能力）
            val algo = AlgoSession.get().acquire(applicationContext, FUSION_FLAG)
            mAlgoSession = algo

            // 2. 相机帧会话
            val frame = FrameSession.get(::readFrame)

            // 3. GL 渲染预览（须传 face_overlay，使 overlay 随 surface 等比缩放，否则画框错位）
            if (!mRendererSet) {
                frame.configureSurface(mSurface, findViewById(R.id.face_overlay))
                mRendererSet = true
            }

            // 4. acquire：引用计数 +1（attach FrameDistributor 驱动取流）
            frame.acquire(algo.frameProcessor()) { mAlgorithmEnabled }
            mFrameSession = frame

            // 渲染桥接（融合：仅 68 点 + 头姿 + zone 面板）
            mBridge = FaceOverlayBridge(findViewById(R.id.face_overlay))

            // 5. 算法结果回调 → 聚合展示识别/疲劳/分心/行为
            algo.setResultCallback { result ->
                runOnUiThread { onAlgorithmResult(result) }
            }

            // 6. 打开相机
            if (!frame.open()) {
                Log.e(TAG, "startPreview: open camera failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "startPreview: failed", e)
        }
    }

    /** 解析一帧融合结果，聚合更新各面板 + 预览 overlay。 */
    private fun onAlgorithmResult(result: IFaceIDAlgorithm.FaceIDResult) {
        val frameW = mFrameSession?.frameDistributor()?.frameWidth ?: 1600
        val frameH = mFrameSession?.frameDistributor()?.frameHeight ?: 1300

        // 1. 预览 overlay（融合：68 点 + 头姿 + zone 面板）
        val distractNow = result.gazeDistracted > 0f
        mBridge?.setFaces(result, distractNow, FaceOverlayBridge.Module.FUSION, frameW, frameH)

        // 疲劳判定：无人脸/有人脸都喂给状态机（hasFace 驱动复位 / 累积闭眼/哈欠统计）。
        // 复用外部引擎（FACEP-015），使用单调时钟避免校时回拨导致时长异常。
        val machine = mFatigueMachine
        val hasFace = result.faceRect != null
        val fatigueOut = machine?.update(
            result.eyeOpenRatio,
            result.mouthOpenRatio,
            hasFace,
            System.nanoTime() / 1_000_000
        )

        if (!hasFace) {
            // 无人脸：清空 overlay（setFaces 内部已 clearFaces）；各面板防抖置为"未检测到"
            mNoFaceCount++
            if (mNoFaceCount >= 5) {
                mRecResult.text = getString(R.string.fusion_no_face)
                mRecConfidence.text = ""
                mFatigueLevel.text = ""
                mFatigueEye.text = ""
                mFatigueMouth.text = ""
                mBehaviorResult.text = ""
                mDistractionZone.text = ""
                // 重置各"仅变化刷新"的缓存，避免重检测到人脸但值相同时不刷新
                mLastZone = -1
                mLastBehaviorClass = -1f
            }
            return
        }
        mNoFaceCount = 0

        // 2. 人脸识别结果
        updateRecognitionPanel(result)

        // 3. 疲劳结果（外部状态机判定的多级疲劳等级 + 实时开合度）
        updateFatiguePanel(result, fatigueOut)

        // 4. 分心 zone（仅变化刷新）
        updateDistractionPanel(result)

        // 5. 行为结果（仅变化刷新）
        updateBehaviorPanel(result)
    }

    /** 人脸识别面板。 */
    private fun updateRecognitionPanel(result: IFaceIDAlgorithm.FaceIDResult) {
        val faceId = result.faceId

        // 已录入数：仅在数量变化时刷新（getCount 内存计数，但避免每帧 setText）
        val enrolledTotal = mAlgoSession?.algorithm()?.getEnrolledCount() ?: 0
        if (enrolledTotal != mLastEnrolledCount) {
            mLastEnrolledCount = enrolledTotal
            mRecEnrolled.text = getString(R.string.fusion_rec_enrolled, enrolledTotal)
        }

        // 识别结果：无人脸时防抖置"未检测到"（mRecResult/mRecConfidence 由外层处理）
        if (faceId.isEmpty()) {
            return
        }
        // faceId 语义：detected(未录入)/spoof(疑似假脸)/unregistered(有效未录入)/姓名
        val displayName = when (faceId) {
            "detected" -> "未录入人脸"
            "spoof" -> "疑似活体攻击 (spoof)"
            "unregistered" -> "有效人脸（未录入）"
            else -> faceId
        }
        mRecResult.text = getString(R.string.fusion_rec_face, displayName)
        mRecConfidence.text = getString(R.string.fusion_rec_conf,
            String.format("%.1f%%", result.confidence * 100f))
    }

    /** 疲劳面板（外部状态机判定的多级等级 + 实时开合度/眼嘴状态）。 */
    private fun updateFatiguePanel(result: IFaceIDAlgorithm.FaceIDResult,
                                   fatigueOut: FatigueStateMachine.FatigueOutput?) {
        val eyeText = if (result.eyeOpen) getString(R.string.fusion_eye_open)
            else getString(R.string.fusion_eye_closed)
        val mouthText = if (result.mouthOpen) getString(R.string.fusion_mouth_open)
            else getString(R.string.fusion_mouth_closed)

        mFatigueEye.text = getString(R.string.fusion_fatigue_eye, eyeText, result.eyeOpenRatio)
        mFatigueMouth.text = getString(R.string.fusion_fatigue_mouth, mouthText, result.mouthOpenRatio)

        // 疲劳等级：复用外部引擎(轻度/中度/重度/正常)的判定结果。
        val levelName = fatigueOut?.let { levelText(it.level) } ?: "正常"
        mFatigueLevel.text = getString(R.string.fusion_fatigue_level, levelName)
    }

    /** 疲劳等级中文名（与独立疲劳模块 FatigueActivity 一致）。 */
    private fun levelText(level: FatigueRule.Level): String = when (level) {
        FatigueRule.Level.NONE -> "正常"
        FatigueRule.Level.LIGHT -> "轻度疲劳"
        FatigueRule.Level.MODERATE -> "中度疲劳"
        FatigueRule.Level.SEVERE -> "重度疲劳"
    }

    /** 分心 zone 面板（仅变化刷新）。 */
    private fun updateDistractionPanel(result: IFaceIDAlgorithm.FaceIDResult) {
        val zoneId = result.zoneId.toInt()
        if (zoneId == mLastZone) return
        mLastZone = zoneId

        val zoneName = if (zoneId in 0..14) ZONE_NAMES[zoneId] else "UNKNOWN($zoneId)"
        val zoneCn = if (zoneId in 0..14) ZONE_NAMES_CN[zoneId] else ""
        val distracted = if (result.gazeDistracted > 0f) "⚠ 分心" else "专注"
        mDistractionZone.text = getString(R.string.fusion_zone_hint,
            "$distracted | Zone:$zoneName $zoneCn")
    }

    /** 行为结果面板（仅变化刷新）。 */
    private fun updateBehaviorPanel(result: IFaceIDAlgorithm.FaceIDResult) {
        val behaviorClass = result.behaviorClass
        if (behaviorClass == mLastBehaviorClass) return
        mLastBehaviorClass = behaviorClass

        val text = when (behaviorClass.toInt()) {
            1 -> getString(R.string.behavior_smoking)
            2 -> getString(R.string.behavior_calling)
            0, -1 -> getString(R.string.behavior_normal)
            else -> getString(R.string.behavior_unknown)
        }
        mBehaviorResult.text = getString(R.string.fusion_behavior_class, text)
        Log.i(TAG, "behavior: class=$behaviorClass text=$text")
    }

    /** 停止预览并释放引用计数。 */
    private fun stopPreview() {
        try {
            mBridge?.clearFaces()
            mBridge = null
            mFrameSession?.release()
            mAlgoSession?.setResultCallback(null)
            mAlgoSession?.release()
        } catch (e: Exception) {
            Log.e(TAG, "stopPreview: error", e)
        } finally {
            mFrameSession = null
            mAlgoSession = null
        }
    }

    /** JNI 帧读取（复用 NativeFrameReader 公共能力）。 */
    private fun readFrame(hwBuffer: HardwareBuffer, width: Int, height: Int): ByteArray? {
        return NativeFrameReader.readHardwareBuffer(hwBuffer, width, height)
    }

    companion object {
        /** 融合监测 flag：一次推理获得识别/疲劳/分心/行为全部能力。 */
        private val FUSION_FLAG =
            atlas.face.sdk.FaceFlag.DETECTION or
            atlas.face.sdk.FaceFlag.RECOGNITION or
            atlas.face.sdk.FaceFlag.LIVENESS or
            atlas.face.sdk.FaceFlag.HEADPOSE or
            atlas.face.sdk.FaceFlag.GAZE or
            atlas.face.sdk.FaceFlag.LANDMARK or
            atlas.face.sdk.FaceFlag.BEHAVIOR

        /** DMS zone 英文名（与 FaceOverlayView 对齐）。 */
        private val ZONE_NAMES = arrayOf(
            "FORWARD", "DRV_LEFT_KNEE", "DRV_RIGHT_KNEE", "DRV_BELT", "PASS_FOOTWELL",
            "PASS_SEAT", "GLOVEBOX", "DRV_LEFT_VENT", "DRV_RIGHT_VENT", "DASHBOARD",
            "STEERING_WHEEL", "GEAR_SELECTOR", "HVAC", "INFOTAINMENT", "CENTER_CONSOLE"
        )

        /** DMS zone 中文名。 */
        private val ZONE_NAMES_CN = arrayOf(
            "正视前方", "驾驶左膝", "驾驶右膝", "安全带", "副驾脚部",
            "副驾驶座", "手套箱", "驾驶左出风口", "驾驶右出风口", "仪表台",
            "方向盘", "挡位选择器", "空调面板", "信息娱乐屏", "中央扶手箱"
        )
    }
}
