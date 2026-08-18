package com.skyworth.faceid.shmtest

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.SharedMemory

/**
 * 算法进程能力服务接口（阶段 E + FACEP-011 阶段 A）。
 *
 * 一个 Binder 同时承载两类能力：
 * 1. **引擎控制**（渲染进程用）：[getSharedMemory]/[getState]/[start]/[stop]/[setDumpPath]；
 * 2. **能力注册/订阅**（对外 App 模块用，FACEP-011）：[register]/[unregister]/[init]/
 *    [subscribe]/[unsubscribe]。外部模块据此注册为消费者并按模块订阅输出，
 *    配合共享内存按 topic 过滤实现「按需订阅/发布」。
 *
 * 原生 Binder 实现（避免 AIDL 文件），事务码：
 * - 1 = getSharedMemory
 * - 2 = getState
 * - 3 = start
 * - 4 = stop
 * - 5 = setDumpPath
 * - 6 = register
 * - 7 = unregister
 * - 8 = init
 * - 9 = subscribe
 * - 10 = unsubscribe
 */
interface AlgoEngineBridge : IInterface {

    fun getSharedMemory(): SharedMemory

    fun getState(): String

    fun start(): Boolean

    fun stop()

    /** 下发算法处理后数据的 dump 路径（渲染层把路径传给算法进程）。 */
    fun setDumpPath(path: String)

    /**
     * 注册为算法能力消费者（FACEP-011）。
     * @param packageName 调用方包名
     * @param token 客户端存活 token（客户端自身的某个 IBinder，用于服务端 linkToDeath
     *        检测进程死亡并自动清理）；可为 null 表示不监听。
     * @return 消费者 id（≥0）；失败返回负数。
     */
    fun register(packageName: String, token: IBinder?): Int

    /** 注销消费者，释放其订阅（FACEP-011）。 */
    fun unregister(clientId: Int)

    /**
     * 初始化算法（幂等）。加载模型；重复调用返回已初始化状态。
     * @return 0=成功；负数为错误码。
     */
    fun init(modelDir: String): Int

    /**
     * 订阅能力模块（FACEP-011）。可多次调用订阅多个模块。
     * @param clientId [register] 返回的消费者 id
     * @param moduleIds [CapabilityModule.topic] 列表
     * @return 0=成功；负数为错误码。
     */
    fun subscribe(clientId: Int, moduleIds: IntArray): Int

    /** 退订能力模块（FACEP-011）。 */
    fun unsubscribe(clientId: Int, moduleIds: IntArray): Int

    companion object {
        const val descriptor = "com.skyworth.faceid.shmtest.AlgoEngineBridge"
    }

    abstract class Stub : Binder(), AlgoEngineBridge {
        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                TRANSACTION_GET_SHM -> {
                    data.enforceInterface(descriptor)
                    val shm = getSharedMemory()
                    reply!!.writeNoException()
                    reply.writeParcelable(shm, 0)
                    true
                }
                TRANSACTION_GET_STATE -> {
                    data.enforceInterface(descriptor)
                    val state = getState()
                    reply!!.writeNoException()
                    reply.writeString(state)
                    true
                }
                TRANSACTION_START -> {
                    data.enforceInterface(descriptor)
                    val started = start()
                    reply!!.writeNoException()
                    reply.writeBoolean(started)
                    true
                }
                TRANSACTION_STOP -> {
                    data.enforceInterface(descriptor)
                    stop()
                    reply!!.writeNoException()
                    true
                }
                TRANSACTION_SET_DUMP_PATH -> {
                    data.enforceInterface(descriptor)
                    val path = data.readString() ?: ""
                    setDumpPath(path)
                    reply!!.writeNoException()
                    true
                }
                TRANSACTION_REGISTER -> {
                    data.enforceInterface(descriptor)
                    val pkg = data.readString() ?: ""
                    val token = data.readStrongBinder()
                    val id = register(pkg, token)
                    reply!!.writeNoException()
                    reply.writeInt(id)
                    true
                }
                TRANSACTION_UNREGISTER -> {
                    data.enforceInterface(descriptor)
                    val id = data.readInt()
                    unregister(id)
                    reply!!.writeNoException()
                    true
                }
                TRANSACTION_INIT -> {
                    data.enforceInterface(descriptor)
                    val modelDir = data.readString() ?: ""
                    val code = init(modelDir)
                    reply!!.writeNoException()
                    reply.writeInt(code)
                    true
                }
                TRANSACTION_SUBSCRIBE -> {
                    data.enforceInterface(descriptor)
                    val id = data.readInt()
                    val modules = data.createIntArray() ?: IntArray(0)
                    val code = subscribe(id, modules)
                    reply!!.writeNoException()
                    reply.writeInt(code)
                    true
                }
                TRANSACTION_UNSUBSCRIBE -> {
                    data.enforceInterface(descriptor)
                    val id = data.readInt()
                    val modules = data.createIntArray() ?: IntArray(0)
                    val code = unsubscribe(id, modules)
                    reply!!.writeNoException()
                    reply.writeInt(code)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }

        companion object {
            private const val TRANSACTION_GET_SHM = FIRST_CALL_TRANSACTION
            private const val TRANSACTION_GET_STATE = FIRST_CALL_TRANSACTION + 1
            private const val TRANSACTION_START = FIRST_CALL_TRANSACTION + 2
            private const val TRANSACTION_STOP = FIRST_CALL_TRANSACTION + 3
            private const val TRANSACTION_SET_DUMP_PATH = FIRST_CALL_TRANSACTION + 4
            private const val TRANSACTION_REGISTER = FIRST_CALL_TRANSACTION + 5
            private const val TRANSACTION_UNREGISTER = FIRST_CALL_TRANSACTION + 6
            private const val TRANSACTION_INIT = FIRST_CALL_TRANSACTION + 7
            private const val TRANSACTION_SUBSCRIBE = FIRST_CALL_TRANSACTION + 8
            private const val TRANSACTION_UNSUBSCRIBE = FIRST_CALL_TRANSACTION + 9

            fun asInterface(obj: IBinder?): AlgoEngineBridge? {
                if (obj == null) return null
                val iin = obj.queryLocalInterface(descriptor)
                return (iin as? AlgoEngineBridge) ?: Proxy(obj)
            }

            private class Proxy(private val remote: IBinder) : AlgoEngineBridge {
                override fun asBinder(): IBinder = remote

                override fun getSharedMemory(): SharedMemory {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(descriptor)
                        remote.transact(TRANSACTION_GET_SHM, data, reply, 0)
                        reply.readException()
                        return reply.readParcelable(SharedMemory::class.java.classLoader)!!
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                }

                override fun getState(): String {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(descriptor)
                        remote.transact(TRANSACTION_GET_STATE, data, reply, 0)
                        reply.readException()
                        return reply.readString() ?: ""
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                }

                override fun start(): Boolean {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(descriptor)
                        remote.transact(TRANSACTION_START, data, reply, 0)
                        reply.readException()
                        return reply.readBoolean()
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                }

                override fun stop() {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(descriptor)
                        remote.transact(TRANSACTION_STOP, data, reply, 0)
                        reply.readException()
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                }

                override fun setDumpPath(path: String) {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(descriptor)
                        data.writeString(path)
                        remote.transact(TRANSACTION_SET_DUMP_PATH, data, reply, 0)
                        reply.readException()
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                }

                override fun register(packageName: String, token: IBinder?): Int {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(descriptor)
                        data.writeString(packageName)
                        data.writeStrongBinder(token)
                        remote.transact(TRANSACTION_REGISTER, data, reply, 0)
                        reply.readException()
                        return reply.readInt()
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                }

                override fun unregister(clientId: Int) {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(descriptor)
                        data.writeInt(clientId)
                        remote.transact(TRANSACTION_UNREGISTER, data, reply, 0)
                        reply.readException()
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                }

                override fun init(modelDir: String): Int {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(descriptor)
                        data.writeString(modelDir)
                        remote.transact(TRANSACTION_INIT, data, reply, 0)
                        reply.readException()
                        return reply.readInt()
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                }

                override fun subscribe(clientId: Int, moduleIds: IntArray): Int {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(descriptor)
                        data.writeInt(clientId)
                        data.writeIntArray(moduleIds)
                        remote.transact(TRANSACTION_SUBSCRIBE, data, reply, 0)
                        reply.readException()
                        return reply.readInt()
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                }

                override fun unsubscribe(clientId: Int, moduleIds: IntArray): Int {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(descriptor)
                        data.writeInt(clientId)
                        data.writeIntArray(moduleIds)
                        remote.transact(TRANSACTION_UNSUBSCRIBE, data, reply, 0)
                        reply.readException()
                        return reply.readInt()
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                }
            }
        }
    }
}
