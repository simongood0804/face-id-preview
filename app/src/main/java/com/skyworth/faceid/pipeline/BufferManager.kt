/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.pipeline

import android.util.Log
import com.android.car.evs.EvsBufferDesc
import com.skyworth.faceid.camera.CameraManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Buffer 生命周期管理器。
 *
 * 核心职责：
 * - 跟踪所有从 EvsSDK 取出的 Buffer
 * - 引用计数管理，防止重复归还
 * - 超时强制回收机制
 * - 监控 Buffer 泄漏并日志告警
 *
 * 线程安全：内部使用 ConcurrentHashMap 和 volatile 保证线程安全。
 */
open class BufferManager
@JvmOverloads constructor(
    private val mCameraManager: CameraManager,
    private val mRecycleTimeoutMs: Long = DEFAULT_RECYCLE_TIMEOUT_MS
) {
    private val TAG = "BufferManager"

    /** 当前在途的 Buffer 追踪表（bufferId -> 包装对象）。 */
    private val mActiveBuffers = ConcurrentHashMap<Int, BufferEntry>()

    /** 超时检测定时器。 */
    private var mTimeoutMonitor: ScheduledExecutorService? = null

    /** 当前取帧计数器（用于统计）。 */
    private val mFrameCount = AtomicInteger(0)

    /** 泄漏计数。 */
    private val mLeakCount = AtomicInteger(0)

    private class BufferEntry(
        val buffer: EvsBufferDesc,
        val acquireTimeMs: Long = System.currentTimeMillis(),
        @Volatile var isRecycled: Boolean = false
    )

    fun registerBuffer(buffer: EvsBufferDesc?) {
        if (buffer == null) return
        val id = buffer.getId()
        mActiveBuffers[id] = BufferEntry(buffer)
        mFrameCount.incrementAndGet()
        Log.d(TAG, "registerBuffer: id=$id, activeCount=${mActiveBuffers.size}")
    }

    fun recycleBuffer(bufferId: Int) {
        val entry = mActiveBuffers.remove(bufferId) ?: run {
            Log.w(TAG, "recycleBuffer: not found or already recycled, id=$bufferId")
            return
        }
        if (entry.isRecycled) {
            Log.w(TAG, "recycleBuffer: duplicate recycle, id=$bufferId")
            return
        }
        entry.isRecycled = true
        mCameraManager.returnBuffer(entry.buffer)
        Log.d(TAG, "recycleBuffer: id=$bufferId, elapsed=${System.currentTimeMillis() - entry.acquireTimeMs}ms")
    }

    fun recycleBuffer(buffer: EvsBufferDesc?) {
        if (buffer == null) return
        recycleBuffer(buffer.getId())
    }

    fun startTimeoutMonitor() {
        if (mTimeoutMonitor?.isShutdown == false) return
        mTimeoutMonitor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "BufferTimeoutMonitor").apply { isDaemon = true }
        }
        mTimeoutMonitor?.scheduleAtFixedRate(
            { checkTimeouts() },
            mRecycleTimeoutMs,
            mRecycleTimeoutMs / 2,
            TimeUnit.MILLISECONDS
        )
        Log.i(TAG, "startTimeoutMonitor: timeout=${mRecycleTimeoutMs}ms")
    }

    private fun checkTimeouts() {
        val now = System.currentTimeMillis()
        val iterator = mActiveBuffers.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.isRecycled) {
                iterator.remove()
                continue
            }
            val elapsed = now - entry.acquireTimeMs
            if (elapsed > mRecycleTimeoutMs) {
                val leakId = entry.buffer.getId()
                Log.w(TAG, "checkTimeouts: force recycle, id=$leakId, elapsed=${elapsed}ms")
                mLeakCount.incrementAndGet()

                entry.isRecycled = true
                mCameraManager.returnBuffer(entry.buffer)

                iterator.remove()
            }
        }
    }

    fun shutdown() {
        mTimeoutMonitor?.apply {
            shutdownNow()
            mTimeoutMonitor = null
        }
        val remaining = mActiveBuffers.size
        if (remaining > 0) {
            Log.w(TAG, "shutdown: force recycle $remaining pending buffers")
            mActiveBuffers.values.forEach { entry ->
                if (!entry.isRecycled) {
                    mCameraManager.returnBuffer(entry.buffer)
                }
            }
            mActiveBuffers.clear()
        }
        Log.i(TAG, "shutdown: done, totalFrames=${mFrameCount.get()}, leaks=${mLeakCount.get()}")
    }

    @get:JvmName("getActiveBufferCount")
    val activeBufferCount: Int get() = mActiveBuffers.size
    @get:JvmName("getLeakCount")
    val leakCount: Int get() = mLeakCount.get()
    @get:JvmName("getTotalFrameCount")
    val totalFrameCount: Int get() = mFrameCount.get()

    companion object {
        const val DEFAULT_RECYCLE_TIMEOUT_MS = 1000L
    }
}
