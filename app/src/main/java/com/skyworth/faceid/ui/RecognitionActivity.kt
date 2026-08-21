package com.skyworth.faceid.ui

import android.content.Intent
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
 * 人脸识别模块（FACEP-011 阶段二）。
 *
 * 复用公共基础设施：`AlgoSession`（算法单例+引用计数）、`FrameSession`（相机单例+引用计数）、
 * `FaceOverlayBridge`（识别区渲染）。
 *
 * - 识别由算法内部 `FaceEnrollmentManager` 驱动（自动录入/识别/替换）；
 * - 本模块只做相机预览 + 识别结果展示（人脸框/姓名/置信度）；
 * - 不依赖信号层（纯本地识别，FACEP-011 §3.2）。
 *
 * 生命周期：onStart 装配并 acquire，onStop release（引用计数归 0 才释放）。
 */
class RecognitionActivity : AppCompatActivity() {

    private val TAG = "RecognitionActivity"

    private lateinit var mSurface: GLSurfaceView
    private lateinit var mFaceIdText: TextView
    private lateinit var mEnrolledCountText: TextView

    private var mAlgoSession: AlgoSession? = null
    private var mFrameSession: FrameSession? = null
    private var mBridge: FaceOverlayBridge? = null

    private var mAlgorithmEnabled = true

    /** 当前识别显示（仅在人脸变化时更新，避免每帧刷 UI）。 */
    private var mLastFaceId: String = ""

    /** 当前已导入人脸数量（仅数量变化时更新，避免每帧刷 UI）。 */
    private var mLastEnrolledCount = -1

    /** 渲染器是否已设置（GLSurfaceView.setRenderer 仅能调用一次）。 */
    private var mRendererSet = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recognition)

        mSurface = findViewById(R.id.preview_surface)
        mFaceIdText = findViewById(R.id.tv_face_id)
        mEnrolledCountText = findViewById(R.id.tv_enrolled_count)
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

    /** 装配并启动相机预览 + 算法会话（FACEP-011 §4.6-A 单例+引用计数）。 */
    private fun startPreview() {
        try {
            // 1. 算法会话（单例，引用计数 +1；只跑识别相关模型，FACEP-011 功能划分）
            val algo = AlgoSession.get().acquire(applicationContext, RECOGNITION_FLAG)
            mAlgoSession = algo

            // 2. 相机帧会话（单例，引用计数 +1，注入 JNI 帧读取回调）
            val frame = FrameSession.get(::readFrame)
                .acquire(algo.frameProcessor()) { mAlgorithmEnabled }
            mFrameSession = frame

            // 3. GL 渲染预览（renderer 每次新建，保持 1600×1300 画面比例；仅配置一次）
            if (!mRendererSet) {
                frame.configureSurface(mSurface, findViewById(R.id.face_overlay))
                mRendererSet = true
            }

            // 4. 渲染桥接（识别区）
            mBridge = FaceOverlayBridge(findViewById(R.id.face_overlay))

            // 5. 注入算法结果回调 → 识别展示
            algo.setResultCallback { result ->
                runOnUiThread { onAlgorithmResult(result) }
            }

            // 进入页面立即刷新一次已导入数量（不依赖首帧算法结果）
            updateEnrolledCount()

            // 6. 打开相机
            if (!frame.open()) {
                Log.e(TAG, "startPreview: open camera failed")
                mFaceIdText.text = "相机打开失败"
            }
        } catch (e: Exception) {
            Log.e(TAG, "startPreview: failed", e)
        }
    }

    /** 停止预览并释放引用计数。 */
    private fun stopPreview() {
        try {
            mBridge?.clearFaces()
            mFrameSession?.release()
            mAlgoSession?.setResultCallback(null)
            mAlgoSession?.release()
        } catch (e: Exception) {
            Log.e(TAG, "stopPreview: error", e)
        } finally {
            mBridge = null
            mFrameSession = null
            mAlgoSession = null
        }
    }

    /** 算法结果回调（识别区桥接 + 识别文本展示）。 */
    private fun onAlgorithmResult(result: IFaceIDAlgorithm.FaceIDResult) {
        // 算法结果已修正回原图空间（1600×1300），用原图尺寸缩放显示（FACEP-011 裁剪映射）
        val distributor = mFrameSession?.frameDistributor()
        val imgW = distributor?.frameWidth ?: ORIGINAL_WIDTH
        val imgH = distributor?.frameHeight ?: ORIGINAL_HEIGHT
        mBridge?.setFaces(result, false, FaceOverlayBridge.Module.RECOGNITION, imgW, imgH)

        val faceId = result.faceId
        if (faceId != mLastFaceId) {
            mLastFaceId = faceId
            mFaceIdText.text = when {
                faceId.isEmpty() -> "无人脸"
                faceId == "detected" -> "检测到人脸（未识别）"
                faceId == "spoof" -> "疑似照片/翻拍"
                else -> "识别: $faceId"
            }
        }

        // 刷新已导入人脸数量（仅数量变化时更新）
        updateEnrolledCount()
    }

    /** 更新已导入人脸数量展示。 */
    private fun updateEnrolledCount() {
        val count = mAlgoSession?.algorithm()?.getEnrolledCount() ?: 0
        if (count != mLastEnrolledCount) {
            mLastEnrolledCount = count
            mEnrolledCountText.text = getString(R.string.enrolled_count_format, count)
        }
    }

    /** JNI 帧读取（复用 NativeFrameReader 公共能力）。 */
    private fun readFrame(hwBuffer: HardwareBuffer, width: Int, height: Int): ByteArray? {
        return NativeFrameReader.readHardwareBuffer(hwBuffer, width, height)
    }

    companion object {
        /** 原始帧尺寸（DMS 摄像头），算法结果坐标基于此缩放显示。 */
        private const val ORIGINAL_WIDTH = 1600
        private const val ORIGINAL_HEIGHT = 1300

        /** 人脸识别模块的算法流程（只跑识别相关，不跑疲劳/分心的头姿/视线）。 */
        private val RECOGNITION_FLAG = atlas.face.sdk.FaceFlag.DETECTION or
            atlas.face.sdk.FaceFlag.RECOGNITION or
            atlas.face.sdk.FaceFlag.LIVENESS or
            atlas.face.sdk.FaceFlag.LANDMARK
    }
}
