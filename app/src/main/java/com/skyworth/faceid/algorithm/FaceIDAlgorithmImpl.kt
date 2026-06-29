/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.algorithm

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import java.io.File

/**
 * Face ID 算法实现 —— 基于 [libfaceid.so] + SNPE DSP。
 *
 * 对接文档：docs/FaceID_SO对接说明.md
 * 部署步骤：
 *   1. DLC 模型文件从 assets/models/ 解压到设备存储
 *   2. 调用 [initialize] 时初始化 native pipeline
 *   3. 每帧调用 [processFrame] 进行人脸检测/活体/识别
 *
 * 线程安全：一个实例绑定一个线程，不支持多线程共享。
 */
class FaceIDAlgorithmImpl : IFaceIDAlgorithm {

    private val TAG = "FaceIDAlgorithm"

    @Volatile
    private var mInitialized = false

    /** Native 句柄，对应 [FaceIDHandle]。 */
    private var mNativeHandle: Long = 0L

    /** 人脸检测结果缓存（避免每帧 new 对象）。 */
    private val mNativeResults = arrayOfNulls<FaceIDNativeResult>(MAX_FACES)

    /** 模型文件存储目录。 */
    private var mModelDir: String = ""

    init {
        for (i in 0 until MAX_FACES) {
            mNativeResults[i] = FaceIDNativeResult()
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
            // 1. 确定模型路径
            mModelDir = config["model_dir"] as? String
                ?: extractModels(context)
            Log.i(TAG, "initialize: model_dir=$mModelDir")

            // 列出模型目录内容
            val modelDirFile = File(mModelDir)
            if (modelDirFile.exists()) {
                val files = modelDirFile.listFiles() ?: emptyArray()
                Log.i(TAG, "initialize: model dir contains ${files.size} files")
                files.forEach { f ->
                    Log.i(TAG, "  model file: ${f.name} (${f.length()} bytes)")
                }
            } else {
                Log.w(TAG, "initialize: model dir does not exist: $mModelDir")
            }

            // 2. 获取运行时
            val runtime = config["runtime"] as? String ?: DEFAULT_RUNTIME
            Log.i(TAG, "initialize: runtime=$runtime")

            // 3. 初始化 native
            val t0 = System.currentTimeMillis()
            mNativeHandle = nativeInit(mModelDir, runtime)
            val t1 = System.currentTimeMillis()
            Log.i(TAG, "initialize: nativeInit -> handle=$mNativeHandle, took=${t1 - t0}ms")

            if (mNativeHandle == 0L) {
                Log.e(TAG, "initialize: nativeInit returned null handle")
                return false
            }

            // 4. 配置启用所有模型
            val flags = FACEID_FLAG_ALL
            val t2 = System.currentTimeMillis()
            val ret = nativeConfigure(mNativeHandle, flags)
            val t3 = System.currentTimeMillis()
            Log.i(TAG, "initialize: nativeConfigure(flags=$flags) -> ret=$ret, took=${t3 - t2}ms")

            if (ret != 0) {
                Log.e(TAG, "initialize: nativeConfigure failed, ret=$ret")
                nativeDestroy(mNativeHandle)
                mNativeHandle = 0L
                return false
            }

            val version = nativeVersion()
            Log.i(TAG, "initialize: success, handle=$mNativeHandle, version=$version")
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
        if (!mInitialized || mNativeHandle == 0L) {
            Log.w(TAG, "processFrame: not initialized")
            return IFaceIDAlgorithm.FaceIDResult(processedData = frameData)
        }

        if (frameData == null) {
            Log.w(TAG, "processFrame: frameData is null")
            return IFaceIDAlgorithm.FaceIDResult(processedData = frameData)
        }

        return try {
            val nativeFormat = when (format) {
                0 -> FACEID_FMT_UYVY
                1 -> FACEID_FMT_RGB
                else -> FACEID_FMT_UYVY
            }

            val t0 = System.currentTimeMillis()
            val n = nativeDetect(
                mNativeHandle, frameData, width, height, 0,
                nativeFormat, mNativeResults, MAX_FACES
            )
            val t1 = System.currentTimeMillis()

            Log.i(TAG, "detect: ${width}x$height format=$nativeFormat -> $n faces, took=${t1 - t0}ms")

            if (n < 0) {
                Log.e(TAG, "processFrame: nativeDetect returned error=$n")
                return IFaceIDAlgorithm.FaceIDResult(processedData = frameData)
            }

            if (n > 0 && n <= MAX_FACES) {
                val r = mNativeResults[0]!!
                Log.i(TAG, "  face[0]: bbox=[${r.x1},${r.y1},${r.x2},${r.y2}] " +
                        "score=${String.format("%.3f", r.score)} " +
                        "liveness=${String.format("%.3f", r.liveness)}")

                if (n > 1) {
                    for (i in 1 until n) {
                        val fi = mNativeResults[i]!!
                        Log.d(TAG, "  face[$i]: bbox=[${fi.x1},${fi.y1},${fi.x2},${fi.y2}] " +
                                "score=${String.format("%.3f", fi.score)}")
                    }
                }

                val faceRect = RectF(r.x1, r.y1, r.x2, r.y2)
                val confidence = r.score.coerceIn(0f, 1f)
                val faceId = when {
                    r.liveness < 0f -> "detected"
                    r.liveness > 0.5f -> "detected"
                    else -> "spoof"
                }

                if (faceId.isNotEmpty()) {
                    Log.i(TAG, "  result: faceId=$faceId, confidence=${String.format("%.1f", confidence * 100)}%")
                }

                IFaceIDAlgorithm.FaceIDResult(
                    faceId = faceId,
                    confidence = confidence,
                    faceRect = faceRect,
                    processedData = frameData
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
        Log.i(TAG, "release: start, handle=$mNativeHandle, initialized=$mInitialized")
        if (mNativeHandle != 0L) {
            try {
                val t0 = System.currentTimeMillis()
                nativeDestroy(mNativeHandle)
                val t1 = System.currentTimeMillis()
                Log.i(TAG, "release: nativeDestroy done, took=${t1 - t0}ms")
            } catch (e: Exception) {
                Log.e(TAG, "release: nativeDestroy error", e)
            }
            mNativeHandle = 0L
        }
        mInitialized = false
        Log.i(TAG, "release: done")
    }

    // ============================================================
    // 模型管理
    // ============================================================

    /**
     * 将 assets/models/ 下的 DLC 文件解压到应用内部存储。
     */
    private fun extractModels(context: Context?): String {
        if (context == null) {
            Log.w(TAG, "extractModels: context is null, using default: $DEFAULT_MODEL_DIR")
            return DEFAULT_MODEL_DIR
        }

        val dir = File(context.filesDir, MODEL_ASSET_PATH)
        Log.i(TAG, "extractModels: target dir=$dir")

        if (dir.exists()) {
            val existing = dir.listFiles()?.filter { it.name.endsWith(".dlc") } ?: emptyList()
            Log.i(TAG, "extractModels: already exists, ${existing.size} DLC files found")
            existing.forEach { Log.i(TAG, "  ${it.name} (${it.length()} bytes)") }
            return dir.absolutePath
        }

        dir.mkdirs()
        val assetManager = context.assets

        try {
            val allAssets = assetManager.list(MODEL_ASSET_PATH) ?: emptyArray()
            Log.i(TAG, "extractModels: assets/models/ contains ${allAssets.size} entries")
            allAssets.forEach { Log.d(TAG, "  asset: $it") }

            val dlcFiles = allAssets.filter { it.endsWith(".dlc") }
            Log.i(TAG, "extractModels: found ${dlcFiles.size} DLC files to extract")

            var extracted = 0
            for (file in dlcFiles) {
                val outFile = File(dir, file)
                assetManager.open("$MODEL_ASSET_PATH/$file").use { input ->
                    outFile.outputStream().use { output ->
                        val bytes = input.copyTo(output)
                        Log.i(TAG, "extracted: $file -> ${outFile.absolutePath} ($bytes bytes)")
                    }
                }
                extracted++
            }
            Log.i(TAG, "extractModels: done, $extracted files extracted to ${dir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "extractModels: failed", e)
        }

        return dir.absolutePath
    }

    // ============================================================
    // JNI
    // ============================================================

    companion object {
        private const val MAX_FACES = 10
        private const val DEFAULT_RUNTIME = "dsp"
        private const val DEFAULT_MODEL_DIR = "/data/faceid/models"
        private const val MODEL_ASSET_PATH = "models"

        // 格式常量（对应 FaceIDFormat 枚举）
        private const val FACEID_FMT_UYVY = 0
        private const val FACEID_FMT_RGB = 1

        // 配置标志（对应 FaceIDFlag 枚举）
        private const val FACEID_FLAG_DET = 1 shl 0
        private const val FACEID_FLAG_LIVENESS = 1 shl 1
        private const val FACEID_FLAG_LANDMARK = 1 shl 2
        private const val FACEID_FLAG_RECOG = 1 shl 3
        private const val FACEID_FLAG_ALL = 0x0F

        init {
            try {
                System.loadLibrary("faceid_jni")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("FaceIDAlgorithm", "Failed to load faceid_jni library", e)
            }
        }
    }

    private external fun nativeInit(modelDir: String, runtime: String): Long
    private external fun nativeConfigure(handle: Long, flags: Int): Int
    private external fun nativeDetect(
        handle: Long, imgData: ByteArray,
        width: Int, height: Int, stride: Int, format: Int,
        results: Array<FaceIDNativeResult?>, maxFaces: Int
    ): Int
    private external fun nativeCompare(emb1: FloatArray, emb2: FloatArray): Float
    private external fun nativeDestroy(handle: Long)
    private external fun nativeVersion(): String

    /**
     * Java 侧用于接收 native 人脸检测结果的数据类。
     * 字段名与 [faceid_jni.cpp] 中 JNI 访问的字段一致。
     */
    class FaceIDNativeResult {
        @JvmField var x1: Float = 0f
        @JvmField var y1: Float = 0f
        @JvmField var x2: Float = 0f
        @JvmField var y2: Float = 0f
        @JvmField var score: Float = 0f
        @JvmField var liveness: Float = -1f
    }
}
