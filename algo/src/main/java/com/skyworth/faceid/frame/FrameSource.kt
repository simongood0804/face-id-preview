package com.skyworth.faceid.frame

import android.hardware.HardwareBuffer

/**
 * 图像帧源抽象。
 *
 * 统一采集层的帧产出接口，屏蔽具体相机实现（EVS / 其他）。
 * [FrameDistributor] 通过本接口从帧源接收帧，再统一分发给算法/渲染消费者。
 *
 * 实现约定：
 * - [start] / [stop] 控制采集启停；
 * - 采集到新帧时通过 [onFrameData] 回调输出；
 * - 帧尺寸变化时通过 [onFrameSizeChanged] 回调输出。
 *
 * 故障隔离：实现类应保证采集异常只触发回调错误上报，不向上抛出导致上层崩溃。
 */
interface FrameSource {

    /**
     * 启动采集。
     *
     * @param cameraId 相机标识
     */
    fun start(cameraId: String)

    /**
     * 停止采集并释放资源。
     */
    fun stop()

    /**
     * 新帧回调（帧到达时触发）。
     *
     * @param hwBuffer 原始 HardwareBuffer
     * @param width 帧宽
     * @param height 帧高
     */
    var onFrameData: ((hwBuffer: HardwareBuffer, width: Int, height: Int) -> Unit)?

    /**
     * 帧尺寸变化回调。
     */
    var onFrameSizeChanged: ((width: Int, height: Int) -> Unit)?
}
