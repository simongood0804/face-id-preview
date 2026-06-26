/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.ui

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.skyworth.faceid.R
import com.skyworth.faceid.algorithm.IFaceIDAlgorithm
import com.skyworth.faceid.camera.CameraManager
import com.skyworth.faceid.pipeline.BufferManager
import com.skyworth.faceid.pipeline.FramePipeline
import com.skyworth.faceid.pipeline.PipelineConfig
import com.skyworth.faceid.render.PreviewRenderer

/**
 * Face ID 预览主界面。
 *
 * 职责：
 * - 初始化所有核心模块
 * - 管理 Activity 生命周期与流水线的绑定
 * - 提供开始/停止预览控制
 * - 显示 Face ID 检测状态信息
 *
 * 线程安全：UI 更新通过 [runOnUiThread] 进行，流水线在后台线程运行。
 */
class PreviewActivity : AppCompatActivity() {

    private val TAG = "PreviewActivity"

    // ============================================================
    // UI 控件
    // ============================================================

    private lateinit var mPreviewSurface: android.view.SurfaceView
    private lateinit var mToggleButton: Button
    private lateinit var mStatusText: TextView
    private lateinit var mFaceIdText: TextView
    private lateinit var mFrameRateText: TextView

    // ============================================================
    // 核心模块
    // ============================================================

    private var mCameraManager: CameraManager? = null
    private var mAlgorithm: IFaceIDAlgorithm? = null
    private var mBufferManager: BufferManager? = null
    private var mPreviewRenderer: PreviewRenderer? = null
    private var mFramePipeline: FramePipeline? = null
    private var mPipelineConfig: PipelineConfig? = null

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
        mPipelineConfig = PipelineConfig()

        // 摄像头管理器
        mCameraManager = CameraManager(object : CameraManager.StateCallback {
            override fun onCameraOpened() {
                runOnUiThread { mStatusText.setText(R.string.status_previewing) }
            }

            override fun onCameraClosed() {
                runOnUiThread { mStatusText.setText(R.string.status_idle) }
            }

            override fun onHalDied() {
                runOnUiThread {
                    mStatusText.setText(R.string.status_camera_disconnected)
                    Toast.makeText(this@PreviewActivity,
                        R.string.status_camera_disconnected, Toast.LENGTH_LONG).show()
                    stopPreview()
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    mStatusText.setText(getString(R.string.status_error, message))
                    Toast.makeText(this@PreviewActivity,
                        getString(R.string.status_error, message), Toast.LENGTH_LONG).show()
                }
            }
        })

        // 算法接口 — 使用 Dummy 实现
        mAlgorithm = createDummyAlgorithm()

        // 预览渲染器
        mPreviewRenderer = PreviewRenderer()
        mPreviewRenderer?.setSurfaceView(mPreviewSurface)

        // Buffer 管理器
        mBufferManager = BufferManager(
            mCameraManager!!,
            mPipelineConfig!!.bufferRecycleTimeoutMs
        )

        // 初始化状态文本
        mStatusText.setText(R.string.status_idle)
        mFaceIdText.setText(getString(R.string.face_id_label) + " " + getString(R.string.face_id_none))
        mFrameRateText.setText(getString(R.string.frame_rate_label) + " 0 " + getString(R.string.frame_rate_value, 0))
    }

    /**
     * 创建流水线（仅在启动预览时创建）。
     */
    private fun createPipeline() {
        mFramePipeline = object : FramePipeline(
            mCameraManager!!,
            mAlgorithm!!,
            mBufferManager!!,
            mPreviewRenderer!!,
            mPipelineConfig!!
        ) {
            override fun onFaceIdDetected(result: IFaceIDAlgorithm.FaceIDResult) {
                runOnUiThread {
                    if (result.faceId.isNotEmpty()) {
                        mFaceIdText.setText(getString(R.string.face_id_label) + " " +
                                result.faceId +
                                " (${String.format("%.1f", result.confidence * 100)}%)")
                    } else {
                        mFaceIdText.setText(getString(R.string.face_id_label) + " " +
                                getString(R.string.face_id_not_detected))
                    }
                }
            }
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
            createPipeline()
            mPreviewRenderer?.start()
            mFramePipeline?.start()

            mIsPreviewing = true
            mToggleButton.setText(R.string.btn_stop_preview)
            mStatusText.setText(R.string.status_initializing)
            Log.i(TAG, "startPreview: done")
        } catch (e: Exception) {
            Log.e(TAG, "startPreview: failed", e)
            mStatusText.setText(getString(R.string.status_error, e.message ?: ""))
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
            mFramePipeline?.stop()
            mFramePipeline = null

            mPreviewRenderer?.stop()

            mCameraManager?.stopCamera()
        } catch (e: Exception) {
            Log.e(TAG, "stopPreview: error", e)
        } finally {
            mIsPreviewing = false
            mToggleButton.setText(R.string.btn_start_preview)
            mStatusText.setText(R.string.status_idle)
            mFaceIdText.setText(getString(R.string.face_id_label) + " " +
                    getString(R.string.face_id_none))
            mFrameRateText.setText(getString(R.string.frame_rate_label) + " 0 " +
                    getString(R.string.frame_rate_value, 0))
            Log.i(TAG, "stopPreview: done")
        }
    }

    // ============================================================
    // Activity 生命周期管理
    // ============================================================

    override fun onPause() {
        super.onPause()
        stopPreview()
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
                // 模拟处理耗时
                try {
                    Thread.sleep(5)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                // 原样返回帧数据，不检测人脸
                return IFaceIDAlgorithm.FaceIDResult(processedData = frameData)
            }

            override fun release() {
                mInitialized = false
                Log.i(TAG, "createDummyAlgorithm: dummy released")
            }
        }
    }
}
