/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.ui

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import com.android.car.evs.EvsGL20CameraRenderer
import com.skyworth.faceid.R
import com.skyworth.faceid.algorithm.FaceEnrollmentManager
import com.skyworth.faceid.algorithm.FaceIDAlgorithmImpl
import com.skyworth.faceid.algorithm.FrameProcessor
import com.skyworth.faceid.algorithm.IFaceIDAlgorithm
import com.skyworth.faceid.camera.CameraManager
import com.skyworth.faceid.camera.FaceIDCameraController
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
    private lateinit var mDumpButton: Button
    private lateinit var mClearDumpButton: Button
    private lateinit var mMoveDumpButton: Button
    private lateinit var mStatusText: TextView
    private lateinit var mFaceIdText: TextView
    private lateinit var mFrameRateText: TextView
    private lateinit var mSpeedText: TextView

    // ============================================================
    // 核心模块
    // ============================================================

    private var mCameraManager: CameraManager? = null
    @Volatile private var mAlgorithm: FaceIDAlgorithmImpl? = null
    private var mEnrollmentManager: FaceEnrollmentManager? = null
    private var mFaceIDController: FaceIDCameraController? = null
    private var mRenderer: EvsGL20CameraRenderer? = null
    private var mFrameProcessor: FrameProcessor? = null

    /** 算法处理线程池（单线程，避免 GL 线程阻塞）。 */
    private val mAlgoExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AlgoProcessor").apply { isDaemon = true }
    }

    /** 是否正在预览。 */
    private var mIsPreviewing = false

    /** 算法是否开启。 */
    private var mAlgorithmEnabled = true

    // ============================================================
    // 车速信号（Car VHAL）与分心防抖
    // ============================================================

    /** Car 服务连接。 */
    private var mCar: Car? = null
    private var mCarPropertyManager: CarPropertyManager? = null

    /** 当前车速（km/h）。-1 表示无车速数据（此时按高速档判定）。 */
    @Volatile private var mVehicleSpeedKmh = -1f

    /** 分心是否已确认触发（多帧/时间防抖后的结果，传给 Overlay 显示）。 */
    @Volatile private var mDistractActive = false

    /** 最近一次分心状态累积起始时间戳（elapsedRealtime）。 */
    private var mDistractAccumStart = 0L

    /** 当前生效的分心触发阈值（ms），随车速分档更新。 */
    private var mDistractTriggerMs = DISTRACT_TRIGGER_MS_FAST

    // ============================================================
    // Activity 生命周期
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        initViews()
        initCoreModules()
        connectVehicleSpeed()

        // 自动开始预览
        startPreview()

        Log.i(TAG, "onCreate: done")
    }

    /**
     * 初始化 UI 控件。
     */
    private fun initViews() {
        mPreviewSurface = findViewById(R.id.preview_surface)
        mFaceOverlay = findViewById(R.id.face_overlay)
        mToggleButton = findViewById(R.id.btn_toggle)
        mDumpButton = findViewById(R.id.btn_dump)
        mClearDumpButton = findViewById(R.id.btn_clear_dump)
        mMoveDumpButton = findViewById(R.id.btn_move_dump)
        mStatusText = findViewById(R.id.tv_status)
        mFaceIdText = findViewById(R.id.tv_face_id)
        mFrameRateText = findViewById(R.id.tv_frame_rate)
        mSpeedText = findViewById(R.id.tv_speed)

        loadAlgorithmState()
        mToggleButton.setOnClickListener { toggleAlgorithm() }
        mToggleButton.setText(
            if (mAlgorithmEnabled) R.string.btn_algo_on else R.string.btn_algo_off
        )
        mDumpButton.setOnClickListener { onDumpClick() }
        mClearDumpButton.setOnClickListener { onClearDumpClick() }
        mMoveDumpButton.setOnClickListener { onMoveDumpClick() }
    }

    /**
     * 初始化核心模块。
     */
    private fun initCoreModules() {
        // 算法接口 — 使用 FaceID AAR SDK
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

        // 帧处理器（单槽替换，GL 线程读取 HardwareBuffer 后以 ByteArray 提交）
        mFrameProcessor = FrameProcessor(
            mAlgorithm!!, mAlgoExecutor
        ) { result ->
            runOnUiThread { handleAlgorithmResult(result) }
        }

        // 自定义控制器（与 FiveCameraController 的 MyEvsCameraController 一致）
        mFaceIDController = FaceIDCameraController().also { controller ->
            // 帧尺寸回调：调整 GLSurfaceView 保持画面比例、靠左显示
            controller.onFrameSizeChanged = { width, height ->
                runOnUiThread { resizePreviewSurface(width, height) }
            }
            // 帧数据处理回调：传入算法进行人脸检测（GL 线程）
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
    // 算法开关控制
    // ============================================================

    /**
     * 切换算法开启/关闭状态。
     */
    private fun toggleAlgorithm() {
        mAlgorithmEnabled = !mAlgorithmEnabled
        mToggleButton.setText(
            if (mAlgorithmEnabled) R.string.btn_algo_on else R.string.btn_algo_off
        )
        saveAlgorithmState()
        Log.i(TAG, "algorithm ${if (mAlgorithmEnabled) "enabled" else "disabled"}")
    }

    /**
     * 手动触发 dump：后台保存最近一帧原始图像，完成后 Toast 提示。
     * 若系统属性未启用 dump，弹框提示设置系统属性。
     */
    private fun onDumpClick() {
        val algo = mAlgorithm
        if (algo == null) {
            Toast.makeText(this, "dump: algorithm not initialized", Toast.LENGTH_SHORT).show()
            return
        }
        if (!algo.isDumpAvailable()) {
            Toast.makeText(this, "dump: system property disabled, set algorithm_face_dump_enable first", Toast.LENGTH_LONG).show()
            return
        }
        algo.triggerManualDump { ok ->
            val msg = if (ok) "dump: saved" else "dump: failed"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        Log.i(TAG, "onDumpClick: manual dump triggered")
    }

    /**
     * 清除 debugDump 文件夹内容，并删除 /sdcard/debugDmsDump 文件夹。
     */
    private fun onClearDumpClick() {
        val algo = mAlgorithm
        if (algo == null) {
            Toast.makeText(this, "clear dump: algorithm not initialized", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "clear dump: running", Toast.LENGTH_SHORT).show()
        algo.clearDumpDirs { ok ->
            val msg = if (ok) "clear dump: done" else "clear dump: failed"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        Log.i(TAG, "onClearDumpClick: clear triggered")
    }

    /**
     * 将 debugDump 中的 png 图像移动到 /sdcard/debugDmsDump 文件夹。
     */
    private fun onMoveDumpClick() {
        val algo = mAlgorithm
        if (algo == null) {
            Toast.makeText(this, "move dump: algorithm not initialized", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "move dump: running", Toast.LENGTH_SHORT).show()
        algo.moveDumpPngToSdcard { ok ->
            val msg = if (ok) "move dump: done" else "move dump: failed"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        Log.i(TAG, "onMoveDumpClick: move triggered")
    }

    // ============================================================
    // 预览控制
    // ============================================================

    /**
     * 开始预览。
     */
    private fun startPreview() {
        if (mIsPreviewing) return

        try {
            mCameraManager?.openCamera()
            mIsPreviewing = true
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
        if (!mIsPreviewing) {
            startPreview()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPreview()
        mAlgorithm?.release()
        disconnectVehicleSpeed()
        Log.i(TAG, "onDestroy: done")
    }

    // ============================================================
    // 算法处理
    // ============================================================

    /** 当前帧尺寸缓存（供 handleAlgorithmResult 使用）。 */
    private var mCurrentFrameW = 0
    private var mCurrentFrameH = 0

    // ============================================================
    // 绘制防抖
    // ============================================================

    /** 连续无结果帧计数（达到阈值才清除画框）。 */
    private var mNoFaceCount = 0

    /** 隐藏画框所需的连续无检测帧数（约 5×16ms = 80ms 延迟）。 */
    private val FACE_HIDE_THRESHOLD = 5

    /**
     * GL 线程回调：立即读取 HardwareBuffer 转为 ByteArray，再提交给算法线程（非阻塞）。
     * 必须在 GL 线程读取 buffer，因为 EVS 帧回调返回后 buffer 可能被回收。
     */
    private fun processWithAlgorithm(hwBuffer: HardwareBuffer, frameW: Int, frameH: Int) {
        if (!mAlgorithmEnabled) return  // 算法关闭，跳过处理

        val fp = mFrameProcessor ?: return
        mCurrentFrameW = frameW
        mCurrentFrameH = frameH

        // 在 GL 线程立即读取 buffer 数据（buffer 此时有效）
        val data = readHardwareBuffer(hwBuffer, frameW, frameH)
        if (data == null) return

        fp.submitFrame(data, frameW, frameH)
    }

    /**
     * 算法结果回调（AlgoProcessor 线程 → runOnUiThread）。
     * 仅对隐藏做防抖：连续 N 帧无人脸才清除画框。
     * 显示和位置更新不做防抖，保证即时响应。
     */
    private fun handleAlgorithmResult(result: IFaceIDAlgorithm.FaceIDResult) {
        val frameW = mCurrentFrameW
        val frameH = mCurrentFrameH

        // 更新裁剪窗口（黄色采样框）
        val fp = mFrameProcessor
        if (fp != null) {
            val size = 900
            mFaceOverlay.setCropRect(
                android.graphics.RectF(
                    fp.cropLeft.toFloat(), fp.cropTop.toFloat(),
                    (fp.cropLeft + size).toFloat(), (fp.cropTop + size).toFloat()
                )
            )
        }

        if (result.faceId.isNotEmpty()) {
            // 有人脸 → 即时显示，无防抖
            mNoFaceCount = 0

            // 分心多帧/时间防抖：根据车速分档判定触发阈值
            val distractActive = updateDistraction(result)
            // 分心提示：固定位置绘制（不随人脸移动）
            mFaceOverlay.setDistracted(distractActive)

            val faceId = result.faceId
            val enrolledTotal = mEnrollmentManager?.getCount() ?: 0
            val displayText = getString(R.string.face_id_label) + " " + faceId +
                    " (${String.format("%.1f", result.confidence * 100)}%)" +
                    " | 已录入: $enrolledTotal"
            mFaceIdText.text = displayText

            if (result.isNewEnrollment) {
                Toast.makeText(this, "录入成功: $displayText", Toast.LENGTH_SHORT).show()
            }

            if (result.faceRect != null) {
                val isNamed = faceId != "detected" && faceId != "spoof"
                val overlayType = if (isNamed || faceId == "detected")
                    FaceOverlayView.FaceType.DETECTED
                else FaceOverlayView.FaceType.SPOOF
                mFaceOverlay.setFaces(
                    listOf(FaceOverlayView.FaceBox(
                        rect = result.faceRect,
                        type = overlayType,
                        confidence = result.confidence,
                        label = if (isNamed) faceId else null,
                        keypoints = result.keypoints,
                        denseLandmarks = result.landmarks,
                        pitch = result.headposePitch,
                        yaw = result.headposeYaw,
                        roll = result.headposeRoll,
                        gazeValid = result.gazeValid,
                        gazeYaw = result.gazeYaw,
                        gazePitch = result.gazePitch,
                        gazeCalibrated = result.gazeCalibrated,
                        gazeDistracted = if (distractActive) 1f else 0f,
                        zoneId = result.zoneId
                    )),
                    frameW, frameH
                )
                mFaceOverlay.visibility = View.VISIBLE
            }
        } else {
            // 无人脸 → 防抖隐藏
            mNoFaceCount++
            // 无人脸时重置分心状态并清除分心提示
            resetDistraction()
            mFaceOverlay.setDistracted(false)
            if (mNoFaceCount >= FACE_HIDE_THRESHOLD) {
                mFaceOverlay.clearFaces()
                mFaceIdText.text = getString(R.string.face_id_label) + " " +
                        getString(R.string.face_id_none)
            }
        }
    }

    // ============================================================
    // 分心防抖 + 车速信号
    // ============================================================

    /**
     * 分心多帧/时间防抖状态机。
     *
     * 算法输出的 gazeDistracted 为单帧结果，存在误检抖动。这里按"持续时间"
     * 累计判定（而非帧数，因单槽替换+跳帧导致帧率不稳定）：
     *  - 连续分心达到当前车速对应的触发阈值 → 触发分心；
     *  - 已触发后，连续非分心达到解除阈值 → 解除分心。
     *
     * @return 防抖后是否判定为分心（用于 UI 显示）。
     */
    private fun updateDistraction(result: IFaceIDAlgorithm.FaceIDResult): Boolean {
        val now = SystemClock.elapsedRealtime()
        val distracted = result.gazeDistracted > 0f

        // 根据车速分档选择触发阈值：无车速数据(<0)或高速(≥50)用快速档，低速用慢速档
        mDistractTriggerMs = if (mVehicleSpeedKmh >= 0f && mVehicleSpeedKmh < SPEED_FAST_THRESHOLD_KMH) {
            DISTRACT_TRIGGER_MS_SLOW
        } else {
            DISTRACT_TRIGGER_MS_FAST
        }

        if (mDistractActive) {
            // 已触发：连续非分心达到解除阈值才解除
            if (!distracted) {
                if (mDistractAccumStart == 0L) {
                    mDistractAccumStart = now
                } else if (now - mDistractAccumStart >= DISTRACT_CLEAR_MS) {
                    mDistractActive = false
                    mDistractAccumStart = 0L
                }
            } else {
                mDistractAccumStart = 0L  // 仍分心，重置解除计时
            }
        } else {
            // 未触发：连续分心达到触发阈值才触发
            if (distracted) {
                if (mDistractAccumStart == 0L) {
                    mDistractAccumStart = now
                } else if (now - mDistractAccumStart >= mDistractTriggerMs) {
                    mDistractActive = true
                    mDistractAccumStart = 0L
                    Log.i(TAG, "DISTRACT triggered, speed=${mVehicleSpeedKmh}km/h threshold=${mDistractTriggerMs}ms")
                }
            } else {
                mDistractAccumStart = 0L  // 非分心，重置触发计时
            }
        }
        return mDistractActive
    }

    /** 重置分心防抖状态（无人脸时调用）。 */
    private fun resetDistraction() {
        mDistractActive = false
        mDistractAccumStart = 0L
    }

    /**
     * 连接 Car 服务并监听车速（PERF_VEHICLE_SPEED）。
     * 通过 VHAL 获取，系统应用有权限。读取失败或未连接时车速保持 -1，
     * 分心判定按高速档（快速触发）处理。
     */
    private fun connectVehicleSpeed() {
        if (mCar != null) return
        try {
            mCar = Car.createCar(this, mCarServiceConnection)
        } catch (e: Exception) {
            Log.w(TAG, "connectVehicleSpeed: createCar failed, will use fast threshold", e)
        }
    }

    /** 释放 Car 服务连接。 */
    private fun disconnectVehicleSpeed() {
        mCarPropertyManager?.unregisterCallback(mVehicleSpeedCallback)
        mCarPropertyManager = null
        try {
            mCar?.disconnect()
        } catch (_: Exception) { }
        mCar = null
    }

    /** Car 服务连接回调。 */
    private val mCarServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: IBinder?) {
            try {
                val car = mCar ?: return
                if (!car.isConnected) return
                val pm = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return
                mCarPropertyManager = pm
                // 订阅车速变化（m/s），SENSOR_RATE_NORMAL = 1Hz
                pm.registerCallback(mVehicleSpeedCallback,
                    VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
                Log.i(TAG, "connectVehicleSpeed: subscribed PERF_VEHICLE_SPEED")
            } catch (e: Exception) {
                Log.w(TAG, "connectVehicleSpeed: onServiceConnected error, use fast threshold", e)
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            Log.w(TAG, "connectVehicleSpeed: car service disconnected, use fast threshold")
            mCarPropertyManager = null
            mVehicleSpeedKmh = -1f
        }
    }

    /** 车速变化回调：PERF_VEHICLE_SPEED 为 Float，单位 m/s，转成 km/h。 */
    private val mVehicleSpeedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: android.car.hardware.CarPropertyValue<*>) {
            val speedMs = value.value as? Float ?: return
            mVehicleSpeedKmh = speedMs * 3.6f  // m/s -> km/h
            // 更新显示（测试用：显示车速与当前阈值档）
            runOnUiThread { updateSpeedText() }
        }

        override fun onErrorEvent(propertyId: Int, zoneId: Int) {
            Log.w(TAG, "connectVehicleSpeed: property error prop=$propertyId zone=$zoneId")
            mVehicleSpeedKmh = -1f
        }
    }

    /** 更新车速/分心阈值档显示（供测试验证分档逻辑）。 */
    private fun updateSpeedText() {
        val speed = mVehicleSpeedKmh
        val threshMs = mDistractTriggerMs
        val speedStr = if (speed < 0f) "N/A" else String.format("%.1f", speed)
        val band = if (threshMs >= DISTRACT_TRIGGER_MS_SLOW) "slow" else "fast"
        mSpeedText.text = getString(R.string.speed_label) +
                " $speedStr km/h | 分心档: ${band}(${threshMs}ms)"
    }

    // ============================================================
    // 状态持久化
    // ============================================================

    private fun loadAlgorithmState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mAlgorithmEnabled = prefs.getBoolean(KEY_ALGO_ENABLED, true)
    }

    private fun saveAlgorithmState() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ALGO_ENABLED, mAlgorithmEnabled)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "faceid_prefs"
        private const val KEY_ALGO_ENABLED = "algorithm_enabled"

        /** 分心触发-快速档（≥50km/h 或无车速数据）：1.5s，合规且快速响应。 */
        private const val DISTRACT_TRIGGER_MS_FAST = 1500L

        /** 分心触发-慢速档（<50km/h）：3.0s。 */
        private const val DISTRACT_TRIGGER_MS_SLOW = 3000L

        /** 分心解除阈值：0.5s，驾驶员回看后快速解除。 */
        private const val DISTRACT_CLEAR_MS = 500L

        /** 分档车速阈值（km/h）。 */
        private const val SPEED_FAST_THRESHOLD_KMH = 50f

        init {
            try {
                System.loadLibrary("hardware_buffer_reader")
            } catch (_: UnsatisfiedLinkError) { }
        }
    }

    /**
     * JNI 调用读取 HardwareBuffer UYVY 数据到 ByteArray（快速 memcpy）。
     * UYVY→RGB 转换在 FrameProcessor 算法线程上异步完成。
     * 黑帧检测在 JNI 侧完成。
     */
    private fun readHardwareBuffer(hwBuffer: HardwareBuffer, width: Int, height: Int): ByteArray? {
        return nativeReadHardwareBuffer(hwBuffer, width, height)
    }

    private external fun nativeReadHardwareBuffer(
        hwBuffer: HardwareBuffer, width: Int, height: Int
    ): ByteArray?
}
