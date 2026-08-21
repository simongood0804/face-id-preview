/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.algorithm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import atlas.face.sdk.FaceFlag
import atlas.face.sdk.FaceImage
import atlas.face.sdk.FaceResult
import atlas.face.sdk.FaceSDK
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Face ID 算法实现 —— 基于 [face-sdk-v1.1.4.aar]（AAR 集成）。
 *
 * 对接文档：proposals/FACEP-006-迁移算法库为AAR集成.md
 * 部署步骤：
 *   1. DLC 模型文件从 assets/models/ 解压到设备存储
 *   2. 调用 [initialize] 时初始化 AAR SDK pipeline
 *   3. 每帧调用 [processFrame] 进行人脸检测/活体/识别
 *
 * 线程安全：一个实例绑定一个线程，不支持多线程共享。
 */
class FaceIDAlgorithmImpl : IFaceIDAlgorithm {

    private val TAG = "FaceIDAlgorithm"

    @Volatile
    private var mInitialized = false

    /** AAR FaceSDK 实例，替代旧 mNativeHandle。 */
    private var mFaceSDK: FaceSDK? = null

    /** AAR FaceResult 缓存数组（避免每帧 new 对象）。 */
    private val mAARResults = arrayOfNulls<FaceResult>(MAX_FACES)

    /** 裁剪偏移（FrameProcessor 在 processFrame 前通过 [setCropOffset] 设置）。 */
    @Volatile var mCropOffsetX: Int = 0
    @Volatile var mCropOffsetY: Int = 0

    /** 设置裁剪偏移（ROI 左上角在原图坐标中的偏移）。 */
    override fun setCropOffset(x: Int, y: Int) {
        mCropOffsetX = x
        mCropOffsetY = y
    }

    /** 模型文件存储目录。 */
    private var mModelDir: String = ""

    // ============ 帧 dump（调试用，手动触发） ============
    /** 应用 Context（属性变化时重新应用 dump 状态用）。 */
    private var mAppContext: Context? = null
    /** dump 目录（与传给算法的路径一致）。 */
    private var mDumpDir: String? = null
    /** 已 dump 的帧计数（用于生成文件名 index）。 */
    private var mDumpFrameCount = 0
    /** 最近一帧原始 UYVY 数据缓存（手动触发时保存此帧）。 */
    private var mLatestFrame: ByteArray? = null
    private var mLatestW = 0
    private var mLatestH = 0
    /** dump 后台执行线程。 */
    private val mDumpExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    /** 主线程 Handler，用于完成回调。 */
    private val mMainHandler = Handler(Looper.getMainLooper())
    /** 上一次读取到的属性值，用于检测变化。 */
    @Volatile private var mLastDumpPropValue: String? = null
    /** 属性名。 */
    private val PROP_DUMP_ENABLE = "algorithm_face_dump_enable"
    /** 导出 dump 图像的目标目录（sdcard）。 */
    private val SDCARD_DUMP_DIR = "/sdcard/debugDmsDump"

    /** 模型文件清单（必须与 manifest.json 中引用的模型一致）。 */
    private val REQUIRED_MODEL_FILES = listOf(
        "det_500m_int8.dlc",
        "face_antispoof_int8.dlc",
        "2d106det_int8.dlc",
        "w600k_mbf_int8.dlc",
        "hopenet_mbv2_int8.dlc",
        "pfld_eye_int8.dlc",
        "manifest.json",
        "dms_calibration.json"
    )

    /** 系统级模型目录（可被外部更新）。 */
    private val VENDOR_MODEL_DIR = "/vendor/etc/faceid"

    /** 录入管理器（延迟初始化）。 */
    private var mEnrollmentManager: FaceEnrollmentManager? = null

    /** 眼睛/嘴巴单帧几何判定器（EAR/MAR → 连续开合度）。 */
    private val mEyeMouthEstimator = EyeMouthStateEstimator()

    /** 眼睛/嘴巴基础状态防抖器（多帧时序 → 稳定基础状态）。 */
    private val mEyeMouthState = EyeMouthStateMachine()

    /** 眼睛/嘴巴阈值动态校准器（维护睁/闭眼、张/闭嘴基准，动态换算滞回阈值）。 */
    private val mEyeMouthCalibrator = EyeMouthCalibrator()

    init {
        for (i in 0 until MAX_FACES) {
            mAARResults[i] = FaceResult()
        }
    }

    /**
     * 设置录入管理器（在 [initialize] 之后调用）。
     */
    fun setEnrollmentManager(manager: FaceEnrollmentManager) {
        mEnrollmentManager = manager
    }

    // ============================================================
    // IFaceIDAlgorithm
    // ============================================================

    override fun initialize(context: Context?, config: MutableMap<String, Any>): Boolean {
        Log.i(TAG, "initialize: start")
        if (mInitialized) {
            Log.w(TAG, "initialize: already initialized")
            return true
        }

        return try {
            // 1. 确定模型路径：优先 vendor 目录，fallback 到应用自身
            mModelDir = resolveModelDir(context)
            mAppContext = context
            Log.i(TAG, "initialize: model_dir=$mModelDir")

            // 2. 根据系统属性 algorithm_face_dump_enable 决定是否启用 dump（必须 init 之前调用）
            applyDumpState(mAppContext)

            // 3. 初始化 AAR FaceSDK
            val t0 = System.currentTimeMillis()
            val sdk = FaceSDK.init("$mModelDir/manifest.json")
            val t1 = System.currentTimeMillis()
            Log.i(TAG, "initialize: FaceSDK.init took=${t1 - t0}ms")

            if (sdk == null) {
                Log.e(TAG, "initialize: FaceSDK.init returned null")
                return false
            }

            // 4. 配置启用所有模型
            val t2 = System.currentTimeMillis()
            sdk.configure(FaceFlag.ALL)
            val t3 = System.currentTimeMillis()
            Log.i(TAG, "initialize: configure(ALL) took=${t3 - t2}ms")

            // 5. 加载 DMS 标定配置（loadZoneConfig 从文件路径加载，含内参/外参/正视基准/分区）
            val calibOk = loadCalibration(sdk, context)
            Log.i(TAG, "initialize: loadCalibration=$calibOk")

            mFaceSDK = sdk
            Log.i(TAG, "initialize: success")
            mInitialized = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "initialize: failed", e)
            false
        }
    }

    override fun processFrame(
        frameData: ByteArray?,
        width: Int,
        height: Int,
        format: Int
    ): IFaceIDAlgorithm.FaceIDResult {
        val sdk = mFaceSDK
        if (!mInitialized || sdk == null) {
            Log.w(TAG, "processFrame: not initialized")
            return IFaceIDAlgorithm.FaceIDResult()
        }

        if (frameData == null) {
            Log.w(TAG, "processFrame: frameData is null")
            return IFaceIDAlgorithm.FaceIDResult()
        }

        return try {
            // FrameProcessor 已裁剪并转为 RGB888
            val image = FaceImage(frameData, width, height, 0, FaceImage.FACE_FMT_RGB)

            val n = sdk.infer(image, mAARResults, MAX_FACES)

            if (n < 0) {
                Log.e(TAG, "processFrame: infer error=$n")
                return IFaceIDAlgorithm.FaceIDResult()
            }

            // 将检测结果坐标从裁剪空间修正回原图空间
            if ((mCropOffsetX != 0 || mCropOffsetY != 0) && n > 0 && n <= MAX_FACES) {
                for (i in 0 until n) {
                    val r = mAARResults[i] ?: continue
                    if (r.box != null && r.box.size >= 4) {
                        val b = r.box
                        r.box = floatArrayOf(b[0] + mCropOffsetX, b[1] + mCropOffsetY,
                                             b[2] + mCropOffsetX, b[3] + mCropOffsetY)
                    }
                    // 5 关键点
                    if (r.keypoints != null) {
                        val kp = r.keypoints
                        for (p in 0 until 5) {
                            kp[p] = floatArrayOf(kp[p][0] + mCropOffsetX, kp[p][1] + mCropOffsetY)
                        }
                    }
                    // 106 密集地标
                    if (r.landmarks != null) {
                        val lm = r.landmarks
                        for (p in 0 until 106) {
                            lm[p] = floatArrayOf(lm[p][0] + mCropOffsetX, lm[p][1] + mCropOffsetY)
                        }
                    }
                }
            }

            if (n > 0 && n <= MAX_FACES) {
                val r = mAARResults[0]!!

                val faceRect = RectF(r.box[0], r.box[1], r.box[2], r.box[3])
                val confidence = r.score.coerceIn(0f, 1f)

                var faceId = "detected"
                var isNewEnroll = false
                val enrollMgr = mEnrollmentManager
                val emb = r.embedding
                val faceSize = maxOf(r.box[2] - r.box[0], r.box[3] - r.box[1])
                if (enrollMgr != null && emb != null && emb.size == 512 &&
                        faceSize >= MIN_FACE_SIZE) {
                    val result = enrollMgr.recognize(emb, r.score, r.liveness)
                    if (result.name != null) {
                        faceId = result.name
                        isNewEnroll = result.isNewEnroll
                    }
                } else {
                    faceId = when {
                        r.liveness < 0f -> "detected"
                        r.liveness > 0.5f -> "detected"
                        else -> "spoof"
                    }
                }

                Log.i(TAG, "faceId=$faceId, conf=${String.format("%.1f", confidence * 100)}%" +
                        if (isNewEnroll) " [NEW]" else "")

                // 转换 5 关键点（float[5][2] → List<PointF>）
                val kpsList = r.keypoints?.let { arr ->
                    if (arr.size >= 5) {
                        (0 until 5).map { PointF(arr[it][0], arr[it][1]) }
                    } else null
                }

                // 转换 106 密集地标（float[106][2] → List<PointF>）
                val lmList = r.landmarks?.let { arr ->
                    if (arr.size >= 106) {
                        (0 until 106).map { PointF(arr[it][0], arr[it][1]) }
                    } else null
                }

                // 眼睛睁闭 / 嘴巴开合判定（阶段四接入）：
                // 106 点（Array<FloatArray>[106][2]）展平为 FloatArray(212) 供 Estimator 使用，
                // 得到连续开合度后喂给状态防抖器，输出稳定基础状态。
                val flatLandmarks: FloatArray? = r.landmarks?.let { arr ->
                    if (arr.size >= 106) {
                        val flat = FloatArray(106 * 2)
                        for (p in 0 until 106) {
                            flat[p * 2] = arr[p][0]
                            flat[p * 2 + 1] = arr[p][1]
                        }
                        flat
                    } else null
                }
                val eyeEst = flatLandmarks?.let { mEyeMouthEstimator.estimate(it) }
                val eyeOpenRatio = eyeEst?.eyeOpenRatio ?: 1f
                val mouthOpenRatio = eyeEst?.mouthOpenRatio ?: 0f
                // 动态校准：更新基准，换算当前滞回阈值，喂给防抖器
                val calibrated = mEyeMouthCalibrator.update(eyeOpenRatio, mouthOpenRatio)
                mEyeMouthState.update(true, eyeOpenRatio, mouthOpenRatio, calibrated)
                val eyeOpen = !mEyeMouthState.isEyeClosed()
                val mouthOpen = mEyeMouthState.isMouthOpen()

                IFaceIDAlgorithm.FaceIDResult(
                    faceId = faceId,
                    confidence = confidence,
                    faceRect = faceRect,
                    // processedData 不再携带帧数据（渲染层不消费，避免每帧大数组拷贝）
                    isNewEnrollment = isNewEnroll,
                    keypoints = kpsList,
                    landmarks = lmList,
                    headposePitch = r.headPitch,
                    headposeYaw = r.headYaw,
                    headposeRoll = r.headRoll,
                    gazeValid = r.gazeValid,
                    gazeYaw = r.gazeYaw,
                    gazePitch = r.gazePitch,
                    gazeDistracted = r.gazeDistracted,
                    gazeCalibrated = r.gazeCalibrated,
                    distractionScore = r.distractionScore,
                    distractionHpScore = r.distractionHpScore,
                    distractionGazeScore = r.distractionGazeScore,
                    zoneId = r.zoneId,
                    zoneConfidence = r.zoneConfidence,
                    eyeOpen = eyeOpen,
                    mouthOpen = mouthOpen
                )
            } else {
                if (n == 0) Log.i(TAG, "  no face detected")
                // 无人脸：重置眼嘴状态防抖器，输出默认（睁眼/闭嘴）
                mEyeMouthState.update(false, 1f, 0f)
                IFaceIDAlgorithm.FaceIDResult()
            }
        } catch (e: Exception) {
            Log.e(TAG, "processFrame: failed", e)
            IFaceIDAlgorithm.FaceIDResult()
        }
    }

    override fun release() {
        Log.i(TAG, "release: start, sdk=$mFaceSDK, initialized=$mInitialized")
        mFaceSDK?.destroy()
        mFaceSDK = null
        mInitialized = false

        // 关闭 dump 后台线程
        mDumpExecutor.shutdownNow()

        Log.i(TAG, "release: done")
    }

    /**
     * 驾驶门开关信号回调（FACEP-010 §3.7.6）：门开时触发眼/嘴阈值校准复位，
     * 提示可能换驾驶员，清空当前基准进入重校窗口，重新采集新驾驶员基准。
     *
     * 由外部（门信号源 → 总线 → 处理器）在检测到驾驶门打开时调用。
     */
    fun onDoorOpened() {
        Log.i(TAG, "onDoorOpened: door open, reset eye/mouth calibrator")
        mEyeMouthCalibrator.reset()
        mEyeMouthState.reset()
    }

    // ============================================================
    // 模型管理
    // ============================================================

    /**
     * 解析模型目录：优先使用 vendor 目录，fallback 到应用自身。
     */
    private fun resolveModelDir(context: Context?): String {
        val vendorDir = File(VENDOR_MODEL_DIR)
        if (vendorDir.exists()) {
            val hasAllFiles = REQUIRED_MODEL_FILES.all { file ->
                File(vendorDir, file).exists()
            }
            if (hasAllFiles) {
                Log.i(TAG, "using vendor model dir: $VENDOR_MODEL_DIR")
                return VENDOR_MODEL_DIR
            }
        }
        val appDir = extractModels(context)
        patchManifestForAppDir(appDir)
        Log.i(TAG, "using app model dir: $appDir")
        return appDir
    }

    private fun patchManifestForAppDir(appDir: String) {
        val manifestFile = File(appDir, "manifest.json")
        if (!manifestFile.exists()) return
        try {
            val content = manifestFile.readText()
            val patched = content.replace("/vendor/etc/faceid/", "$appDir/")
            if (patched != content) {
                manifestFile.writeText(patched)
            }
        } catch (_: Exception) { }
    }

    private fun extractModels(context: Context?): String {
        if (context == null) {
            Log.w(TAG, "extractModels: context is null, using default: $DEFAULT_MODEL_DIR")
            return DEFAULT_MODEL_DIR
        }
        val dir = File(context.filesDir, MODEL_ASSET_PATH)
        if (dir.exists()) {
            val existing = dir.listFiles() ?: emptyArray()
            val dlcCount = existing.count { it.name.endsWith(".dlc") }
            val hasManifest = existing.any { it.name == "manifest.json" }
            if (dlcCount > 0 && hasManifest) {
                return dir.absolutePath
            }
            Log.w(TAG, "extractModels: incomplete, re-extracting...")
            dir.deleteRecursively()
            dir.mkdirs()
        }
        dir.mkdirs()
        val assetManager = context.assets
        try {
            val allAssets = assetManager.list(MODEL_ASSET_PATH) ?: emptyArray()
            val modelFiles = allAssets.filter {
                it.endsWith(".dlc") || it == "manifest.json" || it == "dms_calibration.json"
            }
            for (file in modelFiles) {
                val outFile = File(dir, file)
                assetManager.open("$MODEL_ASSET_PATH/$file").use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        } catch (_: Exception) { }
        return dir.absolutePath
    }

    // ============================================================
    // 格式转换：UYVY → RGB888 / NV21
    // ============================================================

    /**
     * UYVY（YUV422 交错）→ NV21（YUV420 半平面）转换（保留，旧 AAR 兼容用）。
     *
     * UYVY 排列：U0 Y0 V0 Y1 | U2 Y2 V2 Y3 | ...
     * NV21 排列：Y0 Y1 Y2 ... (w*h) | V0 U0 V1 U1 ... (w*h/2)
     */
    private fun uyvyToNv21(uyvy: ByteArray, w: Int, h: Int): ByteArray {
        val ySize = w * h
        val uvSize = w * h / 2
        val nv21 = ByteArray(ySize + uvSize)

        var yIdx = 0
        // Y 分量：UYVY 中每 4 bytes 包含 2 个 Y（位置 1, 3）
        for (i in uyvy.indices step 2) {
            nv21[yIdx++] = uyvy[i + 1]  // Y0
            if (yIdx < ySize) {
                nv21[yIdx++] = uyvy[i + 3]  // Y1（如果有）
            }
        }

        // UV 分量：每 2×2 块取一组 UV（交错 UYVY 中 U 在 0, V 在 2）
        var uvIdx = ySize
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                val srcPos = (row * 2 * w + col * 2) * 2
                nv21[uvIdx++] = uyvy[srcPos + 2]  // V
                nv21[uvIdx++] = uyvy[srcPos]      // U
            }
        }
        return nv21
    }

    // ============================================================
    // Companion
    // ============================================================

    companion object {
        private const val MAX_FACES = 10
        private const val DEFAULT_MODEL_DIR = "/data/faceid/models"
        private const val MODEL_ASSET_PATH = "models"

        /** 录入所需的最小人脸像素尺寸（RECOG 需要足够大的对齐人脸）。 */
        private const val MIN_FACE_SIZE = 200
    }

    /**
     * 比对两个512-D特征向量的余弦相似度。
     * 线程安全。
     */
    fun compare(emb1: FloatArray, emb2: FloatArray): Float {
        return FaceSDK.compare(emb1, emb2)
    }

    /**
     * 缓存最近一帧原始 UYVY 数据，并以帧驱动方式检测系统属性变化。
     * 由 FrameProcessor 在收到原始帧时调用：
     * - 每次缓存当前帧（手动触发 [triggerManualDump] 时保存此帧）
     * - 顺带检查 algorithm_face_dump_enable 是否变化，变化则重新应用 dump 状态
     */
    override fun dumpOriginalFrame(uyvyData: ByteArray, width: Int, height: Int) {
        synchronized(this) {
            mLatestFrame = uyvyData
            mLatestW = width
            mLatestH = height
        }
        checkDumpPropChange()
    }

    /**
     * dump 是否可用（系统属性 algorithm_face_dump_enable 已启用）。
     */
    fun isDumpAvailable(): Boolean = mDumpDir != null

    /**
     * 手动触发 dump：后台线程保存最近一帧原始图像为 PNG。
     * 文件名 dumpOrigin{index}.png，index 每次触发递增。
     * 完成后通过 [onResult] 回调（在主线程执行）。
     *
     * @param onResult 完成回调，参数为是否保存成功。
     */
    fun triggerManualDump(onResult: ((Boolean) -> Unit)? = null) {
        val dir = mDumpDir
        val frame: ByteArray
        val w: Int; val h: Int
        if (dir == null) {
            onResult?.invoke(false)
            return
        }
        synchronized(this) {
            frame = mLatestFrame ?: run {
                onResult?.invoke(false)
                return
            }
            w = mLatestW; h = mLatestH
        }

        // 在后台线程执行转换 + 压缩，避免阻塞 UI 线程
        mDumpExecutor.execute {
            val ok = try {
                val index = mDumpFrameCount++
                val rgb = uyvyToRgb(frame, w, h)
                savePng(File(dir, "dumpOrigin$index.png"), rgb, w, h)
                Log.i(TAG, "triggerManualDump: saved dumpOrigin$index.png (${w}x${h})")
                true
            } catch (e: Exception) {
                Log.e(TAG, "triggerManualDump: failed", e)
                false
            }
            mMainHandler.post { onResult?.invoke(ok) }
        }
    }

    /**
     * 解析 dump 源目录（filesDir/debugDump）。
     * 独立于 mDumpDir（mDumpDir 受属性开关控制），保证 clear/move 在属性关闭时也能操作已存在的 dump 文件。
     */
    private fun resolveDumpSourceDir(): File? {
        val ctx = mAppContext ?: return null
        return File(ctx.filesDir, "debugDump")
    }

    /**
     * 加载 DMS 标定配置，对齐 C 侧 CLI 流程：
     * 1. loadZoneConfig(path)：加载统一配置（内参+外参+正视基准+ADDW 分区）
     * 2. setCameraIntrinsic(...)：设置相机内参，启用 sphere gaze（视线球面）
     * 3. enableCamTransform(pitch,yaw,roll)：启用相机变换
     * 4. setForwardReference(...)：设置正视基准（可选）
     */
    private fun loadCalibration(sdk: FaceSDK, context: Context?): Boolean {
        return try {
            val calibPath = resolveCalibrationPath(context)
            if (calibPath == null || !File(calibPath).exists()) {
                Log.w(TAG, "loadCalibration: dms_calibration.json not found")
                false
            } else {
                val ok = sdk.loadZoneConfig(calibPath)
                Log.i(TAG, "loadCalibration: loadZoneConfig($calibPath)=$ok")
                if (ok) {
                    applyCameraCalibration(sdk, calibPath)
                }
                ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadCalibration: failed", e)
            false
        }
    }

    /**
     * 应用相机标定（内参 + 外参 + 正视基准），对齐 C 侧 CLI。
     * 相机内参是启用 sphere gaze 的前提，必须设置否则视线数据不生效。
     */
    private fun applyCameraCalibration(sdk: FaceSDK, calibPath: String) {
        try {
            val json = org.json.JSONObject(File(calibPath).readText())

            // 1. 相机内参（enable sphere gaze 的关键）
            val intrinsic = json.optJSONObject("camera_intrinsic")
            if (intrinsic != null) {
                val fx = intrinsic.optDouble("fx", 0.0).toFloat()
                val fy = intrinsic.optDouble("fy", 0.0).toFloat()
                val cx = intrinsic.optDouble("cx", 0.0).toFloat()
                val cy = intrinsic.optDouble("cy", 0.0).toFloat()
                val k1 = intrinsic.optDouble("k1", 0.0).toFloat()
                val k2 = intrinsic.optDouble("k2", 0.0).toFloat()
                val p1 = intrinsic.optDouble("p1", 0.0).toFloat()
                val p2 = intrinsic.optDouble("p2", 0.0).toFloat()
                val w = intrinsic.optInt("width", 0)
                val h = intrinsic.optInt("height", 0)
                val ok = sdk.setCameraIntrinsic(fx, fy, cx, cy, k1, k2, p1, p2, w, h)
                Log.i(TAG, "setCameraIntrinsic(fx=$fx fy=$fy cx=$cx cy=$cy k1=$k1 k2=$k2 p1=$p1 p2=$p2 ${w}x${h})=$ok")
            }

            // 2. 相机外参（fwd_pitch/yaw/roll，可选 t_cw）
            val extrinsic = json.optJSONObject("camera_extrinsic")
            if (extrinsic != null) {
                val fwdPitch = extrinsic.optDouble("fwd_pitch", 0.0).toFloat()
                val fwdYaw = extrinsic.optDouble("fwd_yaw", 0.0).toFloat()
                val fwdRoll = extrinsic.optDouble("fwd_roll", 0.0).toFloat()
                val tArr = extrinsic.optJSONArray("t_cw")
                if (tArr != null && tArr.length() >= 3) {
                    val tCw = floatArrayOf(
                        tArr.getDouble(0).toFloat(),
                        tArr.getDouble(1).toFloat(),
                        tArr.getDouble(2).toFloat()
                    )
                    val ok = sdk.setCameraExtrinsic(fwdPitch, fwdYaw, fwdRoll, tCw)
                    Log.i(TAG, "setCameraExtrinsic(pitch=$fwdPitch yaw=$fwdYaw roll=$fwdRoll t=$tCw.contentToString())=$ok")
                } else {
                    val ok = sdk.enableCamTransform(fwdPitch, fwdYaw, fwdRoll)
                    Log.i(TAG, "enableCamTransform(pitch=$fwdPitch yaw=$fwdYaw roll=$fwdRoll)=$ok")
                }
            }

            // 3. 正视基准（可选）
            val fwdRef = json.optJSONObject("forward_reference")
            if (fwdRef != null) {
                val sy = fwdRef.optDouble("sphere_yaw", 0.0).toFloat()
                val sp = fwdRef.optDouble("sphere_pitch", 0.0).toFloat()
                val ok = sdk.setForwardReference(sy, sp)
                Log.i(TAG, "setForwardReference(yaw=$sy pitch=$sp)=$ok")
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyCameraCalibration: failed", e)
        }
    }

    /**
     * 解析 dms_calibration.json 路径：优先模型目录（vendor/manifest 所在目录），
     * 若不在模型目录则尝试从应用 assets/models 解压到 filesDir。
     */
    private fun resolveCalibrationPath(context: Context?): String? {
        // 1. 模型目录（vendor 或 fallback 解压目录）
        val inModelDir = File(mModelDir, "dms_calibration.json")
        if (inModelDir.exists()) return inModelDir.absolutePath

        // 2. 从 assets/models 解压到 filesDir（兜底）
        val ctx = context ?: return null
        val destDir = File(ctx.filesDir, "dms_calibration")
        val dest = File(destDir, "dms_calibration.json")
        return try {
            if (!dest.exists()) {
                destDir.mkdirs()
                ctx.assets.open("models/dms_calibration.json").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            dest.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "resolveCalibrationPath: assets extract failed", e)
            null
        }
    }

    /**
     * 清除 debugDump 文件夹内容，并删除 /sdcard/debugDmsDump 文件夹。
     * 不受系统属性管控，始终操作固定目录。在后台线程执行，完成后回调（主线程）。
     */
    fun clearDumpDirs(onResult: ((Boolean) -> Unit)? = null) {
        mDumpExecutor.execute {
            val ok = try {
                // 1. 清空 debugDump 目录内容
                resolveDumpSourceDir()?.listFiles()?.forEach { it.delete() }
                // 2. 删除 /sdcard/debugDmsDump 文件夹（递归）
                val sdcardDump = File(SDCARD_DUMP_DIR)
                if (sdcardDump.exists()) {
                    sdcardDump.deleteRecursively()
                }
                mDumpFrameCount = 0
                Log.i(TAG, "clearDumpDirs: cleared debugDump & removed $SDCARD_DUMP_DIR")
                true
            } catch (e: Exception) {
                Log.e(TAG, "clearDumpDirs: failed", e)
                false
            }
            mMainHandler.post { onResult?.invoke(ok) }
        }
    }

    /**
     * 将 debugDump 中的 png 图像移动到 /sdcard/debugDmsDump 文件夹。
     * 无该文件夹则创建。不受系统属性管控，始终操作固定目录。
     * 在后台线程执行，完成后回调（主线程）。
     */
    fun moveDumpPngToSdcard(onResult: ((Boolean) -> Unit)? = null) {
        mDumpExecutor.execute {
            val ok = try {
                val srcDir = resolveDumpSourceDir()
                if (srcDir == null || !srcDir.exists()) {
                    Log.w(TAG, "moveDumpPngToSdcard: dump dir not found")
                    false
                } else {
                    val dstDir = File(SDCARD_DUMP_DIR)
                    if (!dstDir.exists()) {
                        dstDir.mkdirs()
                    }
                    var moved = 0
                    srcDir.listFiles()?.forEach { f ->
                        if (f.isFile && f.name.endsWith(".png", ignoreCase = true)) {
                            val dest = File(dstDir, f.name)
                            // 目标已存在则先删除，再移动（覆盖）
                            if (dest.exists()) dest.delete()
                            if (f.renameTo(dest)) moved++
                        }
                    }
                    Log.i(TAG, "moveDumpPngToSdcard: moved $moved png to $SDCARD_DUMP_DIR")
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "moveDumpPngToSdcard: failed", e)
                false
            }
            mMainHandler.post { onResult?.invoke(ok) }
        }
    }

    /**
     * UYVY 帧数据 → RGB888（参照 FrameProcessor.cropFrame 的转换公式）。
     */
    private fun uyvyToRgb(data: ByteArray, width: Int, height: Int): ByteArray {
        val rgb = ByteArray(width * height * 3)
        var dstIdx = 0
        for (row in 0 until height) {
            var srcCol = 0
            for (col in 0 until width step 2) {
                val srcPos = row * width * 2 + srcCol * 2
                val u = data[srcPos].toInt() and 0xFF
                val y0 = data[srcPos + 1].toInt() and 0xFF
                val v = data[srcPos + 2].toInt() and 0xFF
                val y1 = data[srcPos + 3].toInt() and 0xFF
                srcCol += 2

                fun clamp(v: Int): Byte = when { v < 0 -> 0; v > 255 -> 255; else -> v }.toByte()
                val c0 = y0 - 16; val d = u - 128; val e = v - 128
                rgb[dstIdx++] = clamp((298 * c0 + 409 * e + 128) shr 8)
                rgb[dstIdx++] = clamp((298 * c0 - 100 * d - 208 * e + 128) shr 8)
                rgb[dstIdx++] = clamp((298 * c0 + 516 * d + 128) shr 8)

                val c1 = y1 - 16
                rgb[dstIdx++] = clamp((298 * c1 + 409 * e + 128) shr 8)
                rgb[dstIdx++] = clamp((298 * c1 - 100 * d - 208 * e + 128) shr 8)
                rgb[dstIdx++] = clamp((298 * c1 + 516 * d + 128) shr 8)
            }
        }
        return rgb
    }

    /**
     * 将 RGB888 帧数据保存为 PNG。
     */
    private fun savePng(file: File, data: ByteArray, width: Int, height: Int) {
        try {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                // RGB888 → ARGB_8888，逐行拷贝
                val pixels = IntArray(width * height)
                var i = 0
                for (p in 0 until width * height) {
                    val r = data[i].toInt() and 0xFF
                    val g = data[i + 1].toInt() and 0xFF
                    val b = data[i + 2].toInt() and 0xFF
                    pixels[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    i += 3
                }
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
            file.parentFile?.mkdirs()
            // 同名文件存在时覆盖（进程重启后 index 会重新从 0 开始）
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "savePng: failed to delete existing ${file.name}")
            }
            val os = file.outputStream()
            try {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
            } finally {
                os.close()
            }
            bmp.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "savePng: failed", e)
        }
    }

    /**
     * 读取系统属性 algorithm_face_dump_enable 的原始值（线程安全）。
     */
    private fun readDumpProp(): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java, String::class.java)
            method.invoke(null, PROP_DUMP_ENABLE, "disable") as String
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 属性值是否为 enable（"1" 或 "true"，忽略大小写）。
     */
    private fun isDumpPropEnabled(value: String?): Boolean {
        return value.equals("1", ignoreCase = true) ||
            value.equals("true", ignoreCase = true)
    }


    /**
     * 根据当前系统属性应用 dump 状态：
     * - enable：创建 dump 目录，设置 mDumpDir 并传给 FaceSDK
     * - disable：清空 mDumpDir，并置空 FaceSDK dump 路径
     */
    private fun applyDumpState(context: Context?) {
        val value = readDumpProp()
        mLastDumpPropValue = value
        if (isDumpPropEnabled(value)) {
            val dumpDir = File(context?.filesDir, "debugDump").apply { mkdirs() }
            mDumpDir = dumpDir.absolutePath
            FaceSDK.setDebugDumpPath(mDumpDir)
            Log.i(TAG, "applyDumpState: enabled, dumpDir=$mDumpDir")
        } else {
            mDumpDir = null
            FaceSDK.setDebugDumpPath("")
            Log.i(TAG, "applyDumpState: disabled")
        }
    }

    /**
     * 帧驱动检测系统属性变化：由 [dumpOriginalFrame] 在收到每帧时调用。
     * 对比最近值，若 algorithm_face_dump_enable 变化则重新应用 dump 状态
     * （在 GL/帧线程执行，属性读取开销极小，可忽略）。
     */
    private fun checkDumpPropChange() {
        val current = readDumpProp()
        val last = mLastDumpPropValue
        if (current != last) {
            Log.i(TAG, "prop change detected: '$last' -> '$current'")
            applyDumpState(mAppContext)
        }
    }
}
