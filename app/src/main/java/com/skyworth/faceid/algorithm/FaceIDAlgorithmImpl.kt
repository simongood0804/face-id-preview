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

    /** 裁剪偏移（FrameProcessor 在 processFrame 前设置）。 */
    @Volatile var mCropOffsetX: Int = 0
    @Volatile var mCropOffsetY: Int = 0

    /** 模型文件存储目录。 */
    private var mModelDir: String = ""

    /** 模型文件清单。 */
    private val REQUIRED_MODEL_FILES = listOf(
        "det_500m_int8.dlc",
        "face_antispoof_int8.dlc",
        "2d106det_int8.dlc",
        "w600k_mbf_int8.dlc",
        "manifest.json"
    )

    /** 系统级模型目录（可被外部更新）。 */
    private val VENDOR_MODEL_DIR = "/vendor/etc/faceid"

    /** 录入管理器（延迟初始化）。 */
    private var mEnrollmentManager: FaceEnrollmentManager? = null

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
            Log.i(TAG, "initialize: model_dir=$mModelDir")

            // 2. 初始化 AAR FaceSDK
            val t0 = System.currentTimeMillis()
            val sdk = FaceSDK.init("$mModelDir/manifest.json")
            val t1 = System.currentTimeMillis()
            Log.i(TAG, "initialize: FaceSDK.init took=${t1 - t0}ms")

            if (sdk == null) {
                Log.e(TAG, "initialize: FaceSDK.init returned null")
                return false
            }

            // 3. 配置启用所有模型
            val t2 = System.currentTimeMillis()
            sdk.configure(FaceFlag.ALL)
            val t3 = System.currentTimeMillis()
            Log.i(TAG, "initialize: configure(ALL) took=${t3 - t2}ms")

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
            val image = FaceImage(frameData, width, height, width * 3, FaceImage.FACE_FMT_RGB)

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

                Log.i(TAG, "headpose: pitch=%.1f yaw=%.1f roll=%.1f".format(
                    r.headpose_pitch, r.headpose_yaw, r.headpose_roll))

                IFaceIDAlgorithm.FaceIDResult(
                    faceId = faceId,
                    confidence = confidence,
                    faceRect = faceRect,
                    processedData = frameData,
                    isNewEnrollment = isNewEnroll,
                    keypoints = kpsList,
                    landmarks = lmList,
                    headposePitch = r.headpose_pitch,
                    headposeYaw = r.headpose_yaw,
                    headposeRoll = r.headpose_roll
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
            val modelFiles = allAssets.filter { it.endsWith(".dlc") || it == "manifest.json" }
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
}
