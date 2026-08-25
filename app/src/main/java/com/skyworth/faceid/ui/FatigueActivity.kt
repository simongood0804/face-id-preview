package com.skyworth.faceid.ui

import android.graphics.Color
import android.hardware.HardwareBuffer
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.skyworth.faceid.R
import com.skyworth.faceid.algorithm.IFaceIDAlgorithm
import com.skyworth.faceid.core.AlgoSession
import com.skyworth.faceid.core.FaceOverlayBridge
import com.skyworth.faceid.core.FrameSession
import com.skyworth.faceid.core.NativeFrameReader
import com.skyworth.faceid.fatigue.FatigueRule
import com.skyworth.faceid.fatigue.FatigueRuleLoader
import com.skyworth.faceid.fatigue.FatigueStateMachine
import com.skyworth.faceid.signal.DoorSignalSource

/**
 * 疲劳监测模块（FACEP-011 阶段三 / FACEP-015 配置化分级）。
 *
 * 复用公共基础设施：`AlgoSession`（含眼嘴管线）、`FrameSession`、`FaceOverlayBridge`（疲劳区）。
 *
 * - **疲劳判定下沉 `:algo`**（FACEP-015）：规则从 `assets/fatigue_rules.json` 加载（[FatigueRuleLoader]），
 *   判定由 [FatigueStateMachine] 完成——三级疲劳（轻度/中度/重度）覆盖升级、逐级退出、窗口统计、无人脸复位；
 * - **渲染**（FACEP-015 §4.3）：左上角状态指示灯（正常绿/轻度黄/中度橙/重度红）+ 状态文本，
 *   下方闭眼/哈欠描述 + 诊断统计区（当前命中条件与窗口计数，不跳变，便于观察规则影响）；
 * - 门信号（[DoorSignalSource]）：门开触发眼/嘴校准复位（换驾驶员重校）。
 *
 * 生命周期：onStart 装配并 acquire，onStop release。
 */
class FatigueActivity : AppCompatActivity() {

    private val TAG = "FatigueActivity"

    private lateinit var mSurface: GLSurfaceView
    private lateinit var mIndicator: View
    private lateinit var mFatigueText: TextView
    private lateinit var mEyeDesc: TextView
    private lateinit var mMouthDesc: TextView
    private lateinit var mDiagLevel: TextView
    private lateinit var mDiagStats: TextView
    private lateinit var mDiagCont: TextView

    private var mAlgoSession: AlgoSession? = null
    private var mFrameSession: FrameSession? = null
    private var mBridge: FaceOverlayBridge? = null
    private var mDoorSource: DoorSignalSource? = null

    /** FACEP-015：疲劳判定引擎（规则从 JSON 注入）。 */
    private var mFatigueMachine: FatigueStateMachine? = null

    private var mAlgorithmEnabled = true

    /** 渲染器是否已设置（GLSurfaceView.setRenderer 仅能调用一次）。 */
    private var mRendererSet = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fatigue)

        mSurface = findViewById(R.id.preview_surface)
        mIndicator = findViewById(R.id.indicator_light)
        mFatigueText = findViewById(R.id.tv_fatigue)
        mEyeDesc = findViewById(R.id.tv_eye_desc)
        mMouthDesc = findViewById(R.id.tv_mouth_desc)
        mDiagLevel = findViewById(R.id.tv_diag_level)
        mDiagStats = findViewById(R.id.tv_diag_stats)
        mDiagCont = findViewById(R.id.tv_diag_cont)
        findViewById<Button>(R.id.btn_back_home).setOnClickListener { finish() }

        // FACEP-015：加载疲劳规则（assets/fatigue_rules.json；缺失/损坏回退默认）
        val rule = FatigueRuleLoader.loadFromAssets(this)
        mFatigueMachine = FatigueStateMachine(rule)
        Log.i(TAG, "onCreate: fatigue rule loaded (levels=${rule.levels.size})")
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
        // 暂停 GLSurfaceView，停止 GLThread（避免渲染已释放资源 SIGSEGV）
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

    /** 装配并启动预览 + 疲劳判定（FACEP-011 §4.6-A 单例+引用计数）。 */
    private fun startPreview() {
        try {
            val algo = AlgoSession.get().acquire(applicationContext, FATIGUE_FLAG)
            mAlgoSession = algo

            val frame = FrameSession.get(::readFrame)
                .acquire(algo.frameProcessor()) { mAlgorithmEnabled }
            mFrameSession = frame

            if (!mRendererSet) {
                frame.configureSurface(mSurface, findViewById(R.id.face_overlay))
                mRendererSet = true
            }
            mBridge = FaceOverlayBridge(findViewById(R.id.face_overlay))

            // 门信号：门开 → 眼/嘴校准复位 + 疲劳统计复位（换驾驶员重校，§4.6-B 全局事件；
            // FACEP-015 中危修复：避免换人后沿用上一位驾驶员的疲劳累计）
            mDoorSource = DoorSignalSource(this).also { ds ->
                ds.onDoorChanged = { door ->
                    if (door.isOpen) {
                        Log.i(TAG, "door open, reset calibration & fatigue")
                        algo.onDoorOpened()
                        mFatigueMachine?.reset()
                    }
                }
                ds.connect()
            }

            algo.setResultCallback { result ->
                runOnUiThread { onAlgorithmResult(result) }
            }

            if (!frame.open()) {
                Log.e(TAG, "startPreview: open camera failed")
                mFatigueText.text = "相机打开失败"
            }
        } catch (e: Exception) {
            Log.e(TAG, "startPreview: failed", e)
        }
    }

    /** 停止预览并释放。 */
    private fun stopPreview() {
        try {
            mBridge?.clearFaces()
            mDoorSource?.disconnect()
            mFrameSession?.release()
            mAlgoSession?.setResultCallback(null)
            mAlgoSession?.release()
        } catch (e: Exception) {
            Log.e(TAG, "stopPreview: error", e)
        } finally {
            mDoorSource = null
            mBridge = null
            mFrameSession = null
            mAlgoSession = null
        }
    }

    /** 算法结果回调：疲劳区桥接 + 疲劳分级判定（FACEP-015）。 */
    private fun onAlgorithmResult(result: IFaceIDAlgorithm.FaceIDResult) {
        // 算法结果已修正回原图空间（1600×1300），用原图尺寸缩放显示（FACEP-011 裁剪映射）
        val distributor = mFrameSession?.frameDistributor()
        val imgW = distributor?.frameWidth ?: ORIGINAL_WIDTH
        val imgH = distributor?.frameHeight ?: ORIGINAL_HEIGHT
        mBridge?.setFaces(result, false, FaceOverlayBridge.Module.FATIGUE, imgW, imgH)

        val machine = mFatigueMachine ?: return
        val hasFace = result.faceId.isNotEmpty() || result.faceRect != null

        // FACEP-015：疲劳引擎判定（喂连续开合度，引擎内部判闭眼/哈欠/窗口统计/无人脸复位）。
        // 用单调时钟（nanoTime），避免 wall clock 被 NTP/校时回拨导致时长异常（隐患 A 修复）。
        val out = machine.update(
            result.eyeOpenRatio,
            result.mouthOpenRatio,
            hasFace,
            System.nanoTime() / 1_000_000
        )
        renderFatigue(out)
    }

    /** FACEP-015 §4.3：按疲劳等级渲染指示灯/状态文本/闭眼/哈欠描述/诊断统计。 */
    private fun renderFatigue(out: FatigueStateMachine.FatigueOutput) {
        val level = out.level
        val color = when (level) {
            FatigueRule.Level.NONE -> Color.rgb(0x00, 0xFF, 0x00)
            FatigueRule.Level.LIGHT -> Color.rgb(0xFF, 0xFF, 0x00)
            FatigueRule.Level.MODERATE -> Color.rgb(0xFF, 0xA5, 0x00)
            FatigueRule.Level.SEVERE -> Color.rgb(0xFF, 0x00, 0x00)
        }
        mIndicator.setBackgroundColor(color)
        mIndicator.invalidate()
        mFatigueText.setTextColor(color)
        mFatigueText.text = when (level) {
            FatigueRule.Level.NONE -> getString(R.string.fatigue_status_ok)
            FatigueRule.Level.LIGHT -> getString(R.string.fatigue_status_light)
            FatigueRule.Level.MODERATE -> getString(R.string.fatigue_status_moderate)
            FatigueRule.Level.SEVERE -> getString(R.string.fatigue_status_severe)
        }

        // 闭眼状态描述（不跳变，实时刷新）
        mEyeDesc.text = if (out.curEyeCloseMs > 0) {
            getString(R.string.fatigue_desc_eye_closed, out.curEyeCloseMs)
        } else {
            getString(R.string.fatigue_desc_eye_open)
        }

        // 哈欠状态描述
        mMouthDesc.text = if (out.curYawnMs > 0) {
            getString(R.string.fatigue_desc_mouth_open, out.mouthOpenRatio, out.curYawnMs)
        } else {
            getString(R.string.fatigue_desc_mouth_closed)
        }

        // 诊断统计区（当前命中条件 + 窗口计数 + 连续时长）
        val levelName = levelText(level)
        val condition = out.matchedCondition ?: "-"
        mDiagLevel.text = getString(R.string.fatigue_diag_level, levelName, condition)
        mDiagStats.text = getString(R.string.fatigue_diag_stats,
            out.eyeCloseCount60s, out.eyeCloseCount20s, out.yawnCount60s)
        mDiagCont.text = getString(R.string.fatigue_diag_cont,
            out.curEyeCloseMs, out.curYawnMs, out.curNoFaceMs)
    }

    private fun levelText(level: FatigueRule.Level): String = when (level) {
        FatigueRule.Level.NONE -> "正常"
        FatigueRule.Level.LIGHT -> "轻度"
        FatigueRule.Level.MODERATE -> "中度"
        FatigueRule.Level.SEVERE -> "重度"
    }

    /** JNI 帧读取（复用 NativeFrameReader）。 */
    private fun readFrame(hwBuffer: HardwareBuffer, width: Int, height: Int): ByteArray? {
        return NativeFrameReader.readHardwareBuffer(hwBuffer, width, height)
    }

    companion object {
        /** 原始帧尺寸（DMS 摄像头），算法结果坐标基于此缩放显示。 */
        private const val ORIGINAL_WIDTH = 1600
        private const val ORIGINAL_HEIGHT = 1300

        /** 疲劳监测模块算法流程（只需检测 + 68点眼嘴开合，不需识别/头姿/视线）。 */
        private val FATIGUE_FLAG = atlas.face.sdk.FaceFlag.DETECTION or
            atlas.face.sdk.FaceFlag.LANDMARK
    }
}
