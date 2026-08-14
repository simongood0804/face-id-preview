package com.skyworth.faceid.shmtest

import android.app.Service
import android.content.Intent
import android.hardware.HardwareBuffer
import android.os.IBinder
import android.os.SharedMemory
import android.util.Log
import com.skyworth.faceid.algorithm.FaceIDAlgorithmImpl
import com.skyworth.faceid.algorithm.FrameProcessor
import com.skyworth.faceid.algorithm.IFaceIDAlgorithm
import com.skyworth.faceid.camera.CameraManager
import com.skyworth.faceid.camera.FaceIDCameraController
import com.skyworth.faceid.bus.ShmQueue
import com.skyworth.faceid.signal.DistractionStateMachine
import com.skyworth.faceid.signal.VehicleSignalSource
import java.util.concurrent.Executors

/**
 * 阶段 E：`:algo` 进程的算法引擎服务（自包含）。
 *
 * 在独立进程（`android:process=":algo"`）中完成 DMS 的全部算法处理：
 * 1. [FaceIDCameraController] + [CameraManager] **独立取帧**（帧数据源与主进程一致）；
 * 2. [FrameProcessor] + [FaceIDAlgorithmImpl] 推理；
 * 3. [DistractionStateMachine] + [VehicleSignalSource]（VHAL 车速）整合出
 *    [AlgorithmResult]；
 * 4. 把 [AlgorithmResult] 序列化后发布到 [ShmQueue]（跨进程共享内存），
 *    主进程订阅消费并绘制。
 *
 * 不依赖主进程，可独立取帧/推理/采车速，崩溃不影响主进程（故障隔离）。
 */
class AlgoEngineService : Service() {

    private val TAG = "AlgoEngine"

    // 相机（独立取帧）
    private lateinit var mCamera: FaceIDCameraController
    private lateinit var mCameraManager: CameraManager

    // 算法
    private lateinit var mAlgorithm: FaceIDAlgorithmImpl
    private lateinit var mFrameProcessor: FrameProcessor
    private val mExecutor = Executors.newSingleThreadExecutor()

    // 信号
    private val mStateMachine = DistractionStateMachine()
    private var mVehicleSignal: VehicleSignalSource? = null

    // 跨进程发布
    private var mOutQueue: ShmQueue? = null
    private var mShm: SharedMemory? = null

    private var running = false

    private val bridge = object : AlgoEngineBridge.Stub() {
        override fun getSharedMemory(): SharedMemory =
            mShm ?: throw IllegalStateException("engine not initialized")

        override fun getState(): String =
            "running=$running pid=${android.os.Process.myPid()}"

        override fun start(): Boolean {
            startEngine()
            return running
        }

        override fun stop() {
            stopEngine()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: pid=${android.os.Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 自动启动引擎
        startEngine()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = bridge

    /**
     * 启动引擎：初始化算法 + 输出队列 + 相机取帧。
     */
    private fun startEngine() {
        if (running) return
        try {
            // 1. 算法
            mAlgorithm = FaceIDAlgorithmImpl()
            mAlgorithm.initialize(this, HashMap())
            // 2. 输出队列（跨进程）
            val q = ShmQueue.create("algo_result", capacity = 16, maxReaders = 4)
            mOutQueue = q
            mShm = q.ownedShm
            // 3. 车速信号
            mVehicleSignal = VehicleSignalSource(this).also { it.connect() }
            // 4. 相机独立取帧
            mCamera = FaceIDCameraController().also { cam ->
                cam.onFrameData = { hw, w, h -> onCameraFrame(hw, w, h) }
            }
            mCameraManager = CameraManager(mCamera)
            mCameraManager.openCamera()
            // 5. 帧处理器
            mFrameProcessor = FrameProcessor(mAlgorithm, mExecutor) { result ->
                onAlgorithmResult(result)
            }
            running = true
            Log.i(TAG, "engine started")
        } catch (e: Exception) {
            Log.e(TAG, "engine start failed", e)
        }
    }

    /**
     * 相机帧到达：读出 UYVY 字节并送入算法处理（裁剪/推理）。
     * 注意：帧读取在 GL/回调线程，送入 FrameProcessor 后由算法线程处理。
     */
    private fun onCameraFrame(hw: HardwareBuffer, w: Int, h: Int) {
        if (!running) return
        try {
            val bytes = readUyvy(hw, w, h) ?: return
            mFrameProcessor.submitFrame(bytes, w, h)
        } catch (e: Exception) {
            Log.e(TAG, "onCameraFrame error", e)
        }
    }

    /** 读取 HardwareBuffer 中的 UYVY 字节（复用 JNI hardware_buffer_reader，与主进程一致）。 */
    private fun readUyvy(hw: HardwareBuffer, w: Int, h: Int): ByteArray? {
        return nativeReadHardwareBuffer(hw, w, h)
    }

    private external fun nativeReadHardwareBuffer(
        hwBuffer: HardwareBuffer, width: Int, height: Int
    ): ByteArray?

    /**
     * 算法推理完成：整合车速 + 分心判定，发布 [AlgorithmResult] 到共享队列。
     * 在算法线程（FrameProcessor 回调）调用，[DistractionStateMachine] 保持单线程。
     */
    private fun onAlgorithmResult(result: IFaceIDAlgorithm.FaceIDResult) {
        val q = mOutQueue ?: return
        val speed = mVehicleSignal?.speedKmh ?: -1f
        // 分心判定（算法线程内单线程调用状态机）
        val hasFace = result.faceId.isNotEmpty()
        val distracted = if (hasFace) {
            mStateMachine.update(hasFace, result.gazeDistracted, speed)
        } else {
            mStateMachine.reset()
            false
        }
        val algoResult = AlgorithmResult(
            frameW = mCamera.frameWidth,
            frameH = mCamera.frameHeight,
            hasFace = hasFace,
            faceLeft = result.faceRect?.left ?: 0f,
            faceTop = result.faceRect?.top ?: 0f,
            faceRight = result.faceRect?.right ?: 0f,
            faceBottom = result.faceRect?.bottom ?: 0f,
            faceConfidence = result.confidence,
            distracted = distracted,
            distractionBand = mStateMachine.currentSpeedBand(),
            distractionThresholdMs = mStateMachine.currentTriggerMs(),
            speedKmh = speed
        )
        // 序列化后发布到共享内存队列
        try {
            q.publish(ALGO_RESULT_TOPIC, algoResult.encode())
        } catch (e: Exception) {
            Log.e(TAG, "publish result error", e)
        }
    }

    private fun stopEngine() {
        if (!running) return
        running = false
        try { mCameraManager.stopCamera() } catch (_: Exception) { }
        mAlgorithm.release()
        mVehicleSignal?.disconnect()
        mExecutor.shutdown()
        mOutQueue?.close()
        try { mShm?.close() } catch (_: Exception) { }
        Log.i(TAG, "engine stopped")
    }

    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }

    companion object {
        private const val ALGO_RESULT_TOPIC = 200

        init {
            try {
                System.loadLibrary("hardware_buffer_reader")
            } catch (_: UnsatisfiedLinkError) { }
        }
    }
}
