package com.skyworth.faceid.shmtest

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.skyworth.faceid.bus.ShmQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 算法能力客户端 SDK（FACEP-011）。
 *
 * 供**外部 App 模块**接入 `:algo` 算法能力服务，屏蔽底层 Binder / 共享内存细节，
 * 三步即可使用：
 * 1. [connect]：绑定算法能力服务（**阻塞等待绑定完成**）；
 * 2. [init]：注册为消费者 + 初始化算法 + 附着共享内存（**只做一次**）；
 * 3. [subscribe]/[obtainXXX]：订阅能力模块并按需取数。
 *
 * 用法示例：
 * ```kotlin
 * val client = FaceCapabilityClient(context)
 * if (client.connect() && client.init() == 0) {
 *     client.subscribe(setOf(CapabilityModule.FACE_DETECT, CapabilityModule.VEHICLE_SPEED))
 *     // 在回调/线程中轮询
 *     val face = client.obtainFaceBox()
 *     val speed = client.obtainSpeed()
 * }
 * client.disconnect()
 * ```
 *
 * 算法进程按 [CapabilityModule] 独立 topic 发布（FACEP-011 阶段 B），
 * [obtainFaceBox]/[obtainSpeed] 等仅按订阅模块过滤读取，未订阅的模块返回 null。
 *
 * 线程安全：连接/注册/取数/断开可跨线程调用；内部对状态用 volatile + 锁保护。
 */
class FaceCapabilityClient(private val context: Context) {

    private val TAG = "FaceCapabilityClient"

    /** 底层能力服务桥接。 */
    @Volatile private var mBridge: AlgoEngineBridge? = null

    /** 注册返回的消费者 id。 */
    @Volatile private var mClientId = -1

    /** 是否已连接（onServiceConnected 回调后为 true）。 */
    @Volatile private var mConnected = false

    /** 是否已注册 + 已附着共享内存。 */
    @Volatile private var mInitialized = false

    /** 客户端存活 token（服务端 linkToDeath 监听本进程死亡）。 */
    private val mToken = android.os.Binder()

    /** 附着的结果共享队列（init 时 attach 一次，obtain 复用，避免反复 mmap）。 */
    private var mQueue: ShmQueue? = null
    private var mReaderId = -1

    /** 连接状态变化信号：onServiceConnected/onServiceDisconnected 触发。 */
    private val mLock = Any()
    private var mConnectLatch = CountDownLatch(1)

    private val mConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            synchronized(mLock) {
                mBridge = AlgoEngineBridge.Stub.asInterface(binder)
                mConnected = true
            }
            mConnectLatch.countDown()
            Log.i(TAG, "connected to algo engine")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(mLock) {
                mBridge = null
                mConnected = false
                mInitialized = false
                mClientId = -1
                closeQueueLocked()
            }
            Log.w(TAG, "algo engine disconnected")
        }
    }

    /** 绑定算法能力服务（阻塞等待绑定完成，最多 [CONNECT_TIMEOUT_MS]）。 */
    fun connect(): Boolean {
        if (mConnected) return true
        synchronized(mLock) {
            if (mConnected) return true
            // 重新 connect 前重置信号量
            if (mConnectLatch.count <= 0) mConnectLatch = CountDownLatch(1)
        }
        val intent = Intent(context, AlgoEngineService::class.java)
        val ok = try {
            context.bindService(intent, mConn, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "bindService failed", e)
            mConnectLatch.countDown()
            return false
        }
        if (!ok) {
            Log.e(TAG, "bindService returned false")
            mConnectLatch.countDown()
            return false
        }
        // 等待 onServiceConnected（异步 Binder 回调）
        try {
            mConnectLatch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "connect interrupted")
        }
        return mConnected
    }

    /**
     * 注册为消费者 + 初始化算法 + 附着结果共享内存（幂等，只做一次）。
     * @return [AlgoEngineService.ERR_OK] 成功；其他为错误码。
     */
    fun init(): Int {
        val bridge = mBridge ?: return AlgoEngineService.ERR_NOT_INITIALIZED
        if (mInitialized) return AlgoEngineService.ERR_OK

        synchronized(mLock) {
            if (mInitialized) return AlgoEngineService.ERR_OK
            // 1. 注册为消费者
            val id = bridge.register(context.packageName, mToken)
            if (id < 0) {
                Log.e(TAG, "register failed code=$id")
                return id
            }
            mClientId = id
            // 2. 附着共享内存（一次性，obtain 复用），注册固定 reader
            try {
                val shm = bridge.getSharedMemory()
                val q = ShmQueue.attach(shm)
                val rid = q.registerReader()
                if (rid < 0) {
                    Log.e(TAG, "registerReader failed (readers full)")
                    try { q.close() } catch (_: Exception) { }
                    bridge.unregister(id)
                    mClientId = -1
                    return AlgoEngineService.ERR_CLIENT_FULL
                }
                mQueue = q
                mReaderId = rid
            } catch (e: Exception) {
                Log.e(TAG, "attach shared memory failed", e)
                bridge.unregister(id)
                mClientId = -1
                return AlgoEngineService.ERR_NOT_INITIALIZED
            }
            // 3. 初始化算法（幂等）
            mInitialized = true
            return bridge.init("")
        }
    }

    /**
     * 订阅能力模块（可多次调用累加）。
     * @return [AlgoEngineService.ERR_OK] 成功；其他为错误码。
     */
    fun subscribe(modules: Set<CapabilityModule>): Int {
        val bridge = mBridge ?: return AlgoEngineService.ERR_NOT_INITIALIZED
        if (!mInitialized) {
            Log.w(TAG, "subscribe: call init() first")
            return AlgoEngineService.ERR_CLIENT_INVALID
        }
        val topics = modules.map { it.topic }.toIntArray()
        return bridge.subscribe(mClientId, topics)
    }

    /** 退订能力模块。 */
    fun unsubscribe(modules: Set<CapabilityModule>): Int {
        val bridge = mBridge ?: return AlgoEngineService.ERR_NOT_INITIALIZED
        if (!mInitialized) return AlgoEngineService.ERR_CLIENT_INVALID
        val topics = modules.map { it.topic }.toIntArray()
        return bridge.unsubscribe(mClientId, topics)
    }

    /** 获取最近的人脸检测结果；未订阅该模块返回 null。 */
    fun obtainFaceBox(): FaceBoxData? = obtainModule(CapabilityModule.FACE_DETECT) as? FaceBoxData

    /** 获取最近的头姿结果；未订阅该模块返回 null。 */
    fun obtainHeadpose(): HeadposeData? = obtainModule(CapabilityModule.HEADPOSE) as? HeadposeData

    /** 获取最近的视线结果；未订阅该模块返回 null。 */
    fun obtainGaze(): GazeData? = obtainModule(CapabilityModule.GAZE) as? GazeData

    /** 获取最近的分心结果；未订阅该模块返回 null。 */
    fun obtainDistract(): DistractData? = obtainModule(CapabilityModule.DISTRACTION) as? DistractData

    /** 获取最近的车速结果；未订阅该模块返回 null。 */
    fun obtainSpeed(): SpeedData? = obtainModule(CapabilityModule.VEHICLE_SPEED) as? SpeedData

    /**
     * 从缓存的结果队列中，按 [module] topic 读取该模块的最新一条数据。
     * 复用 init 时附着的队列与 reader（不重复 mmap / Binder IPC）。
     * 注意：reader 位置持续前进，重复调用会读到更新的数据；多模块建议
     * 各模块顺序调用（内部不推进其它模块的 topic 数据）。
     */
    private fun obtainModule(module: CapabilityModule): Any? {
        val q = mQueue ?: return null
        val rid = mReaderId
        if (rid < 0 || !mInitialized) return null
        return try {
            var latest: Any? = null
            while (q.hasNext(rid)) {
                val m = q.readNext(rid) ?: break
                if (m.topic == module.topic) {
                    latest = decodeModule(module, m.payload) ?: continue
                }
            }
            latest
        } catch (e: Exception) {
            Log.e(TAG, "obtain $module failed", e)
            null
        }
    }

    private fun decodeModule(module: CapabilityModule, payload: ByteArray): Any? = when (module) {
        CapabilityModule.FACE_DETECT -> FaceBoxData.decode(payload)
        CapabilityModule.HEADPOSE -> HeadposeData.decode(payload)
        CapabilityModule.GAZE -> GazeData.decode(payload)
        CapabilityModule.DISTRACTION -> DistractData.decode(payload)
        CapabilityModule.VEHICLE_SPEED -> SpeedData.decode(payload)
    }

    /** 断开服务，注销消费者并释放资源。 */
    fun disconnect() {
        synchronized(mLock) {
            try {
                if (mInitialized && mClientId >= 0) {
                    mBridge?.unregister(mClientId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "unregister failed", e)
            }
            closeQueueLocked()
            try {
                if (mConnected) context.unbindService(mConn)
            } catch (e: Exception) {
                Log.w(TAG, "unbind failed", e)
            }
            mBridge = null
            mClientId = -1
            mInitialized = false
            mConnected = false
            mConnectLatch.countDown()
        }
    }

    /** 关闭结果队列并释放 reader（需在锁内调用）。 */
    private fun closeQueueLocked() {
        mQueue?.let { q ->
            if (mReaderId >= 0) {
                try { q.unregisterReader(mReaderId) } catch (_: Exception) { }
            }
            try { q.close() } catch (_: Exception) { }
        }
        mQueue = null
        mReaderId = -1
    }

    companion object {
        /** 绑定等待超时（ms）。 */
        private const val CONNECT_TIMEOUT_MS = 2000L
    }
}
