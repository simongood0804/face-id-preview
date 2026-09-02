package com.skyworth.faceid.ui

import android.hardware.HardwareBuffer
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.skyworth.faceid.algorithm.IFaceIDAlgorithm
import com.skyworth.faceid.core.AlgoSession
import com.skyworth.faceid.core.FaceOverlayBridge
import com.skyworth.faceid.core.FrameSession
import com.skyworth.faceid.core.NativeFrameReader
import com.skyworth.faceid.R

/**
 * 行为监测页（FACEP-017）。
 *
 * **阶段一：仅预览**——摄像头取流 + 画面显示（已打通）。
 *
 * **阶段二（当前）**：acquire **BEHAVIOR** flag，解析 face-sdk 1.0.1 的行为识别结果
 * （`FaceResult.behaviorClass`），在预览上叠加**吸烟 / 打电话**状态提示。
 *
 * 行为类别语义（算法头文件定义）：0=normal、1=smoking、2=phone。
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

    /** 最近一次行为类别（仅变化时更新 UI，避免每帧刷）。 */
    private var mLastBehaviorClass = -1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_behavior_monitor)

        mSurface = findViewById(R.id.preview_surface)
        mStatusText = findViewById(R.id.tv_behavior_status)
        findViewById<Button>(R.id.btn_back_home).setOnClickListener {
            finish()
        }

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

    /** 装配并启动相机预览（阶段一仅取流，不处理算法结果）。 */
    private fun startPreview() {
        try {
            // 1. 算法会话（单例，引用计数 +1）；阶段一用最小 flag（DETECTION）仅驱动取流
            val algo = AlgoSession.get().acquire(applicationContext, BEHAVIOR_FLAG)
            mAlgoSession = algo

            // 2. 相机帧会话（单例）
            val frame = FrameSession.get(::readFrame)

            // 3. GL 渲染预览（按实际帧尺寸等比适配；仅配置一次）
            if (!mRendererSet) {
                frame.configureSurface(mSurface, null)
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

    /** 解析行为监测结果，更新状态提示（仅行为类别变化时刷新，避免每帧刷 UI）。 */
    private fun onAlgorithmResult(result: IFaceIDAlgorithm.FaceIDResult) {
        // 画人脸框（仅框，不依赖行为类别）
        drawFaceBox(result)

        val behaviorClass = result.behaviorClass
        if (behaviorClass == mLastBehaviorClass) return
        mLastBehaviorClass = behaviorClass

        // 状态变化时打印完整行为结果（含概率分布），确认算法返回是否正确对接
        val probs = result.behaviorProbsSafe
        Log.i(TAG, "behavior: class=$behaviorClass " +
                (probs?.let {
                    "probs=[normal=%.2f smoking=%.2f phone=%.2f]".format(
                        it.getOrElse(0) { 0f }, it.getOrElse(1) { 0f }, it.getOrElse(2) { 0f })
                } ?: "probs=null"))

        val text = when (behaviorClass.toInt()) {
            1 -> getString(R.string.behavior_smoking)
            2 -> getString(R.string.behavior_calling)
            0, -1 -> getString(R.string.behavior_normal)
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
    }
}
