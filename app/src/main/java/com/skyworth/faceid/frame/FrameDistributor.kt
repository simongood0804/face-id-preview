package com.skyworth.faceid.frame

import android.hardware.HardwareBuffer
import android.util.Log
import com.skyworth.faceid.algorithm.FrameProcessor

/**
 * 图像帧分发器（帧层）。
 *
 * 统一管理"帧源 → 算法帧处理"的分发路径：
 * - 从 [FrameSource] 接收原始帧（HardwareBuffer）；
 * - 通过 [readFrame] 在帧有效期内立即读取数据为 ByteArray；
 * - 提交给 [FrameProcessor] 做 ROI 裁剪 + 算法推理。
 *
 * 设计目标：
 * - **解耦**：帧源与算法处理不直接持有对方引用，通过本分发器桥接；
 * - **故障隔离**：单帧读取/处理异常被捕获并记录，不中断帧源与其他消费者；
 * - **保持渲染独立**：渲染路径（EVS `getNewFrame`）不经过本分发器，互不干扰。
 *
 * 使用方式：构造后调用 [attach]，即完成帧源回调到算法处理的绑定。
 * 线程安全：非线程安全，应在单一线程内配置。
 */
class FrameDistributor(
    private val frameSource: FrameSource,
    private val frameProcessor: FrameProcessor,
    /** 读取 HardwareBuffer 数据为 ByteArray（须在帧有效期内调用，通常在 GL/帧线程）。 */
    private val readFrame: (hwBuffer: HardwareBuffer, width: Int, height: Int) -> ByteArray?,
    /** 算法是否启用的查询（动态开关）。 */
    private val algorithmEnabled: () -> Boolean
) {
    private val TAG = "FrameDistributor"

    /** 当前帧尺寸（供算法结果回调与视口计算使用）。 */
    @Volatile
    var frameWidth = 0
        private set

    @Volatile
    var frameHeight = 0
        private set

    /**
     * 绑定帧源回调到算法处理路径。
     * 调用后，帧源每产生一帧将走算法处理。
     *
     * 统一采用"链式保留"模式：帧源可能已有上层注册的
     * [FrameSource.onFrameSizeChanged] / [FrameSource.onFrameData]
     * （如 PreviewActivity 用于调整渲染画面宽高比），此处先缓存既有的回调，
     * 再挂载本分发器逻辑，并在回调中保留调用既有回调，避免覆盖丢失。
     * 否则会造成：画面尺寸失真（占满全屏）、帧数据回调丢失（算法不处理）。
     */
    fun attach() {
        // 链式保留尺寸回调：先更新内部尺寸，再通知上层调整视口/宽高比
        val prevSizeCallback = frameSource.onFrameSizeChanged
        frameSource.onFrameSizeChanged = { w, h ->
            frameWidth = w
            frameHeight = h
            prevSizeCallback?.invoke(w, h)
        }
        // 链式保留帧数据回调：先走算法处理，再通知上层（若有）
        val prevDataCallback = frameSource.onFrameData
        frameSource.onFrameData = { hwBuffer, w, h ->
            onFrame(hwBuffer, w, h)
            prevDataCallback?.invoke(hwBuffer, w, h)
        }
        Log.i(TAG, "attach: frame source bound to frame processor")
    }

    /**
     * 解除绑定。
     */
    fun detach() {
        frameSource.onFrameData = null
        frameSource.onFrameSizeChanged = null
        Log.i(TAG, "detach: unbound frame source")
    }

    /**
     * 处理一帧（算法路径入口）。
     * 在帧有效期内立即读取数据并提交给帧处理器。
     */
    private fun onFrame(hwBuffer: HardwareBuffer, frameW: Int, frameH: Int) {
        if (!algorithmEnabled()) return  // 算法关闭，跳过处理
        frameWidth = frameW
        frameHeight = frameH

        // 在帧有效期内读取 buffer 数据（buffer 此时有效）
        val data = try {
            readFrame(hwBuffer, frameW, frameH)
        } catch (e: Exception) {
            Log.e(TAG, "onFrame: read buffer failed", e)
            null
        }
        if (data == null) return

        try {
            frameProcessor.submitFrame(data, frameW, frameH)
        } catch (e: Exception) {
            Log.e(TAG, "onFrame: submit frame failed", e)
        }
    }
}
