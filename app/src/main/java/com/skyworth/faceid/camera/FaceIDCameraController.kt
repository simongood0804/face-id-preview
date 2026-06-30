package com.skyworth.faceid.camera

import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import com.android.car.evs.EvsBufferDesc
import com.android.car.evs.EvsBufferProvider
import com.android.car.evs.EvsExecutorService
import com.android.car.evs.EvsFrameRate
import com.android.car.evs.EvsHalWrapper
import com.android.car.evs.EvsHalWrapperImpl
import com.android.car.evs.OpaqueIdentifier
import java.util.Collections

/**
 * 自定义 EVS 摄像头控制器（替换库的 EvsCameraController）。
 *
 * 与 [FiveCameraController] 中的 [MyEvsCameraController] 实现一致，
 * 关键特性：当摄像头打开失败时自动断线重试，确保 DMS 摄像头最终可用。
 */
class FaceIDCameraController : EvsBufferProvider {

    private val TAG = "Evs.Camera"

    /** Buffer 队列最大容量。 */
    private val buffers = Collections.synchronizedList(
        ArrayList<EvsBufferDesc>(MAX_RECEIVE_FRAME)
    )

    /** 当前取出的描述符。 */
    @Volatile private var descriptor: EvsBufferDesc? = null

    /** 最新帧的尺寸，供外部获取用于视口计算。 */
    @Volatile
    var frameWidth: Int = 0
        private set
    @Volatile
    var frameHeight: Int = 0
        private set

    /** 帧尺寸变化回调（主线程）。 */
    var onFrameSizeChanged: ((width: Int, height: Int) -> Unit)? = null

    /**
     * 帧数据回调（算法处理）。
     * 在 [getNewFrame] 中被调用，频率约为每 [FRAME_SKIP] 帧一次。
     * 参数：[HardwareBuffer], 宽, 高。
     */
    var onFrameData: ((hwBuffer: HardwareBuffer, width: Int, height: Int) -> Unit)? = null

    /** 调度执行器（单线程）。 */
    private val dExecutor = EvsExecutorService("DISPATCH", true)

    @Volatile
    private var isActive = false

    /**
     * 摄像头打开标记（静态效果，用于重试守卫）。
     * 对应 [MyEvsCameraController] 中的静态 [CAM_OPEN]，与 [isActive] 分离。
     */
    @Volatile
    private var mCamOpen = false

    /** 首帧到达时间戳，用于 TTFB 统计。 */
    private var startTime: Long = 0
    private var frameRate: EvsFrameRate? = null

    private var mCameraIds: String? = null

    /** 重试计数器（最多 65533 次）。 */
    private var mConnectCount = 0

    private val mHandler = Handler(Looper.getMainLooper())

    /** 上一帧成功的描述符（无新帧时复用，防止黑屏）。 */
    @Volatile private var mLastFrame: EvsBufferDesc? = null

    /** 断线重连任务。 */
    private val mConnectRunnable: Runnable = object : Runnable {
        override fun run() {
            if (mConnectCount < 65533) {
                mConnectCount++
                val open = mCamOpen
                if (!open) return
                mHandler.postDelayed(this, RETRY_INTERVAL_MS)
                stopCameraInternal(false)
                releaseInternal()
                try {
                    Thread.sleep(RETRY_WAIT_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                startCamera(mCameraIds!!)
            } else {
                Log.w(TAG, "connect failed over times, cameraIds=$mCameraIds")
            }
        }
    }

    /** EVS HAL 事件回调。 */
    private val callback = object : EvsHalWrapper.HalEventCallback {
        override fun onFrameEvent(i: Int, buffer: HardwareBuffer?) {
            if (null == buffer) return
            returnBuffers()
            try {
                if (startTime > 0) {
                    startTime = -1
                    Log.w(TAG, "receive first frame")
                    frameRate?.start()
                }
                mHandler.removeCallbacks(mConnectRunnable)
                if (DEBUG) Log.d(TAG, "isopen:$isActive, id:$mCameraIds")
                if (isActive) mHandler.postDelayed(mConnectRunnable, RETRY_INTERVAL_MS)
                if (DEBUG) Log.d(TAG, "onNewFrame: $i")
                frameRate?.post()
                var accept = false
                var frameW = 0
                var frameH = 0
                for (desc in buffers) {
                    if (desc.state != EvsBufferDesc.State.NONE) continue
                    accept = desc.queue(i, buffer, resolution)
                    if (accept) {
                        frameW = desc.width
                        frameH = desc.height
                    }
                    break
                }
                if (!accept) {
                    Log.w(TAG, "drop frame because over size($MAX_RECEIVE_FRAME) for: $i")
                    evsHalWrapper.doneWithFrame(i)
                } else {
                    // 直接从 HAL 回调触发算法（独立于渲染器 getNewFrame）
                    // 使用 desc.width/height（保证正确），不依赖 HardwareBuffer 属性
                    if (frameW > 0 && frameH > 0) {
                        onFrameData?.invoke(buffer, frameW, frameH)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onNewFrame", e)
            }
        }

        override fun onHalDeath() {
            Log.d(TAG, "onHalDeath")
            dExecutor.submit(Runnable { resetBuffers() }, "STREAM_EVENT_STREAM_STOPPED")
        }
    }

    private val evsHalWrapper: EvsHalWrapperImpl = EvsHalWrapperImpl(callback)

    /** 摄像头分辨率，由 [openCamera] 后获取。 */
    private var resolution: Long = 0

    init {
        for (i in 0 until MAX_RECEIVE_FRAME) {
            buffers.add(EvsBufferDesc())
        }
    }

    // ============================================================
    // 公共 API
    // ============================================================

    fun track(value: EvsFrameRate) {
        frameRate = value
    }

    fun release() {
        if (DEBUG) Log.d(TAG, "disconnect to the evs service")
        dExecutor.submit({
            try {
                evsHalWrapper.release()
            } catch (e: Exception) {
                Log.e(TAG, "disconnect", e)
            }
            isActive = false
        }, "disconnect")
    }

    /**
     * 打开摄像头（异步，支持自动重试）。
     */
    fun startCamera(cameraId: String) {
        mHandler.post {
            mCamOpen = true
            mCameraIds = cameraId
        }
        dExecutor.submit({ handleStartVideoStream(cameraId) }, "start")
    }

    /**
     * 关闭摄像头。
     */
    fun stopCamera() {
        mHandler.post { mCamOpen = false }
        dExecutor.submit({ handleStopVideoStream() }, "stop")
    }

    private fun stopCameraInternal(recycle: Boolean) {
        dExecutor.submit({ handleStopVideoStream() }, "stop")
    }

    private fun releaseInternal() {
        dExecutor.submit({
            try {
                evsHalWrapper.release()
            } catch (e: Exception) {
                Log.e(TAG, "disconnect", e)
            }
            isActive = false
        }, "disconnect")
    }

    // ============================================================
    // EvsBufferProvider
    // ============================================================

    override fun getNewFrame(): EvsBufferDesc? {
        if (!isActive) return null
        try {
            for (desc in buffers) {
                if (!desc.dequeue()) continue
                Log.d(TAG, "frame dequeued: id=${desc.id}, ${desc.width}x${desc.height}")

                // Level 1: buffer 生命周期检查（null / closed）
                val hw = desc.hardwareBuffer
                if (hw == null || hw.isClosed) {
                    EvsBufferDesc.recycle(desc)
                    Log.w(TAG, "new frame buffer invalid (null/closed), keeping previous")
                    break
                }

                // 首次获取帧时记录尺寸并回调
                if (desc.width != frameWidth || desc.height != frameHeight) {
                    frameWidth = desc.width
                    frameHeight = desc.height
                    onFrameSizeChanged?.invoke(frameWidth, frameHeight)
                }

                // 推进：回收上一帧，切换到当前帧
                EvsBufferDesc.recycle(descriptor)
                descriptor = desc
                mLastFrame = desc
                break
            }
        } catch (e: Exception) {
            Log.e(TAG, "getNewFrame error", e)
        }
        // 无新帧或新帧被丢弃 → 返回当前 descriptor（未变，保持上一帧画面）
        if (descriptor == null || descriptor!!.hardwareBuffer == null ||
                descriptor!!.hardwareBuffer!!.isClosed) {
            Log.w(TAG, "descriptor invalid, fallback to mLastFrame")
            if (mLastFrame != null && mLastFrame!!.hardwareBuffer != null &&
                    !mLastFrame!!.hardwareBuffer!!.isClosed) {
                Log.w(TAG, "mLastFrame valid, returning it")
            } else {
                Log.w(TAG, "mLastFrame also invalid")
            }
            return mLastFrame
        }
        return descriptor
    }

    // ============================================================
    // 内部方法
    // ============================================================

    @androidx.annotation.WorkerThread
    private fun handleStartVideoStream(cameraId: String) {
        try {
            evsHalWrapper.connectToHalServiceIfNecessary()
        } catch (e: Exception) {
            Log.e(TAG, "connectToHalServiceIfNecessary", e)
        }
        if (!evsHalWrapper.isConnected()) {
            Log.w(TAG, "CarEvsManager is not available.")
            return
        }
        if (isActive) return
        Log.d(TAG, "Request to start a video stream")
        try {
            startTime = SystemClock.elapsedRealtime()
            isActive = true
            evsHalWrapper.openCamera(cameraId)
            resolution = evsHalWrapper.getExtendedInfo(OpaqueIdentifier.RESOLUTION)
            val success = evsHalWrapper.requestToStartVideoStream()
            if (!success) {
                isActive = false
                Log.e(TAG, "startVideoStream failed")
            } else {
                resetBuffers()
                Log.d(TAG, "startVideoStream success")
            }
        } catch (e: Exception) {
            isActive = false
            Log.e(TAG, "startVideoStream panic.", e)
        }
    }

    @androidx.annotation.WorkerThread
    private fun handleStopVideoStream() {
        if (!isActive) return
        Log.d(TAG, "Request to stop a video stream")
        isActive = false
        try {
            evsHalWrapper.requestToStopVideoStream()
            evsHalWrapper.closeCamera()
            Log.d(TAG, "stopVideoStream success")
        } catch (e: Exception) {
            Log.e(TAG, "stopVideoStream panic.", e)
        } finally {
            resetBuffers()
            frameRate?.stop()
        }
    }

    private fun resetBuffers() {
        descriptor = null
        mLastFrame = null
        for (desc in buffers) {
            returnBuffer(desc, false)
        }
    }

    private fun returnBuffers() {
        for (desc in buffers) {
            if (desc.state != EvsBufferDesc.State.RECYCLE) continue
            returnBuffer(desc, true)
        }
    }

    private fun returnBuffer(value: EvsBufferDesc?, done: Boolean) {
        if (null == value || value.id < 0) return
        if (DEBUG) Log.d(TAG, "returnFrameBuffer: " + value.id)
        // 保留 mLastFrame 的 buffer 不关闭，供后续帧复用防止黑屏
        val isLastFrame = mLastFrame != null && value.id == mLastFrame!!.id
        if (!isLastFrame) {
            try {
                val buffer = value.hardwareBuffer
                if (null != buffer && !buffer.isClosed) {
                    buffer.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "close HardwareBuffer", e)
            }
        }
        try {
            if (done) evsHalWrapper.doneWithFrame(value.id)
        } catch (e: Exception) {
            Log.e(TAG, "returnFrameBuffer: " + value.id, e)
        } finally {
            value.recovery()
        }
    }

    companion object {
        private const val DEBUG = false
        private const val MAX_RECEIVE_FRAME = 6
        private const val RETRY_INTERVAL_MS = 1000L
        private const val RETRY_WAIT_MS = 600L
    }
}
