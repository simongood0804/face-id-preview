/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.algorithm

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import atlas.face.sdk.FaceFlag
import atlas.face.sdk.FaceImage
import atlas.face.sdk.FaceResult
import atlas.face.sdk.FaceSDK
import java.io.File

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

    // ============ 算法处理后数据 dump（JNI 层，路径由主应用传入） ============
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

    init {
        for (i in 0 until MAX_FACES) {
            mAARResults[i] = FaceResult()
        }
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
            Log.i(TAG, "initialize: model_dir=$mModelDir")

            // 2. dump 路径（算法处理后数据用）由渲染层通过 Binder 下发，
            //    见 [AlgoEngineBridge.setDumpPath] → [setDumpPath]，不在此处从系统属性读取。

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
            return IFaceIDAlgorithm.FaceIDResult(processedData = frameData)
        }

        if (frameData == null) {
            Log.w(TAG, "processFrame: frameData is null")
            return IFaceIDAlgorithm.FaceIDResult(processedData = frameData)
        }

        return try {
            // FrameProcessor 已裁剪并转为 RGB888
            val image = FaceImage(frameData, width, height, 0, FaceImage.FACE_FMT_RGB)

            val n = sdk.infer(image, mAARResults, MAX_FACES)

            if (n < 0) {
                Log.e(TAG, "processFrame: infer error=$n")
                return IFaceIDAlgorithm.FaceIDResult(processedData = frameData)
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

                // 人脸 ID：按活体检测判定（多进程架构下录入识别由外部管理，
                // 本实现只做活体判定，不内嵌人脸库录入）。
                val faceId = when {
                    r.liveness < 0f -> "detected"
                    r.liveness > 0.5f -> "detected"
                    else -> "spoof"
                }

                Log.i(TAG, "faceId=$faceId, conf=${String.format("%.1f", confidence * 100)}%")

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

                Log.i(TAG, "headpose: pitch=%.1f yaw=%.1f roll=%.1f".format(
                    r.headPitch, r.headYaw, r.headRoll))

                Log.i(TAG,
                    "gaze: valid=${r.gazeValid} yaw=${r.gazeYaw} pitch=${r.gazePitch} " +
                    "distracted=${if (r.gazeDistracted > 0f) "Y" else "N"} " +
                    "calib=${if (r.gazeCalibrated > 0f) "Y" else "N"} " +
                    "zone=${r.zoneId} " +
                    "sphereValid=${r.sphereValid} area3=${r.area3Hit} " +
                    "sphereYaw=${r.sphereYaw} spherePitch=${r.spherePitch}")

                IFaceIDAlgorithm.FaceIDResult(
                    faceId = faceId,
                    confidence = confidence,
                    faceRect = faceRect,
                    processedData = frameData,
                    isNewEnrollment = false,
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
                    zoneConfidence = r.zoneConfidence
                )
            } else {
                if (n == 0) Log.i(TAG, "  no face detected")
                IFaceIDAlgorithm.FaceIDResult(processedData = frameData)
            }
        } catch (e: Exception) {
            Log.e(TAG, "processFrame: failed", e)
            IFaceIDAlgorithm.FaceIDResult(processedData = frameData)
        }
    }

    override fun release() {
        Log.i(TAG, "release: start, sdk=$mFaceSDK, initialized=$mInitialized")
        mFaceSDK?.destroy()
        mFaceSDK = null
        mInitialized = false

        Log.i(TAG, "release: done")
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
    // Companion
    // ============================================================

    companion object {
        private const val MAX_FACES = 10
        private const val DEFAULT_MODEL_DIR = "/data/faceid/models"
        private const val MODEL_ASSET_PATH = "models"
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
     * 设置算法处理后数据的 dump 路径，传给 FaceSDK JNI 层。
     *
     * 该路径由渲染层（主进程）通过 Binder 下发（[AlgoEngineBridge.setDumpPath]），
     * 算法进程收到后调用本方法。渲染层的帧画面 dump/clear/move 由主进程的
     * [com.skyworth.faceid.render.DumpManager] 负责，本实现只承载算法处理后数据的路径下发。
     */
    fun setDumpPath(path: String) {
        try {
            FaceSDK.setDebugDumpPath(path)
            Log.i(TAG, "setDumpPath: debugDumpPath=$path")
        } catch (e: Exception) {
            Log.e(TAG, "setDumpPath: failed", e)
        }
    }
}
