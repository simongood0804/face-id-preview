package com.skyworth.faceid.core

import android.content.Context
import android.util.Log
import com.skyworth.faceid.algorithm.FaceEnrollmentManager
import com.skyworth.faceid.algorithm.FaceIDAlgorithmImpl
import com.skyworth.faceid.algorithm.FrameProcessor
import com.skyworth.faceid.algorithm.IFaceIDAlgorithm
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 算法会话（进程级单例 + 引用计数生命周期）。
 *
 * 提案 FACEP-011 §4.6-A：三个功能模块（人脸识别/疲劳监测/分心监测）共享唯一的
 * 算法实例，避免重复创建（模型只加载一次）。各模块通过 [acquire]/[release] 管理
 * 引用计数，计数归 0 才真正释放算法与帧处理器。
 *
 * 职责：
 * - 持有唯一 [IFaceIDAlgorithm] 实例与 [FrameProcessor]（算法帧处理）；
 * - 引用计数 [acquire]/[release]，切页不重建、计数归 0 才销毁；
 * - 门信号复位 [onDoorOpened] 透传（校准复位，见 FACEP-011 §4.6-B 全局事件）；
 * - [setResultCallback] 注入帧处理结果回调（由各模块提供，如回 UI 线程）。
 *
 * 线程安全：acquire/release 加锁，引用计数原子，避免并发切换竞态。
 */
class AlgoSession private constructor() {

    private val TAG = "AlgoSession"

    /** 唯一算法实例（模型只加载一次）。 */
    private val mAlgorithm: FaceIDAlgorithmImpl = FaceIDAlgorithmImpl()

    /** 算法处理线程池（单线程，避免 GL 线程阻塞）。 */
    private val mAlgoExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "AlgoProcessor").apply { isDaemon = true }
        }

    /** 帧处理器（算法帧处理，需回调注入）。 */
    private val mFrameProcessor: FrameProcessor

    /** 引用计数。 */
    private val mRefCount = AtomicInteger(0)

    /** 是否已初始化（算法模型加载成功）。 */
    @Volatile
    private var mInitialized = false

    /** 初始化锁。 */
    private val mInitLock = Any()

    /** 模块结果回调（各模块注入，如 runOnUiThread 回 UI）。 */
    @Volatile
    private var mResultCallback: ((IFaceIDAlgorithm.FaceIDResult) -> Unit)? = null

    init {
        // 帧处理器初始回调为空（模块 acquire 时通过 setResultCallback 注入）
        mFrameProcessor = FrameProcessor(mAlgorithm, mAlgoExecutor) {
            // 由 setResultCallback 设置的模块回调接管
            mResultCallback?.invoke(it)
        }
    }

    /** 算法实例。 */
    fun algorithm(): IFaceIDAlgorithm = mAlgorithm

    /** 帧处理器（相机帧提交入口）。 */
    fun frameProcessor(): FrameProcessor = mFrameProcessor

    /** 当前引用计数（调试/诊断用）。 */
    fun refCount(): Int = mRefCount.get()

    /** 设置帧处理结果回调（模块 acquire 时调用，切换模块时替换）。 */
    fun setResultCallback(cb: ((IFaceIDAlgorithm.FaceIDResult) -> Unit)?) {
        mResultCallback = cb
    }

    companion object {
        @Volatile
        private var sInstance: AlgoSession? = null

        /** 获取进程级单例。 */
        @JvmStatic
        fun get(): AlgoSession {
            return sInstance ?: synchronized(this) {
                sInstance ?: AlgoSession().also { sInstance = it }
            }
        }
    }

    /**
     * 获取算法会话并增加引用计数。
     *
     * @param context 用于首次初始化算法模型（后续 acquire 复用）。
     * @return 本会话实例。
     */
    fun acquire(context: Context): AlgoSession = acquire(context, null)

    /**
     * 获取算法会话并增加引用计数（支持按模块裁剪算法流程，FACEP-011 功能划分）。
     *
     * @param context 用于首次初始化算法模型（后续 acquire 复用）。
     * @param flag [atlas.face.sdk.FaceFlag] 按位或组合；null 保持默认 ALL。
     *             如人脸识别模块传 DETECTION|RECOGNITION|LIVENESS|LANDMARK。
     * @return 本会话实例。
     */
    fun acquire(context: Context, flag: Int?): AlgoSession {
        synchronized(mInitLock) {
            if (mRefCount.getAndIncrement() == 0) {
                // 首次 acquire：初始化算法模型
                initializeAlgo(context)
            }
        }
        // 按模块裁剪算法流程 + 复位眼嘴管线（FACEP-011 功能切换）
        if (flag != null) {
            mAlgorithm.setFlagAndReset(flag)
        }
        Log.i(TAG, "acquire: refCount=${mRefCount.get()} flag=$flag")
        return this
    }

    /**
     * 释放引用，计数归 0 时释放算法与帧处理器。
     */
    fun release() {
        val now = mRefCount.decrementAndGet()
        if (now < 0) {
            mRefCount.set(0)
            Log.w(TAG, "release: underflow, forced 0")
            return
        }
        if (now == 0) {
            synchronized(mInitLock) {
                if (mRefCount.get() == 0) {
                    doRelease()
                }
            }
        }
        Log.i(TAG, "release: refCount=$now")
    }

    /**
     * 驾驶门开关信号：复位眼/嘴校准（全局事件，见 FACEP-011 §4.6-B）。
     */
    fun onDoorOpened() {
        mAlgorithm.onDoorOpened()
    }

    // ============================================================
    // 内部
    // ============================================================

    /** 初始化算法模型与人脸库。 */
    private fun initializeAlgo(context: Context) {
        if (mInitialized) return
        val config = HashMap<String, Any>()
        try {
            val ok = mAlgorithm.initialize(context, config)
            if (ok) {
                // 注入人脸录入/识别管理器（识别模块依赖，FACEP-011 §4.6-B 归属算法单例）
                mAlgorithm.setEnrollmentManager(FaceEnrollmentManager(context, mAlgorithm))
            }
            mInitialized = ok
            Log.i(TAG, "initializeAlgo: ok=$ok")
        } catch (e: Exception) {
            Log.e(TAG, "initializeAlgo: failed", e)
            mInitialized = false
        }
    }

    /**
     * 引用计数归 0 时的释放逻辑。
     *
     * **注意**：AlgoSession 是进程级单例，算法实例 [mAlgorithm] 与执行器 [mAlgoExecutor]
     * 均为单例字段（进程存活期间只创建一次，不会重建）。因此此处**不能**销毁它们：
     * - 若 `mAlgoExecutor.shutdown()`，单例复用时 `FrameProcessor.submitFrame`
     *   调 `mExecutor.submit()` 会抛 `RejectedExecutionException` → 算法任务不执行
     *   → 退出功能页后再次进入**算法无响应**。
     * - 若 `mAlgorithm.release()` 销毁 SDK，虽可重新 initialize，但非必要且增加开销。
     *
     * 计数归 0 仅代表当前无模块正在使用，算法与执行器保持常驻复用（模型只加载一次）。
     * 这里只重置模块结果回调，避免泄漏对已销毁 Activity 的引用。
     */
    private fun doRelease() {
        mResultCallback = null
        Log.i(TAG, "doRelease: reset callback, algo kept alive for reuse")
    }
}
