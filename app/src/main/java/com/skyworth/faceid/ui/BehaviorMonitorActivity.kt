package com.skyworth.faceid.ui

import android.hardware.HardwareBuffer
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.skyworth.faceid.R
import com.skyworth.faceid.algorithm.IFaceIDAlgorithm
import com.skyworth.faceid.behavior.BehaviorStateMachine
import com.skyworth.faceid.core.AlgoSession
import com.skyworth.faceid.core.FaceOverlayBridge
import com.skyworth.faceid.core.FrameSession
import com.skyworth.faceid.core.NativeFrameReader

/**
 * 行为监测页（FACEP-017）。
 *
 * 解析 face-sdk 的行为识别结果（`FaceResult.behaviorClass`），在预览上叠加
 * **吸烟 / 打电话**状态提示。
 *
 * 行为类别语义（算法头文件定义）：0=normal、1=smoking、2=phone；SDK 只输出**逐帧**结果，
 * **不做时序逻辑**（头文件明确"持续时长/报警由 app 侧做"）。
 *
 * 时序确认（GB/T 标准）**不在本页实现**，统一由共享层 [AlgoSession] 承担
 * （FACEP-019：行为状态机挂在算法结果入口，谁用都调同一份）：
 * - 抽烟连续 **≥2s**、打电话连续 **≥3s** 才判为对应行为；
 * - 持续时长不足（含逐帧类别抖动/断续）一律判为**正常**。
 *
 * **时序延时配置**（本页新增，2026-10）：右下角 4 个滑块分别控制
 * 吸烟/打电话的**进入**与**退出**延时，行程 0~10s，拖动实时生效：
 * - 进入延时 = 连续满足多久才确认（0 = 不延时，当帧即确认）；
 * - 退出延时 = 中断多久才回落（0 = 不宽限，立即回落）。
 */
class BehaviorMonitorActivity : AppCompatActivity() {

    private val TAG = "BehaviorMonitorActivity"

    private lateinit var mSurface: GLSurfaceView
    private lateinit var mStatusText: TextView

    private var mAlgoSession: AlgoSession? = null
    private var mFrameSession: FrameSession? = null
    private var mBridge: FaceOverlayBridge? = null

    private var mAlgorithmEnabled = true

    /** 渲染器是否已设置（GLSurfaceView.setRenderer 仅能调用一次）。 */
    private var mRendererSet = false

    /** 最近一次**确认后**的行为类别（仅变化时更新 UI，避免每帧刷；-1=未初始化）。 */
    private var mLastBehaviorClass = -1

    // ---- 时序延时控件（4 个：吸烟/打电话 × 进入/退出）----
    private lateinit var mTvSmokeEnter: TextView
    private lateinit var mTvSmokeExit: TextView
    private lateinit var mTvPhoneEnter: TextView
    private lateinit var mTvPhoneExit: TextView
    private lateinit var mSbSmokeEnter: SeekBar
    private lateinit var mSbSmokeExit: SeekBar
    private lateinit var mSbPhoneEnter: SeekBar
    private lateinit var mSbPhoneExit: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_behavior_monitor)

        mSurface = findViewById(R.id.preview_surface)
        mStatusText = findViewById(R.id.tv_behavior_status)
        findViewById<Button>(R.id.btn_back_home).setOnClickListener {
            finish()
        }

        setupDelayControls()

        Log.i(TAG, "onCreate: done")
    }

    // ============================================================
    // 时序延时配置（4 个滑块，0~10s）
    // ============================================================

    /**
     * 初始化 4 个延时滑块：行程 0~[STEP_COUNT]，按 [BehaviorStateMachine.MAX_DELAY_MS]
     * 线性映射为毫秒（每档 100ms）。初始值取自共享层状态机的**当前值**，保证与运行态一致。
     */
    private fun setupDelayControls() {
        mTvSmokeEnter = findViewById(R.id.tv_smoke_enter)
        mTvSmokeExit = findViewById(R.id.tv_smoke_exit)
        mTvPhoneEnter = findViewById(R.id.tv_phone_enter)
        mTvPhoneExit = findViewById(R.id.tv_phone_exit)
        mSbSmokeEnter = findViewById(R.id.sb_smoke_enter)
        mSbSmokeExit = findViewById(R.id.sb_smoke_exit)
        mSbPhoneEnter = findViewById(R.id.sb_phone_enter)
        mSbPhoneExit = findViewById(R.id.sb_phone_exit)

        // 初始值：取共享层当前配置（跨页/跨次进入保持上次设置），持锁快照避免读到撕裂值
        val d = AlgoSession.get().behaviorDelaysSnapshot()
        bindDelay(mSbSmokeEnter, mTvSmokeEnter, R.string.behavior_delay_smoke_enter, d[0])
        bindDelay(mSbSmokeExit, mTvSmokeExit, R.string.behavior_delay_smoke_exit, d[1])
        bindDelay(mSbPhoneEnter, mTvPhoneEnter, R.string.behavior_delay_phone_enter, d[2])
        bindDelay(mSbPhoneExit, mTvPhoneExit, R.string.behavior_delay_phone_exit, d[3])
    }

    /**
     * 绑定单个滑块：初值 → 文本；拖动时更新文本并**实时下发**到共享层状态机。
     *
     * 每档 100ms，行程 [STEP_COUNT] 档 == [BehaviorStateMachine.MAX_DELAY_MS]（10s）。
     */
    private fun bindDelay(sb: SeekBar, tv: TextView, labelRes: Int, initialMs: Long) {
        sb.max = STEP_COUNT
        val initStep = (initialMs / STEP_MS).toInt().coerceIn(0, STEP_COUNT)
        sb.progress = initStep
        tv.text = getString(labelRes, formatSec(initStep * STEP_MS))
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tv.text = getString(labelRes, formatSec(progress * STEP_MS))
                if (fromUser) applyDelays()   // 拖动即时生效；程序化 setProgress 不触发下发
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    /**
     * 把 4 个滑块的当前值下发到共享层状态机（算法线程侧统一生效）。
     *
     * 越界值由状态机内部钳制；下发的值会清空状态机当前计时，
     * 避免"改大延时后仍沿用旧起点立即触发"的反直觉行为。
     */
    private fun applyDelays() {
        val algo = mAlgoSession ?: AlgoSession.get()
        algo.setBehaviorDelays(
            smokingEnterMs = mSbSmokeEnter.progress * STEP_MS,
            smokingExitMs = mSbSmokeExit.progress * STEP_MS,
            phoneEnterMs = mSbPhoneEnter.progress * STEP_MS,
            phoneExitMs = mSbPhoneExit.progress * STEP_MS
        )
    }

    /** 秒数格式化：0 显示"不延时"，其余保留 1 位小数（如 "2.0 s"）。文案走资源以支持多语言。 */
    private fun formatSec(ms: Long): String =
        if (ms <= 0L) getString(R.string.behavior_delay_zero)
        else getString(R.string.behavior_delay_sec, ms / 1000f)

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

    /** 装配并启动相机预览（阶段一仅取流，不处理算法结果）。 */
    private fun startPreview() {
        try {
            // 1. 算法会话（单例，引用计数 +1）；阶段一用最小 flag（DETECTION）仅驱动取流
            val algo = AlgoSession.get().acquire(applicationContext, BEHAVIOR_FLAG)
            mAlgoSession = algo

            // 2. 相机帧会话（单例）
            val frame = FrameSession.get(::readFrame)

            // 3. GL 渲染预览（按实际帧尺寸等比适配；仅配置一次）
            // 必须传入 face_overlay，使 overlay 随 surface 等比缩放，
            // 否则人脸框 View 保持 match_parent 全屏尺寸，与实际画面比例不符导致画框错位。
            if (!mRendererSet) {
                frame.configureSurface(mSurface, findViewById(R.id.face_overlay))
                mRendererSet = true
            }

            // 4. acquire：引用计数 +1（attach FrameDistributor 驱动取流）
            frame.acquire(algo.frameProcessor()) { mAlgorithmEnabled }
            mFrameSession = frame

            // 渲染桥接（行为监测：仅画人脸框）
            mBridge = FaceOverlayBridge(findViewById(R.id.face_overlay))

            // 5. 注入算法结果回调 → 解析行为类别（吸烟/打电话）叠加提示
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

    /**
     * 解析行为监测结果，更新状态提示。
     *
     * **时序判定来自共享层**（FACEP-019）：[AlgoSession] 在算法结果入口已用统一的行为
     * 状态机完成确认（抽烟 ≥2s / 打电话 ≥3s + 防抖），本页只读取
     * [AlgoSession.lastBehaviorClass] 展示，**不再自持状态机**，因此与融合监测等页面
     * 判定口径天然一致。
     *
     * UI 仅在**确认后**的类别变化时刷新（避免每帧刷）。
     */
    private fun onAlgorithmResult(result: IFaceIDAlgorithm.FaceIDResult) {
        // 画人脸框（仅框，不依赖行为类别）
        drawFaceBox(result)

        val rawClass = result.behaviorClass
        if (rawClass < 0f) {
            // 无人脸/行为无效：共享状态机内部已复位，这里仅同步 UI 为"正常"
            if (mLastBehaviorClass != -1) {
                mLastBehaviorClass = -1
                mStatusText.text = getString(R.string.behavior_normal)
            }
            return
        }

        // 读取共享层时序确认结果
        val confirmed = mAlgoSession?.lastBehaviorClass ?: BehaviorStateMachine.CLASS_NORMAL
        if (confirmed == mLastBehaviorClass) return
        mLastBehaviorClass = confirmed

        // 类别变化时打印完整行为结果（含概率分布与当前生效延时），便于核对算法与实际判定
        val probs = result.behaviorProbsSafe
        val d = mAlgoSession?.behaviorDelaysSnapshot()
        Log.i(TAG, "behavior: raw=${rawClass.toInt()} confirmed=$confirmed " +
                "source=AlgoSession " +
                "delays=[smoke in=${d?.get(0)}ms out=${d?.get(1)}ms, " +
                "phone in=${d?.get(2)}ms out=${d?.get(3)}ms] " +
                (probs?.let {
                    "probs=[normal=%.2f smoking=%.2f phone=%.2f]".format(
                        it.getOrElse(0) { 0f }, it.getOrElse(1) { 0f }, it.getOrElse(2) { 0f })
                } ?: "probs=null"))

        val text = when (confirmed) {
            BehaviorStateMachine.CLASS_SMOKING -> getString(R.string.behavior_smoking)
            BehaviorStateMachine.CLASS_PHONE -> getString(R.string.behavior_calling)
            BehaviorStateMachine.CLASS_NORMAL -> getString(R.string.behavior_normal)
            else -> getString(R.string.behavior_unknown)
        }
        mStatusText.text = text
        Log.i(TAG, "behavior status: $text")
    }

    /**
     * 绘制当前人脸框（行为监测：仅画框）。
     * 算法结果坐标已修正回原图空间，用帧实际尺寸缩放显示。
     */
    private fun drawFaceBox(result: IFaceIDAlgorithm.FaceIDResult) {
        val bridge = mBridge ?: return
        val distributor = mFrameSession?.frameDistributor()
        val imgW = distributor?.frameWidth ?: 1600
        val imgH = distributor?.frameHeight ?: 1300
        bridge.setFaces(result, false, FaceOverlayBridge.Module.BEHAVIOR, imgW, imgH)
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
        /** 行为监测模块的算法流程（阶段二：行为识别 flag）。 */
        private val BEHAVIOR_FLAG = atlas.face.sdk.FaceFlag.BEHAVIOR

        /** 延时滑块每档粒度：100ms。 */
        private const val STEP_MS = 100L

        /** 滑块档数：100 档 × 100ms = 10s（与 [BehaviorStateMachine.MAX_DELAY_MS] 一致）。 */
        private const val STEP_COUNT = 100
    }
}
