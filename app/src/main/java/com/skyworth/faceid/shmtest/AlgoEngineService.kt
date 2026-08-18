package com.skyworth.faceid.shmtest

import android.app.Service
import android.content.Intent
import android.hardware.HardwareBuffer
import android.os.IBinder
import android.os.SharedMemory
import android.util.Log
import com.skyworth.faceid.algorithm.FaceIDAlgorithmImpl
import com.skyworth.faceid.algorithm.FrameProcessor
import com.skyworth.faceid.algorithm.IFaceIDAlgorithm
import com.skyworth.faceid.camera.CameraManager
import com.skyworth.faceid.camera.FaceIDCameraController
import com.skyworth.faceid.bus.ShmQueue
import com.skyworth.faceid.signal.DistractionStateMachine
import com.skyworth.faceid.signal.VehicleSignalSource
import com.skyworth.faceid.util.HardwareBufferReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 阶段 E：`:algo` 进程的算法引擎服务（自包含）。
 *
 * 在独立进程（`android:process=":algo"`）中完成 DMS 的全部算法处理：
 * 1. [FaceIDCameraController] + [CameraManager] **独立取帧**（帧数据源与主进程一致）；
 * 2. [FrameProcessor] + [FaceIDAlgorithmImpl] 推理；
 * 3. [DistractionStateMachine] + [VehicleSignalSource]（VHAL 车速）整合出
 *    [AlgorithmResult]；
 * 4. 把 [AlgorithmResult] 序列化后发布到 [ShmQueue]（跨进程共享内存），
 *    主进程订阅消费并绘制。
 *
 * 不依赖主进程，可独立取帧/推理/采车速，崩溃不影响主进程（故障隔离）。
 */
class AlgoEngineService : Service() {

    private val TAG = "AlgoEngine"

    // 相机（独立取帧）
    private var mCamera: FaceIDCameraController? = null
    private var mCameraManager: CameraManager? = null

    // 算法
    private var mAlgorithm: FaceIDAlgorithmImpl? = null
    private var mFrameProcessor: FrameProcessor? = null
    /** 算法线程池：随 startEngine 创建、stopEngine 关闭（支持多次 bind 重建）。 */
    private var mExecutor: ExecutorService? = null

    // 信号
    private val mStateMachine = DistractionStateMachine()
    private var mVehicleSignal: VehicleSignalSource? = null

    // 跨进程发布
    @Volatile private var mOutQueue: ShmQueue? = null
    private var mShm: SharedMemory? = null

    /** 相机 buffer 回收线程（周期调用 getNewFrame 让 EVS buffer 循环）。 */
    private var mBufferThread: Thread? = null

    /** FACEP-011 阶段 B：按模块发布器（算法线程读、stopEngine 置 null，需可见）。 */
    @Volatile private var mPublisher: CapabilityPublisher? = null

    private var running = false

    // ============================================================
    // FACEP-011：多消费者注册与订阅管理（阶段 A）
    // ============================================================

    /** 消费者注册表：clientId → 包名。线程安全（Binder 线程可能并发）。 */
    private val mClients = mutableMapOf<Int, String>()

    /** 订阅表：clientId → 已订阅能力模块集合（阶段 A 建立结构，阶段 B 用于按模块发布）。 */
    private val mSubscriptions = mutableMapOf<Int, MutableSet<Int>>()

    /** 下一个可分配 clientId。 */
    private var mNextClientId = 0

    /** 消费者注册与订阅操作锁。 */
    private val mClientLock = Any()

    /** 客户端存活 token → clientId 的反向映射（用于死亡清理）。 */
    private val mTokenToClient = mutableMapOf<IBinder, Int>()

    /** clientId → 已注册的死亡监听（stopEngine/unregister 时释放）。 */
    private val mDeathRecipients = mutableMapOf<Int, IBinder.DeathRecipient>()

    private val bridge = object : AlgoEngineBridge.Stub() {
        override fun getSharedMemory(): SharedMemory =
            mShm ?: throw IllegalStateException("engine not initialized")

        override fun getState(): String =
            "running=$running pid=${android.os.Process.myPid()}"

        override fun start(): Boolean {
            startEngine()
            return running
        }

        override fun stop() {
            stopEngine()
        }

        override fun setDumpPath(path: String) {
            // 渲染层把算法处理后数据的 dump 路径下发给算法进程
            mAlgorithm?.setDumpPath(path)
        }

        // ============ FACEP-011：能力注册/订阅 ============

        override fun register(packageName: String, token: IBinder?): Int {
            // 安全校验：仅允许同 uid（android.uid.system / 本应用）的调用方注册
            val callingUid = android.os.Binder.getCallingUid()
            if (callingUid != android.os.Process.myUid()) {
                Log.w(TAG, "register: rejected uid=$callingUid (expect ${android.os.Process.myUid()})")
                return ERR_ACCESS_DENIED
            }

            synchronized(mClientLock) {
                if (mClients.size >= MAX_CLIENTS) {
                    Log.w(TAG, "register: max clients reached ($MAX_CLIENTS)")
                    return ERR_CLIENT_FULL
                }
                // 从 0 递增分配；在 0..MAX_CLIENTS-1 内找第一个空闲 id，避免溢出/重复
                var id = mNextClientId
                for (trial in 0 until MAX_CLIENTS) {
                    val candidate = (id + trial) % MAX_CLIENTS
                    if (!mClients.containsKey(candidate)) {
                        mClients[candidate] = packageName
                        mSubscriptions[candidate] = mutableSetOf()
                        mNextClientId = (candidate + 1) % MAX_CLIENTS

                        // linkToDeath：客户端进程死亡时自动清理，防僵尸 clientId 占满槽位
                        if (token != null) {
                            try {
                                val death = IBinder.DeathRecipient {
                                    onClientDied(candidate)
                                }
                                token.linkToDeath(death, 0)
                                mTokenToClient[token] = candidate
                                mDeathRecipients[candidate] = death
                            } catch (e: Exception) {
                                Log.w(TAG, "register: linkToDeath failed for clientId=$candidate", e)
                            }
                        }
                        Log.i(TAG, "register: clientId=$candidate pkg=$packageName total=${mClients.size}")
                        return candidate
                    }
                }
                return ERR_CLIENT_FULL
            }
        }

        override fun unregister(clientId: Int) {
            synchronized(mClientLock) {
                cleanupClientLocked(clientId)
            }
        }

        /** 客户端进程死亡回调：自动注销其订阅并释放槽位。 */
        private fun onClientDied(clientId: Int) {
            Log.w(TAG, "client died, auto unregister clientId=$clientId")
            unregister(clientId)
        }

        /** 在锁内释放指定 clientId 的所有资源（注册、订阅、死亡监听）。 */
        private fun cleanupClientLocked(clientId: Int) {
            val removed = mClients.remove(clientId)
            mSubscriptions.remove(clientId)
            // 释放死亡监听 + token 映射
            mDeathRecipients.remove(clientId)?.let { death ->
                val token = mTokenToClient.entries.firstOrNull { it.value == clientId }?.key
                if (token != null) {
                    try { token.unlinkToDeath(death, 0) } catch (_: Exception) { }
                    mTokenToClient.remove(token)
                }
            }
            mDeathRecipients.remove(clientId)
            if (removed != null) {
                Log.i(TAG, "unregister: clientId=$clientId total=${mClients.size}")
            }
        }

        override fun init(modelDir: String): Int {
            // 算法已在 onBind → startEngine 时初始化，此处幂等确认
            return if (mAlgorithm != null) {
                Log.i(TAG, "init: already initialized (engine running)")
                ERR_OK
            } else {
                Log.w(TAG, "init: algorithm not initialized")
                ERR_NOT_INITIALIZED
            }
        }

        override fun subscribe(clientId: Int, moduleIds: IntArray): Int {
            // 校验模块合法并解析为枚举（避免使用易受旧 Kotlin 推断问题影响的 lambda 集合 API）
            val parsed = java.util.ArrayList<CapabilityModule>(moduleIds.size)
            for (topic in moduleIds) {
                val module = CapabilityModule.fromTopic(topic)
                if (module == null) {
                    Log.w(TAG, "subscribe: invalid module topic=$topic")
                    return ERR_INVALID_MODULE
                }
                parsed.add(module)
            }
            synchronized(mClientLock) {
                if (!mClients.containsKey(clientId)) {
                    Log.w(TAG, "subscribe: unknown clientId=$clientId")
                    return ERR_CLIENT_INVALID
                }
                val set = mSubscriptions.getOrPut(clientId) { mutableSetOf() }
                for (module in parsed) {
                    set.add(module.topic)
                }
                val names = StringBuilder()
                for (m in parsed) {
                    if (names.isNotEmpty()) names.append(", ")
                    names.append(m.name)
                }
                Log.i(TAG, "subscribe: clientId=$clientId modules=[$names]")
                return ERR_OK
            }
        }

        override fun unsubscribe(clientId: Int, moduleIds: IntArray): Int {
            synchronized(mClientLock) {
                if (!mClients.containsKey(clientId)) {
                    Log.w(TAG, "unsubscribe: unknown clientId=$clientId")
                    return ERR_CLIENT_INVALID
                }
                mSubscriptions[clientId]?.let { set ->
                    moduleIds.forEach { set.remove(it) }
                }
                Log.i(TAG, "unsubscribe: clientId=$clientId")
                return ERR_OK
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: pid=${android.os.Process.myPid()}")
    }

    // 生命周期由绑定驱动（避免 START_STICKY 导致空跑/双重推理）：
    // - 第一个客户端 bind 时启动引擎；
    // - 全部客户端 unbind 时停止引擎。
    private var bindCount = 0

    override fun onBind(intent: Intent?): IBinder? {
        bindCount++
        Log.i(TAG, "onBind: bindCount=$bindCount")
        startEngine()
        return bridge
    }

    override fun onUnbind(intent: Intent?): Boolean {
        bindCount--
        Log.i(TAG, "onUnbind: bindCount=$bindCount")
        if (bindCount <= 0) {
            stopEngine()
        }
        return false
    }

    /**
     * 启动引擎：初始化算法 + 输出队列 + 相机取帧。
     */
    private fun startEngine() {
        if (running) return
        var ok = false
        try {
            // 1. 算法（检查初始化结果）
            val algorithm = FaceIDAlgorithmImpl()
            if (!algorithm.initialize(this, HashMap())) {
                Log.e(TAG, "algorithm initialize failed")
                return
            }
            mAlgorithm = algorithm
            // 2. 算法线程池（每次 startEngine 重建，避免复用已 shutdown 的池）
            val exec = Executors.newSingleThreadExecutor()
            mExecutor = exec
            // 3. 输出队列（跨进程）+ 按模块发布器（FACEP-011）
            val q = ShmQueue.create("algo_result", capacity = 16, maxReaders = 4)
            mOutQueue = q
            mShm = q.ownedShm
            mPublisher = CapabilityPublisher(q) { subscribedTopicsSnapshot() }
            // 4. 车速信号
            mVehicleSignal = VehicleSignalSource(this).also { it.connect() }
            // 5. 相机独立取帧
            val cam = FaceIDCameraController().also { c ->
                c.onFrameData = { hw, w, h -> onCameraFrame(hw, w, h) }
            }
            mCamera = cam
            val camMgr = CameraManager(cam)
            mCameraManager = camMgr
            camMgr.openCamera()
            // 6. 帧处理器（算法进程不需要帧画面 dump，onRawFrame 不传）
            mFrameProcessor = FrameProcessor(
                algorithm, exec,
                mCallback = { result -> onAlgorithmResult(result) }
            )
            // 6. 相机 buffer 回收线程（方案 1）
            //    本引擎无 GL 渲染器调用 getNewFrame，若不手动 dequeue，EVS buffer
            //    会停留在 QUEUED，onFrameEvent 找不到 NONE buffer → drop frame，
            //    onFrameData 无法持续触发。此线程周期调用 getNewFrame()（内部
            //    descriptor 链自动回收），使 buffer 循环，保证帧持续送入算法。
            startBufferRecycler(cam)
            ok = true
            running = true
            Log.i(TAG, "engine started")
        } catch (e: Exception) {
            Log.e(TAG, "engine start failed", e)
        } finally {
            // 任一初始化步骤失败：释放已初始化的资源，避免泄漏
            if (!ok) {
                cleanupPartialInit()
            }
        }
    }

    /**
     * 启动相机 buffer 回收线程。
     *
     * 周期调用 [FaceIDCameraController.getNewFrame] 让 EVS buffer 状态循环
     * （QUEUED → DEQUEUED → RECYCLE → NONE），否则 buffer 停留在 QUEUED，
     * onFrameEvent 找不到 NONE buffer 而 drop frame。
     * 返回值由 getNewFrame 内部 descriptor 链管理，此处不手动 recycle，避免双回收。
     */
    private fun startBufferRecycler(cam: FaceIDCameraController) {
        mBufferThread = Thread {
            while (running) {
                try {
                    cam.getNewFrame()
                    Thread.sleep(BUFFER_RECYCLE_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "buffer recycler error", e)
                }
            }
        }.apply { isDaemon = true }.also { it.start() }
    }

    /**
     * 相机帧到达：读出 UYVY 字节并送入算法处理（裁剪/推理）。
     * 注意：帧读取在 GL/回调线程，送入 FrameProcessor 后由算法线程处理。
     */
    private fun onCameraFrame(hw: HardwareBuffer, w: Int, h: Int) {
        if (!running) return
        try {
            val bytes = readUyvy(hw, w, h) ?: return
            mFrameProcessor?.submitFrame(bytes, w, h)
        } catch (e: Exception) {
            Log.e(TAG, "onCameraFrame error", e)
        }
    }

    /** 读取 HardwareBuffer 中的 UYVY 字节（共享 JNI 类，与主进程一致）。 */
    private fun readUyvy(hw: HardwareBuffer, w: Int, h: Int): ByteArray? {
        return HardwareBufferReader.read(hw, w, h)
    }

    /**
     * 算法推理完成：整合车速 + 分心判定，发布 [AlgorithmResult] 到共享队列。
     * 在算法线程（FrameProcessor 回调）调用，[DistractionStateMachine] 保持单线程。
     */
    private fun onAlgorithmResult(result: IFaceIDAlgorithm.FaceIDResult) {
        if (mOutQueue == null) {
            Log.w(TAG, "onAlgorithmResult: mOutQueue null")
            return
        }
        val speed = mVehicleSignal?.speedKmh ?: -1f
        Log.d(TAG, "onAlgorithmResult: face=${result.faceId} subs=${subscriptionCount()}")
        // 分心判定（算法线程内单线程调用状态机）
        val hasFace = result.faceId.isNotEmpty()
        val distracted = if (hasFace) {
            mStateMachine.update(hasFace, result.gazeDistracted, speed)
        } else {
            mStateMachine.reset()
            false
        }
        val cam = mCamera
        val algoResult = AlgorithmResult(
            frameW = cam?.frameWidth ?: 0,
            frameH = cam?.frameHeight ?: 0,
            hasFace = hasFace,
            faceLeft = result.faceRect?.left ?: 0f,
            faceTop = result.faceRect?.top ?: 0f,
            faceRight = result.faceRect?.right ?: 0f,
            faceBottom = result.faceRect?.bottom ?: 0f,
            faceConfidence = result.confidence,
            headposePitch = result.headposePitch,
            headposeYaw = result.headposeYaw,
            headposeRoll = result.headposeRoll,
            gazeValid = result.gazeValid,
            gazeYaw = result.gazeYaw,
            gazePitch = result.gazePitch,
            zoneId = result.zoneId,
            gazeCalibrated = result.gazeCalibrated,
            distracted = distracted,
            distractionBand = mStateMachine.currentSpeedBand(),
            distractionThresholdMs = mStateMachine.currentTriggerMs(),
            speedKmh = speed,
            distractionScore = result.distractionScore,
            distractionHpScore = result.distractionHpScore,
            distractionGazeScore = result.distractionGazeScore,
            keypoints = result.keypoints?.let { pts ->
                FloatArray(10) { i ->
                    if (i % 2 == 0) pts[i / 2].x else pts[i / 2].y
                }
            }
        )
        // 按模块 topic 发布（FACEP-011 阶段 B）：只发布有消费者订阅的模块
        mPublisher?.publishModules(algoResult)
    }

    /**
     * 计算当前至少有一个消费者订阅的能力模块 topic 集合（FACEP-011）。
     * 供 [CapabilityPublisher] 决定只发布哪些模块。
     */
    private fun subscribedTopicsSnapshot(): Set<Int> {
        synchronized(mClientLock) {
            val topics = mutableSetOf<Int>()
            for (set in mSubscriptions.values) {
                topics.addAll(set)
            }
            return topics
        }
    }

    /** 当前订阅消费者数（调试用）。 */
    private fun subscriptionCount(): Int {
        synchronized(mClientLock) {
            return mSubscriptions.count { it.value.isNotEmpty() }
        }
    }

    private fun stopEngine() {
        running = false
        mBufferThread?.interrupt()
        mBufferThread = null
        try { mCameraManager?.stopCamera() } catch (_: Exception) { }
        try { mAlgorithm?.release() } catch (_: Exception) { }
        mAlgorithm = null
        mVehicleSignal?.disconnect()
        mExecutor?.shutdown()
        mExecutor = null
        mOutQueue?.close()
        try { mShm?.close() } catch (_: Exception) { }
        mPublisher = null
        mFrameProcessor = null
        // 清空消费者注册表/订阅表/死亡监听，避免引擎重启后残留"幽灵"订阅
        // 导致错误发布（旧 clientId 对应 reader 槽位与重建后的队列错位）。
        synchronized(mClientLock) {
            for (entry in mTokenToClient.entries) {
                val death = mDeathRecipients[entry.value]
                if (death != null) {
                    try { entry.key.unlinkToDeath(death, 0) } catch (_: Exception) { }
                }
            }
            mTokenToClient.clear()
            mDeathRecipients.clear()
            mClients.clear()
            mSubscriptions.clear()
            mNextClientId = 0
        }
        Log.i(TAG, "engine stopped")
    }

    /**
     * 初始化失败时，释放已初始化但不完整的资源（避免泄漏）。
     * 仅在 [startEngine] 中途异常时调用。
     */
    private fun cleanupPartialInit() {
        try { mCameraManager?.stopCamera() } catch (_: Exception) { }
        try { mAlgorithm?.release() } catch (_: Exception) { }
        mVehicleSignal?.disconnect()
        mOutQueue?.close()
        try { mShm?.close() } catch (_: Exception) { }
        // 复位引用，避免重复释放
        mOutQueue = null
        mShm = null
        mVehicleSignal = null
        mFrameProcessor = null
        Log.w(TAG, "engine partial init cleaned")
    }

    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }

    companion object {
        /** 相机 buffer 回收线程周期（ms）：需 ≤ 相机帧间隔，保证 buffer 循环跟上。 */
        private const val BUFFER_RECYCLE_INTERVAL_MS = 15L

        // ============ FACEP-011：能力接口错误码 ============
        const val ERR_OK = 0
        const val ERR_CLIENT_INVALID = -1
        const val ERR_CLIENT_FULL = -2
        const val ERR_INVALID_MODULE = -3
        const val ERR_NOT_INITIALIZED = -4
        const val ERR_ACCESS_DENIED = -5

        /** 最大并发消费者数（决定共享内存 reader 槽位上限）。 */
        const val MAX_CLIENTS = 4
    }
}
