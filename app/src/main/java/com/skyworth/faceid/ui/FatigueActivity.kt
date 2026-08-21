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
    private var mMouthOpenSince = 0L
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
        if (result.faceId.isEmpty() && result.faceRect == null) {
            // 无人脸：重置疲劳计时
            mEyeClosedSince = 0L
            mMouthOpenSince = 0L
            mFatigueActive = false
            mFatigueKind = 0
            mFatigueText.setTextColor(0xFF00FF00.toInt())
            mFatigueText.setText(R.string.fatigue_status_ok)
            return
        }

        // 持续闭眼判定
        if (!result.eyeOpen) {
            if (mEyeClosedSince == 0L) mEyeClosedSince = now
            val closedMs = now - mEyeClosedSince
            if (closedMs >= EYE_CLOSE_ALERT_MS) setFatigue(1)
        } else {
            mEyeClosedSince = 0L
        }

        // 持续哈欠判定
        if (result.mouthOpen) {
            if (mMouthOpenSince == 0L) mMouthOpenSince = now
            val openMs = now - mMouthOpenSince
            if (openMs >= YAWN_ALERT_MS) setFatigue(2)
        } else {
            mMouthOpenSince = 0L
        }

        // 无持续告警 → 恢复正常
        if (!mFatigueActive && mFatigueKind == 0) {
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

    /** JNI 帧读取（复用 NativeFrameReader）。 */
    private fun readFrame(hwBuffer: HardwareBuffer, width: Int, height: Int): ByteArray? {
        return NativeFrameReader.readHardwareBuffer(hwBuffer, width, height)
    }

    companion object {
        /** 原始帧尺寸（DMS 摄像头），算法结果坐标基于此缩放显示。 */
        private const val ORIGINAL_WIDTH = 1600
        private const val ORIGINAL_HEIGHT = 1300
        /** 持续闭眼告警阈值（ms）。 */
        private const val EYE_CLOSE_ALERT_MS = 3000L
        /** 持续打哈欠告警阈值（ms）。 */
        private const val YAWN_ALERT_MS = 2000L

        /** 疲劳监测模块算法流程（只需检测 + 106点眼嘴开合，不需识别/头姿/视线）。 */
        private val FATIGUE_FLAG = atlas.face.sdk.FaceFlag.DETECTION or
            atlas.face.sdk.FaceFlag.LANDMARK
    }
}
