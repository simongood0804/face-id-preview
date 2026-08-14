/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.hardware.HardwareBuffer
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
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
import com.skyworth.faceid.algorithm.FrameProcessor
import com.skyworth.faceid.algorithm.IFaceIDAlgorithm
import com.skyworth.faceid.bus.BusHub
import com.skyworth.faceid.bus.BusPublisher
import com.skyworth.faceid.bus.ServiceRegistry
import com.skyworth.faceid.bus.ShmQueue
import com.skyworth.faceid.camera.CameraManager
import com.skyworth.faceid.camera.FaceIDCameraController
import com.skyworth.faceid.frame.FrameDistributor
import com.skyworth.faceid.render.FaceOverlayView
import com.skyworth.faceid.signal.DistractionStateMachine
import com.skyworth.faceid.signal.SignalDispatcher
import com.skyworth.faceid.signal.VehicleSignalSource
import com.skyworth.faceid.shmtest.AlgoEngineBridge
import com.skyworth.faceid.shmtest.AlgorithmResult
import com.skyworth.faceid.shmtest.AlgoEngineService
import java.util.concurrent.Executors

/**
 * Face ID 预览主界面。
 *
 * 使用 [EvsGL20CameraRenderer] 渲染 EVS 摄像头画面，
 * 通过 [FaceIDCameraController] 管理摄像头取流（含自动重试）。
 *
 * 分层重构后职责收窄为：
 * - 装配各层模块（帧层 [FrameDistributor] / 信号层 [VehicleSignalSource]、[DistractionStateMachine] / 绘制层 [FaceOverlayView]）；
 * - 负责 UI 展示与交互（按钮、文本、dump 控制）。
 * 图像采集、帧分发、车速信号、分心判定等逻辑已下沉到对应分层模块。
 */
class PreviewActivity : AppCompatActivity() {

    private val TAG = "PreviewActivity"

    // ============================================================
    // UI 控件
    // ============================================================

    private lateinit var mPreviewSurface: GLSurfaceView
    private lateinit var mFaceOverlay: FaceOverlayView
    private lateinit var mToggleButton: Button
    private lateinit var mCropButton: Button
    private lateinit var mDumpButton: Button
    private lateinit var mClearDumpButton: Button
    private lateinit var mMoveDumpButton: Button
    private lateinit var mStatusText: TextView
    private lateinit var mFaceIdText: TextView
    private lateinit var mFrameRateText: TextView
    private lateinit var mSpeedText: TextView

    // ============================================================
    // 核心模块（分层）
    // ============================================================

    private var mCameraManager: CameraManager? = null
    @Volatile private var mAlgorithm: FaceIDAlgorithmImpl? = null
    private var mEnrollmentManager: FaceEnrollmentManager? = null
    private var mFaceIDController: FaceIDCameraController? = null
    private var mRenderer: EvsGL20CameraRenderer? = null
    private var mFrameProcessor: FrameProcessor? = null

    /** 帧层：图像帧分发器。 */
    private var mFrameDistributor: FrameDistributor? = null

    /** 信号层：车机车速信号源。 */
    private var mVehicleSignal: VehicleSignalSource? = null

    // ============================================================
    // 消息总线（bus 层）+ 信号分发
    // ============================================================

    /** 消息总线枢纽。 */
    private var mBusHub: BusHub? = null

    /** 消息发布端。 */
    private var mBusPublisher: BusPublisher? = null

    /** 信号分发器（消费 VEHICLE_SPEED / ALGO_RESULT，驱动分心状态机）。 */
    private var mSignalDispatcher: SignalDispatcher? = null

    /** 信号分发线程（单线程驱动 poll，保证状态机单线程调用）。 */
    private var mSignalThread: HandlerThread? = null
    private var mSignalHandler: Handler? = null

    /** 算法处理线程池（单线程，避免 GL 线程阻塞）。 */
    private val mAlgoExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AlgoProcessor").apply { isDaemon = true }
    }

    /** 是否正在预览。 */
    private var mIsPreviewing = false

    /** 算法是否开启。 */
    private var mAlgorithmEnabled = true

    /** 是否启用 ROI 裁剪（控制算法是否做裁剪，关闭时全图推理）。 */
    private var mCropEnabled = true

    // ============================================================
    // 多进程模式（FACEP-010 阶段 E，方案 A 渐进）
    // ============================================================

    /**
     * 是否运行在多进程模式：绑定 `:algo` 进程引擎消费结果（而非本进程内跑算法）。
     * 默认 false（单进程）；通过 [toggleMultiProcess] 或偏好切换，可回退。
     */
    private var mMultiProcessMode = false

    /** 多进程：`:algo` 引擎桥接。 */
    private var mAlgoBridge: AlgoEngineBridge? = null

    /** 多进程：算法结果共享队列（消费端）。 */
    private var mAlgoResultQueue: ShmQueue? = null
    private var mAlgoReaderId = -1

    /** 多进程：结果消费线程。 */
    private var mResultThread: Thread? = null
    private var mResultRunning = false

    // ============================================================
    // Activity 生命周期
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        initViews()

        if (mMultiProcessMode) {
            // 多进程模式：主进程只保留预览取帧 + 渲染 + 消费 :algo 结果
            initMultiProcessModules()
        } else {
            // 单进程模式：本进程内跑算法 + 信号
            initCoreModules()
            initSignalLayer()
            initFrameLayer()
        }

        // 自动开始预览
        startPreview()

        Log.i(TAG, "onCreate: done, mode=${if (mMultiProcessMode) "multi-process" else "single-process"}")
    }

    /**
     * 初始化 UI 控件。
     */
    private fun initViews() {
        mPreviewSurface = findViewById(R.id.preview_surface)
        mFaceOverlay = findViewById(R.id.face_overlay)
        mToggleButton = findViewById(R.id.btn_toggle)
        mCropButton = findViewById(R.id.btn_crop)
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
        // 裁剪开关：控制算法是否做 ROI 裁剪
        mCropButton.setOnClickListener { toggleCrop() }
        mCropButton.setText(
            if (mCropEnabled) R.string.btn_crop_on else R.string.btn_crop_off
        )
        mDumpButton.setOnClickListener { onDumpClick() }
        mClearDumpButton.setOnClickListener { onClearDumpClick() }
        mMoveDumpButton.setOnClickListener { onMoveDumpClick() }
    }

    /**
     * 多进程模式初始化（方案 A）：主进程只做预览取帧 + 渲染，算法在 `:algo` 进程。
     *
     * - 创建预览相机（[FaceIDCameraController] + [CameraManager]）与 GL 渲染器；
     * - 绑定 `:algo` 进程的 [AlgoEngineService]，获取算法结果共享内存并订阅消费。
     */
    private fun initMultiProcessModules() {
        // 自定义控制器（与单进程一致：帧尺寸回调调整 GLSurfaceView）
        mFaceIDController = FaceIDCameraController().also { controller ->
            controller.onFrameSizeChanged = { width, height ->
                runOnUiThread { resizePreviewSurface(width, height) }
            }
        }
        mCameraManager = CameraManager(mFaceIDController!!)

        // GL 渲染器（预览画面）
        mRenderer = EvsGL20CameraRenderer().apply {
            setProvider(mFaceIDController!!)
        }
        mPreviewSurface.setEGLContextClientVersion(2)
        mPreviewSurface.setRenderer(mRenderer!!)
        mPreviewSurface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // 观察帧率
        mCameraManager?.frameRate?.value?.observe(this) { fps ->
            mFrameRateText.text = getString(R.string.frame_rate_label) + " " +
                    getString(R.string.frame_rate_value, fps)
        }

        mStatusText.setText(R.string.status_idle)
        mFaceIdText.text = getString(R.string.face_id_label) + " " + getString(R.string.face_id_none)
        mFrameRateText.text = getString(R.string.frame_rate_label) + " " + getString(R.string.frame_rate_value, 0)

        // 绑定 :algo 进程引擎，消费算法结果
        bindMultiProcessEngine()
    }

    /** 绑定 `:algo` 进程引擎并订阅算法结果共享队列。 */
    private fun bindMultiProcessEngine() {
        val intent = Intent(this, AlgoEngineService::class.java)
        val ok = bindService(intent, mAlgoEngineConn, Context.BIND_AUTO_CREATE)
        Log.i(TAG, "bindMultiProcessEngine: bind=$ok")
        if (!ok) {
            mStatusText.setText("多进程绑定失败，请检查 :algo 进程")
        }
    }

    private val mAlgoEngineConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            mAlgoBridge = AlgoEngineBridge.Stub.asInterface(binder)
            mAlgoBridge?.let { bridge ->
                try {
                    val shm = bridge.getSharedMemory()
                    mAlgoResultQueue = ShmQueue.attach(shm).also { q ->
                        mAlgoReaderId = q.registerReader()
                    }
                    mStatusText.setText("已连接 :algo 引擎")
                    startResultConsumer()
                    Log.i(TAG, "multi-process engine connected, pid=${android.os.Process.myPid()}")
                } catch (e: Exception) {
                    Log.e(TAG, "multi-process attach failed", e)
                    mStatusText.setText("多进程 attach 失败: ${e.message}")
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mStatusText.setText(":algo 引擎断开")
            mAlgoBridge = null
        }
    }

    /** 消费线程：周期性读取 `:algo` 引擎发布的 [AlgorithmResult] 并绘制。 */
    private fun startResultConsumer() {
        mResultRunning = true
        mResultThread = Thread {
            while (mResultRunning) {
                try {
                    val q = mAlgoResultQueue ?: break
                    val rid = mAlgoReaderId
                    if (rid >= 0) {
                        while (q.hasNext(rid)) {
                            val m = q.readNext(rid) ?: break
                            val algoResult = AlgorithmResult.decode(m.payload)
                            runOnUiThread { handleMultiProcessResult(algoResult) }
                        }
                    }
                    Thread.sleep(RESULT_POLL_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "result consumer error", e)
                }
            }
        }.apply { isDaemon = true }.also { it.start() }
    }

    /**
     * 多进程模式：消费 [AlgorithmResult] 并喂给 [FaceOverlayView]。
     * 由于 [AlgorithmResult] 为跨进程精简结构，仅绘制人脸框 + 分心 + 置信度，
     * 头姿/视线/关键点等高级绘制留待后续扩展。
     */
    private fun handleMultiProcessResult(r: AlgorithmResult) {
        val frameW = if (r.frameW > 0) r.frameW else 1600
        val frameH = if (r.frameH > 0) r.frameH else 1300

        // 分心提示（固定位置）
        mFaceOverlay.setDistracted(r.distracted)
        // 车速/档位显示
        updateSpeedTextMulti(r)

        if (r.hasFace && r.faceRight > r.faceLeft && r.faceBottom > r.faceTop) {
            mNoFaceCount = 0
            val rect = android.graphics.RectF(r.faceLeft, r.faceTop, r.faceRight, r.faceBottom)
            mFaceOverlay.setFaces(
                listOf(FaceOverlayView.FaceBox(
                    rect = rect,
                    type = FaceOverlayView.FaceType.DETECTED,
                    confidence = r.faceConfidence,
                    label = null,
                    keypoints = null,
                    denseLandmarks = null,
                    pitch = 0f, yaw = 0f, roll = 0f,
                    gazeValid = 0f,
                    gazeYaw = 0f, gazePitch = 0f,
                    gazeCalibrated = 0f,
                    gazeDistracted = if (r.distracted) 1f else 0f,
                    zoneId = 0f
                )),
                frameW, frameH
            )
            mFaceOverlay.visibility = View.VISIBLE
        } else {
            mNoFaceCount++
            if (mNoFaceCount >= FACE_HIDE_THRESHOLD) {
                mFaceOverlay.clearFaces()
                mFaceIdText.text = getString(R.string.face_id_label) + " " +
                        getString(R.string.face_id_none)
            }
        }
    }

    /** 多进程模式的车速/分心档位显示。 */
    private fun updateSpeedTextMulti(r: AlgorithmResult) {
        val speed = r.speedKmh
        val threshMs = r.distractionThresholdMs
        val band = r.distractionBand
        val speedStr = if (speed < 0f) "N/A" else String.format("%.1f", speed)
        mSpeedText.text = getString(R.string.speed_label) +
                " $speedStr km/h | 分心档: ${band}(${threshMs}ms)"
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

    /**
     * 初始化信号层：消息总线 + 信号分发器 + 车机车速信号源。
     *
     * 总线接线：
     * - [VehicleSignalSource] 车速变化 → 发布到 [ServiceRegistry.Topic.VEHICLE_SPEED]；
     * - 算法结果 → 发布到 [ServiceRegistry.Topic.ALGO_RESULT]（见 [handleAlgorithmResult]）；
     * - [SignalDispatcher] 在独立信号线程周期性 [SignalDispatcher.poll] 消费这两个 topic，
     *   驱动分心状态机（保证单线程调用），并把防抖结果通过 [SignalDispatcher.lastDistraction] 输出。
     */
    private fun initSignalLayer() {
        val hub = BusHub()
        mBusHub = hub
        val publisher = BusPublisher(hub)
        mBusPublisher = publisher
        mSignalDispatcher = SignalDispatcher(hub, publisher)

        mVehicleSignal = VehicleSignalSource(this).also { vs ->
            vs.onSpeedChanged = { speed ->
                publisher.publish(ServiceRegistry.Topic.VEHICLE_SPEED, speed)
            }
            vs.connect()
        }

        // 信号分发线程：周期性 poll 总线，驱动分心状态机
        mSignalThread = HandlerThread("signal-dispatcher").also { it.start() }
        mSignalHandler = Handler(mSignalThread!!.looper).also { handler ->
            handler.post(signalPollRunnable)
        }
    }

    /** 信号轮询周期（ms）。对应 VEHICLE_SPEED(10Hz)/ALGO_RESULT(20Hz) 的消费节奏。 */
    private val signalPollRunnable = object : Runnable {
        override fun run() {
            val dispatcher = mSignalDispatcher
            val handler = mSignalHandler
            if (dispatcher == null || handler == null) return
            try {
                dispatcher.poll()
                val d = dispatcher.lastDistraction
                runOnUiThread {
                    mFaceOverlay.setDistracted(d.distracted)
                    updateSpeedText()
                }
            } catch (e: Exception) {
                Log.e(TAG, "signal poll error", e)
            }
            handler.postDelayed(this, SIGNAL_POLL_INTERVAL_MS)
        }
    }

    /**
     * 初始化帧层：图像帧分发器，将相机帧路由到算法处理。
     */
    private fun initFrameLayer() {
        val controller = mFaceIDController ?: return
        val fp = mFrameProcessor ?: return
        mFrameDistributor = FrameDistributor(
            frameSource = controller,
            frameProcessor = fp,
            readFrame = { hwBuffer, w, h -> readHardwareBuffer(hwBuffer, w, h) },
            algorithmEnabled = { mAlgorithmEnabled }
        ).also { it.attach() }
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
     * 切换 ROI 裁剪开关：控制算法是否做 900×900 裁剪。
     * 开启时裁剪 ROI 送算法；关闭时整帧全图送算法（坐标不修正）。
     * 按钮文本实时显示当前开关状态。
     */
    private fun toggleCrop() {
        mCropEnabled = !mCropEnabled
        mFrameProcessor?.enableCrop = mCropEnabled
        mCropButton.setText(
            if (mCropEnabled) R.string.btn_crop_on else R.string.btn_crop_off
        )
        Log.i(TAG, "ROI crop ${if (mCropEnabled) "enabled" else "disabled"}")
    }

    /**
     * 切换单进程 / 多进程模式（FACEP-010 阶段 E 方案 A 调试入口）。
     * 保存偏好并重建 Activity 使模式生效。
     */
    private fun toggleMultiProcess() {
        mMultiProcessMode = !mMultiProcessMode
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MULTIPROCESS, mMultiProcessMode)
            .apply()
        Toast.makeText(
            this,
            if (mMultiProcessMode) "已切换多进程模式" else "已切换单进程模式",
            Toast.LENGTH_SHORT
        ).show()
        recreate()
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
        if (mMultiProcessMode) {
            // 多进程：释放 :algo 绑定 + 结果消费
            mResultRunning = false
            mResultThread?.interrupt()
            mAlgoResultQueue?.let { q ->
                if (mAlgoReaderId >= 0) q.unregisterReader(mAlgoReaderId)
                q.close()
            }
            mAlgoResultQueue = null
            try { unbindService(mAlgoEngineConn) } catch (_: Exception) { }
        } else {
            // 单进程：释放算法 + 信号层 + 总线
            mAlgorithm?.release()
            mFrameDistributor?.detach()
            mSignalHandler?.removeCallbacksAndMessages(null)
            mSignalThread?.quitSafely()
            mSignalThread = null
            mSignalHandler = null
            mSignalDispatcher?.close()
            mSignalDispatcher = null
            mBusHub?.reset()
            mBusHub = null
            mBusPublisher = null
            mVehicleSignal?.disconnect()
        }
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
     * 算法结果回调（AlgoProcessor 线程 → runOnUiThread）。
     * 仅对隐藏做防抖：连续 N 帧无人脸才清除画框。
     * 显示和位置更新不做防抖，保证即时响应。
     */
    private fun handleAlgorithmResult(result: IFaceIDAlgorithm.FaceIDResult) {
        // 从帧层读取最近一帧尺寸（FrameDistributor 在接收帧时更新）
        val fd = mFrameDistributor
        val frameW = fd?.frameWidth ?: mCurrentFrameW
        val frameH = fd?.frameHeight ?: mCurrentFrameH

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

            // 发布算法结果到总线，SignalDispatcher 在信号线程驱动分心状态机
            mBusPublisher?.publish(ServiceRegistry.Topic.ALGO_RESULT, result)
            // 读取最近的分心判定结果（SignalDispatcher 消费总线后异步更新）
            val distractActive = mSignalDispatcher?.lastDistraction?.distracted ?: false
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
            // 发布无人脸结果到总线（SignalDispatcher 会 reset 分心状态）
            mBusPublisher?.publish(ServiceRegistry.Topic.ALGO_RESULT, result)
            // 清除分心提示（固定位置显示）
            mFaceOverlay.setDistracted(false)
            if (mNoFaceCount >= FACE_HIDE_THRESHOLD) {
                mFaceOverlay.clearFaces()
                mFaceIdText.text = getString(R.string.face_id_label) + " " +
                        getString(R.string.face_id_none)
            }
        }
    }

    // ============================================================
    // 分心防抖 + 车速信号（委托给信号层模块）
    // ============================================================

    /**
     * 分心多帧/时间防抖状态机。
     *
     * 委托给信号层的 [DistractionStateMachine]，逻辑与原内联实现保持一致：
     *  - 连续分心达到当前车速对应的触发阈值 → 触发分心；
     *  - 已触发后，连续非分心达到解除阈值 → 解除分心。
     *
     * @return 防抖后是否判定为分心（用于 UI 显示）。
     */
    /**
     * 当前车速（km/h），来自信号层。负数表示无数据。
     */
    private fun currentSpeedKmh(): Float {
        return mVehicleSignal?.speedKmh ?: -1f
    }

    /**
     * 更新车速/分心阈值档显示（供测试验证分档逻辑）。
     * 分心档位与阈值来自信号分发器 [SignalDispatcher.lastDistraction]（signal 线程异步更新）。
     */
    private fun updateSpeedText() {
        val speed = currentSpeedKmh()
        val d = mSignalDispatcher?.lastDistraction
        val threshMs = d?.activeThresholdMs ?: DistractionStateMachine.TRIGGER_MS_FAST
        val band = d?.speedBand ?: "fast"
        val speedStr = if (speed < 0f) "N/A" else String.format("%.1f", speed)
        mSpeedText.text = getString(R.string.speed_label) +
                " $speedStr km/h | 分心档: ${band}(${threshMs}ms)"
    }

    // ============================================================
    // 状态持久化
    // ============================================================

    private fun loadAlgorithmState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mAlgorithmEnabled = prefs.getBoolean(KEY_ALGO_ENABLED, true)
        mMultiProcessMode = prefs.getBoolean(KEY_MULTIPROCESS, false)
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
        private const val KEY_MULTIPROCESS = "multi_process_mode"

        /** 信号分发轮询周期（ms）：对应 VEHICLE_SPEED(10Hz)/ALGO_RESULT(20Hz) 消费节奏。 */
        private const val SIGNAL_POLL_INTERVAL_MS = 50L

        /** 多进程模式：结果消费轮询周期（ms）。 */
        private const val RESULT_POLL_INTERVAL_MS = 50L

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
