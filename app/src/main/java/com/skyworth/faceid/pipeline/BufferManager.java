/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.pipeline;

import android.util.Log;

import com.android.car.evs.EvsBufferDesc;
import com.skyworth.faceid.camera.CameraManager;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Buffer 生命周期管理器。
 *
 * <p>核心职责：
 * <ul>
 *   <li>跟踪所有从 EvsSDK 取出的 Buffer</li>
 *   <li>引用计数管理，防止重复归还</li>
 *   <li>超时强制回收机制 — 避免因算法处理卡死导致 Buffer 泄漏</li>
 *   <li>监控 Buffer 泄漏并日志告警</li>
 * </ul>
 *
 * <p>线程安全：内部使用 ConcurrentHashMap 和 volatile 保证线程安全，
 * 超时检测在独立监控线程中执行。
 */
public class BufferManager {

    private static final String TAG = "BufferManager";

    /** 相机管理器引用，用于归还 Buffer。 */
    private final CameraManager mCameraManager;

    /** Buffer 超时时间（毫秒）。 */
    private final long mRecycleTimeoutMs;

    /** 当前在途的 Buffer 追踪表（bufferId -> 包装对象）。 */
    private final ConcurrentHashMap<Integer, BufferEntry> mActiveBuffers =
            new ConcurrentHashMap<>();

    /** 超时检测定时器。 */
    private ScheduledExecutorService mTimeoutMonitor;

    /** 当前取帧计数器（用于统计）。 */
    private final AtomicInteger mFrameCount = new AtomicInteger(0);

    /** 泄漏计数。 */
    private final AtomicInteger mLeakCount = new AtomicInteger(0);

    /**
     * Buffer 包装条目。
     */
    private static class BufferEntry {

        final EvsBufferDesc buffer;
        final long acquireTimeMs;
        volatile boolean isRecycled;

        BufferEntry(EvsBufferDesc buffer) {
            this.buffer = buffer;
            this.acquireTimeMs = System.currentTimeMillis();
            this.isRecycled = false;
        }
    }

    /**
     * 构造 BufferManager。
     *
     * @param cameraManager    相机管理器，用于归还 Buffer
     * @param recycleTimeoutMs Buffer 超时回收阈值（毫秒）
     */
    public BufferManager(CameraManager cameraManager, long recycleTimeoutMs) {
        mCameraManager = cameraManager;
        mRecycleTimeoutMs = recycleTimeoutMs;
    }

    /**
     * 注册一个新获取的 Buffer。
     *
     * @param buffer 从 EvsCameraController.getNewFrame() 获取的 Buffer
     */
    public void registerBuffer(EvsBufferDesc buffer) {
        if (buffer == null) {
            return;
        }

        int id = buffer.getId();
        mActiveBuffers.put(id, new BufferEntry(buffer));
        mFrameCount.incrementAndGet();
        Log.d(TAG, "registerBuffer: id=" + id + ", activeCount=" + mActiveBuffers.size());
    }

    /**
     * 通过 ID 归还 Buffer。
     *
     * @param bufferId Buffer 的 ID
     */
    public void recycleBuffer(int bufferId) {
        BufferEntry entry = mActiveBuffers.remove(bufferId);
        if (entry == null) {
            Log.w(TAG, "recycleBuffer: not found or already recycled, id=" + bufferId);
            return;
        }

        if (entry.isRecycled) {
            Log.w(TAG, "recycleBuffer: duplicate recycle, id=" + bufferId);
            return;
        }

        entry.isRecycled = true;
        mCameraManager.returnBuffer(entry.buffer);
        Log.d(TAG, "recycleBuffer: id=" + bufferId
                + ", elapsed=" + (System.currentTimeMillis() - entry.acquireTimeMs) + "ms");
    }

    /**
     * 通过 EvsBufferDesc 对象归还 Buffer。
     *
     * @param buffer 待归还的 Buffer
     */
    public void recycleBuffer(EvsBufferDesc buffer) {
        if (buffer == null) {
            return;
        }
        recycleBuffer(buffer.getId());
    }

    /**
     * 启动超时监控。
     *
     * <p>定期检查是否有 Buffer 超过超时时间未归还，强制回收并告警。
     */
    public void startTimeoutMonitor() {
        if (mTimeoutMonitor != null && !mTimeoutMonitor.isShutdown()) {
            return;
        }

        mTimeoutMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BufferTimeoutMonitor");
            t.setDaemon(true);
            return t;
        });

        mTimeoutMonitor.scheduleAtFixedRate(this::checkTimeouts,
                mRecycleTimeoutMs, mRecycleTimeoutMs / 2, TimeUnit.MILLISECONDS);

        Log.i(TAG, "startTimeoutMonitor: timeout=" + mRecycleTimeoutMs + "ms");
    }

    /**
     * 检查并回收超时的 Buffer。
     */
    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        Iterator<BufferEntry> iterator = mActiveBuffers.values().iterator();

        while (iterator.hasNext()) {
            BufferEntry entry = iterator.next();
            if (entry.isRecycled) {
                iterator.remove();
                continue;
            }

            long elapsed = now - entry.acquireTimeMs;
            if (elapsed > mRecycleTimeoutMs) {
                int leakId = entry.buffer.getId();
                Log.w(TAG, "checkTimeouts: force recycle, id=" + leakId
                        + ", elapsed=" + elapsed + "ms");

                mLeakCount.incrementAndGet();
                recycleBuffer(leakId);
                iterator.remove();
            }
        }
    }

    /**
     * 停止超时监控并强制清理所有 Buffer。
     */
    public void shutdown() {
        if (mTimeoutMonitor != null) {
            mTimeoutMonitor.shutdownNow();
            mTimeoutMonitor = null;
        }

        // 强制回收所有活跃 Buffer
        int remaining = mActiveBuffers.size();
        if (remaining > 0) {
            Log.w(TAG, "shutdown: force recycle " + remaining + " pending buffers");
            for (BufferEntry entry : mActiveBuffers.values()) {
                if (!entry.isRecycled) {
                    mCameraManager.returnBuffer(entry.buffer);
                }
            }
            mActiveBuffers.clear();
        }

        Log.i(TAG, "shutdown: done, totalFrames=" + mFrameCount.get()
                + ", leaks=" + mLeakCount.get());
    }

    /**
     * 获取当前活跃 Buffer 数量。
     */
    public int getActiveBufferCount() {
        return mActiveBuffers.size();
    }

    /**
     * 获取泄漏次数。
     */
    public int getLeakCount() {
        return mLeakCount.get();
    }

    /**
     * 获取总处理帧数。
     */
    public int getTotalFrameCount() {
        return mFrameCount.get();
    }
}
