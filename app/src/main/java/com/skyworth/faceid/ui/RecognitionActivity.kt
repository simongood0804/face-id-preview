package com.skyworth.faceid.ui

import android.content.Intent
import android.hardware.HardwareBuffer
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.skyworth.faceid.algorithm.FaceIDAlgorithmImpl
import com.skyworth.faceid.algorithm.IFaceIDAlgorithm
import com.skyworth.faceid.core.AlgoSession
import com.skyworth.faceid.core.FaceOverlayBridge
import com.skyworth.faceid.core.FrameSession
import com.skyworth.faceid.core.NativeFrameReader
import com.skyworth.faceid.R

/**
 * 人脸识别模块（FACEP-011 阶段二）。
 *
 * 复用公共基础设施：`AlgoSession`（算法单例+引用计数）、`FrameSession`（相机单例+引用计数）、
 * `FaceOverlayBridge`（识别区渲染）。
 *
 * - 识别由算法内部 `FaceEnrollmentManager` 驱动（自动录入/识别/替换）；
 * - 本模块只做相机预览 + 识别结果展示（人脸框/姓名/置信度）；
 * - 不依赖信号层（纯本地识别，FACEP-011 §3.2）。
 *
 * 生命周期：onStart 装配并 acquire，onStop release（引用计数归 0 才释放）。
 */
class RecognitionActivity : AppCompatActivity() {

    private val TAG = "RecognitionActivity"

    private lateinit var mSurface: GLSurfaceView
    private lateinit var mFaceIdText: TextView
    private lateinit var mEnrolledCountText: TextView
    private lateinit var mEnrollStatusText: TextView
    private lateinit var mStartEnrollBtn: Button
    private lateinit var mManageFacesBtn: Button
    private lateinit var mContinuousDumpBtn: Button
    private lateinit var mMoveDumpBtn: Button
    private lateinit var mDumpTimerText: TextView

    /** 连续 dump 采样总帧数（150 帧，三等分各 50 张）。 */
    private val CONTINUOUS_DUMP_TOTAL_FRAMES = 150

    /** 连续 dump 倒计时更新 Handler。 */
    private val mDumpCountDownHandler = Handler(Looper.getMainLooper())

    /** 连续 dump 剩余张数。 */
    @Volatile private var mDumpRemainingFrames = CONTINUOUS_DUMP_TOTAL_FRAMES

    private var mAlgoSession: AlgoSession? = null
    private var mFrameSession: FrameSession? = null
    private var mBridge: FaceOverlayBridge? = null

    private var mAlgorithmEnabled = true

    /** 当前识别显示（仅在人脸变化时更新，避免每帧刷 UI）。 */
    private var mLastFaceId: String = ""

    /** 当前已导入人脸数量（仅数量变化时更新，避免每帧刷 UI）。 */
    private var mLastEnrolledCount = -1

    /** 渲染器是否已设置（GLSurfaceView.setRenderer 仅能调用一次）。 */
    private var mRendererSet = false

    /** FACEP-012：是否处于手动录入模式。 */
    private var mIsEnrolling = false

    /** FACEP-012：命名弹框是否已弹出（避免每帧重复弹）。 */
    private var mEnrollDialogShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recognition)

        mSurface = findViewById(R.id.preview_surface)
        mFaceIdText = findViewById(R.id.tv_face_id)
        mEnrolledCountText = findViewById(R.id.tv_enrolled_count)
        mEnrollStatusText = findViewById(R.id.tv_enroll_status)
        mStartEnrollBtn = findViewById(R.id.btn_start_enroll)
        mManageFacesBtn = findViewById(R.id.btn_manage_faces)
        findViewById<Button>(R.id.btn_back_home).setOnClickListener {
            finish()
        }

        mStartEnrollBtn.setOnClickListener { onStartEnrollClick() }
        // 人脸管理改为独立页面（FaceManageActivity）
        mManageFacesBtn.setOnClickListener {
            startActivity(Intent(this, FaceManageActivity::class.java))
        }

        // FACEP-011：恢复 dump 调试功能（dump原图/导出/清除）
        findViewById<Button>(R.id.btn_dump).setOnClickListener { onDumpClick() }
        mMoveDumpBtn = findViewById(R.id.btn_move_dump)
        mMoveDumpBtn.setOnClickListener { onMoveDumpClick() }
        findViewById<Button>(R.id.btn_clear_dump).setOnClickListener { onClearDumpClick() }
        // 连续 dump（最快 5fps，保存驱动采样；点击后置灰显示"连续dump中"，完成恢复）
        mContinuousDumpBtn = findViewById(R.id.btn_dump_continuous)
        mContinuousDumpBtn.setOnClickListener { onContinuousDumpClick() }
        mDumpTimerText = findViewById(R.id.tv_dump_timer)

        Log.i(TAG, "onCreate: done")
    }

    override fun onResume() {
        super.onResume()
        mSurface.onResume()
        // 从人脸管理页返回后刷新已导入数量（可能发生删除）
        updateEnrolledCount()
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
        // 暂停 GLSurfaceView，停止 GLThread（避免渲染已释放的相机/算法资源导致 SIGSEGV）
        mSurface.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        // 连续 dump 进行中时停止，避免后台线程持续采集/回调
        dumpAlgo()?.takeIf { it.isContinuousDumpActive() }?.stopContinuousDump()
        stopPreview()
        super.onDestroy()
    }

    /** 装配并启动相机预览 + 算法会话（FACEP-011 §4.6-A 单例+引用计数）。 */
    private fun startPreview() {
        try {
            // 1. 算法会话（单例，引用计数 +1；只跑识别相关模型，FACEP-011 功能划分）
            val algo = AlgoSession.get().acquire(applicationContext, RECOGNITION_FLAG)
            mAlgoSession = algo

            // 2. 相机帧会话（单例）
            val frame = FrameSession.get(::readFrame)

            // 3. GL 渲染预览（renderer 每次新建，按实际帧尺寸等比适配；仅配置一次）
            //    顺序要求：configureSurface 须在 acquire（attach FrameDistributor）之前，
            //    以便 attach 链式保留 onFrameSizeChanged（更新 frameWidth + 触发适配）。
            if (!mRendererSet) {
                frame.configureSurface(mSurface, findViewById(R.id.face_overlay))
                mRendererSet = true
            }

            // 4. acquire：引用计数 +1，注入 JNI 帧读取回调（attach FrameDistributor）
            frame.acquire(algo.frameProcessor()) { mAlgorithmEnabled }
            mFrameSession = frame

            // 4. 渲染桥接（识别区）
            mBridge = FaceOverlayBridge(findViewById(R.id.face_overlay))

            // 5. 注入算法结果回调 → 识别展示
            algo.setResultCallback { result ->
                runOnUiThread { onAlgorithmResult(result) }
            }

            // 进入页面立即刷新一次已导入数量（不依赖首帧算法结果）
            updateEnrolledCount()

            // 6. 打开相机
            if (!frame.open()) {
                Log.e(TAG, "startPreview: open camera failed")
                mFaceIdText.text = "相机打开失败"
            }
        } catch (e: Exception) {
            Log.e(TAG, "startPreview: failed", e)
        }
    }

    /** 停止预览并释放引用计数。 */
    private fun stopPreview() {
        // 若处于录入模式，先退出，避免状态残留到下次进入
        if (mIsEnrolling) {
            mAlgoSession?.algorithm()?.stopManualEnrollment()
            mIsEnrolling = false
            mEnrollDialogShown = false
        }
        try {
            mBridge?.clearFaces()
            mFrameSession?.release()
            mAlgoSession?.setResultCallback(null)
            mAlgoSession?.release()
        } catch (e: Exception) {
            Log.e(TAG, "stopPreview: error", e)
        } finally {
            mBridge = null
            mFrameSession = null
            mAlgoSession = null
        }
    }

    /** 算法结果回调（识别区桥接 + 识别文本展示 + 录入采集）。 */
    private fun onAlgorithmResult(result: IFaceIDAlgorithm.FaceIDResult) {
        // 算法结果已修正回原图空间（1600×1300），用原图尺寸缩放显示（FACEP-011 裁剪映射）
        val distributor = mFrameSession?.frameDistributor()
        val imgW = distributor?.frameWidth ?: ORIGINAL_WIDTH
        val imgH = distributor?.frameHeight ?: ORIGINAL_HEIGHT
        mBridge?.setFaces(result, false, FaceOverlayBridge.Module.RECOGNITION, imgW, imgH)

        // FACEP-012：录入模式下，采集成功 → 弹命名框
        if (mIsEnrolling) {
            if (result.enrollmentReady && !mEnrollDialogShown) {
                mEnrollDialogShown = true
                showNameDialog()
            }
            return
        }

        val faceId = result.faceId
        if (faceId != mLastFaceId) {
            mLastFaceId = faceId
            mFaceIdText.text = when {
                faceId.isEmpty() -> "无人脸"
                faceId == "detected" -> "检测到人脸（未识别）"
                faceId == "spoof" -> "疑似照片/翻拍"
                faceId == "unregistered" -> getString(R.string.unregistered_face)
                else -> "识别: $faceId"
            }
        }

        // 刷新已导入人脸数量（仅数量变化时更新）
        updateEnrolledCount()
    }

    // ============================================================
    // FACEP-011：dump 调试功能（恢复，仅识别页提供入口）
    // ============================================================

    /** 获取算法具体类以调用 dump 方法（AlgoSession.algorithm() 返回接口类型）。 */
    private fun dumpAlgo(): FaceIDAlgorithmImpl? = mAlgoSession?.algorithm() as? FaceIDAlgorithmImpl

    /**
     * 手动触发 dump：后台保存最近一帧原始图像为 PNG，完成后 Toast 提示。
     * 若系统属性 algorithm_face_dump_enable 未启用，弹框提示。
     */
    private fun onDumpClick() {
        val algo = dumpAlgo()
        if (algo == null) {
            Toast.makeText(this, "dump: algorithm not initialized", Toast.LENGTH_SHORT).show()
            return
        }
        if (!algo.isDumpAvailable()) {
            Toast.makeText(
                this,
                "dump: system property disabled, set algorithm_face_dump_enable first",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        algo.triggerManualDump { ok ->
            val msg = if (ok) "dump: saved" else "dump: failed"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        Log.i(TAG, "onDumpClick: manual dump triggered")
    }

    /**
     * 连续 dump：点击后先弹二次确认弹框选择输出格式（PNG / JPEG / 视频），
     * 再按所选模式启动连续 dump（默认 150 帧，采满自然结束）。
     */
    private fun onContinuousDumpClick() {
        val algo = dumpAlgo()
        if (algo == null) {
            Toast.makeText(this, "dump: algorithm not initialized", Toast.LENGTH_SHORT).show()
            return
        }
        if (algo.isContinuousDumpActive()) return
        if (!algo.isDumpAvailable()) {
            Toast.makeText(
                this,
                "dump: system property disabled, set algorithm_face_dump_enable first",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        // 二次确认弹框：选择连续 dump 输出格式
        val options = arrayOf(
            getString(R.string.dump_mode_png),
            getString(R.string.dump_mode_jpeg),
            getString(R.string.dump_mode_video)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.dump_mode_title)
            .setItems(options) { _, which ->
                val mode = when (which) {
                    0 -> FaceIDAlgorithmImpl.ContinuousDumpMode.PNG
                    1 -> FaceIDAlgorithmImpl.ContinuousDumpMode.JPEG
                    else -> FaceIDAlgorithmImpl.ContinuousDumpMode.VIDEO
                }
                startContinuousDumpWith(mode)
            }
            .setNegativeButton(R.string.enroll_cancel, null)
            .show()
    }

    /** 按所选模式启动连续 dump。 */
    private fun startContinuousDumpWith(mode: FaceIDAlgorithmImpl.ContinuousDumpMode) {
        val algo = dumpAlgo() ?: return
        // 置灰按钮 + 显示进行中
        mContinuousDumpBtn.isEnabled = false
        mContinuousDumpBtn.setText(R.string.btn_dump_continuous_active)
        Log.i(TAG, "startContinuousDumpWith: mode=$mode total=${CONTINUOUS_DUMP_TOTAL_FRAMES} frames")

        val started = algo.startContinuousDump(
            totalFrames = CONTINUOUS_DUMP_TOTAL_FRAMES, intervalMs = 200, mode = mode
        ) {
            // 完成后恢复按钮（主线程）；展示实际保存帧数（与倒计时口径一致）
            val saved = algo.getContinuousSavedCount()
            mContinuousDumpBtn.isEnabled = true
            mContinuousDumpBtn.setText(R.string.btn_dump_continuous)
            stopDumpCountdown()   // 停止倒计时 + 隐藏提示
            val label = when (mode) {
                FaceIDAlgorithmImpl.ContinuousDumpMode.VIDEO -> "视频录制完成"
                else -> "连续dump完成: 保存 $saved 帧"
            }
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
            Log.i(TAG, "startContinuousDumpWith: finished mode=$mode saved=$saved")
        }
        if (!started) {
            // 启动失败，恢复按钮
            mContinuousDumpBtn.isEnabled = true
            mContinuousDumpBtn.setText(R.string.btn_dump_continuous)
        } else {
            startDumpCountdown()   // 启动左上角倒计时 + 动作提示
        }
    }

    /**
     * 启动连续 dump 倒计时：每秒更新左上角提示（剩余张数 + 当前动作）。
     * 分三段（各 50 张）：前"请坐好"，中间"请做出打电话动作"，最后"请做出抽烟动作"。
     */
    private fun startDumpCountdown() {
        mDumpRemainingFrames = CONTINUOUS_DUMP_TOTAL_FRAMES
        mDumpTimerText.visibility = TextView.VISIBLE
        mDumpCountDownHandler.removeCallbacksAndMessages(null)
        mDumpCountDownHandler.post(mDumpCountdownRunnable)
    }

    /** 停止倒计时并隐藏提示。 */
    private fun stopDumpCountdown() {
        mDumpCountDownHandler.removeCallbacksAndMessages(null)
        mDumpTimerText.visibility = TextView.GONE
    }

    private val mDumpCountdownRunnable = object : Runnable {
        override fun run() {
            if (mDumpRemainingFrames <= 0) return
            // 剩余张数 = 总帧数 - 实际保存成功帧数（与磁盘文件数一致，避免按采样计数跳动）
            val saved = dumpAlgo()?.getContinuousSavedCount() ?: 0
            val remaining = (CONTINUOUS_DUMP_TOTAL_FRAMES - saved).coerceAtLeast(0)
            // 按剩余张数三等分（总 150，各 50）：>100 坐好，50~100 打电话，≤50 抽烟
            val action = when {
                remaining > 100 -> getString(R.string.dump_action_sit)
                remaining > 50 -> getString(R.string.dump_action_call)
                else -> getString(R.string.dump_action_smoke)
            }
            mDumpTimerText.text = getString(R.string.dump_timer_format, remaining, action)
            mDumpRemainingFrames = remaining
            mDumpCountDownHandler.postDelayed(this, 1000L)
        }
    }

    /** 清除 debugDump 文件夹内容，并删除 /sdcard/debugDmsDump。 */
    private fun onClearDumpClick() {
        val algo = dumpAlgo()
        if (algo == null) {
            Toast.makeText(this, "clear dump: algorithm not initialized", Toast.LENGTH_SHORT).show()
            return
        }
        algo.clearDumpDirs { ok ->
            val msg = if (ok) "clear dump: done" else "clear dump: failed"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        Log.i(TAG, "onClearDumpClick: clear triggered")
    }

    /** 将 debugDump 中的 png 移动到 /sdcard/debugDmsDump。 */
    private fun onMoveDumpClick() {
        val algo = dumpAlgo()
        if (algo == null) {
            Toast.makeText(this, "move dump: algorithm not initialized", Toast.LENGTH_SHORT).show()
            return
        }
        // 导出中：置灰按钮 + 显示"导出中"，防重复触发，完成后再恢复
        if (!mMoveDumpBtn.isEnabled) return   // 防重复
        mMoveDumpBtn.isEnabled = false
        mMoveDumpBtn.setText(R.string.btn_move_dump_active)
        Log.i(TAG, "onMoveDumpClick: move triggered")
        algo.moveDumpPngToSdcard { ok ->
            // 完成后恢复按钮（主线程回调）
            mMoveDumpBtn.isEnabled = true
            mMoveDumpBtn.setText(R.string.btn_move_dump)
            val msg = if (ok) "move dump: done" else "move dump: failed"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // FACEP-012：手动录入 / 人脸管理
    // ============================================================

    /** 「开始录入 / 取消录入」按钮。 */
    private fun onStartEnrollClick() {
        val algo = mAlgoSession?.algorithm() ?: return
        if (!mIsEnrolling) {
            algo.startManualEnrollment()
            mIsEnrolling = true
            mEnrollDialogShown = false
            mEnrollStatusText.visibility = View.VISIBLE
            mStartEnrollBtn.text = getString(R.string.enroll_cancel)
            mFaceIdText.text = getString(R.string.enroll_prompt)
            Toast.makeText(this, R.string.enroll_prompt, Toast.LENGTH_SHORT).show()
            Log.i(TAG, "manual enrollment started")
        } else {
            algo.stopManualEnrollment()
            exitEnrollment()
        }
    }

    /** 退出录入模式（取消或保存完成），恢复 UI。 */
    private fun exitEnrollment() {
        mIsEnrolling = false
        mEnrollDialogShown = false
        mEnrollStatusText.visibility = View.GONE
        mStartEnrollBtn.text = getString(R.string.btn_start_enroll)
        mFaceIdText.text = getString(R.string.face_id_label)
        mLastFaceId = ""
        Log.i(TAG, "manual enrollment ended")
    }

    /** 弹出命名对话框（录入采集成功）。 */
    private fun showNameDialog() {
        val emb = mAlgoSession?.algorithm()?.pendingEmbedding() ?: run {
            // 无待命名特征：重置标志，等待下一帧
            mEnrollDialogShown = false
            return
        }
        val input = EditText(this)
        input.hint = getString(R.string.enroll_name_hint)
        input.textSize = 14f
        // 限制输入框宽度，避免车机大屏下弹框过宽
        val editWidth = (resources.displayMetrics.density * 260).toInt()
        input.width = editWidth
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.enroll_prompt)
            .setView(input)
            .setPositiveButton(R.string.enroll_confirm, null)
            .setNegativeButton(R.string.enroll_cancel, null)
            .create()
        // 确认时校验并保存；失败则保持弹框，不退出录入
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.enroll_name_empty, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val algo = mAlgoSession?.algorithm() ?: return@setOnClickListener
                if (!algo.addEnrolledFace(name, emb)) {
                    Toast.makeText(this, R.string.enroll_name_duplicate, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                Toast.makeText(this, R.string.enroll_captured, Toast.LENGTH_SHORT).show()
                algo.stopManualEnrollment()
                exitEnrollment()
                updateEnrolledCount()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
                mAlgoSession?.algorithm()?.stopManualEnrollment()
                exitEnrollment()
            }
        }
        dialog.setOnDismissListener {
            // 若未通过确认/取消正常退出（如点击外部），也结束录入模式
            mEnrollDialogShown = false
            if (mIsEnrolling) {
                mAlgoSession?.algorithm()?.stopManualEnrollment()
                exitEnrollment()
            }
        }
        dialog.show()
        shrinkDialog(dialog)
    }

    /** 缩小 AlertDialog 窗口宽度（车机大屏下默认弹框过宽）。 */
    private fun shrinkDialog(dialog: AlertDialog) {
        dialog.window?.let { w ->
            val width = (resources.displayMetrics.density * 360).toInt()
            w.setLayout(width, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    /** 更新已导入人脸数量展示。 */
    private fun updateEnrolledCount() {
        val count = mAlgoSession?.algorithm()?.getEnrolledCount() ?: 0
        if (count != mLastEnrolledCount) {
            mLastEnrolledCount = count
            mEnrolledCountText.text = getString(R.string.enrolled_count_format, count)
        }
    }

    /** JNI 帧读取（复用 NativeFrameReader 公共能力）。 */
    private fun readFrame(hwBuffer: HardwareBuffer, width: Int, height: Int): ByteArray? {
        return NativeFrameReader.readHardwareBuffer(hwBuffer, width, height)
    }

    companion object {
        /** 原始帧尺寸（DMS 摄像头），算法结果坐标基于此缩放显示。 */
        private const val ORIGINAL_WIDTH = 1600
        private const val ORIGINAL_HEIGHT = 1300

        /** 人脸识别模块的算法流程（只跑识别相关，不跑疲劳/分心的头姿/视线）。 */
        private val RECOGNITION_FLAG = atlas.face.sdk.FaceFlag.DETECTION or
            atlas.face.sdk.FaceFlag.RECOGNITION or
            atlas.face.sdk.FaceFlag.LIVENESS or
            atlas.face.sdk.FaceFlag.LANDMARK
    }
}
