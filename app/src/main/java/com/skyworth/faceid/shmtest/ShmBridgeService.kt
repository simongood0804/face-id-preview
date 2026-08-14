package com.skyworth.faceid.shmtest

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SharedMemory
import android.util.Log
import com.skyworth.faceid.bus.ShmMessageSerializer
import com.skyworth.faceid.bus.ShmQueue

/**
 * 阶段 B PoC：运行在 `:shmtest` 进程的共享内存发布服务。
 *
 * 职责：
 * - 在 `:shmtest` 进程创建 [ShmQueue]（基于 [SharedMemory]）；
 * - 启动后台线程周期性向队列发布递增序号消息；
 * - 通过 [ShmBridge.Stub] 把 [SharedMemory] 经 Binder 分发给主进程，
 *   主进程 attach 后订阅读取，验证跨进程共享内存写入可见性。
 */
class ShmBridgeService : Service() {

    private var shmQueue: ShmQueue? = null
    private var publishThread: Thread? = null
    private var running = false

    // 发布统计：{已发布数, 当前序号}（供主进程通过 Binder 核对）
    private var publishedCount = 0L
    private var currentSeq = 0L

    private val bridge = object : ShmBridge.Stub() {
        override fun getShm(): SharedMemory =
            shmQueue?.ownedShm ?: throw IllegalStateException("queue not initialized")

        override fun getStats(): LongArray = longArrayOf(publishedCount, currentSeq)
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: pid=${android.os.Process.myPid()}")
        try {
            // 创建队列（capacity=16 足够 PoC）
            val q = ShmQueue.create("shm_test", capacity = 16, maxReaders = 4)
            shmQueue = q
            startPublisher(q)
            Log.i(TAG, "onCreate: queue created, totalSize=${ShmQueue.totalSize(16, 4)}")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: failed", e)
        }
    }

    /** 后台线程周期性发布消息。 */
    private fun startPublisher(q: ShmQueue) {
        running = true
        publishThread = Thread {
            var seq = 0L
            while (running) {
                try {
                    // payload：几个字节的序号 + 时间戳，验证跨进程读到一致数据
                    val payload = "seq=$seq t=${System.currentTimeMillis()}".toByteArray()
                    q.publish(TOPIC_PING, payload)
                    synchronized(this) {
                        publishedCount++
                        currentSeq = seq
                    }
                    seq++
                    Thread.sleep(PUBLISH_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "publish error", e)
                    Thread.sleep(PUBLISH_INTERVAL_MS)
                }
            }
        }.apply { isDaemon = true }.also { it.start() }
    }

    override fun onBind(intent: Intent?): IBinder? = bridge

    override fun onDestroy() {
        running = false
        publishThread?.interrupt()
        shmQueue?.let { q ->
            q.close()
            try { q.ownedShm?.close() } catch (_: Exception) { }
        }
        shmQueue = null
        super.onDestroy()
        Log.i(TAG, "onDestroy")
    }

    companion object {
        private const val TAG = "ShmBridgeService"
        private const val PUBLISH_INTERVAL_MS = 100L
        const val TOPIC_PING = 100
    }
}
