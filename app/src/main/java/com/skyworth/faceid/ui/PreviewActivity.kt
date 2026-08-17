/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import com.skyworth.faceid.shmtest.AlgorithmResult
import com.skyworth.faceid.shmtest.AlgoEngineService
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

    /** `:algo` 引擎桥接（Binder，仅用于获取共享内存 + 下发 dump 路径）。 */
    private var mAlgoBridge: AlgoEngineBridge? = null

    /** 算法结果共享队列（消费端）。 */
    private var mAlgoResultQueue: ShmQueue? = null
    private var mAlgoReaderId = -1

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
                    val shm = it.getSharedMemory()
                    mAlgoResultQueue = ShmQueue.attach(shm).also { q ->
                        mAlgoReaderId = q.registerReader()
                    }
                    // 把 dump 路径下发给算法进程（算法处理后数据 dump 用）
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
            mAlgoBridge = null
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

    /** 消费线程：周期性读取 `:algo` 引擎发布的 [AlgorithmResult] 并绘制。 */
    private fun startResultConsumer() {
        mResultRunning = true
        mResultThread = Thread {
            while (mResultRunning) {
                try {
                    val q = mAlgoResultQueue ?: break
                    val rid = mAlgoReaderId
                    if (rid >= 0) {
                        // 每轮最多消费 MAX_RESULTS_PER_POLL 条，避免积压时向 UI 线程
                        // 一次性 post 过多 runOnUiThread 任务导致卡顿；未消费的留到下一轮。
                        var n = 0
                        while (q.hasNext(rid) && n < MAX_RESULTS_PER_POLL) {
                            val m = q.readNext(rid) ?: break
                            n++
                            // 版本/长度校验失败（格式不兼容）则丢弃，不阻塞
                            val algoResult = AlgorithmResult.decode(m.payload) ?: continue
                            runOnUiThread { handleAlgoResult(algoResult) }
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
     * 消费 [AlgorithmResult] 并喂给 [FaceOverlayView]。
     * 由于 [AlgorithmResult] 为跨进程精简结构，仅绘制人脸框 + 分心 + 置信度。
     */
    private fun handleAlgoResult(r: AlgorithmResult) {
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
                    pitch = r.headposePitch, yaw = r.headposeYaw, roll = r.headposeRoll,
                    gazeValid = r.gazeValid,
                    gazeYaw = r.gazeYaw, gazePitch = r.gazePitch,
                    gazeCalibrated = 0f,
                    gazeDistracted = if (r.distracted) 1f else 0f,
                    zoneId = r.zoneId
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

    /** 车速/分心档位显示（来自算法进程结果）。 */
    private fun updateSpeedTextMulti(r: AlgorithmResult) {
        val speed = r.speedKmh
        val threshMs = r.distractionThresholdMs
        val band = r.distractionBand
        val speedStr = if (speed < 0f) "N/A" else String.format("%.1f", speed)
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

    companion object {
        /** 多进程模式：结果消费轮询周期（ms）。 */
        private const val RESULT_POLL_INTERVAL_MS = 50L

        /** 多进程模式：每轮消费的最大结果条数（避免 UI 任务堆积）。 */
        private const val MAX_RESULTS_PER_POLL = 4

        /** dump 帧缓存节流间隔（ms）。仅需缓存最近一帧，避免持续读大 buffer。 */
        private const val DUMP_FRAME_CACHE_INTERVAL_MS = 100L
    }
}
