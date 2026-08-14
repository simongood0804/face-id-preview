package com.skyworth.faceid.shmtest

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.SharedMemory

/**
 * 跨进程共享内存桥接接口（阶段 B PoC）。
 *
 * [ShmBridgeService]（运行在 `:shmtest` 进程）实现本接口，
 * 主进程通过 Binder 获取：
 * - [getShm]：创建/已初始化的 [SharedMemory]（[ShmQueue] 队列所在的共享内存）；
 * - [getStats]：发布进程侧的统计（用于验证主进程确实读到了其数据）。
 *
 * 用原生 Binder 实现（避免 AIDL 文件），事务码：
 * - 1 = getShm：返回 SharedMemory（Parcelable）
 * - 2 = getStats：返回发布计数/序号
 */
interface ShmBridge : IInterface {

    /** 获取共享内存队列的 SharedMemory（可经 Binder 跨进程传递）。 */
    fun getShm(): SharedMemory

    /** 获取发布进程侧的统计：{seq, 发布数}。 */
    fun getStats(): LongArray

    companion object {
        const val descriptor = "com.skyworth.faceid.shmtest.ShmBridge"
    }

    abstract class Stub : Binder(), ShmBridge {
        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                TRANSACTION_GET_SHM -> {
                    data.enforceInterface(descriptor)
                    val shm = getShm()
                    reply!!.writeNoException()
                    reply.writeParcelable(shm, 0)
                    true
                }
                TRANSACTION_GET_STATS -> {
                    data.enforceInterface(descriptor)
                    val stats = getStats()
                    reply!!.writeNoException()
                    reply.writeLongArray(stats)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }

        companion object {
            private const val TRANSACTION_GET_SHM = FIRST_CALL_TRANSACTION
            private const val TRANSACTION_GET_STATS = FIRST_CALL_TRANSACTION + 1

            /** 将 Binder 转为 [ShmBridge] 接口。 */
            fun asInterface(obj: IBinder?): ShmBridge? {
                if (obj == null) return null
                val iin = obj.queryLocalInterface(descriptor)
                return (iin as? ShmBridge) ?: Proxy(obj)
            }

            /** Binder 代理（跨进程时使用）。 */
            private class Proxy(private val remote: IBinder) : ShmBridge {
                override fun asBinder(): IBinder = remote

                override fun getShm(): SharedMemory {
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

                override fun getStats(): LongArray {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(descriptor)
                        remote.transact(TRANSACTION_GET_STATS, data, reply, 0)
                        reply.readException()
                        return reply.createLongArray()!!
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                }
            }
        }
    }
}
