package com.skyworth.faceid.core

import android.hardware.HardwareBuffer
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.android.car.evs.EvsGL20CameraRenderer
import com.skyworth.faceid.algorithm.FrameProcessor
import com.skyworth.faceid.camera.CameraManager
import com.skyworth.faceid.camera.FaceIDCameraController
import com.skyworth.faceid.frame.FrameDistributor
import java.util.concurrent.atomic.AtomicInteger

/**
 * 相机帧会话（单例 + 引用计数）。
 *
 * 提案 FACEP-011 §4.6-A：三个功能模块共享唯一的相机/帧分发会话，避免重复创建
 * （多 Camera 抢设备、重复渲染装配）。各模块通过 [acquire]/[release] 管理引用计数，
 * 计数归 0 才真正关闭相机与释放帧会话。
 *
 * 封装内容：
 * - [FaceIDCameraController]（相机源）+ [CameraManager]（开/关相机）；
 * - [EvsGL20CameraRenderer]（GL 渲染器，供 UI 绑定 GLSurfaceView）；
 * - [FrameDistributor]（帧分发：相机帧 → 算法帧处理）。
 *
 * 关键设计（FACEP-011 §4.6-B 数据隔离）：
 * - `readFrame` 由**调用方注入**（`hardware_buffer_reader` JNI 绑定在调用方类，无法移入本类），
 *   本会话不持有 JNI 符号，仅作为帧分发装配的一部分；
 * - 帧分发在 [acquire] 时用 [AlgoSession.frameProcessor] 装配，模块切换时复用。
 *
 * 线程安全：acquire/release 加锁，引用计数原子。
 */
class FrameSession private constructor(
    /** 读取 HardwareBuffer 为 ByteArray（调用方 JNI 提供，须在帧有效期内调用）。 */
    private val readFrame: (hwBuffer: HardwareBuffer, width: Int, height: Int) -> ByteArray?
) {

    private val TAG = "FrameSession"

    /** 相机源。 */
    private val mController: FaceIDCameraController = FaceIDCameraController()

    /** 摄像头管理器（与渲染器共享同一 controller）。 */
    private val mCameraManager: CameraManager = CameraManager(mController)

    /** 帧分发器（相机帧 → 算法），算法关闭时跳过。 */
    @Volatile
    private var mFrameDistributor: FrameDistributor? = null

    /** 引用计数。 */
    private val mRefCount = AtomicInteger(0)

    /** 生命周期锁。 */
    private val mLock = Any()

    /** 相机源。 */
    fun controller(): FaceIDCameraController = mController

    /** 摄像头管理器。 */
    fun cameraManager(): CameraManager = mCameraManager

    /**
     * 创建 GL 渲染器（每次调用新建，绑定共享相机源）。
     *
     * 注意：EvsGL20CameraRenderer 不能在多个 GLSurfaceView/GL 上下文间复用
     * （shader 是 GL 上下文相关的，复用会导致 onSurfaceCreated 崩溃）。
     * 因此每个 Activity 的 GLSurfaceView 必须使用自己新建的 renderer。
     */
    fun createRenderer(): EvsGL20CameraRenderer {
        return EvsGL20CameraRenderer().apply {
            setProvider(mController)
        }
    }

    /**
     * 配置 GLSurfaceView 及其覆盖层（每个模块首次调用一次）。
     *
     * 对齐 PreviewActivity 的配置：
     * - GLES 2.0 上下文（EvsGL20CameraRenderer 依赖）；
     * - 新建 renderer 绑定共享相机源；
     * - 连续渲染；
     * - **不依赖帧数据回调**：DMS 帧分辨率恒定 1600×1300，直接在布局完成后
     *   基于父容器尺寸按固定比例等比缩放并居中，只算一次（避免首帧时序导致拉伸）。
     *
     * 注意：surface 的父容器须为 FrameLayout（FrameLayout 尊重 layoutParams.width/height
     * 与 gravity；ConstraintLayout 用约束计算尺寸、忽略 width/height）。
     *
     * @param surface 模块的 GLSurfaceView。
     * @param overlay 覆盖在其上的人脸框 View（随 surface 一起缩放），可为 null。
     * @param resizeEnabled 是否启用固定比例（1600:1300）等比适配（默认 true）。
     */
    fun configureSurface(
        surface: GLSurfaceView,
        overlay: View? = null,
        resizeEnabled: Boolean = true
    ) {
        surface.setEGLContextClientVersion(2)
        surface.setRenderer(createRenderer())
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        if (resizeEnabled) {
            // 帧分辨率恒定 1600×1300：布局完成后按固定比例等比缩放 + 居中，只算一次。
            // 不注册 onFrameSizeChanged（避免帧回调时序、parent 未布局导致的拉伸）。
            surface.post { fitFixedAspect(surface, overlay, ORIGINAL_WIDTH, ORIGINAL_HEIGHT) }
        }
    }

    /**
     * 基于父容器尺寸，把 surface 与 overlay 等比缩放到固定帧比例（1600:1300）并**居中**。
     * 未覆盖区域由黑色背景填充。
     * 只调用一次（布局完成后），不依赖帧数据回调。
     * 父容器应为 FrameLayout（尊重 layoutParams 与 gravity），
     * 否则 ConstraintLayout 会忽略 width/height 导致拉伸。
     */
    private fun fitFixedAspect(
        surface: View, overlay: View?, frameW: Int, frameH: Int, retry: Int = MAX_RESIZE_RETRY
    ) {
        val parent = surface.parent as? View ?: return
        val parentW = parent.width
        val parentH = parent.height
        if (parentW <= 0 || parentH <= 0) {
            // 父容器尚未完成首次布局，延迟重试直至布局完成（通常 1~2 帧内）。
            // 限制重试次数，避免极端情况（父容器始终无尺寸）无限 post 形成死循环。
            if (retry <= 0) {
                Log.w(TAG, "fitFixedAspect: give up, parent size unavailable (${parentW}x${parentH})")
                return
            }
            surface.post { fitFixedAspect(surface, overlay, frameW, frameH, retry - 1) }
            return
        }
        if (frameW <= 0 || frameH <= 0) return

        val frameAspect = frameW.toFloat() / frameH.toFloat()
        val parentAspect = parentW.toFloat() / parentH.toFloat()

        val targetW: Int
        val targetH: Int
        if (frameAspect > parentAspect) {
            targetW = parentW
            targetH = (parentW / frameAspect).toInt()
        } else {
            targetH = parentH
            targetW = (parentH * frameAspect).toInt()
        }

        resizeTo(surface, targetW, targetH)
        overlay?.let { resizeTo(it, targetW, targetH) }
    }

    /**
     * 设置 view 尺寸并居中（FrameLayout 尊重 layoutParams 与 gravity；需 requestLayout 生效）。
     *
     * 注意：
     * - width/height 定义于 ViewGroup.LayoutParams 基类，**必须无条件设置**，
     *   否则 GLSurfaceView 的 layoutParams 若非 FrameLayout.LayoutParams（inflate 时序），
     *   尺寸不被修改 → 保持 match_parent → 预览拉伸。
     * - gravity 仅存在于 FrameLayout.LayoutParams，故仅对 FrameLayout 父容器额外居中，
     *   使等比缩放小于父容器时水平/垂直居中，避免靠左显示。
     */
    private fun resizeTo(view: View, w: Int, h: Int) {
        val lp = view.layoutParams
        if (lp != null) {
            lp.width = w
            lp.height = h
            // 父容器为 FrameLayout 时设置居中（非 FrameLayout 则忽略）
            if (lp is android.widget.FrameLayout.LayoutParams) {
                lp.gravity = android.view.Gravity.CENTER
            }
        }
        view.layoutParams = view.layoutParams
        view.requestLayout()
    }

    /** 帧分发器。 */
    fun frameDistributor(): FrameDistributor? = mFrameDistributor

    /** 当前引用计数（诊断用）。 */
    fun refCount(): Int = mRefCount.get()

    /**
     * 打开相机并开始预览。
     * @return 是否成功打开。
     */
    fun open(): Boolean {
        return try {
            mCameraManager.openCamera()
            true
        } catch (e: Exception) {
            Log.e(TAG, "open: failed", e)
            false
        }
    }

    /**
     * 停止相机预览。
     */
    fun stop() {
        try {
            mCameraManager.stopCamera()
        } catch (e: Exception) {
            Log.e(TAG, "stop: error", e)
        }
    }

    companion object {
        /** 原始帧尺寸（DMS 摄像头恒定分辨率），用于固定比例预览适配。 */
        private const val ORIGINAL_WIDTH = 1600
        private const val ORIGINAL_HEIGHT = 1300

        /** 固定比例适配的最大重试次数（父容器未布局时防无限 post）。 */
        private const val MAX_RESIZE_RETRY = 10

        @Volatile
        private var sInstance: FrameSession? = null

        /**
         * 获取相机帧会话（进程级单例，首次创建时注入 readFrame）。
         *
         * @param readFrame 读取 HardwareBuffer 为 ByteArray 的回调（调用方 JNI 提供）。
         * @return 单例实例（首次创建后复用，readFrame 仅首次生效）。
         */
        @JvmStatic
        fun get(readFrame: (hwBuffer: HardwareBuffer, width: Int, height: Int) -> ByteArray?): FrameSession {
            return sInstance ?: synchronized(this) {
                sInstance ?: FrameSession(readFrame).also { sInstance = it }
            }
        }

        /** 单例读取（用于内部释放）。 */
        internal fun get(): FrameSession? = sInstance
    }

    /**
     * 获取会话并增加引用计数；首次 acquire 时装配帧分发。
     *
     * @param frameProcessor 算法帧处理器（来自 [AlgoSession]）。
     * @param algorithmEnabled 算法启用查询（动态开关）。
     * @return 本会话。
     */
    fun acquire(frameProcessor: FrameProcessor, algorithmEnabled: () -> Boolean): FrameSession {
        synchronized(mLock) {
            if (mRefCount.getAndIncrement() == 0) {
                attachFrameDistributor(frameProcessor, algorithmEnabled)
            }
        }
        Log.i(TAG, "acquire: refCount=${mRefCount.get()}")
        return this
    }

    /**
     * 释放引用，计数归 0 时停止相机并解除帧分发。
     */
    fun release() {
        val now = mRefCount.decrementAndGet()
        if (now < 0) {
            mRefCount.set(0)
            Log.w(TAG, "release: underflow, forced 0")
            return
        }
        if (now == 0) {
            synchronized(mLock) {
                if (mRefCount.get() == 0) {
                    doRelease()
                }
            }
        }
        Log.i(TAG, "release: refCount=$now")
    }

    // ============================================================
    // 内部
    // ============================================================

    /** 装配帧分发（相机帧 → 算法帧处理）。 */
    private fun attachFrameDistributor(
        frameProcessor: FrameProcessor,
        algorithmEnabled: () -> Boolean
    ) {
        if (mFrameDistributor != null) {
            mFrameDistributor?.detach()
            mFrameDistributor = null
        }
        mFrameDistributor = FrameDistributor(
            frameSource = mController,
            frameProcessor = frameProcessor,
            readFrame = readFrame,
            algorithmEnabled = algorithmEnabled
        ).also { it.attach() }
        Log.i(TAG, "attachFrameDistributor: done")
    }

    /** 释放帧会话：停止相机、解除帧分发。 */
    private fun doRelease() {
        try {
            stop()
        } catch (e: Exception) {
            Log.w(TAG, "doRelease: stop error", e)
        }
        try {
            mFrameDistributor?.detach()
        } catch (e: Exception) {
            Log.w(TAG, "doRelease: detach error", e)
        }
        mFrameDistributor = null
        Log.i(TAG, "doRelease: done")
    }
}
