/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.ui

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.android.car.evs.EvsGL20CameraRenderer
import com.skyworth.faceid.R
import com.skyworth.faceid.algorithm.IFaceIDAlgorithm
import com.skyworth.faceid.camera.CameraManager
import com.skyworth.faceid.camera.FaceIDCameraController

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
    private lateinit var mToggleButton: Button
    private lateinit var mStatusText: TextView
    private lateinit var mFaceIdText: TextView
    private lateinit var mFrameRateText: TextView

    // ============================================================
    // 核心模块
    // ============================================================

    private var mCameraManager: CameraManager? = null
    private var mAlgorithm: IFaceIDAlgorithm? = null
    private var mFaceIDController: FaceIDCameraController? = null
    private var mRenderer: EvsGL20CameraRenderer? = null

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
        // 算法接口 — 使用 Dummy 实现
        mAlgorithm = createDummyAlgorithm()

        // 自定义控制器（与 FiveCameraController 的 MyEvsCameraController 一致）
        mFaceIDController = FaceIDCameraController()

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
        mFrameRateText.text = getString(R.string.frame_rate_label) + " 0 " + getString(R.string.frame_rate_value, 0)
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
    // 算法实现
    // ============================================================

    /**
     * 创建模拟算法实现用于开发和测试。
     */
    private fun createDummyAlgorithm(): IFaceIDAlgorithm {
        return object : IFaceIDAlgorithm {
            private var mInitialized = false

            override fun initialize(context: Context?, config: MutableMap<String, Any>): Boolean {
                mInitialized = true
                Log.i(TAG, "createDummyAlgorithm: dummy initialized")
                return true
            }

            override fun processFrame(
                frameData: ByteArray?,
                width: Int,
                height: Int,
                format: Int
            ): IFaceIDAlgorithm.FaceIDResult {
                try {
                    Thread.sleep(5)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                return IFaceIDAlgorithm.FaceIDResult(processedData = frameData)
            }

            override fun release() {
                mInitialized = false
                Log.i(TAG, "createDummyAlgorithm: dummy released")
            }
        }
    }
}
