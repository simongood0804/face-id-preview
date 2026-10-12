package com.skyworth.faceid.core

import android.content.Context
import android.util.Log
import com.skyworth.faceid.algorithm.FaceEnrollmentManager
import com.skyworth.faceid.algorithm.FaceIDAlgorithmImpl
import com.skyworth.faceid.algorithm.FrameProcessor
import com.skyworth.faceid.algorithm.IFaceIDAlgorithm
import com.skyworth.faceid.behavior.BehaviorStateMachine
import com.skyworth.faceid.fatigue.FatigueRule
import com.skyworth.faceid.fatigue.FatigueStateMachine
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
 * - [setResultCallback] 注入帧处理结果回调（由各模块提供，如回 UI 线程）；
 * - **时序模块统一入口**（FACEP-019）：`algo` 结果回调在**算法线程**内**先**喂入共享的
 *   行为状态机与疲劳状态机（同一实例、同一时序逻辑），再分发给模块回调。各页面不再
 *   自持状态机实例，避免"同一功能各页各持一份"导致判定不一致。
 *
 * 线程安全：acquire/release 加锁，引用计数原子，避免并发切换竞态；
 * [setResultCallback] 与 [setFatigueRule] 须在创建/停止预览的线程调用。
 */
class AlgoSession private constructor() {

    private val TAG = "AlgoSession"

    /** 唯一算法实例（模型只加载一次）。 */
    private val mAlgorithm: FaceIDAlgorithmImpl = FaceIDAlgorithmImpl()

    // ============================================================
    // FACEP-019：时序模块（行为 / 疲劳）共享层
    // ============================================================

    /**
     * 行为时序判定状态机（抽烟 ≥2s / 打电话 ≥3s + 双阈值滞回防抖，方案 D）。
     *
     * 进程级共享：与算法结果同一入口（[FrameProcessor] 算法线程）喂入，各页面读取
     * [lastBehaviorClass]，**不再各自 new**，保证判定口径唯一。
     */
    private val mBehaviorSm = BehaviorStateMachine()

    /**
     * 行为状态机读写锁。
     *
     * 状态机本身非线程安全，而访问方来自两个线程：
     * - **算法线程**：`feedTimingModules` 内 `update()`；
     * - **UI 线程**：`resetTimingModules()`（门开换驾驶员）、`behaviorStateMachine()`
     *   读取延时值。配置变更经 [setBehaviorDelays] 投递到算法线程，天然串行。
     * 锁只覆盖"跨线程且可能并发"的路径，不进入算法热路径的额外开销（同一线程重入无竞争）。
     */
    private val mBehaviorSmLock = Any()

    /** 疲劳状态机读写锁（reset 与规则替换互斥）。 */
    private val mFatigueSmLock = Any()

    /** 疲劳规则（由 [setFatigueRule] / [fatigueRuleProvider] 注入；默认内置规则）。 */
    @Volatile
    private var mFatigueRule: FatigueRule = FatigueRule()

    /** 疲劳状态机（规则变化时经 [setFatigueRule] 原子替换）。 */
    @Volatile
    private var mFatigueSm: FatigueStateMachine = FatigueStateMachine(mFatigueRule)

    /** 疲劳规则是否已从 [fatigueRuleProvider] 注入（只注入一次）。 */
    @Volatile
    private var mFatigueRuleProviderApplied = false

    /**
     * 疲劳规则加载器（浮层提供，如从 assets/fatigue_rules.json）。
     *
     * 由 `:app` 在 acquire 前注入：`:algo` 不依赖 Android 资源路径，仅调用该函数拿到规则。
     * 未注入时使用内置默认规则。
     */
    @Volatile
    var fatigueRuleProvider: (() -> FatigueRule)? = null

    /** 最近一次行为时序确认结果（0=正常 / 1=抽烟 / 2=打电话）。 */
    @Volatile
    var lastBehaviorClass: Int = BehaviorStateMachine.CLASS_NORMAL
        private set

    /** 最近一次疲劳判定输出（含诊断信息）；未跑过为 null。 */
    @Volatile
    var lastFatigueOutput: FatigueStateMachine.FatigueOutput? = null
        private set

    /** 行为确认类别变化的监听（**算法线程**回调，UI 层请自行切线程）。 */
    @Volatile
    var onBehaviorChanged: ((Int) -> Unit)? = null

    /** 行为诊断探针的帧计数（仅 [feedTimingModules] 单线程访问）。 */
    private var mBehaviorProbeCount = 0L

    // ---- 算法/帧处理 ----

    /**
     * 算法处理线程池（单线程，避免 GL 线程阻塞）。
     *
     * 线程优先级设为**低**（NORM-2=3）：算法推理耗时高（数百 ms/帧），若占用高优先级 CPU，
     * 会抢占 GLThread 渲染线程导致预览帧率下降。降为低优先级后，渲染/UI 优先获取 CPU，
     * 保证"预览帧率跟随摄像头、算法结果作为附加层慢一点也无妨"。
     */
    private val mAlgoExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "AlgoProcessor").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 2  // 3，低于 UI/GL（默认 5）
            }
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
            // 时序模块先行：在算法线程内以统一入口喂入行为/疲劳状态机，
            // 保证"谁用都是同一份时序判定"，再分发给模块回调（UI 层）。
            feedTimingModules(it)
            mResultCallback?.invoke(it)
        }
    }

    /**
     * 喂入时序模块（**算法线程**调用）。
     *
     * - 行为：逐帧 `behaviorClass` → [mBehaviorSm] 时序确认（GB/T 门槛 + 防抖），
     *   `behaviorClass < 0`（无人脸/无效）时复位，避免残留计时误判；
     * - 疲劳：`eyeOpenRatio` / `mouthOpenRatio` / 有人脸 → [mFatigueSm]，
     *   输出缓存到 [lastFatigueOutput] 供页面直接取用（页面不再自己 update）。
     *
     * 线程安全：本方法仅在单线程 `AlgoProcessor` 内执行；结果字段为 `@Volatile` 供 UI 读。
     */
    private fun feedTimingModules(result: IFaceIDAlgorithm.FaceIDResult) {
        // ---- 行为时序确认 ----
        val rawClass = result.behaviorClass

        // ★ 诊断探针（每 PROBE_INTERVAL 帧打一次，避免刷屏）：
        //   每帧**无条件**记录算法原始类别与概率，用于判断"界面抖动"的来源。
        //   此前只在 confirmed 变化时打日志，存在采样偏差（恰好都采到 raw=0），
        //   无法区分是"算法 raw 在抖"还是"状态机自己振荡"。
        //   判读：raw 恒定而 confirmed 翻转 → 状态机问题；
        //         raw 在 0/1 间交替 → 算法返回值抖动，需算法侧解决。
        if (mBehaviorProbeCount++ % BEHAVIOR_PROBE_INTERVAL == 0L) {
            val probs = result.behaviorProbs
            Log.i(
                TAG,
                "behavior probe: raw=${rawClass.toInt()} " +
                    "probs=${probs?.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) } ?: "null"} " +
                    "confirmed=${mBehaviorSm.confirmedClass()} " +
                    "delays=[sm in=${mBehaviorSm.smokingEnterMs},out=${mBehaviorSm.smokingExitMs} " +
                    "ph in=${mBehaviorSm.phoneEnterMs},out=${mBehaviorSm.phoneExitMs}]"
            )
        }

        // 持锁：resetTimingModules() 可从 UI 线程并发 reset 同一状态机（见 mBehaviorSmLock）
        if (rawClass < 0f) {
            synchronized(mBehaviorSmLock) { mBehaviorSm.reset() }
            lastBehaviorClass = BehaviorStateMachine.CLASS_NORMAL
        } else {
            val confirmed = synchronized(mBehaviorSmLock) { mBehaviorSm.update(rawClass.toInt()) }
            if (confirmed != lastBehaviorClass) {
                lastBehaviorClass = confirmed
                val cb = onBehaviorChanged
                if (cb != null) {
                    try { cb(confirmed) } catch (e: Exception) { Log.e(TAG, "behavior cb error", e) }
                }
            }
        }

        // ---- 疲劳判定 ----
        // 用单调时钟（nanoTime），避免 wall clock 被 NTP/校时回拨导致时长异常。
        //
        // 持锁原因：[setFatigueRule] 会**整体替换** mFatigueSm、[resetTimingModules] 会
        // reset() 它，二者可从 UI 线程调用。若此处不持锁，替换/复位可能发生在 update()
        // 中途（FatigueStateMachine 内部有可变列表），导致状态错乱甚至
        // ConcurrentModificationException——异常被 FrameProcessor 捕获后会让整轮算法
        // 循环退出。锁与 setFatigueRule / resetTimingModules 共用，保证互斥。
        val hasFace = result.faceRect != null
        val fatigueOut = synchronized(mFatigueSmLock) {
            mFatigueSm.update(
                result.eyeOpenRatio,
                result.mouthOpenRatio,
                hasFace,
                System.nanoTime() / 1_000_000
            )
        }
        lastFatigueOutput = fatigueOut
    }

    /**
     * 注入疲劳规则（页面从 `assets/fatigue_rules.json` 加载后传入）。
     *
     * 会**重建**疲劳状态机并清零累计统计——规则变化时调用。
     * 须在预览启动前 / 停止预览后调用，避免与算法线程并发读写。
     */
    fun setFatigueRule(rule: FatigueRule) {
        synchronized(mFatigueSmLock) {
            mFatigueRule = rule
            mFatigueSm = FatigueStateMachine(rule)
            lastFatigueOutput = null
        }
        Log.i(TAG, "setFatigueRule: levels=${rule.levels.size}")
    }

    /** 当前疲劳规则。 */
    fun fatigueRule(): FatigueRule = mFatigueRule

    /**
     * 设置行为监测的**四个判定延时**（ms），范围 0 ~ [BehaviorStateMachine.MAX_DELAY_MS]（10s）。
     *
     * - 进入延时为 0 表示**不延时**（当帧即确认）；
     * - 退出延时为 0 表示**不宽限**（类别不再匹配立即回落）。
     *
     * 可在预览运行中实时调整（UI 滑块用）。越界值由状态机内部钳制。
     *
     * **线程安全**：状态机由算法线程独占（`feedTimingModules` 内驱动），本方法可从
     * 任意线程调用——参数会被**投递到算法线程**串行执行，从而与 `update()` 天然互斥，
     * 避免"UI 线程改字段 / 算法线程读字段"的数据竞争（此前直接赋值，拖动滑块时可能
     * 读到撕裂的 `mCandidateSinceMs=0`，导致延时被绕过而误确认）。
     *
     * @param smokingEnterMs 抽烟进入延时
     * @param smokingExitMs  抽烟退出延时
     * @param phoneEnterMs   打电话进入延时
     * @param phoneExitMs    打电话退出延时
     */
    fun setBehaviorDelays(
        smokingEnterMs: Long,
        smokingExitMs: Long,
        phoneEnterMs: Long,
        phoneExitMs: Long
    ) {
        mAlgoExecutor.execute {
            mBehaviorSm.smokingEnterMs = smokingEnterMs
            mBehaviorSm.smokingExitMs = smokingExitMs
            mBehaviorSm.phoneEnterMs = phoneEnterMs
            mBehaviorSm.phoneExitMs = phoneExitMs
            Log.i(
                TAG,
                "setBehaviorDelays: smoking=[in=${mBehaviorSm.smokingEnterMs}ms,out=${mBehaviorSm.smokingExitMs}ms] " +
                    "phone=[in=${mBehaviorSm.phoneEnterMs}ms,out=${mBehaviorSm.phoneExitMs}ms]"
            )
        }
    }

    /**
     * 读取当前行为延时配置的**一致性快照**（供 UI 初始化滑块 / 日志）。
     *
     * 返回 `[抽烟进入, 抽烟退出, 打电话进入, 打电话退出]`（ms）。
     * 持锁读取，避免滑块初始化时读到"改到一半"的撕裂值。
     *
     * ⚠ 配置变更**必须**走 [setBehaviorDelays]（投递到算法线程），不要试图直接改状态机。
     */
    fun behaviorDelaysSnapshot(): LongArray = synchronized(mBehaviorSmLock) {
        longArrayOf(
            mBehaviorSm.smokingEnterMs,
            mBehaviorSm.smokingExitMs,
            mBehaviorSm.phoneEnterMs,
            mBehaviorSm.phoneExitMs
        )
    }

    /**
     * 复位时序模块（行为 + 疲劳），清零累计统计。
     *
     * 用于**换驾驶员**（门开）等全局事件：各页面的 `DoorSignalSource` 回调里调用，
     * 与 [onDoorOpened]（眼/嘴校准复位）配套使用。
     */
    fun resetTimingModules() {
        // 行为与疲劳各持自己的锁，分别与算法线程的 update() 互斥
        // （不嵌套两把锁，避免与 feedTimingModules 的顺序形成死锁）。
        synchronized(mBehaviorSmLock) { mBehaviorSm.reset() }
        synchronized(mFatigueSmLock) { mFatigueSm.reset() }
        lastBehaviorClass = BehaviorStateMachine.CLASS_NORMAL
        lastFatigueOutput = null
        Log.i(TAG, "resetTimingModules: behavior & fatigue reset")
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
        /**
         * 行为诊断探针的打印间隔（帧）：约每 20 帧一次。
         *
         * 调试逐帧级问题（如"raw 与 confirmed 不一致"）时可临时置 1（每帧打印），
         * 但**正式版本必须 ≥20**，否则日志量过大且字符串拼接有额外开销。
         */
        private const val BEHAVIOR_PROBE_INTERVAL = 20L

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
        // FACEP-019：疲劳规则由浮层（:app）提供，首次 acquire 时注入到共享层
        if (!mFatigueRuleProviderApplied) {
            fatigueRuleProvider?.let {
                try {
                    setFatigueRule(it())
                    mFatigueRuleProviderApplied = true
                } catch (e: Exception) {
                    Log.w(TAG, "fatigue rule provider failed, use default", e)
                }
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
     *
     * 注意：本方法**不**复位时序模块（行为/疲劳），以免"只想复位校准"的调用方
     * 意外清空疲劳统计；需要一起复位请调用 [resetTimingModules]。
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
     * 这里只重置模块结果回调，避免泄漏对已销毁 Activity 的引用；**时序状态机同样常驻**
     * （累计统计跨页保留，换驾驶员才由 [resetTimingModules] 清零）。
     */
    private fun doRelease() {
        mResultCallback = null
        Log.i(TAG, "doRelease: reset callback, algo kept alive for reuse")
    }
}
