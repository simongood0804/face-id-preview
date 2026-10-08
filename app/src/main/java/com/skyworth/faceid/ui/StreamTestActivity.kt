package com.skyworth.faceid.ui

import android.hardware.HardwareBuffer
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.android.car.evs.CameraIds
import com.skyworth.faceid.R
import com.skyworth.faceid.camera.CameraSwitchClient
import com.skyworth.faceid.core.CameraPreference
import com.skyworth.faceid.core.FrameSession
import com.skyworth.faceid.core.NativeFrameReader
import com.skyworth.faceid.core.PushTarget
import com.skyworth.faceid.core.RelayDiscovery
import com.skyworth.faceid.media.MediaRecordSession
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 推流测试校验页（对接 media_record native 库）。
 *
 * 能力：
 * - **预览**：复用 [FrameSession] 的 GL 预览（相机帧 → EVS GL 渲染器 → GLSurfaceView）；
 * - **接口调用**：读取 `assets` 下的管线 config（录制 / 推流两套），调用
 *   `mr_session_open/start/push_frame/push_frame_eof/close`；
 * - **CPU 路径**（「开始录制 / 开始推流」）：相机 [HardwareBuffer]（UYVY）在 native 侧
 *   转 RGBA 后交给引擎，在相机 HAL 回调线程同步完成（帧有效期内），上一帧未处理完则丢帧；
 * - **surface 路径**（「开始推流（surface）」）：`surface_mode = 1`，由 native 侧
 *   `mr_session_get_input_surface` 取到编码器输入 surface，自建 EGL/GLES 管线直绘：
 *   相机回调线程内完成 UYVY→RGBA 转换，内部 GL 线程上传纹理、按比例 letterbox 绘制，
 *   再 `mr_session_notify_frame`。适用于**只接受 surface 输入**的硬件编码器
 *   （CPU 路径在该类平台上表现为 `emitted = 0`）。
 *
 *   说明：早期实现走 EGLImage 零拷贝，但真机上会触发 vendor gralloc 崩溃
 *   （预览 GL 线程在 `libqdMetaData.so` 段错误），故改为当前方案——好处是不再对相机
 *   buffer 建 EGLImage、也不跨线程持有它。详见 `encoder_surface_renderer.h`。
 *
 * config 说明：
 * - `external_source_record.json`：外部帧源 → 编码 → 录制 → MP4 落盘；
 * - `external_source_push.json`：外部帧源 → 编码 → WebRTC 推流（+ MP4 落盘）；
 * - config 内 `${FILES_DIR}` 占位符在加载时替换为本应用 files 目录。
 *
 * 结束录制用 `pushEof()`（会 finalize MP4）；`stop()` 会中止导致无输出，仅用于放弃。
 */
class StreamTestActivity : AppCompatActivity() {

    private val TAG = "StreamTestActivity"

    private lateinit var mSurface: GLSurfaceView
    private lateinit var mStatus: TextView
    private lateinit var mState: TextView
    private lateinit var mRecordSwitch: SwitchCompat

    /** 正在用代码回写控件状态（避免 Switch 监听被自己的同步动作触发）。 */
    private var mSyncingUi = false

    private var mFrameSession: FrameSession? = null

    /** 当前推流/录制会话（null = 空闲）。相机回调线程也会读，故 @Volatile。 */
    @Volatile
    private var mSession: MediaRecordSession? = null

    /** 是否有会话正在收尾（pushEof→wait→close）。期间禁止启动新会话。 */
    @Volatile
    private var mStopping = false

    /** 推帧串行化：HAL 回调可能密集，上一帧未处理完则丢弃当前帧，避免阻塞回调线程。 */
    private val mPushing = AtomicBoolean(false)

    @Volatile
    private var mPushedFrames = 0L

    @Volatile
    private var mDroppedFrames = 0L

    /** 目标推流尺寸（来自 config 的 source 节点）；0 表示不缩放、按相机源尺寸推送。 */
    @Volatile
    private var mTargetWidth = 0

    @Volatile
    private var mTargetHeight = 0

    /** 推帧耗时统计（诊断缩放对帧率的影响）。 */
    @Volatile
    private var mCostSumMs = 0.0

    @Volatile
    private var mCostCount = 0

    /** 分段耗时累计（us）：lock(GPU->CPU 同步) / 转换+缩放 / push。 */
    @Volatile
    private var mLockSumUs = 0L

    @Volatile
    private var mConvSumUs = 0L

    @Volatile
    private var mPushSumUs = 0L

    /** 当前会话是否走 surface 路径（编码器输入 surface + 宿主 GL 直绘）。 */
    @Volatile
    private var mSurfaceMode = false

    /** 编码器输入 surface 尺寸（来自 mr_session_get_input_surface）。 */
    @Volatile
    private var mSurfaceWidth = 0

    @Volatile
    private var mSurfaceHeight = 0

    /** 当前会话是否包含推流 / 录制（由启动时选的 config 决定），用于状态行与按钮文案。 */
    @Volatile
    private var mSessionPushing = false

    @Volatile
    private var mSessionRecording = false

    /** 当前会话用的 assets 名（null = 无会话）；用于判断目标组合是否需要换 config 重启。 */
    @Volatile
    private var mSessionAsset: String? = null

    /** 用户期望的组合（两个开关）；会话收尾完成后自动切到这个目标。 */
    @Volatile
    private var mWantPush = false

    @Volatile
    private var mWantRecord = false

    /** 页面已销毁：不再自动拉起会话。 */
    @Volatile
    private var mDestroyed = false

    /** 当前会话的开始时刻（wall clock ms），用于算实时 fps。 */
    @Volatile
    private var mSessionStartMs = 0L

    // ---- 推流健康 watchdog ----
    // 背景：上游 StreamImpl::OnReconnectTick() 没有任何调用者 → ReconnectHandler::Tick()
    // 永不推进 → **掉线后永不重连**（与 reconnect_max_interval_s 取值无关）；而编码器计数
    // 照涨，外部无从分辨（“假推流”）。库已透出 push_active/push_state/push_frames_sent，
    // 因此在这里主动检测并重建会话。
    /** 上次观测到的 push_frames_sent（-1 = 未知）。 */
    @Volatile
    private var mLastPushSent = -1L
    /** 上次 push_frames_sent 发生变化的时刻（判断停滞用）。 */
    @Volatile
    private var mLastPushSentAtMs = 0L
    /** 上次重建时刻（冷却，避免反复重建）。 */
    @Volatile
    private var mLastPushRebuildMs = 0L
    /** 本会话是否曾经进入过 streaming(3)：用于区分“启动中”与“已断开”。 */
    @Volatile
    private var mPushEverStreamed = false

    /** 面板日志（环形保留最近 N 行）。 */
    private val mLogLines = ArrayDeque<String>()

    /** 摄像头切换中继客户端（仅本页存活期间轮询）。 */
    private var mSwitcher: CameraSwitchClient? = null

    /** 当前摄像头编号（1..N，对应 [CameraPreference.selectableCameraIds] 下标+1）。 */
    @Volatile
    private var mCurrentCameraIndex = 0

    /** 上一次切换状态文本，用于抑制重复的状态刷屏（如中继不可达）。 */
    @Volatile
    private var mLastSwitchStatus: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stream_test)

        mSurface = findViewById(R.id.preview_surface)
        mStatus = findViewById(R.id.tv_status)
        mState = findViewById(R.id.tv_stream_state)

        // 录制与推流统一走 P4-C「surface」路径：本平台硬件编码器只接受 surface 输入，
        // CPU 内存路径（mr_session_push_frame）拿不到任何输出（encoder_emitted 恒为 0），
        // 因此不再保留 CPU 版的推流入口。
        // 录制用 Switch 表示**状态**：打开 = 要录制，关闭 = 不要录制。
        // 推流与录制可任意组合，切换由 applyDesiredSession() 统一处理（必要时重启一次会话）。
        mRecordSwitch = findViewById(R.id.sw_record)
        mRecordSwitch.setOnCheckedChangeListener { _, checked ->
            if (mSyncingUi) return@setOnCheckedChangeListener
            mWantRecord = checked
            applyDesiredSession()
        }
        // 清理落盘的输出文件（车机磁盘空间有限）
        findViewById<Button>(R.id.btn_clean).setOnClickListener {
            confirmCleanOutputs()
        }
        // 推流按钮是**可恢复的开关**：切的是"要不要推流"，与录制开关组合后由状态机落地。
        findViewById<Button>(R.id.btn_push_surface).setOnClickListener {
            mWantPush = !mWantPush
            applyDesiredSession()
        }
        findViewById<Button>(R.id.btn_target).setOnClickListener {
            showTargetDialog()
        }
        findViewById<Button>(R.id.btn_back_home).setOnClickListener {
            finish()
        }

        // config 输出路径 ${FILES_DIR}/out/... 需要目录存在
        File(filesDir, "out").mkdirs()

        // 对端（PC）地址：运行期可配（PC 走 WiFi 拿 DHCP，地址会变）
        PushTarget.init(this)
        appendLog(
            "对端：${PushTarget.host}（推流 :${PushTarget.WHIP_PORT}，中继 :${PushTarget.RELAY_PORT}）"
        )

        // 打印库版本（同时触发 native 库加载，可及早发现集成问题）
        val ver = MediaRecordSession.libraryVersion()
        appendLog(
            if (ver != null) "media_record v${ver[0]}.${ver[1]}.${ver[2]}"
            else "media_record 库加载失败（见 logcat）"
        )

        updateStreamUi()   // 初始状态：未推流
        refreshOutSize()   // 「清理」按钮上显示当前落盘占用
    }

    override fun onStart() {
        super.onStart()
        startPreview()
        startSwitcher()
    }

    override fun onStop() {
        stopSwitcher()
        stopPreview()
        super.onStop()
    }

    override fun onPause() {
        mSurface.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        mSurface.onResume()
    }

    override fun onDestroy() {
        // 离开本页即收尾当前会话：否则 native 侧还在推流/录制，再次进入本页时
        // mSession 已为 null，状态提示会与实际情况不一致。
        // 注意排除配置变更（语言/字号/深色模式等会重建 Activity）——那种情况不该中断推流。
        mDestroyed = true      // 不再自动拉起会话
        mWantPush = false
        mWantRecord = false
        if (mSession != null && !isChangingConfigurations) {
            appendLog("离开页面，停止会话并保存…")
            stopSessionAsync()
        }
        stopPreview()
        super.onDestroy()
    }

    // ============================================================
    // 预览
    // ============================================================

    /** 装配纯取流预览（不挂算法分发），并消费相机帧推给录制/推流引擎。 */
    private fun startPreview() {
        try {
            val frame = FrameSession.get(::readFrame)
            mFrameSession = frame
            frame.configureSurface(mSurface, null)
            // 不调用 acquire：不 attach FrameDistributor，onFrameData 由本页独占用于推帧
            frame.controller().onFrameData = { hw, w, h -> onCameraFrame(hw, w, h) }
            if (frame.open()) {
                appendLog("相机预览已启动")
            } else {
                appendLog("相机打开失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "startPreview failed", e)
            appendLog("预览启动异常：${e.message}")
        }
    }

    /** 停止预览并收尾会话。 */
    private fun stopPreview() {
        try {
            mFrameSession?.controller()?.onFrameData = null
            mFrameSession?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "stopPreview error", e)
        } finally {
            mFrameSession = null
        }
        stopSessionAsync()
    }

    // ============================================================
    // 摄像头切换（中继指令）
    // ============================================================

    /**
     * 启动中继轮询，接收「切换到摄像头 N」指令。
     *
     * 指令链路：浏览器 → 控制中继 server.py → 本页。切换只换**帧来源**，
     * media_record 的 surface 会话与编码器不受影响，推流不中断。
     */
    private fun startSwitcher() {
        if (mSwitcher != null) return
        // 与当前偏好对齐，避免中继下发同一路时重复重开相机
        mCurrentCameraIndex = SWITCH_CAMERA_ORDER
            .indexOf(CameraPreference.selectedCameraId)
            .let { if (it >= 0) it + 1 else 0 }
        mSwitcher = CameraSwitchClient(
            relayBase = PushTarget.relayBase,
            onSwitch = { cam -> runOnUiThread { switchCamera(cam) } },
            onStatus = { msg -> onSwitchStatus(msg) }
        ).also { it.startPolling() }
        appendLog("切换中继轮询已启动：${PushTarget.relayBase}（当前 $mCurrentCameraIndex 路）")
    }

    /** 停止中继轮询。 */
    private fun stopSwitcher() {
        mSwitcher?.stop()
        mSwitcher = null
        mLastSwitchStatus = null
    }

    /** 对端地址变更后重启轮询（推流会话需重新点「开始推流」才生效）。 */
    private fun restartSwitcher() {
        stopSwitcher()
        startSwitcher()
    }

    /** 状态回调（轮询线程）。仅在状态**变化**时上屏，避免"中继不可达"反复刷屏。 */
    private fun onSwitchStatus(msg: String) {
        if (msg == mLastSwitchStatus) return
        mLastSwitchStatus = msg
        appendLog("中继：$msg")
    }

    /**
     * 切换摄像头：停掉当前 EVS 摄像头、按编号重开另一个。
     *
     * 编号映射见 [SWITCH_CAMERA_ORDER]（1=AVMF … 6=DMS），与观看端
     * `index.html` 的 `DEFAULT_NAMES`、中继 `server.py` 的 `CSWITCH_CAMS` 三处必须一致。
     *
     * 约束：各路**分辨率需一致**（库会校验送帧尺寸）。本页 surface 通路由 GL 缩放到
     * 编码器固定尺寸，但预览与算法仍按实际帧尺寸工作，切换后会自动适配。
     */
    private fun switchCamera(index: Int) {
        if (index < 1 || index > SWITCH_CAMERA_ORDER.size) {
            appendLog("忽略切换指令：cam=$index（有效 1..${SWITCH_CAMERA_ORDER.size}）")
            return
        }
        if (index == mCurrentCameraIndex) {
            Log.i(TAG, "switchCamera: cam=$index 已是当前路，忽略")
            return
        }
        val id = SWITCH_CAMERA_ORDER[index - 1]
        appendLog("切换摄像头 → [$index] $id")
        try {
            val frame = mFrameSession ?: run {
                appendLog("切换失败：相机会话未就绪")
                return
            }
            // 只做「停流 → 换 id → 开流」，**不释放 EVS 服务**：
            // CameraManager.stopCamera() 会额外 controller.release()（断开 CarEvsService），
            // 紧接着再重连一次，中间那个带 500ms 超时的执行器任务有被取消的风险，
            // 容易把相机留在半开状态。同一次连接内换摄像头不需要走这一步。
            // media_record 的 surface 会话与编码器完全不受影响，推流不中断。
            frame.cameraManager().cameraId = id     // 同步管理器状态，避免后续 open/stop 判断失配
            val controller = frame.controller()
            controller.stopCamera()
            CameraPreference.setSelected(this, id)
            controller.startCamera(id)
            mCurrentCameraIndex = index
        } catch (e: Exception) {
            Log.e(TAG, "switchCamera($index) failed", e)
            appendLog("切换失败：${e.message}")
        }
    }

    // ============================================================
    // 对端（PC）地址
    // ============================================================

    /**
     * 「对端地址」弹窗：手填 PC 的 IP/主机名，或点「自动发现」扫本网段找中继。
     *
     * 背景：PC 走 WiFi 拿 DHCP 地址、**会变**，写死会导致推流与切换一起失效。
     */
    private fun showTargetDialog() {
        val input = EditText(this).apply {
            setText(PushTarget.host)
            hint = "PC 的 IP 或主机名"
            setSingleLine()
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.stream_test_btn_target)
            .setMessage(
                "MediaMTX :${PushTarget.WHIP_PORT}（推流）\n" +
                    "中继 :${PushTarget.RELAY_PORT}（切换指令，server.py）\n" +
                    "当前：${PushTarget.host}"
            )
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) {
                    PushTarget.setHost(this, value)
                    appendLog("对端地址 → ${PushTarget.host}（推流 ${PushTarget.whipUrl}）")
                    if (mSession != null) appendLog("推流会话仍在用旧地址，请停止后重新开始")
                    restartSwitcher()
                }
            }
            .setNeutralButton("自动发现") { _, _ -> autoDiscoverTarget() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 扫描本网段寻找中继（server.py）；找到即采用其 IP 作为对端地址。 */
    private fun autoDiscoverTarget() {
        appendLog("正在扫描本网段寻找中继（:${PushTarget.RELAY_PORT}）…")
        Thread {
            val ip = try {
                RelayDiscovery.findRelay()
            } catch (t: Throwable) {
                Log.e(TAG, "findRelay failed", t)
                null
            }
            runOnUiThread {
                if (ip == null) {
                    appendLog(
                        "未发现中继：确认 PC 上中继已启动" +
                            "（CSWITCH_CAMS=6 python3 server.py），且车机与 PC 同网段"
                    )
                } else {
                    PushTarget.setHost(this, ip)
                    appendLog("发现中继：$ip → 对端地址已更新（推流 ${PushTarget.whipUrl}）")
                    if (mSession != null) appendLog("推流会话仍在用旧地址，请停止后重新开始")
                    restartSwitcher()
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    // ============================================================
    // 外部帧推送
    // ============================================================

    /**
     * 相机帧回调（HAL 线程）。同步推送，必须在 HardwareBuffer 有效期内完成。
     * 无活动会话 / 上一帧未处理完时直接丢弃，避免阻塞相机回调。
     */
    private fun onCameraFrame(hwBuffer: HardwareBuffer, width: Int, height: Int) {
        val session = mSession ?: return
        if (mSurfaceMode) {
            onCameraFrameSurface(session, hwBuffer, width, height)
            return
        }
        if (!mPushing.compareAndSet(false, true)) {
            mDroppedFrames++
            return
        }
        try {
            val t0 = System.nanoTime()
            val tsUs = t0 / 1000
            // 目标尺寸 = config 的 source 节点尺寸（引擎要求一致）；native 侧负责缩放
            val rc = session.pushHardwareBuffer(
                hwBuffer, width, height, mTargetWidth, mTargetHeight, tsUs
            )
            val costMs = (System.nanoTime() - t0) / 1_000_000.0
            when (rc) {
                MediaRecordSession.MR_OK -> {
                    mPushedFrames++
                    mCostSumMs += costMs
                    mCostCount++
                    session.lastTimings()?.let { t ->
                        if (t.size == 3) {
                            mLockSumUs += t[0]
                            mConvSumUs += t[1]
                            mPushSumUs += t[2]
                        }
                    }
                    if (mPushedFrames % 60 == 1L) {
                        val n = if (mCostCount > 0) mCostCount else 1
                        val total = mCostSumMs / n
                        val lock = mLockSumUs / 1000.0 / n
                        val conv = mConvSumUs / 1000.0 / n
                        val push = mPushSumUs / 1000.0 / n
                        // 库内部编码诊断（定位 emitted=0）：
                        //   notify=编码器收到的帧通知, polls=Poll 次数, fail=Poll 失败次数,
                        //   lastSt=最近一次失败的原始 codec 状态, emitted=输出包数
                        // 诊断树：notify>0 但 polls=0 → 编码器没被 pump；
                        //         polls>0 但 emitted=0 → codec 无产出；fail>0 → Poll 报错(看 lastSt)
                        val st = session.stats()
                        val notify = st?.getOrNull(1) ?: -1L
                        val polls = st?.getOrNull(2) ?: -1L
                        val pollFail = st?.getOrNull(3) ?: -1L
                        val lastPoll = st?.getOrNull(4) ?: -1L
                        val emitted = st?.getOrNull(5) ?: -1L
                        val kfSps = st?.getOrNull(6) ?: -1L
                        runOnUiThread {
                            appendLog(
                                "已推 $mPushedFrames 帧 / 丢 $mDroppedFrames" +
                                    "（${width}x$height → ${mTargetWidth}x$mTargetHeight）" +
                                    " 均耗时 %.1f ms [lock %.1f / conv %.1f / push %.1f]"
                                        .format(total, lock, conv, push) +
                                    " | enc notify=$notify polls=$polls fail=$pollFail" +
                                    " lastSt=$lastPoll emitted=$emitted kfSps=$kfSps"
                            )
                        }
                    }
                }
                MediaRecordSession.MR_ERROR_OVERFLOW -> mDroppedFrames++
                else -> if (mPushedFrames == 0L) {
                    runOnUiThread { appendLog("推帧失败 rc=$rc：${session.lastError()}") }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "push frame error", e)
        } finally {
            mPushing.set(false)
        }
    }

    /**
     * surface 路径的相机帧回调（HAL 线程）：
     * 只把 HardwareBuffer 投递给 native 渲染器（acquire + 入队后立即返回），
     * 真正的 EGL 绘制在 native 的 GL 线程上完成，不阻塞取流。
     */
    private fun onCameraFrameSurface(
        session: MediaRecordSession,
        hwBuffer: HardwareBuffer,
        width: Int,
        height: Int
    ) {
        try {
            val tsUs = System.nanoTime() / 1000
            val rc = session.enqueueToSurface(hwBuffer, width, height, tsUs)
            when (rc) {
                MediaRecordSession.MR_OK -> {
                    mPushedFrames++
                    if (mPushedFrames % 60 == 1L) {
                        reportSurfaceProgress(session, width, height)
                    }
                }
                MediaRecordSession.MR_ERROR_OVERFLOW -> mDroppedFrames++
                else -> if (mPushedFrames == 0L) {
                    runOnUiThread { appendLog("surface 投帧失败 rc=$rc") }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "enqueue surface frame error", e)
        }
    }

    /** surface 路径进度日志：渲染器诊断 + 引擎诊断（定位 emitted=0）。 */
    private fun reportSurfaceProgress(
        session: MediaRecordSession,
        width: Int,
        height: Int
    ) {
        val ss = session.surfaceStats()
        val drawn = ss?.getOrNull(0) ?: -1L
        val drop = ss?.getOrNull(1) ?: -1L
        val drawFail = ss?.getOrNull(2) ?: -1L
        val notifyFail = ss?.getOrNull(3) ?: -1L
        val lastDrawUs = ss?.getOrNull(4) ?: -1L
        val lastConvertUs = ss?.getOrNull(5) ?: -1L
        val zeroCopy = ss?.getOrNull(6) ?: -1L
        val st = session.stats()
        val beats = st?.getOrNull(0) ?: -1L
        val notify = st?.getOrNull(1) ?: -1L
        val polls = st?.getOrNull(2) ?: -1L
        val emitted = st?.getOrNull(5) ?: -1L
        val surfaceSrc = st?.getOrNull(11) ?: -1L
        // 推流侧健康（库 2026-10-08 追加的 mr_stats 字段，索引 12~21）
        val pushPresent = st?.getOrNull(12) ?: -1L
        val pushActive = st?.getOrNull(13) ?: -1L
        val pushState = st?.getOrNull(14) ?: -1L
        val pushSent = st?.getOrNull(15) ?: -1L
        val pushBytes = st?.getOrNull(17) ?: -1L
        val line = "surface 投 $mPushedFrames 帧（${width}x$height → " +
            "enc ${mSurfaceWidth}x$mSurfaceHeight）drawn=$drawn drop=$drop " +
            "drawFail=$drawFail notifyFail=$notifyFail | " +
            (if (zeroCopy == 1L) "零拷贝" else "CPU(conv %.2fms)".format(lastConvertUs / 1000.0)) +
            " draw %.2fms".format(lastDrawUs / 1000.0) +
            " | enc beats=$beats notify=$notify polls=$polls " +
            "emitted=$emitted surfSrc=$surfaceSrc" +
            " | push present=$pushPresent active=$pushActive state=$pushState " +
            "sent=$pushSent bytes=$pushBytes"
        runOnUiThread { appendLog(line) }

        // ---- 推流 watchdog：会话死了就重建（上游不重连，只能宿主兜底）----
        // 判据用「本会话确实在推流」（mSessionPushing，由 startSession 按 asset 设置），
        // 不能用用户期望开关 mWantPush —— 页面被系统恢复/自动拉起时前者为真、后者可能仍是 false。
        if (pushPresent == 1L && mSessionPushing) {
            val nowMs = System.currentTimeMillis()
            if (pushSent != mLastPushSent) {
                mLastPushSent = pushSent
                mLastPushSentAtMs = nowMs
            }
            // 判据一（权威口径）：active=0（库已放弃），或状态既不是 streaming(3) 也不是
            // reconnecting(4)/ice-connected(7) —— 这样 5(disconnected) 以及**未在文档中定义的
            // 6** 都能覆盖（实测服务端 terminated 后库报 state=6，而 active 仍为 1，只认 5 会漏）。
            // 库 v1.0.3 新增 7=ice-connected：ICE 通道已通（恢复中），**不能判死**，否则会误杀。
            // 加“曾经进过 3”这道闩，避免启动初期的 0/1/2 被误判为断开。
            if (pushState == 3L) mPushEverStreamed = true
            val stateDead = (pushActive == 0L) ||
                (mPushEverStreamed && pushState != 3L && pushState != 4L && pushState != 7L)
            // 判据二（兜底）：frames_sent 连续 15s 不增长——**仅当该计数器确实在工作时才用**。
            // ⚠️ 若库版本里 push_frames_sent/push_bytes_sent 恒为 0（与 rtt/loss 同属“上游未回填”），
            // 不加 pushSent > 0 这个门槛，判据二会恒真，导致每 30s 误杀一条**健康**会话
            // （实测：mediamtx 侧明明 is publishing，却被反复拆重建）。
            // 阈值取 15s：这条无线链路有秒级卡顿，10s 容易踩线。
            val stagnated =
                pushSent > 0 && mLastPushSentAtMs > 0 && nowMs - mLastPushSentAtMs > 15_000
            if ((stateDead || stagnated) && nowMs - mLastPushRebuildMs > 30_000) {
                mLastPushRebuildMs = nowMs
                mPushEverStreamed = false  // 新会话重新计一次“曾经 streaming”
                val why = if (stateDead) "active=$pushActive state=$pushState"
                          else "frames_sent 连续 10s 未增长（$pushSent）"
                runOnUiThread {
                    appendLog("推流会话异常（$why，bytes=$pushBytes）→ 重建会话")
                    // 清掉“当前组合”标记，applyDesiredSession() 才会走「先收尾、完成后重新拉起」
                    mSessionAsset = null
                    applyDesiredSession()
                }
            }
        }

        // 顶部状态栏的实时摘要（随进度每 ~2 秒刷新；UI 线程切换由 helper 内部处理）
        val elapsedMs = (System.currentTimeMillis() - mSessionStartMs).coerceAtLeast(1L)
        val fps = mPushedFrames * 1000.0 / elapsedMs
        updateStreamUi(
            detail = buildString {
                append("%.1ffps".format(fps))
                append(" · 投 $mPushedFrames 帧")
                append(if (emitted >= 0) " · 编码 $emitted 帧" else " · 编码统计不可用")
                if (drop > 0) append(" · 丢 $drop")
                // 推流侧状态明确标出，避免“计数照涨、其实没推上去”的假象
                when {
                    pushPresent != 1L -> Unit
                    pushActive == 0L || pushState == 5L -> append(" · 推流已断开")
                    pushState == 4L -> append(" · 推流重连中")
                }
            }
        )
    }

    // ============================================================
    // 会话（录制 / 推流）
    // ============================================================

    /**
     * 刷新顶部状态提示与推流按钮文案（三个状态：推流中 / 正在停止 / 未推流）。
     *
     * 状态以 [mSession] 是否为 null 为准，因而**天然可恢复**：任何路径（按钮、
     * 录制按钮、启动失败、收尾完成）都会把界面带回一致状态。可从任意线程调用。
     *
     * @param stopping 正在收尾（finalize 输出文件）——此时按钮置灰，避免重入启动。
     * @param detail   推流中的实时摘要（如 `29.8fps · 投 3400 帧 · 编码 3399 帧`）。
     */
    private fun updateStreamUi(stopping: Boolean = false, detail: String? = null) {
        val live = mSession != null
        val pushing = live && mSessionPushing
        val recording = live && mSessionRecording
        val name = sessionName()
        runOnUiThread {
            val btn = findViewById<Button>(R.id.btn_push_surface)
            val cleanBtn = findViewById<Button>(R.id.btn_clean)
            // 录制开关：勾选状态跟着会话实际情况走（收尾/切换期间置灰）
            mSyncingUi = true
            mRecordSwitch.isChecked = recording
            mRecordSwitch.isEnabled = !stopping
            mSyncingUi = false
            // 有会话正在写文件时不允许清理（muxer 正占用那个文件）
            cleanBtn.isEnabled = !live && !stopping
            when {
                live -> {
                    btn.isEnabled = true
                    btn.setText(
                        if (pushing) R.string.stream_test_btn_push_stop
                        else R.string.stream_test_btn_record_stop
                    )
                    mState.setTextColor(0xFF00E676.toInt())  // 绿色 = 会话在跑
                    mState.text = getString(
                        R.string.stream_test_state_live,
                        name,
                        detail ?: "启动中…"
                    )
                }
                stopping -> {
                    btn.isEnabled = false
                    btn.setText(R.string.stream_test_btn_stopping)
                    mState.setTextColor(0xFFFFB300.toInt())  // 橙色 = 收尾中
                    mState.setText(R.string.stream_test_state_stopping)
                }
                else -> {
                    btn.isEnabled = true
                    btn.setText(R.string.stream_test_btn_push_surface)
                    mState.setTextColor(0xFF888888.toInt())  // 灰色 = 未推流
                    mState.setText(R.string.stream_test_state_idle)
                }
            }
        }
    }

    // ============================================================
    // 会话组合状态机（推流 / 录制 任意组合，动态切换）
    // ============================================================

    /** 当前会话的名称（用于状态行）。 */
    private fun sessionName(): String = when {
        mSessionPushing && mSessionRecording -> "推流+录制"
        mSessionPushing -> LABEL_PUSH
        mSessionRecording -> LABEL_RECORD
        else -> LABEL_PUSH
    }

    /** 启动失败时把期望组合归零，避免状态机反复重试。 */
    private fun resetWantsToIdle() {
        mWantPush = false
        mWantRecord = false
    }

    /**
     * 把「期望组合」落地成实际会话（推流 / 录制 两个开关的组合）。
     *
     * 三种组合对应三套 config：
     * - 只推流 → [ASSET_PUSH]（纯推流，不落盘）
     * - 只录制 → [ASSET_RECORD]
     * - 都要   → [ASSET_PUSH_RECORD]（推流 + MP4 落盘）
     *
     * 组合变化时**重启一次会话**：native 侧的 surface 渲染器是全局单槽，两路并发会让
     * CPU 翻倍（单路 UYVY→RGBA 已 20~30ms/帧），所以宁可重启也不并发。重启期间界面显示
     * 「正在停止…」，收尾完成后本方法会被再次调用以拉起新组合——用户只需操作开关，
     * 不需要"停了再点一次"。
     */
    private fun applyDesiredSession() {
        if (mDestroyed) return
        val want = when {
            mWantPush && mWantRecord -> ASSET_PUSH_RECORD
            mWantPush -> ASSET_PUSH
            mWantRecord -> ASSET_RECORD
            else -> null
        }
        if (want == mSessionAsset) return              // 已经是目标组合
        if (mSession != null || mStopping) {           // 有会话/正在收尾：先收尾，完成后继续
            stopSessionAsync()
            return
        }
        if (want == null) {
            updateStreamUi()                           // 目标也是空闲：刷一次界面即可
            return
        }
        appendLog("切换会话组合 → $want")
        startSession(want, surfaceMode = SURFACE_MODE_ON)
    }

    // ============================================================
    // 输出文件清理（车机磁盘空间有限）
    // ============================================================

    /** out 目录下的输出文件：MP4 与中途残留的 .tmp。 */
    private fun outFiles(): List<File> {
        val dir = File(filesDir, "out")
        return dir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".mp4") || it.name.endsWith(".tmp")) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /** 人类可读的大小文本。 */
    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }

    /** 把 out 目录现状刷到「清理」按钮文案上（有文件时带占用大小）。 */
    private fun refreshOutSize() {
        val files = outFiles()
        val text = if (files.isEmpty()) {
            getString(R.string.stream_test_btn_clean)
        } else {
            getString(R.string.stream_test_btn_clean_size, formatSize(files.sumOf { it.length() }))
        }
        runOnUiThread { findViewById<Button>(R.id.btn_clean).text = text }
    }

    /** 二次确认后删除 out 目录下的输出文件。 */
    private fun confirmCleanOutputs() {
        val files = outFiles()
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.stream_test_clean_none, Toast.LENGTH_SHORT).show()
            refreshOutSize()
            return
        }
        val bytes = files.sumOf { it.length() }
        AlertDialog.Builder(this)
            .setTitle(R.string.stream_test_clean_title)
            .setMessage(getString(R.string.stream_test_clean_msg, files.size, formatSize(bytes)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                var removed = 0
                var freed = 0L
                for (f in files) {
                    val len = f.length()
                    if (f.delete()) {
                        removed++
                        freed += len
                    }
                }
                appendLog("清理：删除 $removed 个文件，释放 ${formatSize(freed)}")
                Toast.makeText(
                    this,
                    getString(R.string.stream_test_clean_done, removed, formatSize(freed)),
                    Toast.LENGTH_SHORT
                ).show()
                refreshOutSize()
            }
            .show()
    }

    /**
     * 读取 assets config 并打开/启动一个持续运行（resident）的会话。
     *
     * @param surfaceMode 0 = CPU 内存路径（`mr_session_push_frame`）；
     *                    1 = 编码器输入 surface 路径（宿主 GL 直绘 + `notify_frame`）。
     */
    private fun startSession(assetName: String, surfaceMode: Int = SURFACE_MODE_ON) {
        // 由 config 名推导这套管线包含什么（纯推流 / 纯录制 / 推流+录制）
        val pushing = assetName == ASSET_PUSH || assetName == ASSET_PUSH_RECORD
        val recording = assetName == ASSET_RECORD || assetName == ASSET_PUSH_RECORD
        val label = when {
            pushing && recording -> "推流+录制"
            pushing -> LABEL_PUSH
            else -> LABEL_RECORD
        }
        val json = loadConfig(assetName)
        if (json == null) {
            appendLog("读取 $assetName 失败")
            resetWantsToIdle()
            updateStreamUi()
            return
        }
        mTargetWidth = 0
        mTargetHeight = 0
        mSurfaceWidth = 0
        mSurfaceHeight = 0
        mSurfaceMode = false

        if (surfaceMode != SURFACE_MODE_ON) {
            // CPU 路径：引擎要求推送帧尺寸与 config 中 ExternalFrameSourceNode 声明一致，
            // 这里解析出目标尺寸，由 native 推帧时把相机帧缩放到该尺寸。
            val srcSize = parseSourceSize(json)
            mTargetWidth = srcSize?.first ?: 0
            mTargetHeight = srcSize?.second ?: 0
            appendLog(
                if (mTargetWidth > 0) "$label 目标尺寸 ${mTargetWidth}x$mTargetHeight"
                else "$label 未解析到 source 尺寸（按源尺寸推送）"
            )
        }

        val session = MediaRecordSession.open(json, surfaceMode = surfaceMode, resident = 1)
        if (session == null) {
            appendLog("$label 会话打开失败（见 logcat）")
            resetWantsToIdle()
            updateStreamUi()
            return
        }
        val rc = session.start()
        if (rc != MediaRecordSession.MR_OK) {
            appendLog("$label 启动失败 rc=$rc：${session.lastError()}")
            session.close()
            resetWantsToIdle()
            updateStreamUi()
            return
        }

        if (surfaceMode == SURFACE_MODE_ON) {
            // 取编码器输入 surface 并启动宿主侧 EGL 渲染器；尺寸由编码器决定，
            // 相机帧按该尺寸缩放（GL 采样），不再依赖 config 的 source 尺寸。
            val size = session.startSurface()
            if (size == null) {
                appendLog("$label 取编码器输入 surface 失败（见 logcat）")
                session.stop()
                session.close()
                resetWantsToIdle()
                updateStreamUi()
                return
            }
            mSurfaceWidth = size.first
            mSurfaceHeight = size.second
            mSurfaceMode = true
            appendLog("$label 编码器输入 surface ${size.first}x${size.second}（宿主 GL 直绘）")
        }

        mPushedFrames = 0
        mDroppedFrames = 0
        mCostSumMs = 0.0
        mCostCount = 0
        mLockSumUs = 0
        mConvSumUs = 0
        mPushSumUs = 0
        mSession = session
        mSessionAsset = assetName
        mSessionPushing = pushing
        mSessionRecording = recording
        mSessionStartMs = System.currentTimeMillis()
        appendLog("$label 已启动（$assetName）")
        updateStreamUi()  // 立刻反映新组合（状态行 + 两个控件）
    }

    /**
     * 异步收尾当前会话：pushEof（finalize）→ wait → close。
     *
     * @return true = 刚启动了收尾流程（调用方不应紧接着开新会话）；false = 本来就没有会话。
     */
    private fun stopSessionAsync(): Boolean {
        val session = mSession ?: return false
        mSession = null
        mSessionAsset = null
        mSessionPushing = false
        mSessionRecording = false
        val wasSurface = mSurfaceMode
        mSurfaceMode = false
        mStopping = true
        appendLog("正在停止并保存…")
        updateStreamUi(stopping = true)  // 置灰按钮，避免收尾期间重入启动
        Thread {
            var surfaceStats: LongArray? = null
            var stats: LongArray? = null
            var eofRc = Int.MIN_VALUE
            var waitRc = Int.MIN_VALUE
            var failure: Throwable? = null
            try {
                // pushEof 会先停 surface 渲染器（join 渲染线程）再 finalize MP4
                surfaceStats = session.surfaceStats()
                eofRc = session.pushEof()
                waitRc = session.waitSession(WAIT_FINALIZE_MS)
                stats = session.stats()
            } catch (t: Throwable) {
                // 收尾阶段的异常必须兜住：否则界面会永久卡在「正在停止」（按钮置灰无法恢复）
                failure = t
                Log.e(TAG, "stop session failed", t)
            } finally {
                session.close()  // 幂等
                runOnUiThread {
                    mStopping = false
                    val fail = failure
                    if (fail != null) {
                        appendLog("收尾异常：${fail.message}（会话已释放）")
                    } else {
                        appendLog(
                            "已停止：eof=$eofRc wait=$waitRc，" +
                                "推帧 $mPushedFrames / 丢 $mDroppedFrames"
                        )
                        if (wasSurface) {
                            appendLog(
                                "surface: drawn=${surfaceStats?.getOrNull(0)} " +
                                    "drop=${surfaceStats?.getOrNull(1)} " +
                                    "drawFail=${surfaceStats?.getOrNull(2)} " +
                                    "notifyFail=${surfaceStats?.getOrNull(3)} " +
                                    "zeroCopy=${surfaceStats?.getOrNull(5)}"
                            )
                        }
                        stats?.let {
                            appendLog(
                                "stats: beats=${it[0]} notify=${it[1]} " +
                                    "polls=${it[2]} emitted=${it[5]}"
                            )
                        }
                    }
                    updateStreamUi()        // 回到「未推流」，按钮恢复「开始推流」
                    refreshOutSize()        // 收尾后落盘文件大小已变化
                    applyDesiredSession()   // 若期望组合在此期间变了，这里拉起新会话
                }
            }
        }.start()
        return true
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 读取 assets 管线 config：
     * 1. 替换 `${FILES_DIR}` 与 `${PUSH_HOST}` 占位符
     *    （后者来自 [PushTarget]，PC 走 WiFi 的 DHCP 地址会变，不能写死在 assets 里）；
     * 2. 把 `options.output` 的**相对路径**补成 filesDir 下的绝对路径。
     *
     * 第 2 步是必需的：引擎用 C 的 `open()` 打开输出文件，相对路径按**进程 CWD** 解析
     * （Android 应用进程 CWD 是 `/`，不可写）→ 打不开，会话启动失败。已核对引擎 .so，
     * 其内部并没有 `chdir`/基目录逻辑，不会替我们解析相对路径。
     */
    private fun loadConfig(assetName: String): String? = try {
        val raw = assets.open(assetName).bufferedReader().use { it.readText() }
            .replace("\${FILES_DIR}", filesDir.absolutePath)
            .replace("\${PUSH_HOST}", PushTarget.host)
        absolutizeOutputPaths(raw)
    } catch (e: Exception) {
        Log.e(TAG, "loadConfig $assetName failed", e)
        null
    }

    /**
     * 把 config 中各节点 `options.output` 的相对路径补成 filesDir 下的绝对路径；
     * 已是绝对路径（以 `/` 开头）的原样保留。解析失败时原样返回，不阻断流程。
     */
    private fun absolutizeOutputPaths(json: String): String = try {
        val root = org.json.JSONObject(json)
        val nodes = root.optJSONArray("nodes")
        var changed = false
        if (nodes != null) {
            for (i in 0 until nodes.length()) {
                val node = nodes.optJSONObject(i) ?: continue
                val opts = node.optJSONObject("options") ?: continue
                if (!opts.has("output")) continue
                val out = opts.optString("output")
                if (out.isEmpty() || out.startsWith("/")) continue
                val abs = File(filesDir, out).absolutePath
                opts.put("output", abs)
                changed = true
                appendLog("输出路径已补全：$out → $abs")
            }
        }
        if (changed) root.toString() else json
    } catch (e: Exception) {
        Log.w(TAG, "absolutizeOutputPaths failed", e)
        json
    }

    /**
     * 从管线 config 解析 `ExternalFrameSourceNode` 的 width/height。
     * 引擎要求推送帧尺寸与该声明一致，因此用它作为 native 缩放的目标尺寸。
     */
    private fun parseSourceSize(json: String): Pair<Int, Int>? = try {
        val nodes = org.json.JSONObject(json).optJSONArray("nodes")
        var found: Pair<Int, Int>? = null
        if (nodes != null) {
            for (i in 0 until nodes.length()) {
                val node = nodes.optJSONObject(i) ?: continue
                if (node.optString("type") != "ExternalFrameSourceNode") continue
                val opts = node.optJSONObject("options") ?: continue
                val w = opts.optInt("width", 0)
                val h = opts.optInt("height", 0)
                if (w > 0 && h > 0) found = w to h
                break
            }
        }
        found
    } catch (e: Exception) {
        Log.w(TAG, "parseSourceSize failed", e)
        null
    }

    /** JNI 帧读取（本页不使用算法分发，仅为 FrameSession 构造签名提供）。 */
    private fun readFrame(hwBuffer: HardwareBuffer, width: Int, height: Int): ByteArray? =
        NativeFrameReader.readHardwareBuffer(hwBuffer, width, height)

    /** 追加一行日志到左侧面板。 */
    private fun appendLog(line: String) {
        Log.i(TAG, line)
        runOnUiThread {
            mLogLines.addLast(line)
            while (mLogLines.size > MAX_LOG_LINES) mLogLines.removeFirst()
            mStatus.text = mLogLines.joinToString("\n")
        }
    }

    companion object {
        /** 录制 config：外部帧源 → 编码 → 录制 → MP4。 */
        private const val ASSET_RECORD = "external_source_record.json"

        /** 纯推流 config：外部帧源 → 编码 → WebRTC（**不落盘**）。 */
        private const val ASSET_PUSH = "external_source_push.json"

        /** 推流 + 录制 config：外部帧源 → 编码 → 推流 + MP4 落盘。 */
        private const val ASSET_PUSH_RECORD = "external_source_push_record.json"

        /** 停止时等待 finalize 的最长时间（ms）。 */
        private const val WAIT_FINALIZE_MS = 5000

        /** `mr_session_open` 的 surface_mode：1 = 编码器输入 surface 路径（P4-C）。 */
        private const val SURFACE_MODE_ON = 1

        /** 面板最多保留日志行数。 */
        private const val MAX_LOG_LINES = 40

        /** 会话名称（状态提示与按钮文案共用）。 */
        private const val LABEL_PUSH = "推流"
        private const val LABEL_RECORD = "录制"

        /**
         * 中继/观看端约定的摄像头编号顺序（**第 i 个按钮 = 第 i 路**）。
         *
         * 三处必须一致，改动时一起改：
         * 1) 观看端 `index.html` 的 `DEFAULT_NAMES`；
         * 2) 中继 `server.py` 的 `CSWITCH_CAMS`（默认 5，**要含 DMS 必须设为 6**，
         *    否则第 6 路的指令会被它按 `1 <= cam <= NUM_CAMS` 静默丢弃）；
         * 3) 本列表。
         *
         * 刻意与 [CameraPreference.selectableCameraIds] 解耦：那是主页下拉框的展示顺序，
         * 为 UI 需要调整时不应连带把中继的编号映射改错。
         */
        private val SWITCH_CAMERA_ORDER = listOf(
            CameraIds.AVMF,  // 1
            CameraIds.AVMR,  // 2
            CameraIds.AVMB,  // 3
            CameraIds.AVML,  // 4
            CameraIds.RVC,   // 5
            CameraIds.DMS    // 6
        )
    }
}
