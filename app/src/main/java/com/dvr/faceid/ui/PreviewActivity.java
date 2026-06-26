/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.ui;

import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.skyworth.faceid.R;
import com.skyworth.faceid.algorithm.IFaceIDAlgorithm;
import com.skyworth.faceid.camera.CameraManager;
import com.skyworth.faceid.pipeline.BufferManager;
import com.skyworth.faceid.pipeline.FramePipeline;
import com.skyworth.faceid.pipeline.PipelineConfig;
import com.skyworth.faceid.render.PreviewRenderer;

/**
 * Face ID 预览主界面。
 *
 * <p>职责：
 * <ul>
 *   <li>初始化所有核心模块</li>
 *   <li>管理 Activity 生命周期与流水线的绑定</li>
 *   <li>提供开始/停止预览控制</li>
 *   <li>显示 Face ID 检测状态信息</li>
 * </ul>
 *
 * <p>线程安全：UI 更新通过 {@link #runOnUiThread} 进行，流水线在后台线程运行。
 */
public class PreviewActivity extends AppCompatActivity {

    private static final String TAG = "PreviewActivity";

    // ============================================================
    // UI 控件
    // ============================================================

    private SurfaceView mPreviewSurface;
    private Button mToggleButton;
    private TextView mStatusText;
    private TextView mFaceIdText;
    private TextView mFrameRateText;

    // ============================================================
    // 核心模块
    // ============================================================

    private CameraManager mCameraManager;
    private IFaceIDAlgorithm mAlgorithm;
    private BufferManager mBufferManager;
    private PreviewRenderer mPreviewRenderer;
    private FramePipeline mFramePipeline;
    private PipelineConfig mPipelineConfig;

    /** 是否正在预览。 */
    private boolean mIsPreviewing;

    // ============================================================
    // Activity 生命周期
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);

        initViews();
        initCoreModules();

        Log.i(TAG, "onCreate: done");
    }

    /**
     * 初始化 UI 控件。
     */
    private void initViews() {
        mPreviewSurface = findViewById(R.id.preview_surface);
        mToggleButton = findViewById(R.id.btn_toggle);
        mStatusText = findViewById(R.id.tv_status);
        mFaceIdText = findViewById(R.id.tv_face_id);
        mFrameRateText = findViewById(R.id.tv_frame_rate);

        mToggleButton.setOnClickListener(v -> togglePreview());
    }

    /**
     * 初始化核心模块。
     *
     * <p>按依赖顺序初始化：
     * <ol>
     *   <li>{@link PipelineConfig} — 流水线配置</li>
     *   <li>{@link CameraManager} — 摄像头管理</li>
     *   <li>{@link IFaceIDAlgorithm} — 算法接口</li>
     *   <li>{@link PreviewRenderer} — 预览渲染器</li>
     *   <li>{@link BufferManager} — Buffer 管理</li>
     * </ol>
     */
    private void initCoreModules() {
        mPipelineConfig = new PipelineConfig();

        // 摄像头管理器
        mCameraManager = new CameraManager(new CameraManager.StateCallback() {
            @Override
            public void onCameraOpened() {
                runOnUiThread(() -> mStatusText.setText(R.string.status_previewing));
            }

            @Override
            public void onCameraClosed() {
                runOnUiThread(() -> mStatusText.setText(R.string.status_idle));
            }

            @Override
            public void onHalDied() {
                runOnUiThread(() -> {
                    mStatusText.setText(R.string.status_camera_disconnected);
                    Toast.makeText(PreviewActivity.this,
                            R.string.status_camera_disconnected, Toast.LENGTH_LONG).show();
                    stopPreview();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    mStatusText.setText(getString(R.string.status_error, message));
                    Toast.makeText(PreviewActivity.this,
                            getString(R.string.status_error, message), Toast.LENGTH_LONG).show();
                });
            }
        });

        // 算法接口 — 先使用 Dummy 实现
        mAlgorithm = createDummyAlgorithm();

        // 预览渲染器
        mPreviewRenderer = new PreviewRenderer();
        mPreviewRenderer.setSurfaceView(mPreviewSurface);

        // Buffer 管理器
        mBufferManager = new BufferManager(
                mCameraManager, mPipelineConfig.getBufferRecycleTimeoutMs());

        // 初始化状态文本
        mStatusText.setText(R.string.status_idle);
        mFaceIdText.setText(getString(R.string.face_id_label)
                + " " + getString(R.string.face_id_none));
        mFrameRateText.setText(getString(R.string.frame_rate_label)
                + " 0 " + getString(R.string.frame_rate_value, 0));
    }

    /**
     * 创建流水线（仅在启动预览时创建）。
     */
    private void createPipeline() {
        mFramePipeline = new FramePipeline(
                mCameraManager, mAlgorithm, mBufferManager,
                mPreviewRenderer, mPipelineConfig
        ) {
            @Override
            protected void onFaceIdDetected(IFaceIDAlgorithm.FaceIDResult result) {
                runOnUiThread(() -> {
                    if (result.getFaceId() != null && !result.getFaceId().isEmpty()) {
                        mFaceIdText.setText(getString(R.string.face_id_label)
                                + " " + result.getFaceId()
                                + " (" + String.format("%.1f",
                                        result.getConfidence() * 100) + "%)");
                    } else {
                        mFaceIdText.setText(getString(R.string.face_id_label)
                                + " " + getString(R.string.face_id_not_detected));
                    }
                });
            }
        };
    }

    // ============================================================
    // 预览控制
    // ============================================================

    /**
     * 切换预览状态（开始/停止）。
     */
    private void togglePreview() {
        if (mIsPreviewing) {
            stopPreview();
        } else {
            startPreview();
        }
    }

    /**
     * 开始预览。
     */
    private void startPreview() {
        if (mIsPreviewing) {
            return;
        }

        try {
            mCameraManager.openCamera();
            createPipeline();
            mPreviewRenderer.start();
            mFramePipeline.start();

            mIsPreviewing = true;
            mToggleButton.setText(R.string.btn_stop_preview);
            mStatusText.setText(R.string.status_initializing);
            Log.i(TAG, "startPreview: done");

        } catch (Exception e) {
            Log.e(TAG, "startPreview: failed", e);
            mStatusText.setText(getString(R.string.status_error, e.getMessage()));
            Toast.makeText(this,
                    getString(R.string.status_error, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 停止预览。
     */
    private void stopPreview() {
        if (!mIsPreviewing) {
            return;
        }

        try {
            if (mFramePipeline != null) {
                mFramePipeline.stop();
                mFramePipeline = null;
            }

            if (mPreviewRenderer != null) {
                mPreviewRenderer.stop();
            }

            if (mCameraManager != null) {
                mCameraManager.stopCamera();
            }

        } catch (Exception e) {
            Log.e(TAG, "stopPreview: error", e);
        } finally {
            mIsPreviewing = false;
            mToggleButton.setText(R.string.btn_start_preview);
            mStatusText.setText(R.string.status_idle);
            mFaceIdText.setText(getString(R.string.face_id_label)
                    + " " + getString(R.string.face_id_none));
            mFrameRateText.setText(getString(R.string.frame_rate_label)
                    + " 0 " + getString(R.string.frame_rate_value, 0));
            Log.i(TAG, "stopPreview: done");
        }
    }

    // ============================================================
    // Activity 生命周期管理
    // ============================================================

    @Override
    protected void onPause() {
        super.onPause();
        stopPreview();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPreview();
        if (mAlgorithm != null) {
            mAlgorithm.release();
        }
        Log.i(TAG, "onDestroy: done");
    }

    // ============================================================
    // 算法实现
    // ============================================================

    /**
     * 创建模拟算法实现用于开发和测试。
     *
     * <p>在没有算法库接入时，模拟算法直接返回原数据并继续后续绘制流程。
     * 算法团队接入时替换为真实 {@link IFaceIDAlgorithm} 实现。
     *
     * <p>行为：
     * <ul>
     *   <li>不做人脸检测</li>
     *   <li>返回空 faceId + 置信度 0</li>
     *   <li>原样返回帧数据 → 渲染管线继续正常绘制原始帧</li>
     * </ul>
     */
    private IFaceIDAlgorithm createDummyAlgorithm() {
        return new IFaceIDAlgorithm() {
            private boolean mInitialized;

            @Override
            public boolean initialize(android.content.Context context,
                                       java.util.Map<String, Object> config) {
                mInitialized = true;
                Log.i(TAG, "createDummyAlgorithm: dummy initialized");
                return true;
            }

            @Override
            public FaceIDResult processFrame(byte[] frameData,
                                              int width, int height, int format) {
                // 模拟处理耗时
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // 原样返回帧数据，不检测人脸
                return new FaceIDResult("", 0f, null, frameData, null);
            }

            @Override
            public void release() {
                mInitialized = false;
                Log.i(TAG, "createDummyAlgorithm: dummy released");
            }
        };
    }
}
