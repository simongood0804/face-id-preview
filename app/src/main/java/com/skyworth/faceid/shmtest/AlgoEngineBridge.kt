package com.skyworth.faceid.shmtest

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.SharedMemory

/**
 * 算法进程引擎控制接口（阶段 E）。
 *
 * 主进程通过 Binder 控制 `:algo` 进程的 [AlgoEngineService]：
 * - [getSharedMemory]：获取算法结果 [ShmQueue] 所在的 [SharedMemory]；
 * - [getState]：查询引擎状态（含 pid）；
 * - [start]/[stop]：启动/停止引擎。
 *
 * 原生 Binder 实现（避免 AIDL 文件），事务码：
 * - 1 = getSharedMemory
 * - 2 = getState
 * - 3 = start
 * - 4 = stop
 * - 5 = setDumpPath
 */
interface AlgoEngineBridge : IInterface {

    fun getSharedMemory(): SharedMemory

    fun getState(): String

    fun start(): Boolean

    fun stop()

    /** 下发算法处理后数据的 dump 路径（渲染层把路径传给算法进程）。 */
    fun setDumpPath(path: String)

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
                else -> super.onTransact(code, data, reply, flags)
            }
        }

        companion object {
            private const val TRANSACTION_GET_SHM = FIRST_CALL_TRANSACTION
            private const val TRANSACTION_GET_STATE = FIRST_CALL_TRANSACTION + 1
            private const val TRANSACTION_START = FIRST_CALL_TRANSACTION + 2
            private const val TRANSACTION_STOP = FIRST_CALL_TRANSACTION + 3
            private const val TRANSACTION_SET_DUMP_PATH = FIRST_CALL_TRANSACTION + 4

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
            }
        }
    }
}
