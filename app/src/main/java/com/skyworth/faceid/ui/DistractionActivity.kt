package com.skyworth.faceid.ui

import android.hardware.HardwareBuffer
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.skyworth.faceid.R
import com.skyworth.faceid.algorithm.IFaceIDAlgorithm
import com.skyworth.faceid.bus.BusHub
import com.skyworth.faceid.bus.BusPublisher
import com.skyworth.faceid.core.AlgoSession
import com.skyworth.faceid.core.FaceOverlayBridge
import com.skyworth.faceid.core.FrameSession
import com.skyworth.faceid.core.NativeFrameReader
import com.skyworth.faceid.signal.DistractionSource
import com.skyworth.faceid.signal.DistractionSourceStore
import com.skyworth.faceid.signal.SignalDispatcher
import com.skyworth.faceid.signal.VehicleSignalSource
import com.skyworth.faceid.zone.GazeFallpointDetector
import com.skyworth.faceid.zone.RegionConfigLoader

/**
 * 分心监测模块（FACEP-011 阶段四）。
 *
 * 复用公共基础设施：`AlgoSession`、`FrameSession`、`FaceOverlayBridge`（分心区）、
 * `SignalDispatcher`（车速分档 + 防抖）+ `VehicleSignalSource`（车速）。
 *
 * - 分心判定：算法结果经 `SignalDispatcher.processAlgorithmResult`（防抖 + 车速分档）；
 * - 车速显示：`VehicleSignalSource` 订阅车速，展示当前档位；
 * - 渲染只消费分心区字段（gazeDistracted/zoneId/头姿/视线，数据隔离 §4.6-B）。
 *
 * 生命周期：onStart 装配并 acquire，onStop release。
 */
class DistractionActivity : AppCompatActivity() {

    private val TAG = "DistractionActivity"

    private lateinit var mSurface: GLSurfaceView
    private lateinit var mDistractionText: TextView
    private lateinit var mSpeedText: TextView
    private lateinit var mSourceText: TextView

    private var mAlgoSession: AlgoSession? = null
    private var mFrameSession: FrameSession? = null
    private var mBridge: FaceOverlayBridge? = null
    private var mDispatcher: SignalDispatcher? = null
    private var mVehicleSource: VehicleSignalSource? = null

    private var mAlgorithmEnabled = true

    /** 渲染器是否已设置（GLSurfaceView.setRenderer 仅能调用一次）。 */
    private var mRendererSet = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_distraction)

        mSurface = findViewById(R.id.preview_surface)
        mDistractionText = findViewById(R.id.tv_distraction)
        mSpeedText = findViewById(R.id.tv_speed)
        mSourceText = findViewById(R.id.tv_distraction_source)
        findViewById<Button>(R.id.btn_back_home).setOnClickListener { finish() }
        // FACEP-016：点击切换分心数据源（SDK/SELF），即时生效 + 持久化
        mSourceText.setOnClickListener { toggleDistractionSource() }

        Log.i(TAG, "onCreate: done")
    }

    override fun onStart() {
        super.onStart()
        startPreview()
    }

    override fun onStop() {
        stopPreview()
        super.onStop()
    }

    override fun onPause() {
        // 暂停 GLSurfaceView，停止 GLThread（避免渲染已释放资源 SIGSEGV）
        mSurface.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        mSurface.onResume()
    }

    override fun onDestroy() {
        stopPreview()
        super.onDestroy()
    }

    /** 装配并启动预览 + 分心判定（FACEP-011 §4.6-A 单例+引用计数）。 */
    private fun startPreview() {
        try {
            val algo = AlgoSession.get().acquire(applicationContext, DISTRACTION_FLAG)
            mAlgoSession = algo

            val frame = FrameSession.get(::readFrame)

            // 顺序要求：configureSurface 须在 acquire（attach FrameDistributor）之前，
            // 以便 attach 链式保留 onFrameSizeChanged（更新 frameWidth + 触发按实际帧尺寸适配）。
            if (!mRendererSet) {
                frame.configureSurface(mSurface, findViewById(R.id.face_overlay))
                mRendererSet = true
            }

            frame.acquire(algo.frameProcessor()) { mAlgorithmEnabled }
            mFrameSession = frame
            mBridge = FaceOverlayBridge(findViewById(R.id.face_overlay))

            // 分心信号链路（直接路径：算法结果 → processAlgorithmResult）
            val hub = BusHub()
            val publisher = BusPublisher(hub)
            // FACEP-016：自研视线落点判定器（SELF 源）+ 数据源开关（默认 SDK，持久化读取）
            // 区域配置从 assets 的 zone_regions.json 解析（4 点四边形，后续可改）。
            val regions = RegionConfigLoader.loadFromAssets(this)
            val fallpointDetector = GazeFallpointDetector(regions)
            mDispatcher = SignalDispatcher(
                hub = hub,
                publisher = publisher,
                fallpointDetector = fallpointDetector,
                initialSource = DistractionSourceStore.load(this)
            )
            updateDistractionSource()   // 初始化显示当前数据源

            // 车速源
            mVehicleSource = VehicleSignalSource(this).also { vs ->
                vs.onSpeedChanged = { speed ->
                    mDispatcher?.processVehicleSpeed(speed)
                    runOnUiThread { updateSpeedText() }
                }
                vs.connect()
            }

            algo.setResultCallback { result ->
                mDispatcher?.processAlgorithmResult(result)
                runOnUiThread { onAlgorithmResult(result) }
            }

            if (!frame.open()) {
                Log.e(TAG, "startPreview: open camera failed")
                mDistractionText.text = "相机打开失败"
            }
        } catch (e: Exception) {
            Log.e(TAG, "startPreview: failed", e)
        }
    }

    /** 停止预览并释放。 */
    private fun stopPreview() {
        try {
            mBridge?.clearFaces()
            mVehicleSource?.disconnect()
            mDispatcher?.close()
            mFrameSession?.release()
            mAlgoSession?.setResultCallback(null)
            mAlgoSession?.release()
        } catch (e: Exception) {
            Log.e(TAG, "stopPreview: error", e)
        } finally {
            mVehicleSource = null
            mDispatcher = null
            mBridge = null
            mFrameSession = null
            mAlgoSession = null
        }
    }

    /** 算法结果回调：分心区桥接 + 分心状态展示。 */
    private fun onAlgorithmResult(result: IFaceIDAlgorithm.FaceIDResult) {
        val distractActive = mDispatcher?.lastDistraction?.distracted ?: false
        // 算法结果已修正回原图空间（1600×1300），用原图尺寸缩放显示（FACEP-011 裁剪映射）
        val distributor = mFrameSession?.frameDistributor()
        val imgW = distributor?.frameWidth ?: ORIGINAL_WIDTH
        val imgH = distributor?.frameHeight ?: ORIGINAL_HEIGHT
        mBridge?.setFaces(result, distractActive, FaceOverlayBridge.Module.DISTRACTION,
            imgW, imgH)

        if (result.faceRect == null) {
            mDistractionText.setTextColor(0xFF00FF00.toInt())
            mDistractionText.setText(R.string.distraction_status_ok)
            return
        }

        if (distractActive) {
            mDistractionText.setTextColor(0xFFFF0000.toInt())
            mDistractionText.setText(R.string.distraction_status_active)
        } else {
            mDistractionText.setTextColor(0xFF00FF00.toInt())
            mDistractionText.setText(R.string.distraction_status_ok)
        }
    }

    /**
     * FACEP-016：点击切换分心数据源（SDK ↔ SELF），即时生效 + 持久化（重启保留）。
     */
    private fun toggleDistractionSource() {
        val dispatcher = mDispatcher ?: return
        val next = if (dispatcher.distractionSource == DistractionSource.SDK) {
            DistractionSource.SELF
        } else {
            DistractionSource.SDK
        }
        dispatcher.setDistractionSource(next)
        DistractionSourceStore.save(this, next)   // 持久化，重启保留
        updateDistractionSource()
        Log.i(TAG, "distraction source -> $next")
    }

    /** 更新数据源显示文本。 */
    private fun updateDistractionSource() {
        val source = mDispatcher?.distractionSource ?: DistractionSource.SDK
        mSourceText.text = if (source == DistractionSource.SDK) {
            "SRC:SDK(算法) 点击切换"
        } else {
            "SRC:SELF(自研) 点击切换"
        }
    }

    /** 更新车速显示。 */
    private fun updateSpeedText() {
        val speed = mDispatcher?.currentSpeedKmh ?: -1f
        mSpeedText.text = if (speed < 0f) {
            getString(R.string.distraction_speed_na)
        } else {
            "车速：%.1f km/h".format(speed)
        }
    }

    /** JNI 帧读取（复用 NativeFrameReader）。 */
    private fun readFrame(hwBuffer: HardwareBuffer, width: Int, height: Int): ByteArray? {
        return NativeFrameReader.readHardwareBuffer(hwBuffer, width, height)
    }

    companion object {
        /** 原始帧尺寸（DMS 摄像头），算法结果坐标基于此缩放显示。 */
        private const val ORIGINAL_WIDTH = 1600
        private const val ORIGINAL_HEIGHT = 1300

        /** 分心监测模块算法流程（检测 + 头姿坐标系 + 视线注意点 + 关键五点）。 */
        private val DISTRACTION_FLAG = atlas.face.sdk.FaceFlag.DETECTION or
            atlas.face.sdk.FaceFlag.HEADPOSE or
            atlas.face.sdk.FaceFlag.GAZE or
            atlas.face.sdk.FaceFlag.LANDMARK
    }
}
