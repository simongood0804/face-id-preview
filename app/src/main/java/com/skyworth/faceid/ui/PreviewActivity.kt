/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PointF
import android.hardware.HardwareBuffer
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.android.car.evs.EvsGL20CameraRenderer
import com.skyworth.faceid.R
import com.skyworth.faceid.bus.ShmQueue
import com.skyworth.faceid.camera.CameraManager
import com.skyworth.faceid.camera.FaceIDCameraController
import com.skyworth.faceid.render.DumpManager
import com.skyworth.faceid.render.FaceOverlayView
import com.skyworth.faceid.shmtest.AlgoEngineBridge
import com.skyworth.faceid.shmtest.AlgoEngineService
import com.skyworth.faceid.shmtest.CapabilityModule
import com.skyworth.faceid.shmtest.DistractData
import com.skyworth.faceid.shmtest.FaceBoxData
import com.skyworth.faceid.shmtest.GazeData
import com.skyworth.faceid.shmtest.HeadposeData
import com.skyworth.faceid.shmtest.SpeedData
import com.skyworth.faceid.util.HardwareBufferReader

/**
 * 渲染进程主界面（多进程架构，FACEP-010 阶段 E）。
 *
 * 进程职责分离：
 * - **算法进程（`:algo`，[AlgoEngineService]）**：帧数据投喂、算法分析、结果分发（自包含）。
 * - **本进程（渲染进程）**：帧数据渲染、算法结果处理、dump 图像处理。
 *
 * 两个进程唯一的关联为：算法结果通过共享内存（[ShmQueue]）分享。
 * 本进程绑定 `:algo` 引擎，获取结果 [ShmQueue] 并订阅消费，绘制人脸框/分心提示。
 * 渲染层通过 [DumpManager] 控制帧画面 dump/clear/move；算法处理后数据的 dump 路径
 * 通过 Binder（[AlgoEngineBridge.setDumpPath]）下发给算法进程，不写系统属性、不依赖算法实例。
 */
class PreviewActivity : AppCompatActivity() {

    private val TAG = "PreviewActivity"

    // ============================================================
    // UI 控件
    // ============================================================

    private lateinit var mPreviewSurface: GLSurfaceView
    private lateinit var mFaceOverlay: FaceOverlayView
    private lateinit var mStatusText: TextView
    private lateinit var mFaceIdText: TextView
    private lateinit var mFrameRateText: TextView
    private lateinit var mSpeedText: TextView

    // ============================================================
    // 渲染 / 相机 / dump
    // ============================================================

    private var mCameraManager: CameraManager? = null
    private var mFaceIDController: FaceIDCameraController? = null
    private var mRenderer: EvsGL20CameraRenderer? = null

    /** 渲染层 dump 控制器（帧画面 dump/clear/move，与算法解耦）。 */
    private var mDumpManager: DumpManager? = null

    /** dump 帧缓存节流：上次缓存最近帧的时间戳（ms）。 */
    private var mLastDumpFrameCacheTime = 0L

    /** 是否正在预览。 */
    private var mIsPreviewing = false

    // ============================================================
    // 算法进程（`:algo`）关联：仅通过共享内存分享结果
    // ============================================================

    /** `:algo` 引擎桥接（Binder，用于注册/订阅 + 获取共享内存 + 下发 dump 路径）。 */
    private var mAlgoBridge: AlgoEngineBridge? = null

    /** 注册为算法能力消费者返回的 clientId（FACEP-011）。 */
    private var mAlgoClientId = -1

    /** 渲染进程存活 token（服务端 linkToDeath 监听本进程死亡自动注销）。 */
    private val mAlgoToken = android.os.Binder()

    /** 算法结果共享队列（消费端）。 */
    private var mAlgoResultQueue: ShmQueue? = null
    private var mAlgoReaderId = -1

    /** 本进程订阅的能力模块（渲染所需：人脸框/头姿/视线/分心/车速）。 */
    private val mSubscribedModules = intArrayOf(
        CapabilityModule.FACE_DETECT.topic,
        CapabilityModule.HEADPOSE.topic,
        CapabilityModule.GAZE.topic,
        CapabilityModule.DISTRACTION.topic,
        CapabilityModule.VEHICLE_SPEED.topic
    )

    /** 最近收到的各模块数据快照（消费线程组装后原子替换，UI 线程读取，保证跨帧一致）。 */
    @Volatile private var mModules: ModulesSnapshot? = null

    /** 结果消费线程。 */
    private var mResultThread: Thread? = null
    private var mResultRunning = false

    // ============================================================
    // Activity 生命周期
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        initViews()

        // 渲染层 dump 控制器（帧画面 dump/clear/move，与算法解耦）
        mDumpManager = DumpManager(this)

        // 渲染进程：预览取帧 + 渲染 + 消费 :algo 结果
        initRenderModules()

        // 自动开始预览
        startPreview()

        Log.i(TAG, "onCreate: done (render process, pid=${android.os.Process.myPid()})")
    }

    /**
     * 初始化 UI 控件（渲染进程：仅保留渲染/结果/文本，无算法开关）。
     */
    private fun initViews() {
        mPreviewSurface = findViewById(R.id.preview_surface)
        mFaceOverlay = findViewById(R.id.face_overlay)
        mStatusText = findViewById(R.id.tv_status)
        mFaceIdText = findViewById(R.id.tv_face_id)
        mFrameRateText = findViewById(R.id.tv_frame_rate)
        mSpeedText = findViewById(R.id.tv_speed)

        findViewById<View>(R.id.btn_dump).setOnClickListener { onDumpClick() }
        findViewById<View>(R.id.btn_clear_dump).setOnClickListener { onClearDumpClick() }
        findViewById<View>(R.id.btn_move_dump).setOnClickListener { onMoveDumpClick() }
    }

    /**
     * 渲染进程初始化：
     * - 创建预览相机（[FaceIDCameraController] + [CameraManager]）与 GL 渲染器；
     * - 绑定 `:algo` 进程的 [AlgoEngineService]，获取算法结果共享内存并订阅消费；
     * - 把 dump 路径通过 Binder 下发给算法进程。
     */
    private fun initRenderModules() {
        // 自定义控制器：帧尺寸回调调整 GLSurfaceView
        mFaceIDController = FaceIDCameraController().also { controller ->
            controller.onFrameSizeChanged = { width, height ->
                runOnUiThread { resizePreviewSurface(width, height) }
            }
            // 渲染层有帧数据：把原始帧节流缓存给 DumpManager，供手动 dump（不跑算法）
            // 帧在 HAL 回调线程到达，读 buffer 后立即返回，仅缓存最近一帧用于 dump。
            controller.onFrameData = { hw, w, h -> cacheFrameForDump(hw, w, h) }
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
        bindAlgoEngine()
    }

    /** 绑定 `:algo` 进程引擎并订阅算法结果共享队列。 */
    private fun bindAlgoEngine() {
        val intent = Intent(this, AlgoEngineService::class.java)
        val ok = bindService(intent, mAlgoEngineConn, Context.BIND_AUTO_CREATE)
        Log.i(TAG, "bindAlgoEngine: bind=$ok")
        if (!ok) {
            mStatusText.setText("多进程绑定失败，请检查 :algo 进程")
        }
    }

    private val mAlgoEngineConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val bridge = AlgoEngineBridge.Stub.asInterface(binder)
            mAlgoBridge = bridge
            bridge?.let {
                try {
                    // 1. 注册为算法能力消费者（FACEP-011），携带存活 token 供死亡检测
                    val clientId = it.register(packageName, mAlgoToken)
                    if (clientId < 0) {
                        Log.e(TAG, "register algo client failed code=$clientId")
                        mStatusText.setText("算法消费者注册失败")
                        return@let
                    }
                    mAlgoClientId = clientId

                    // 2. 初始化算法（幂等）
                    it.init("")

                    // 3. 订阅渲染所需能力模块（人脸框 + 分心 + 车速）
                    it.subscribe(clientId, mSubscribedModules)

                    // 4. attach 算法结果共享内存
                    val shm = it.getSharedMemory()
                    mAlgoResultQueue = ShmQueue.attach(shm).also { q ->
                        mAlgoReaderId = q.registerReader()
                    }

                    // 5. 把 dump 路径下发给算法进程（算法处理后数据 dump 用）
                    mDumpManager?.getDumpDir()?.let { dir ->
                        it.setDumpPath(dir.absolutePath)
                    }
                    mStatusText.setText("已连接 :algo 引擎")
                    startResultConsumer()
                    Log.i(TAG, "algo engine connected, pid=${android.os.Process.myPid()}")
                } catch (e: Exception) {
                    Log.e(TAG, "algo attach failed", e)
                    mStatusText.setText("多进程 attach 失败: ${e.message}")
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mStatusText.setText(":algo 引擎断开")
            // 注销消费者
            try {
                mAlgoBridge?.unregister(mAlgoClientId)
            } catch (_: Exception) { }
            mAlgoBridge = null
            mAlgoClientId = -1
            // 停止结果消费，清理队列与 reader（避免 :algo 重启重连后 reader 泄漏）
            mResultRunning = false
            mResultThread?.interrupt()
            mResultThread = null
            mAlgoResultQueue?.let { q ->
                if (mAlgoReaderId >= 0) {
                    try { q.unregisterReader(mAlgoReaderId) } catch (_: Exception) { }
                }
                q.close()
            }
            mAlgoResultQueue = null
            mAlgoReaderId = -1
        }
    }

    /** 消费线程：周期性读取 `:algo` 引擎发布的各能力模块数据并绘制（FACEP-011 阶段 B）。 */
    private fun startResultConsumer() {
        mResultRunning = true
        mResultThread = Thread {
            while (mResultRunning) {
                try {
                    val q = mAlgoResultQueue ?: break
                    val rid = mAlgoReaderId
                    if (rid >= 0) {
                        // 每轮最多消费 MAX_RESULTS_PER_POLL 条，避免积压；未消费的留到下一轮。
                        // 本轮的模块数据暂存局部变量，轮末组装为不可变快照一次原子替换，
                        // 避免跨帧错位（人脸框来自帧A、分心来自帧B）。
                        var n = 0
                        var faceBox: FaceBoxData? = null
                        var headpose: HeadposeData? = null
                        var gaze: GazeData? = null
                        var distract: DistractData? = null
                        var speed: SpeedData? = null
                        var updated = false
                        while (q.hasNext(rid) && n < MAX_RESULTS_PER_POLL) {
                            val m = q.readNext(rid) ?: break
                            n++
                            // 按模块 topic 分模块解析，只处理本进程订阅的模块
                            val topic = m.topic
                            val module = CapabilityModule.fromTopic(topic) ?: continue
                            when (module) {
                                CapabilityModule.FACE_DETECT -> {
                                    val d = FaceBoxData.decode(m.payload) ?: continue
                                    faceBox = d; updated = true
                                }
                                CapabilityModule.HEADPOSE -> {
                                    val d = HeadposeData.decode(m.payload) ?: continue
                                    headpose = d; updated = true
                                }
                                CapabilityModule.GAZE -> {
                                    val d = GazeData.decode(m.payload) ?: continue
                                    gaze = d; updated = true
                                }
                                CapabilityModule.DISTRACTION -> {
                                    val d = DistractData.decode(m.payload) ?: continue
                                    distract = d; updated = true
                                }
                                CapabilityModule.VEHICLE_SPEED -> {
                                    val d = SpeedData.decode(m.payload) ?: continue
                                    speed = d; updated = true
                                }
                            }
                        }
                        // 该轮有新数据才组装快照并触发一次绘制（避免无变化时反复刷新文本）
                        if (updated) {
                            val prev = mModules
                            val snap = ModulesSnapshot(
                                faceBox ?: prev?.faceBox,
                                headpose ?: prev?.headpose,
                                gaze ?: prev?.gaze,
                                distract ?: prev?.distract,
                                speed ?: prev?.speed
                            )
                            mModules = snap
                            runOnUiThread { handleModules() }
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
     * 汇总各能力模块数据快照并喂给 [FaceOverlayView]（FACEP-011 阶段 B）。
     * 数据来自 [startResultConsumer] 组装并原子替换的 [mModules] 快照，
     * 在 UI 线程执行，绘制逻辑与重构前一致。
     */
    private fun handleModules() {
        val box = mModules?.faceBox
        val headpose = mModules?.headpose
        val gaze = mModules?.gaze
        val distract = mModules?.distract
        val speed = mModules?.speed

        // 分心提示（固定位置）
        mFaceOverlay.setDistracted(distract?.distracted ?: false)
        // 车速/档位显示
        updateSpeedText(speed, distract)

        if (box != null && box.hasFace &&
            box.faceRight > box.faceLeft && box.faceBottom > box.faceTop) {
            mNoFaceCount = 0
            val frameW = if (box.frameW > 0) box.frameW else 1600
            val frameH = if (box.frameH > 0) box.frameH else 1300
            val rect = android.graphics.RectF(box.faceLeft, box.faceTop, box.faceRight, box.faceBottom)
            mFaceOverlay.setFaces(
                listOf(FaceOverlayView.FaceBox(
                    rect = rect,
                    type = FaceOverlayView.FaceType.DETECTED,
                    confidence = box.faceConfidence,
                    label = null,
                    // 5 关键点（视线线/头姿箭头起点，来自 FACE_DETECT 模块；FloatArray→PointF）
                    keypoints = box.keypoints?.let { arr ->
                        if (arr.size >= 10) {
                            (0 until 5).map { i -> PointF(arr[i * 2], arr[i * 2 + 1]) }
                        } else null
                    },
                    denseLandmarks = null,
                    // 头姿/视线来自订阅的 HEADPOSE/GAZE 模块（修复：此前硬编码 0 导致绘制异常）
                    pitch = headpose?.pitch ?: 0f,
                    yaw = headpose?.yaw ?: 0f,
                    roll = headpose?.roll ?: 0f,
                    gazeValid = gaze?.valid ?: 0f,
                    gazeYaw = gaze?.yaw ?: 0f,
                    gazePitch = gaze?.pitch ?: 0f,
                    gazeCalibrated = gaze?.calibrated ?: 0f,
                    gazeDistracted = if (distract?.distracted == true) 1f else 0f,
                    zoneId = box.zoneId
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

    /** 车速/分心档位显示（来自算法进程的能力模块数据）。 */
    private fun updateSpeedText(speed: SpeedData?, distract: DistractData?) {
        val speedStr = if (speed != null && speed.speedKmh >= 0f)
            String.format("%.1f", speed.speedKmh) else "N/A"
        val band = distract?.band ?: "fast"
        val threshMs = distract?.thresholdMs ?: 0L
        mSpeedText.text = getString(R.string.speed_label) +
                " $speedStr km/h | 分心档: ${band}(${threshMs}ms)"
    }

    // ============================================================
    // dump 控制（渲染层，与算法解耦）
    // ============================================================

    /**
     * 手动触发 dump：由渲染层 [DumpManager] 保存最近一帧原始图像，完成后 Toast 提示。
     * 若系统属性未启用 dump，弹框提示设置系统属性。
     */
    private fun onDumpClick() {
        val dm = mDumpManager
        if (dm == null) {
            Toast.makeText(this, "dump: not ready", Toast.LENGTH_SHORT).show()
            return
        }
        if (!dm.isDumpAvailable()) {
            Toast.makeText(this, "dump: system property disabled, set algorithm_face_dump_enable first", Toast.LENGTH_LONG).show()
            return
        }
        dm.triggerManualDump { ok ->
            val msg = if (ok) "dump: saved" else "dump: failed"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        Log.i(TAG, "onDumpClick: manual dump triggered")
    }

    /**
     * 清除 debugDump 文件夹内容，并删除 /sdcard/debugDmsDump 文件夹。
     * 由渲染层 [DumpManager] 执行，与算法解耦。
     */
    private fun onClearDumpClick() {
        val dm = mDumpManager
        if (dm == null) {
            Toast.makeText(this, "clear dump: not ready", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "clear dump: running", Toast.LENGTH_SHORT).show()
        dm.clearDump { ok ->
            val msg = if (ok) "clear dump: done" else "clear dump: failed"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        Log.i(TAG, "onClearDumpClick: clear triggered")
    }

    /**
     * 将 debugDump 中的 png 图像移动到 /sdcard/debugDmsDump 文件夹。
     * 由渲染层 [DumpManager] 执行，与算法解耦。
     */
    private fun onMoveDumpClick() {
        val dm = mDumpManager
        if (dm == null) {
            Toast.makeText(this, "move dump: not ready", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "move dump: running", Toast.LENGTH_SHORT).show()
        dm.moveToSdcard { ok ->
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

        // 释放 :algo 绑定 + 结果消费
        mResultRunning = false
        mResultThread?.interrupt()
        mResultThread = null
        // 主动注销消费者（防残留 clientId 占槽；若已有其它绑定或服务已断开，幂等无副作用）
        try {
            mAlgoBridge?.unregister(mAlgoClientId)
        } catch (_: Exception) { }
        mAlgoBridge = null
        mAlgoClientId = -1
        mAlgoResultQueue?.let { q ->
            if (mAlgoReaderId >= 0) {
                try { q.unregisterReader(mAlgoReaderId) } catch (_: Exception) { }
            }
            q.close()
        }
        mAlgoResultQueue = null
        mAlgoReaderId = -1
        try { unbindService(mAlgoEngineConn) } catch (_: Exception) { }

        // 渲染层 dump 控制器释放
        mDumpManager?.release()
        mDumpManager = null
        Log.i(TAG, "onDestroy: done")
    }

    // ============================================================
    // 绘制防抖
    // ============================================================

    /** 连续无结果帧计数（达到阈值才清除画框）。 */
    private var mNoFaceCount = 0

    /** 隐藏画框所需的连续无检测帧数（约 5×16ms = 80ms 延迟）。 */
    private val FACE_HIDE_THRESHOLD = 5

    // ============================================================
    // 工具
    // ============================================================

    /**
     * JNI 调用读取 HardwareBuffer UYVY 数据到 ByteArray（快速 memcpy）。
     * UYVY→RGB 转换在算法进程的 FrameProcessor 算法线程上异步完成。
     * 黑帧检测在 JNI 侧完成。
     */
    private fun readHardwareBuffer(hwBuffer: HardwareBuffer, width: Int, height: Int): ByteArray? {
        return HardwareBufferReader.read(hwBuffer, width, height)
    }

    /**
     * 从渲染层取到的帧节流缓存给 DumpManager（供手动 dump）。
     * 帧在 HAL 回调线程到达，读 UYVY 后立即返回，仅缓存最近一帧。
     * 节流避免每帧都读大 buffer（约 1600×1300×2），降低持续开销。
     */
    private fun cacheFrameForDump(hwBuffer: HardwareBuffer, width: Int, height: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - mLastDumpFrameCacheTime < DUMP_FRAME_CACHE_INTERVAL_MS) return
        mLastDumpFrameCacheTime = now
        val dm = mDumpManager ?: return
        try {
            val data = readHardwareBuffer(hwBuffer, width, height) ?: return
            dm.cacheFrame(data, width, height)
        } catch (e: Exception) {
            Log.w(TAG, "cacheFrameForDump: read frame failed", e)
        }
    }

    /**
     * 渲染所需各能力模块数据的不可变快照。
     * 消费线程组装后以单一 [mModules] 引用原子替换，UI 线程 [handleModules] 读取，
     * 保证同一帧内人脸框/头姿/视线/分心/车速来自一致的数据（避免跨帧错位）。
     */
    private data class ModulesSnapshot(
        val faceBox: FaceBoxData?,
        val headpose: HeadposeData?,
        val gaze: GazeData?,
        val distract: DistractData?,
        val speed: SpeedData?
    )

    companion object {
        /** 多进程模式：结果消费轮询周期（ms）。 */
        private const val RESULT_POLL_INTERVAL_MS = 50L

        /** 多进程模式：每轮消费的最大结果条数（避免 UI 任务堆积）。 */
        private const val MAX_RESULTS_PER_POLL = 4

        /** dump 帧缓存节流间隔（ms）。仅需缓存最近一帧，避免持续读大 buffer。 */
        private const val DUMP_FRAME_CACHE_INTERVAL_MS = 100L
    }
}
