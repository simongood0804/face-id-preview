/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.ui

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.android.car.evs.EvsGL20CameraRenderer
import com.skyworth.faceid.R
import com.skyworth.faceid.algorithm.FaceEnrollmentManager
import com.skyworth.faceid.algorithm.FaceIDAlgorithmImpl
import com.skyworth.faceid.algorithm.IFaceIDAlgorithm
import com.skyworth.faceid.camera.CameraManager
import com.skyworth.faceid.camera.FaceIDCameraController
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Face ID 预览主界面。
 *
 * 使用 [EvsGL20CameraRenderer] 渲染 EVS 摄像头画面，
 * 通过 [FaceIDCameraController] 管理摄像头取流（含自动重试）。
 */
class PreviewActivity : AppCompatActivity() {

    private val TAG = "PreviewActivity"

    // ============================================================
    // UI 控件
    // ============================================================

    private lateinit var mPreviewSurface: GLSurfaceView
    private lateinit var mFaceOverlay: FaceOverlayView
    private lateinit var mToggleButton: Button
    private lateinit var mStatusText: TextView
    private lateinit var mFaceIdText: TextView
    private lateinit var mFrameRateText: TextView

    // ============================================================
    // 核心模块
    // ============================================================

    private var mCameraManager: CameraManager? = null
    @Volatile private var mAlgorithm: FaceIDAlgorithmImpl? = null
    private var mEnrollmentManager: FaceEnrollmentManager? = null
    private var mFaceIDController: FaceIDCameraController? = null
    private var mRenderer: EvsGL20CameraRenderer? = null

    /** 算法处理线程池（单线程，避免 GL 线程阻塞）。 */
    private val mAlgoExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AlgoProcessor").apply { isDaemon = true }
    }

    /** 是否正在预览。 */
    private var mIsPreviewing = false

    // ============================================================
    // Activity 生命周期
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        initViews()
        initCoreModules()

        Log.i(TAG, "onCreate: done")
    }

    /**
     * 初始化 UI 控件。
     */
    private fun initViews() {
        mPreviewSurface = findViewById(R.id.preview_surface)
        mFaceOverlay = findViewById(R.id.face_overlay)
        mToggleButton = findViewById(R.id.btn_toggle)
        mStatusText = findViewById(R.id.tv_status)
        mFaceIdText = findViewById(R.id.tv_face_id)
        mFrameRateText = findViewById(R.id.tv_frame_rate)

        mToggleButton.setOnClickListener { togglePreview() }
    }

    /**
     * 初始化核心模块。
     */
    private fun initCoreModules() {
        // 算法接口 — 使用 FaceID 原生算法
        mAlgorithm = FaceIDAlgorithmImpl()

        // 人脸录入管理器（持久化存储 embedding）
        mEnrollmentManager = FaceEnrollmentManager(this, mAlgorithm!!)
        mAlgorithm!!.setEnrollmentManager(mEnrollmentManager!!)
        Log.i(TAG, "initCoreModules: enrolled faces: ${mEnrollmentManager?.getCount()}")
        val algoConfig = mutableMapOf<String, Any>(
            "runtime" to "dsp"
        )
        if (mAlgorithm?.initialize(this, algoConfig) == true) {
            Log.i(TAG, "initCoreModules: algorithm initialized successfully")
        } else {
            Log.w(TAG, "initCoreModules: algorithm init failed, will retry")
        }

        // 自定义控制器（与 FiveCameraController 的 MyEvsCameraController 一致）
        mFaceIDController = FaceIDCameraController().also { controller ->
            // 帧尺寸回调：调整 GLSurfaceView 保持画面比例、靠左显示
            controller.onFrameSizeChanged = { width, height ->
                runOnUiThread { resizePreviewSurface(width, height) }
            }
            // 帧数据处理回调：传入算法进行人脸检测
            controller.onFrameData = { hwBuffer, frameW, frameH ->
                processWithAlgorithm(hwBuffer, frameW, frameH)
            }
        }

        // 摄像头管理器（传入同一个 controller 实例）
        mCameraManager = CameraManager(mFaceIDController!!)

        // GL 渲染器（传入同一个 controller 实例）
        mRenderer = EvsGL20CameraRenderer().apply {
            setProvider(mFaceIDController!!)
        }

        // 配置 GLSurfaceView
        mPreviewSurface.setEGLContextClientVersion(2)
        mPreviewSurface.setRenderer(mRenderer!!)
        mPreviewSurface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // 初始化状态文本
        mStatusText.setText(R.string.status_idle)
        mFaceIdText.text = getString(R.string.face_id_label) + " " + getString(R.string.face_id_none)
        mFrameRateText.text = getString(R.string.frame_rate_label) + " " + getString(R.string.frame_rate_value, 0)

        // 观察帧率实时数据
        mCameraManager?.frameRate?.value?.observe(this) { fps ->
            mFrameRateText.text = getString(R.string.frame_rate_label) + " " + getString(R.string.frame_rate_value, fps)
        }
    }

    // ============================================================
    // 预览控制
    // ============================================================

    /**
     * 切换预览状态（开始/停止）。
     */
    private fun togglePreview() {
        if (mIsPreviewing) stopPreview() else startPreview()
    }

    /**
     * 开始预览。
     */
    private fun startPreview() {
        if (mIsPreviewing) return

        try {
            mCameraManager?.openCamera()
            mIsPreviewing = true
            mToggleButton.setText(R.string.btn_stop_preview)
            mStatusText.setText(R.string.status_previewing)
            Log.i(TAG, "startPreview: done")
        } catch (e: Exception) {
            Log.e(TAG, "startPreview: failed", e)
            mStatusText.text = getString(R.string.status_error, e.message ?: "")
            Toast.makeText(this,
                getString(R.string.status_error, e.message ?: ""),
                Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 停止预览。
     */
    private fun stopPreview() {
        if (!mIsPreviewing) return

        try {
            mCameraManager?.stopCamera()
        } catch (e: Exception) {
            Log.e(TAG, "stopPreview: error", e)
        } finally {
            mIsPreviewing = false
            mToggleButton.setText(R.string.btn_start_preview)
            mStatusText.setText(R.string.status_idle)
            mFaceIdText.text = getString(R.string.face_id_label) + " " +
                    getString(R.string.face_id_none)
            mFrameRateText.text = getString(R.string.frame_rate_label) + " 0 " +
                    getString(R.string.frame_rate_value, 0)
            Log.i(TAG, "stopPreview: done")
        }
    }

    // ============================================================
    // 视口调整
    // ============================================================

    /**
     * 根据帧尺寸调整 [GLSurfaceView] 大小，保持画面原始宽高比、靠左显示。
     * 未覆盖区域由黑色背景填充。
     */
    private fun resizePreviewSurface(frameW: Int, frameH: Int) {
        val parent = mPreviewSurface.parent as View
        val parentW = parent.width
        val parentH = parent.height
        if (parentW <= 0 || parentH <= 0 || frameW <= 0 || frameH <= 0) return

        val frameAspect = frameW.toFloat() / frameH.toFloat()
        val parentAspect = parentW.toFloat() / parentH.toFloat()

        val targetW: Int
        val targetH: Int
        if (frameAspect > parentAspect) {
            // 画面更宽：以父容器宽度为准，高度按比例缩放
            targetW = parentW
            targetH = (parentW / frameAspect).toInt()
        } else {
            // 画面更高：以父容器高度为准，宽度按比例缩放
            targetH = parentH
            targetW = (parentH * frameAspect).toInt()
        }

        val lp = mPreviewSurface.layoutParams as ConstraintLayout.LayoutParams
        lp.width = targetW
        lp.height = targetH
        // 移除右侧和底部约束，保持顶部+左侧贴边
        lp.rightToRight = ConstraintLayout.LayoutParams.UNSET
        lp.bottomToTop = ConstraintLayout.LayoutParams.UNSET
        mPreviewSurface.layoutParams = lp
    }

    // ============================================================
    // Activity 生命周期管理
    // ============================================================

    override fun onPause() {
        super.onPause()
        mPreviewSurface.onPause()
        stopPreview()
    }

    override fun onResume() {
        super.onResume()
        mPreviewSurface.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPreview()
        mAlgorithm?.release()
        Log.i(TAG, "onDestroy: done")
    }

    // ============================================================
    // 算法处理
    // ============================================================

    /**
     * 后台异步执行算法检测。
     *
     * 1. GL 线程：wrapHardwareBuffer() 创建 HARDWARE Bitmap（轻量，仅 GPU 引用）
     * 2. 后台线程：copy(ARGB_8888) → copyPixelsToBuffer → processFrame
     *    copy() 在后台线程执行，避免 GL 线程 GPU 同步死锁
     */
    private fun processWithAlgorithm(hwBuffer: android.hardware.HardwareBuffer, frameW: Int, frameH: Int) {
        if (mAlgorithm == null) return
        // 在 GL 线程上同步读取像素数据（此时 HardwareBuffer 有效）
        val rawData = try {
            nativeReadHardwareBuffer(hwBuffer, frameW, frameH)
        } catch (e: Exception) {
            Log.w(TAG, "algo: read failed", e)
            return
        }
        if (rawData == null) { Log.w(TAG, "algo: read null"); return }

        // 像素数据已拷贝到 Java heap，后台线程安全使用
        mAlgoExecutor.submit {
            try {
                val result = mAlgorithm?.processFrame(rawData, frameW, frameH, 0) // UYVY

                if (result != null && result.faceId.isNotEmpty()) {
                    runOnUiThread {
                        val enrolledTotal = mEnrollmentManager?.getCount() ?: 0
                        val displayText = getString(R.string.face_id_label) + " " + result.faceId +
                                " (${String.format("%.1f", result.confidence * 100)}%)" +
                                " | 已录入: $enrolledTotal"
                        mFaceIdText.text = displayText

                        // 新录入弹出 Toast
                        if (result.isNewEnrollment) {
                            Toast.makeText(this,
                                "录入成功: $displayText",
                                Toast.LENGTH_SHORT).show()
                        }

                        // 传递人脸框到覆盖层
                        if (result.faceRect != null) {
                            val isNamed = result.faceId != "detected" &&
                                    result.faceId != "spoof" &&
                                    result.faceId.isNotEmpty()
                            val isDetected = isNamed || result.faceId == "detected"
                            mFaceOverlay.setFaces(
                                listOf(FaceOverlayView.FaceBox(
                                    rect = result.faceRect,
                                    type = if (isDetected) FaceOverlayView.FaceType.DETECTED
                                           else FaceOverlayView.FaceType.SPOOF,
                                    confidence = result.confidence,
                                    label = if (isNamed) result.faceId else null
                                )),
                                frameW, frameH
                            )
                            mFaceOverlay.visibility = View.VISIBLE
                        }
                    }
                } else {
                    runOnUiThread { mFaceOverlay.clearFaces() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "algorithm error", e)
            }
        }
    }

    companion object {
        init {
            try {
                System.loadLibrary("faceid_jni")
            } catch (_: UnsatisfiedLinkError) { }
        }
    }

    private external fun nativeReadHardwareBuffer(
        hwBuffer: android.hardware.HardwareBuffer, width: Int, height: Int
    ): ByteArray?
}
