package com.skyworth.faceid.ui

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
import com.skyworth.faceid.signal.DoorSignalSource

/**
 * 疲劳监测模块（FACEP-011 阶段三）。
 *
 * 复用公共基础设施：`AlgoSession`（含眼嘴管线）、`FrameSession`、`FaceOverlayBridge`（疲劳区）。
 *
 * - 疲劳业务判定（补全 FACEP-011 §4.3）：基于算法输出的 `eyeOpen`/`mouthOpen`，
 *   统计**持续闭眼/打哈欠时长**，超过阈值 → 疲劳告警展示；
 * - 门信号（[DoorSignalSource]）：门开触发眼/嘴校准复位（换驾驶员重校）；
 * - 渲染只消费疲劳区字段（数据隔离，§4.6-B）。
 *
 * 生命周期：onStart 装配并 acquire，onStop release。
 */
class FatigueActivity : AppCompatActivity() {

    private val TAG = "FatigueActivity"

    private lateinit var mSurface: GLSurfaceView
    private lateinit var mFatigueText: TextView

    private var mAlgoSession: AlgoSession? = null
    private var mFrameSession: FrameSession? = null
    private var mBridge: FaceOverlayBridge? = null
    private var mDoorSource: DoorSignalSource? = null

    private var mAlgorithmEnabled = true

    /** 疲劳业务判定计时（单调时钟注入便于测试）。 */
    private var mEyeClosedSince = 0L
    private var mEyeOpenSince = 0L
    private var mMouthOpenSince = 0L
    private var mMouthClosedSince = 0L
    private var mNoFaceSince = 0L
    private var mFatigueActive = false
    private var mFatigueKind = 0  // 0=无, 1=闭眼, 2=哈欠

    /** 渲染器是否已设置（GLSurfaceView.setRenderer 仅能调用一次）。 */
    private var mRendererSet = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fatigue)

        mSurface = findViewById(R.id.preview_surface)
        mFatigueText = findViewById(R.id.tv_fatigue)
        findViewById<Button>(R.id.btn_back_home).setOnClickListener { finish() }

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

            // 门信号：门开 → 眼/嘴校准复位（换驾驶员重校，§4.6-B 全局事件）
            mDoorSource = DoorSignalSource(this).also { ds ->
                ds.onDoorChanged = { door ->
                    if (door.isOpen) {
                        Log.i(TAG, "door open, reset calibration")
                        algo.onDoorOpened()
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

    /** 算法结果回调：疲劳区桥接 + 疲劳业务判定。 */
    private fun onAlgorithmResult(result: IFaceIDAlgorithm.FaceIDResult) {
        // 算法结果已修正回原图空间（1600×1300），用原图尺寸缩放显示（FACEP-011 裁剪映射）
        val distributor = mFrameSession?.frameDistributor()
        val imgW = distributor?.frameWidth ?: ORIGINAL_WIDTH
        val imgH = distributor?.frameHeight ?: ORIGINAL_HEIGHT
        mBridge?.setFaces(result, false, FaceOverlayBridge.Module.FATIGUE, imgW, imgH)

        val now = System.currentTimeMillis()

        // 无人脸：不立即复位（算法可能因漏检/遮挡短暂丢失人脸），
        // 持续无人脸达到 NO_FACE_RESET_MS 才整体复位，期间保持当前告警状态。
        if (result.faceId.isEmpty() && result.faceRect == null) {
            if (mNoFaceSince == 0L) mNoFaceSince = now
            if (now - mNoFaceSince >= NO_FACE_RESET_MS) resetFatigue()
            return
        }
        mNoFaceSince = 0L

        // 闭眼/睁眼计时（进入闭眼告警与退出闭眼告警共用）
        if (!result.eyeOpen) {
            mEyeOpenSince = 0L
            if (mEyeClosedSince == 0L) mEyeClosedSince = now
            val closedMs = now - mEyeClosedSince
            if (closedMs >= EYE_CLOSE_ALERT_MS) setFatigue(1)
        } else {
            mEyeClosedSince = 0L
            if (mEyeOpenSince == 0L) mEyeOpenSince = now
        }

        // 哈欠/闭嘴计时（进入哈欠告警与退出哈欠告警共用）
        if (result.mouthOpen) {
            mMouthClosedSince = 0L
            if (mMouthOpenSince == 0L) mMouthOpenSince = now
            val openMs = now - mMouthOpenSince
            if (openMs >= YAWN_ALERT_MS) setFatigue(2)
        } else {
            mMouthOpenSince = 0L
            if (mMouthClosedSince == 0L) mMouthClosedSince = now
        }

        // 告警退出（不对称防抖）：恢复正常表现达短清除阈值即停止告警
        // （进入需长阈值严格防误报；退出用短阈值，避免"睁眼/闭嘴后仍长时间告警"）
        if (mFatigueKind == 1 && mEyeOpenSince != 0L && now - mEyeOpenSince >= EYE_CLOSE_CLEAR_MS) {
            clearFatigue()
        } else if (mFatigueKind == 2 && mMouthClosedSince != 0L && now - mMouthClosedSince >= YAWN_CLEAR_MS) {
            clearFatigue()
        }

        // 无告警 → 恢复正常
        if (mFatigueKind == 0) {
            mFatigueText.setTextColor(0xFF00FF00.toInt())
            mFatigueText.setText(R.string.fatigue_status_ok)
        }
    }

    /** 设置疲劳告警。 */
    private fun setFatigue(kind: Int) {
        mFatigueActive = true
        if (mFatigueKind == kind) return
        mFatigueKind = kind
        mFatigueText.setTextColor(0xFFFF0000.toInt())
        mFatigueText.setText(
            if (kind == 1) R.string.fatigue_status_eye_closed
            else R.string.fatigue_status_yawn
        )
    }

    /** 解除疲劳告警（恢复正常持续达阈值）。 */
    private fun clearFatigue() {
        mFatigueActive = false
        mFatigueKind = 0
        mEyeClosedSince = 0L
        mEyeOpenSince = 0L
        mMouthOpenSince = 0L
        mMouthClosedSince = 0L
        mFatigueText.setTextColor(0xFF00FF00.toInt())
        mFatigueText.setText(R.string.fatigue_status_ok)
    }

    /** 整体复位疲劳状态（持续无人脸达阈值）。 */
    private fun resetFatigue() {
        mFatigueActive = false
        mFatigueKind = 0
        mEyeClosedSince = 0L
        mEyeOpenSince = 0L
        mMouthOpenSince = 0L
        mMouthClosedSince = 0L
        mNoFaceSince = 0L
        mFatigueText.setTextColor(0xFF00FF00.toInt())
        mFatigueText.setText(R.string.fatigue_status_ok)
    }

    /** JNI 帧读取（复用 NativeFrameReader）。 */
    private fun readFrame(hwBuffer: HardwareBuffer, width: Int, height: Int): ByteArray? {
        return NativeFrameReader.readHardwareBuffer(hwBuffer, width, height)
    }

    companion object {
        /** 原始帧尺寸（DMS 摄像头），算法结果坐标基于此缩放显示。 */
        private const val ORIGINAL_WIDTH = 1600
        private const val ORIGINAL_HEIGHT = 1300
        /** 持续闭眼告警阈值（ms）：连续闭眼达到该时长触发闭眼告警（进入严格防误报）。 */
        private const val EYE_CLOSE_ALERT_MS = 3000L
        /** 退出闭眼告警阈值（ms）：连续睁眼达到该时长解除告警（远小于进入阈值，不对称防抖，
         *  避免"睁大眼睛后仍长时间显示闭眼"，也避免眨眼/单帧抖动把清除计时反复清零）。 */
        private const val EYE_CLOSE_CLEAR_MS = 500L
        /** 持续打哈欠告警阈值（ms）：连续张嘴达到该时长触发哈欠告警（进入严格防误报）。 */
        private const val YAWN_ALERT_MS = 2000L
        /** 退出哈欠告警阈值（ms）：连续闭嘴达到该时长解除告警（不对称防抖，理由同眼睛）。 */
        private const val YAWN_CLEAR_MS = 500L
        /** 无人脸复位确认时长（ms）：连续无人脸达到该时长才复位（防算法漏检误复位）。 */
        private const val NO_FACE_RESET_MS = 3000L

        /** 疲劳监测模块算法流程（只需检测 + 68点眼嘴开合，不需识别/头姿/视线）。 */
        private val FATIGUE_FLAG = atlas.face.sdk.FaceFlag.DETECTION or
            atlas.face.sdk.FaceFlag.LANDMARK
    }
}
