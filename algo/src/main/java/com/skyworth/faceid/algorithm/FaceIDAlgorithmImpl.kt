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
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    /** 连续 dump 输出模式。 */
    enum class ContinuousDumpMode { PNG, JPEG, VIDEO }

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

    /**
     * 动态配置算法流程（FACEP-011 功能划分）。
     *
     * 按模块裁剪运行的模型：如人脸识别模块只跑 DETECTION|RECOGNITION|LIVENESS|LANDMARK，
     * 不跑 HEADPOSE/GAZE（疲劳/分心用）。在 acquire 时调用，控制每帧推理的模型。
     *
     * @param flag [atlas.face.sdk.FaceFlag] 按位或组合；null 保留当前。
     */
    fun configure(flag: Int) {
        if (mCurrentFlag == flag) return
        val sdk = mFaceSDK ?: return
        try {
            sdk.configure(flag)
            mCurrentFlag = flag
            Log.i(TAG, "configure: flag=$flag")
        } catch (e: Exception) {
            Log.e(TAG, "configure: failed flag=$flag", e)
        }
    }

    /** 当前启用的 FaceFlag。 */
    fun currentFlag(): Int = mCurrentFlag

    /**
     * 切换算法流程并复位眼嘴管线（FACEP-011 功能切换）。
     *
     * 模块切换（如人脸识别 → 疲劳监测）时调用：
     * - [configure] 更新 FaceFlag（控制每帧推理的模型）；
     * - reset 眼嘴状态机与校准器，清除上一模块残留的闭眼计时/校准基准，
     *   确保新模块眼嘴判定从干净状态开始。
     *
     * @param flag [atlas.face.sdk.FaceFlag] 按位或组合。
     */
    fun setFlagAndReset(flag: Int) {
        configure(flag)
        // 复位眼嘴判定（动态校准基准 + 眼睛滞回状态；防抖状态机已去除）
        try {
            mEyeMouthCalibrator.reset()
            mEyeWasOpen = true
            Log.i(TAG, "setFlagAndReset: eye/mouth calibrator reset, flag=$flag")
        } catch (e: Exception) {
            Log.w(TAG, "setFlagAndReset: reset error", e)
        }
    }

    /** 模型文件存储目录。 */
    private var mModelDir: String = ""

    /** 当前启用的 FaceFlag（默认 ALL，模块可裁剪，FACEP-011 功能划分）。 */
    private var mCurrentFlag: Int = atlas.face.sdk.FaceFlag.ALL

    // ============ 帧 dump（调试用，手动触发） ============
    /** 应用 Context（属性变化时重新应用 dump 状态用）。 */
    private var mAppContext: Context? = null
    /** dump 目录（与传给算法的路径一致）。 */
    private var mDumpDir: String? = null
    /** 已 dump 的帧计数（用于生成文件名 index）。 */
    private var mDumpFrameCount = 0
    /** 连续 dump 是否进行中。 */
    @Volatile private var mContinuousActive = false
    /** 连续 dump 定时任务句柄（用于手动停止）。 */
    private var mContinuousFuture: ScheduledFuture<*>? = null
    /** 连续 dump 子目录（debugDump/continuous）。 */
    private var mContinuousDir: File? = null
    /** 连续 dump 保存子目录（采集线程写）。 */
    private var mContinuousSaveDir: File? = null

    /** 最近一帧原始 UYVY 数据缓存（手动触发时保存此帧）。 */
    private var mLatestFrame: ByteArray? = null
    private var mLatestW = 0
    private var mLatestH = 0
    /** dump 后台执行线程。 */
    private val mDumpExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    /** 连续 dump 定时线程（独立于 mDumpExecutor，避免长时间占用阻塞其他 dump）。 */
    private val mContinuousExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()
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
        "pipnet68_int8.dlc",
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

    /** 眼睛/嘴巴阈值动态校准器（维护睁/闭眼、张/闭嘴基准，动态换算阈值）。 */
    private val mEyeMouthCalibrator = EyeMouthCalibrator()

    /** 眼睛滞回状态：上一帧是否睁眼（滞回区间内维持上一状态，防半开合抖动）。 */
    private var mEyeWasOpen = true

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

    /**
     * 已导入人脸数量（识别模块 UI 展示）。
     * 透传 [FaceEnrollmentManager.getCount]；未注入录入管理器时返回 0。
     */
    override fun getEnrolledCount(): Int = mEnrollmentManager?.getCount() ?: 0

    // ============================================================
    // FACEP-012：手动录入 / 人脸管理（透传至 FaceEnrollmentManager）
    // ============================================================

    override fun isEnrolling(): Boolean = mEnrollmentManager?.isEnrolling ?: false

    override fun startManualEnrollment() {
        mEnrollmentManager?.startManualEnrollment()
    }

    override fun stopManualEnrollment() {
        mEnrollmentManager?.stopManualEnrollment()
    }

    override fun onEnrollmentFrame(emb: FloatArray, score: Float): Boolean =
        mEnrollmentManager?.onEnrollmentFrame(emb, score) ?: false

    override fun pendingEmbedding(): FloatArray? = mEnrollmentManager?.pendingEmbedding()

    override fun addEnrolledFace(name: String, emb: FloatArray): Boolean =
        mEnrollmentManager?.addEnrolledFace(name, emb) ?: false

    override fun deleteFace(name: String): Boolean =
        mEnrollmentManager?.deleteFace(name) ?: false

    override fun getEnrolledNames(): Set<String> =
        mEnrollmentManager?.getEnrolledNames() ?: emptySet()

    override fun defaultNameCandidates(): List<String> =
        mEnrollmentManager?.defaultNameCandidates() ?: emptyList()

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

            // 4. 配置启用模型（默认 ALL；可通过 config[KEY_FACE_FLAG] 裁剪，见 FACEP-011 功能划分）
            val t2 = System.currentTimeMillis()
            val flag = config[KEY_FACE_FLAG] as? Int ?: FaceFlag.ALL
            sdk.configure(flag)
            mCurrentFlag = flag
            val t3 = System.currentTimeMillis()
            Log.i(TAG, "initialize: configure($flag) took=${t3 - t2}ms")

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
                    // 68 密集地标
                    if (r.landmarks != null) {
                        val lm = r.landmarks
                        for (p in 0 until 68) {
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
                var enrollmentReady = false
                val enrollMgr = mEnrollmentManager
                val emb = r.embedding
                val faceSize = maxOf(r.box[2] - r.box[0], r.box[3] - r.box[1])
                if (enrollMgr != null && emb != null && emb.size == 512 &&
                        faceSize >= MIN_FACE_SIZE) {
                    if (enrollMgr.isEnrolling) {
                        // FACEP-012：手动录入模式——采集稳定帧，成功后置可命名标记
                        if (enrollMgr.onEnrollmentFrame(emb, r.score)) {
                            enrollmentReady = true
                        }
                        faceId = "enrolling"
                    } else {
                        // FACEP-012：recognize 仅匹配，未命中（库外人脸）→ 显示"未录入人脸"
                        val result = enrollMgr.recognize(emb, r.score, r.liveness)
                        faceId = result.name ?: "unregistered"
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

                // 转换 68 密集地标（float[68][2] → List<PointF>）
                val lmList = r.landmarks?.let { arr ->
                    if (arr.size >= 68) {
                        (0 until 68).map { PointF(arr[it][0], arr[it][1]) }
                    } else null
                }

                // 眼睛睁闭 / 嘴巴开合判定（阶段四接入）：
                // 68 点（Array<FloatArray>[68][2]）展平为 FloatArray(136) 供 Estimator 使用，
                // 得到连续开合度后喂给状态防抖器，输出稳定基础状态。
                val flatLandmarks: FloatArray? = r.landmarks?.let { arr ->
                    if (arr.size >= 68) {
                        val flat = FloatArray(68 * 2)
                        for (p in 0 until 68) {
                            flat[p * 2] = arr[p][0]
                            flat[p * 2 + 1] = arr[p][1]
                        }
                        flat
                    } else null
                }
                val eyeEst = flatLandmarks?.let { mEyeMouthEstimator.estimate(it) }
                // [EYE-CAL] 临时调试：输出每帧 aperture（睑距/脸宽）、EAR 与归一化开合度，用于重新标定阈值（采集后移除）
                if (eyeEst != null) {
                    Log.d(TAG, "[EYE-CAL] aperture=%.4f ear=%.4f faceW=%.1f ratio=%.2f mar=%.4f mouthRatio=%.2f"
                        .format(eyeEst.aperture, eyeEst.ear, eyeEst.faceWidth, eyeEst.eyeOpenRatio, eyeEst.mar, eyeEst.mouthOpenRatio))
                }
                // 有效估计：估计器 valid=true（眼睑区域齐全且几何可算）。缺失/无效帧不更新校准基准，
                // 避免 0 值（缺失哨兵）污染分位数窗口导致闭眼/张嘴判定失效。
                val validEst = eyeEst?.takeIf { it.valid }
                if (validEst != null) {
                    // 动态校准：只跟踪量纲正确、数据真实的帧——
                    // - faceWidth>0：aperture 主路径（睑距/脸宽）；外眼角缺失时估计器回退 EAR，
                    //   量纲不同（≈0.2~0.5 vs aperture 0.02~0.12），混入窗口会拉高高位基准、
                    //   把完全睁眼归一化压低导致误判闭眼，此类帧跳过眼睛轴；
                    // - mar>0：嘴巴区域缺失时估计器置 0（缺失哨兵），跳过避免把低位基准拉向 0。
                    if (validEst.faceWidth > 0f) mEyeMouthCalibrator.updateEye(validEst.aperture)
                    if (validEst.mar > 0f) mEyeMouthCalibrator.updateMouth(validEst.mar)
                }
                val calibrated = mEyeMouthCalibrator.thresholds()
                val eyeOpenRatio = validEst?.let { mEyeMouthCalibrator.normalizeEye(it.aperture) } ?: 1f
                val mouthOpenRatio = validEst?.let { mEyeMouthCalibrator.normalizeMouth(it.mar) } ?: 0f
                // 去除眼嘴防抖（FACEP-015 后疲劳引擎用连续量 eyeOpenRatio/mouthOpenRatio，
                // 布尔仅作渲染标注）：用滞回判定——开合度 ≤0.08 判闭眼、>0.15 判睁眼，
                // 0.08~0.15 之间维持上一状态（防半开合抖动）。mouthOpen 用张嘴上界。
                val eyeOpen = if (eyeOpenRatio <= EYE_CLOSE_RATIO) {
                    false
                } else if (eyeOpenRatio > EYE_OPEN_RATIO) {
                    true
                } else {
                    mEyeWasOpen // 滞回区间：维持上一状态
                }
                mEyeWasOpen = eyeOpen
                val mouthOpen = mouthOpenRatio >= calibrated.mouthOpenRatio  // 开合度高于张嘴上界 → 张嘴

                IFaceIDAlgorithm.FaceIDResult(
                    faceId = faceId,
                    confidence = confidence,
                    faceRect = faceRect,
                    // processedData 不再携带帧数据（渲染层不消费，避免每帧大数组拷贝）
                    isNewEnrollment = isNewEnroll,
                    enrollmentReady = enrollmentReady,
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
                    mouthOpen = mouthOpen,
                    // FACEP-015：透传连续开合度供疲劳判定（打哈欠/闭眼分级）
                    eyeOpenRatio = eyeOpenRatio,
                    mouthOpenRatio = mouthOpenRatio,
                    // 透传 AAR FaceResult 补充返回值（活体/头姿/球面视线/视线关键点/头姿坐标系）
                    liveness = r.liveness,
                    flags = r.flags,
                    headPose6d = r.headPose6d,
                    headValid = r.headValid,
                    sphereYaw = r.sphereYaw,
                    spherePitch = r.spherePitch,
                    area3Hit = r.area3Hit,
                    sphereValid = r.sphereValid,
                    gazeKps = r.gazeKps,
                    headHcRot = r.headHcRot,
                    headHcT = r.headHcT,
                    headHwRot = r.headHwRot,
                    headHwT = r.headHwT,
                    // 车辆系头朝向（headDir 单位向量 + yaw/pitch 角，需 flags&HEADFRAME 且 headDirValid==1）
                    headDir = r.headDir,
                    headDirYaw = r.headDirYaw,
                    headDirPitch = r.headDirPitch,
                    headDirValid = r.headDirValid
                )
            } else {
                if (n == 0) Log.i(TAG, "  no face detected")
                // 无人脸：输出默认（睁眼/闭嘴），眼睛滞回状态复位为睁眼
                mEyeWasOpen = true
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
        mEyeWasOpen = true
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

        /** 眼睛滞回阈值：开合度 ≤ 此值判闭眼（与 FatigueRule.EYE_CLOSE_RATIO 一致）。 */
        private const val EYE_CLOSE_RATIO = 0.08f
        /** 眼睛滞回阈值：开合度 > 此值判睁眼；[EYE_CLOSE_RATIO]~此值之间维持上一状态（0.08~0.15 滞回）。 */
        private const val EYE_OPEN_RATIO = 0.15f

        /** 录入所需的最小人脸像素尺寸（RECOG 需要足够大的对齐人脸）。 */
        private const val MIN_FACE_SIZE = 200

        /** initialize config 中 FaceFlag 的 key（FACEP-011 功能划分）。 */
        const val KEY_FACE_FLAG = "face_flag"
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
     * 连续 dump 是否进行中（用于 UI 置灰按钮）。
     */
    fun isContinuousDumpActive(): Boolean = mContinuousActive

    /** 连续 dump 当前已采样帧数。 */
    fun getContinuousSampledCount(): Int = sampledCount.get()

    /** 连续 dump 实际保存成功帧数（用于 UI 显示剩余张数倒计时，与磁盘文件数一致）。 */
    fun getContinuousSavedCount(): Int = mContinuousSavedIndex.get()

    /**
     * 启动连续 dump：定时（[intervalMs] 间隔）从最近一帧原始 UYVY 采样保存为 PNG，
     * 共 [totalFrames] 帧，完成后回调（主线程）。
     *
     * 目录结构：`debugDump/continuous/dumpOrigin{idx}.png`，
     * 保存 index 从 0 开始（idx 3 位），以"实际保存成功帧数"为据，编号连续。
     *
     * @param totalFrames 采样帧数上限（默认 300，采满即自然结束，不设固定时长）
     * @param intervalMs  采样间隔（默认 200ms，最快 5fps）
     * @param onResult    完成回调（主线程），参数为采样完成帧数
     * @return 是否成功启动（进行中或 dump 不可用返回 false）
     */
    fun startContinuousDump(
        totalFrames: Int = 300,
        intervalMs: Long = 200,
        mode: ContinuousDumpMode = ContinuousDumpMode.PNG,
        onResult: ((Int) -> Unit)? = null
    ): Boolean {
        if (mContinuousActive) {
            Log.w(TAG, "startContinuousDump: already active")
            return false
        }
        val sourceDir = resolveDumpSourceDir()
        if (sourceDir == null) {
            Log.w(TAG, "startContinuousDump: dump source dir unavailable")
            onResult?.invoke(0)
            return false
        }
        // 连续 dump 固定目录 debugDump/continuous/（不按时间区分子目录）。
        // 每次启动保存 index 从 0 开始，写 dumpOrigin{idx}.png 同名覆盖，
        // 因此多次连续 dump 只保留最新一轮的帧（后覆盖前），不累积。
        val dir = File(sourceDir, "continuous")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "startContinuousDump: failed to create dir $dir")
            onResult?.invoke(0)
            return false
        }

        mContinuousDir = dir
        mContinuousSaveDir = dir
        mContinuousMode = mode
        // 关键：重置采样计数器。sampledCount 是实例字段，若上一次未归零，
        // 第二次启动 getAndIncrement() 从上次的 totalFrames 继续，第一次采样即 seq>=totalFrames
        // 立刻触发 finish，导致"第二次点击连续 dump 立刻完成"而非重新开始。
        sampledCount.set(0)
        mContinuousSavedIndex.set(0)
        mContinuousActive = true
        onResultContinuous = onResult   // finish 时消费一次

        // VIDEO 模式：不在此处初始化编码器（帧尺寸未知，见 encodeVideoFrame 首帧延迟初始化）
        Log.i(TAG, "startContinuousDump: dir=$dir total=$totalFrames interval=${intervalMs}ms mode=$mode")

        // 保存驱动采样（保帧率）：采一帧 → 保存一帧 → 完成后再调度下一次采样。
        // 实际节奏 = max(保存耗时, intervalMs)，保证每帧都能成功写盘、不丢帧，最终存满 totalFrames。
        // 保存失败也不中断，单帧异常不终止整轮（否则按钮卡在"连续DUMP中…"无法恢复）。
        mContinuousFuture = mContinuousExecutor.schedule({
            continuousSampleAndSave(totalFrames, intervalMs)
        }, 0, TimeUnit.MILLISECONDS)
        return true
    }

    /**
     * 连续 dump 单次"采样+保存"步骤（在 mContinuousExecutor 线程执行）。
     * 采满 totalFrames 即结束并回调；否则延迟 [intervalMs] 后再采下一帧，
     * 保证相邻两帧至少间隔 intervalMs（最快 5fps），且保存完成才继续。
     */
    private fun continuousSampleAndSave(totalFrames: Int, intervalMs: Long) {
        if (!mContinuousActive) return
        try {
            val seq = sampledCount.getAndIncrement()
            if (seq >= totalFrames) {
                // 已采满，停止并回调（按钮恢复）
                finishContinuousDump(seq)
                return
            }
            // 采样最近一帧（UYVY 拷贝，避免被主线程后续覆盖）
            val frame: ByteArray?; val w: Int; val h: Int
            synchronized(this@FaceIDAlgorithmImpl) {
                frame = mLatestFrame
                w = mLatestW; h = mLatestH
            }
            if (frame != null && w > 0 && h > 0) {
                val copy = frame.copyOf()
                val dir = mContinuousSaveDir
                if (dir != null) {
                    // 同步保存本帧（保存多快就多快，不丢帧），成功后才 +index 并调度下一帧
                    val idx = mContinuousSavedIndex.get()
                    when (mContinuousMode) {
                        ContinuousDumpMode.PNG ->
                            savePngScaled(File(dir, "dumpOrigin%03d.png".format(Locale.US, idx)), copy, w, h)
                        ContinuousDumpMode.JPEG ->
                            saveJpegScaled(File(dir, "dumpOrigin%03d.jpg".format(Locale.US, idx)), copy, w, h)
                        ContinuousDumpMode.VIDEO ->
                            encodeVideoFrame(copy, w, h)
                    }
                    mContinuousSavedIndex.incrementAndGet()
                }
            }
            // 保存完成后，延迟 intervalMs 再采下一帧（保证最快不超过 5fps）
            if (mContinuousActive) {
                mContinuousFuture = mContinuousExecutor.schedule({
                    continuousSampleAndSave(totalFrames, intervalMs)
                }, intervalMs, TimeUnit.MILLISECONDS)
            }
        } catch (e: Throwable) {
            // 关键：单帧异常不终止整轮，继续调度下一帧
            Log.e(TAG, "continuousSampleAndSave: frame failed, continue", e)
            if (mContinuousActive) {
                mContinuousFuture = mContinuousExecutor.schedule({
                    continuousSampleAndSave(totalFrames, intervalMs)
                }, intervalMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** 连续 dump 已采样帧数（每次 start 重置）。 */
    private val sampledCount = AtomicInteger(0)

    /**
     * 连续 dump 实际保存成功帧数（每次 start 重置）。
     * 保存线程每成功写盘一帧 +1，文件名 index 以此为据，保证磁盘文件编号连续（0,1,2,...）。
     */
    private val mContinuousSavedIndex = AtomicInteger(0)

    /**
     * 手动停止连续 dump（取消采样）。回调已采到的帧数（主线程）。
     */
    fun stopContinuousDump(onResult: ((Int) -> Unit)? = null) {
        if (!mContinuousActive) return
        mContinuousFuture?.cancel(false)
        mContinuousFuture = null
        val count = sampledCount.get()
        mContinuousActive = false
        // VIDEO 模式：结束编码并封装 MP4
        if (mContinuousMode == ContinuousDumpMode.VIDEO) {
            finishVideoRecorder()
        }
        // 置空自动完成回调，避免持有已销毁 Activity 的引用造成泄漏
        // （手动停止后不会再走 finishContinuousDump 消费该回调）
        onResultContinuous = null
        Log.i(TAG, "stopContinuousDump: stopped, sampled $count frames")
        mMainHandler.post { onResult?.invoke(count) }
    }

    /** 完成连续 dump（达到帧数上限自动调用）：停止采样并回调（后台保存继续）。 */
    private fun finishContinuousDump(sampled: Int) {
        mContinuousFuture?.cancel(false)
        mContinuousFuture = null
        mContinuousActive = false
        // VIDEO 模式：结束编码并封装 MP4（同步完成后再回调）
        if (mContinuousMode == ContinuousDumpMode.VIDEO) {
            finishVideoRecorder()
        }
        // 回调的是"采样完成帧数"（按钮准时恢复）；后台保存队列可能仍在写，属正常。
        Log.i(TAG, "finishContinuousDump: sampling done, sampled $sampled frames")
        // 关键：先取出回调到局部变量，再置 null。若 post 的 lambda 直接捕获字段，
        // 而 post 是异步的，执行时字段已被置 null，导致回调不触发（按钮无法恢复）。
        val cb = onResultContinuous
        onResultContinuous = null
        mMainHandler.post { cb?.invoke(sampled) }
    }

    /** 连续 dump 完成回调（finish 时消费一次）。 */
    private var onResultContinuous: ((Int) -> Unit)? = null

    // ---------- 连续 dump 视频录制（ContinuousDumpMode.VIDEO） ----------
    private var mVideoEncoder: MediaCodec? = null
    private var mVideoMuxer: MediaMuxer? = null
    private var mVideoMuxerStarted = false
    private var mVideoTrackIndex = -1
    /** 视频起始真实时间（elapsedRealtimeNanos），PTS 用真实经过时间，避免快进。 */
    private var mVideoStartNanos = 0L
    private var mVideoPtsUs = 0L
    /** 视频帧转换复用缓冲（仅尺寸变化时分配，避免每帧 5.4MB 数组引发 GC）。 */
    private var mVideoRgbBuf: ByteArray? = null
    private var mVideoRgbSize = 0
    private var mVideoNv12Buf: ByteArray? = null
    private var mVideoNv12Size = 0
    /** 视频输出 MP4 文件。 */
    private var mVideoOutputFile: File? = null
    /** 当前连续 dump 模式（默认 PNG，保持向后兼容）。 */
    @Volatile private var mContinuousMode = ContinuousDumpMode.PNG

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
                // 0. 若连续 dump 进行中，先停止（避免边删边写）
                if (mContinuousActive) {
                    mContinuousFuture?.cancel(false)
                    mContinuousFuture = null
                    mContinuousActive = false
                }
                // 1. 彻底删除应用内 debugDump 目录（含 continuous/{时间戳} 子目录）
                //    用 deleteRecursively 而非逐项 delete（后者删不掉非空子目录）
                resolveDumpSourceDir()?.let { if (it.exists()) it.deleteRecursively() }
                // 2. 删除 /sdcard/debugDmsDump 文件夹（递归）
                val sdcardDump = File(SDCARD_DUMP_DIR)
                if (sdcardDump.exists()) {
                    sdcardDump.deleteRecursively()
                }
                mDumpFrameCount = 0
                Log.i(TAG, "clearDumpDirs: removed debugDump & $SDCARD_DUMP_DIR")
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
                    // 递归复制整个源目录结构到目标目录（保留子目录，如 continuous/{时间戳}/）。
                    // 用复制而非 renameTo：跨文件系统 rename 可能失败，且需保留源文件。
                    val copied = copyTree(srcDir, dstDir)
                    Log.i(TAG, "moveDumpPngToSdcard: copied $copied files to $SDCARD_DUMP_DIR")
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
     * 递归复制目录树：把 [src] 的内容原样复制到 [dst]（保留相对目录结构）。
     * @return 复制的文件数（含子目录下的）。
     */
    private fun copyTree(src: File, dst: File): Int {
        var copied = 0
        src.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                val subDst = File(dst, f.name)
                if (!subDst.exists()) subDst.mkdirs()
                copied += copyTree(f, subDst)
            } else {
                val dest = File(dst, f.name)
                try {
                    dest.parentFile?.mkdirs()
                    // 目标已存在则覆盖
                    if (dest.exists()) dest.delete()
                    f.copyTo(dest, overwrite = true)
                    copied++
                } catch (e: Exception) {
                    Log.e(TAG, "copyTree: failed to copy ${f.name}", e)
                }
            }
        }
        return copied
    }

    /**
     * UYVY 帧数据 → RGB888（参照 FrameProcessor.cropFrame 的转换公式）。
     * 可传入 [dst] 复用缓冲（大小须 ≥ width*height*3），避免高频路径每帧分配大数组。
     */
    private fun uyvyToRgb(data: ByteArray, width: Int, height: Int, dst: ByteArray? = null): ByteArray {
        val rgb = dst ?: ByteArray(width * height * 3)
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
     * 连续 dump 保存：UYVY → RGB → PNG，**原尺寸**（1600×1300 等，不缩图）。
     *
     * 由保存驱动采样调用（采一帧存一帧），保存多快采多快，不丢帧，最终存满 totalFrames。
     */
    private fun savePngScaled(file: File, data: ByteArray, w: Int, h: Int) {
        try {
            if (w <= 0 || h <= 0) return
            // 原尺寸：UYVY → RGB888 → savePng（全分辨率）
            val rgb = uyvyToRgb(data, w, h)
            savePng(file, rgb, w, h)
        } catch (e: Exception) {
            Log.e(TAG, "savePngScaled: failed", e)
        }
    }

    /**
     * 连续 dump JPEG 模式：UYVY → RGB → JPEG（原尺寸）。
     * 用 Bitmap.compress(JPEG, 90) 压缩，文件比 PNG 小、编码更快。
     */
    private fun saveJpegScaled(file: File, data: ByteArray, w: Int, h: Int) {
        try {
            if (w <= 0 || h <= 0) return
            val rgb = uyvyToRgb(data, w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(w * h)
            var i = 0
            for (p in 0 until w * h) {
                val r = rgb[i].toInt() and 0xFF
                val g = rgb[i + 1].toInt() and 0xFF
                val b = rgb[i + 2].toInt() and 0xFF
                pixels[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                i += 3
            }
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            file.parentFile?.mkdirs()
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "saveJpegScaled: failed to delete existing ${file.name}")
            }
            val os = file.outputStream()
            try {
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, os)
            } finally {
                os.close()
            }
            bmp.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "saveJpegScaled: failed", e)
        }
    }

    // ============================================================
    // 连续 dump 视频录制（ContinuousDumpMode.VIDEO）：MediaCodec H.264 + MediaMuxer MP4
    // ============================================================

    /**
     * 初始化 H.264 编码器与 MediaMuxer，输出 [dir]/continuous.mp4。
     * 在 continuousExecutor 线程调用。
     *
     * 宽高用**实际帧尺寸**（[encodeVideoFrame] 首帧时初始化），
     * 避免与 DMS 分辨率不同（1600×1300）的其他摄像头输入尺寸不匹配导致全绿/花屏。
     */
    private fun initVideoRecorder(dir: File, width: Int, height: Int): Boolean {
        try {
            val file = File(dir, "continuous.mp4")
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 10)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mVideoEncoder = codec
            mVideoMuxer = muxer
            mVideoMuxerStarted = false
            mVideoTrackIndex = -1
            mVideoStartNanos = SystemClock.elapsedRealtimeNanos()
            mVideoPtsUs = 0L
            mVideoOutputFile = file
            Log.i(TAG, "initVideoRecorder: $file ${width}x$height")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "initVideoRecorder: failed", e)
            releaseVideoRecorder()
            return false
        }
    }

    /**
     * 视频模式：把一帧 UYVY 编码进 MP4。
     * 流程：UYVY→RGB→NV12 → encoder 输入 → 编码 → muxer 写采样。
     * 编码器在**首帧**用实际帧尺寸初始化（不同摄像头分辨率不同）。
     */
    private fun encodeVideoFrame(uyvy: ByteArray, w: Int, h: Int) {
        // 首帧延迟初始化编码器：用实际帧尺寸，避免尺寸不匹配导致全绿
        if (mVideoEncoder == null) {
            val dir = mContinuousSaveDir
            if (dir == null || !initVideoRecorder(dir, w, h)) {
                Log.e(TAG, "encodeVideoFrame: recorder init failed (${w}x$h)")
                return
            }
        }
        val encoder = mVideoEncoder ?: return
        val muxer = mVideoMuxer ?: return
        try {
            // 复用转换缓冲（仅尺寸变化时分配），避免每帧 5.4MB 数组引发 GC 卡顿
            val rgb = obtainVideoRgb(w, h)
            uyvyToRgb(uyvy, w, h, rgb)
            val nv12 = rgbToNv12(rgb, w, h, obtainVideoNv12(w, h))
            // PTS 用真实经过时间（单调时钟），播放时长与实际采集一致，避免快进
            val pts = ((SystemClock.elapsedRealtimeNanos() - mVideoStartNanos) / 1_000).coerceAtLeast(mVideoPtsUs)
            mVideoPtsUs = pts

            val inputIndex = encoder.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuf = encoder.getInputBuffer(inputIndex) ?: return
                inputBuf.clear()
                inputBuf.put(nv12)
                encoder.queueInputBuffer(inputIndex, 0, nv12.size, pts, 0)
            }
            drainVideoEncoder(encoder, muxer, false)
        } catch (e: Exception) {
            Log.e(TAG, "encodeVideoFrame: failed", e)
        }
    }

    /** 获取复用的 RGB 缓冲（写入并返回；仅尺寸变化时重新分配）。 */
    private fun obtainVideoRgb(w: Int, h: Int): ByteArray {
        val need = w * h * 3
        return if (mVideoRgbBuf == null || mVideoRgbSize != need) {
            ByteArray(need).also { mVideoRgbBuf = it; mVideoRgbSize = need }
        } else mVideoRgbBuf!!
    }

    /** 获取复用的 NV12 缓冲（仅尺寸变化时重新分配）。 */
    private fun obtainVideoNv12(w: Int, h: Int): ByteArray {
        val need = w * h * 3 / 2
        return if (mVideoNv12Buf == null || mVideoNv12Size != need) {
            ByteArray(need).also { mVideoNv12Buf = it; mVideoNv12Size = need }
        } else mVideoNv12Buf!!
    }

    /** 结束视频录制：发送 EOS，编码剩余帧，停止 muxer，释放资源。 */
    private fun finishVideoRecorder() {
        val encoder = mVideoEncoder ?: return
        val muxer = mVideoMuxer ?: return
        try {
            val inputIndex = encoder.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(inputIndex, 0, 0,
                    mVideoPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainVideoEncoder(encoder, muxer, true)
            if (mVideoMuxerStarted) {
                try { muxer.stop() } catch (e: Exception) { Log.w(TAG, "muxer.stop", e) }
            }
            Log.i(TAG, "finishVideoRecorder: ${mVideoOutputFile?.absolutePath} pts=${mVideoPtsUs}us")
        } catch (e: Exception) {
            Log.e(TAG, "finishVideoRecorder: failed", e)
        } finally {
            releaseVideoRecorder()
        }
    }

    /** 从编码器取输出并写入 muxer。 */
    private fun drainVideoEncoder(encoder: MediaCodec, muxer: MediaMuxer, endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!mVideoMuxerStarted) {
                        mVideoTrackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        mVideoMuxerStarted = true
                    }
                }
                outIndex >= 0 -> {
                    val outBuf = encoder.getOutputBuffer(outIndex) ?: continue
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0  // CSD 数据已随 format 传递
                    }
                    if (info.size > 0 && mVideoMuxerStarted) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        muxer.writeSampleData(mVideoTrackIndex, outBuf, info)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** 释放编码器与 muxer。 */
    private fun releaseVideoRecorder() {
        try {
            mVideoEncoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        } catch (e: Exception) { Log.w(TAG, "releaseVideoRecorder: encoder", e) }
        try {
            mVideoMuxer?.let { runCatching { it.release() } }
        } catch (e: Exception) { Log.w(TAG, "releaseVideoRecorder: muxer", e) }
        mVideoEncoder = null
        mVideoMuxer = null
        mVideoMuxerStarted = false
        mVideoTrackIndex = -1
        mVideoPtsUs = 0L
    }

    /** RGB888 → NV12（YUV420 半平面），供 H.264 编码器输入。可传入 [dst] 复用缓冲。 */
    private fun rgbToNv12(rgb: ByteArray, w: Int, h: Int, dst: ByteArray? = null): ByteArray {
        val yuv = dst ?: ByteArray(w * h * 3 / 2)
        var yIdx = 0
        var uvIdx = w * h
        for (j in 0 until h) {
            var rowBase = j * w * 3
            for (i in 0 until w) {
                val r = rgb[rowBase].toInt() and 0xFF
                val g = rgb[rowBase + 1].toInt() and 0xFF
                val b = rgb[rowBase + 2].toInt() and 0xFF
                rowBase += 3
                yuv[yIdx++] = clamp8(((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = clamp8(((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128)
                    val v = clamp8(((112 * r - 94 * g - 18 * b + 128) shr 8) + 128)
                    yuv[uvIdx++] = u.toByte()
                    yuv[uvIdx++] = v.toByte()
                }
            }
        }
        return yuv
    }

    /** 0~255 截断。 */
    private fun clamp8(v: Int): Int = when { v < 0 -> 0; v > 255 -> 255; else -> v }

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
