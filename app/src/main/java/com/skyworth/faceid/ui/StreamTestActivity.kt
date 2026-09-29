package com.skyworth.faceid.ui

import android.hardware.HardwareBuffer
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.skyworth.faceid.R
import com.skyworth.faceid.core.FrameSession
import com.skyworth.faceid.core.NativeFrameReader
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

    private var mFrameSession: FrameSession? = null
    private var mSession: MediaRecordSession? = null

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

    /** 面板日志（环形保留最近 N 行）。 */
    private val mLogLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stream_test)

        mSurface = findViewById(R.id.preview_surface)
        mStatus = findViewById(R.id.tv_status)

        findViewById<Button>(R.id.btn_record).setOnClickListener {
            startSession(ASSET_RECORD, "录制")
        }
        findViewById<Button>(R.id.btn_push).setOnClickListener {
            startSession(ASSET_PUSH, "推流")
        }
        // P4-C：编码器输入 surface 路径（自建 EGL 管线直绘，适配只接受 surface 输入的编码器）
        findViewById<Button>(R.id.btn_push_surface).setOnClickListener {
            startSession(ASSET_PUSH, "推流(surface)", surfaceMode = SURFACE_MODE_ON)
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            stopSessionAsync()
        }
        findViewById<Button>(R.id.btn_back_home).setOnClickListener {
            finish()
        }

        // config 输出路径 ${FILES_DIR}/out/... 需要目录存在
        File(filesDir, "out").mkdirs()

        // 打印库版本（同时触发 native 库加载，可及早发现集成问题）
        val ver = MediaRecordSession.libraryVersion()
        appendLog(
            if (ver != null) "media_record v${ver[0]}.${ver[1]}.${ver[2]}"
            else "media_record 库加载失败（见 logcat）"
        )
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
        val st = session.stats()
        val beats = st?.getOrNull(0) ?: -1L
        val notify = st?.getOrNull(1) ?: -1L
        val polls = st?.getOrNull(2) ?: -1L
        val emitted = st?.getOrNull(5) ?: -1L
        val surfaceSrc = st?.getOrNull(11) ?: -1L
        val line = "surface 投 $mPushedFrames 帧（${width}x$height → " +
            "enc ${mSurfaceWidth}x$mSurfaceHeight）drawn=$drawn drop=$drop " +
            "drawFail=$drawFail notifyFail=$notifyFail | conv %.2fms draw %.2fms".format(
                lastConvertUs / 1000.0, lastDrawUs / 1000.0
            ) + " | enc beats=$beats notify=$notify polls=$polls " +
            "emitted=$emitted surfSrc=$surfaceSrc"
        runOnUiThread { appendLog(line) }
    }

    // ============================================================
    // 会话（录制 / 推流）
    // ============================================================

    /**
     * 读取 assets config 并打开/启动一个持续运行（resident）的会话。
     *
     * @param surfaceMode 0 = CPU 内存路径（`mr_session_push_frame`）；
     *                    1 = 编码器输入 surface 路径（宿主 GL 直绘 + `notify_frame`）。
     */
    private fun startSession(assetName: String, label: String, surfaceMode: Int = 0) {
        stopSessionAsync()
        val json = loadConfig(assetName)
        if (json == null) {
            appendLog("读取 $assetName 失败")
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
            return
        }
        val rc = session.start()
        if (rc != MediaRecordSession.MR_OK) {
            appendLog("$label 启动失败 rc=$rc：${session.lastError()}")
            session.close()
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
        appendLog("$label 已启动（$assetName）")
    }

    /** 异步收尾当前会话：pushEof（finalize）→ wait → close。 */
    private fun stopSessionAsync() {
        val session = mSession ?: return
        mSession = null
        val wasSurface = mSurfaceMode
        mSurfaceMode = false
        appendLog("正在停止并保存…")
        Thread {
            // pushEof 会先停 surface 渲染器（join GL 线程）再 finalize MP4
            val surfaceStats = session.surfaceStats()
            val eofRc = session.pushEof()
            val waitRc = session.waitSession(WAIT_FINALIZE_MS)
            val stats = session.stats()
            session.close()
            runOnUiThread {
                appendLog("已停止：eof=$eofRc wait=$waitRc，推帧 $mPushedFrames / 丢 $mDroppedFrames")
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
                    appendLog("stats: beats=${it[0]} notify=${it[1]} polls=${it[2]} emitted=${it[5]}")
                }
            }
        }.start()
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 读取 assets 管线 config：
     * 1. 替换 `${FILES_DIR}` 占位符；
     * 2. 把 `options.output` 的**相对路径**补成 filesDir 下的绝对路径。
     *
     * 第 2 步是必需的：引擎用 C 的 `open()` 打开输出文件，相对路径按**进程 CWD** 解析
     * （Android 应用进程 CWD 是 `/`，不可写）→ 打不开，会话启动失败。已核对引擎 .so，
     * 其内部并没有 `chdir`/基目录逻辑，不会替我们解析相对路径。
     */
    private fun loadConfig(assetName: String): String? = try {
        val raw = assets.open(assetName).bufferedReader().use { it.readText() }
            .replace("\${FILES_DIR}", filesDir.absolutePath)
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

        /** 推流 config：外部帧源 → 编码 → WebRTC 推流（+ MP4）。 */
        private const val ASSET_PUSH = "external_source_record_push.json"

        /** 停止时等待 finalize 的最长时间（ms）。 */
        private const val WAIT_FINALIZE_MS = 5000

        /** `mr_session_open` 的 surface_mode：1 = 编码器输入 surface 路径（P4-C）。 */
        private const val SURFACE_MODE_ON = 1

        /** 面板最多保留日志行数。 */
        private const val MAX_LOG_LINES = 40
    }
}
