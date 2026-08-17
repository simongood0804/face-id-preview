/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.render

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper

/**
 * 渲染层 dump 控制管理器（与算法解耦）。
 *
 * 职责收窄为「帧画面 dump」的控制，由主应用（渲染层）负责：
 * - [triggerManualDump]：手动保存最近一帧原始图像为 PNG；
 * - [clearDump]：清空 debugDump 目录并删除 /sdcard/debugDmsDump；
 * - [moveToSdcard]：将 debugDump 中的 PNG 移动到 /sdcard/debugDmsDump；
 * - [isDumpAvailable]：是否启用 dump（读系统属性 algorithm_face_dump_enable）。
 * - [getDumpDir]：获取 dump 源目录，供渲染层通过 Binder 把算法处理后数据的
 *   dump 路径下发给算法进程（[AlgoEngineBridge.setDumpPath]）。
 *
 * 两个进程唯一关联为共享内存分享算法结果；dump 路径通过 Binder 下发，
 * 不写入系统属性、不依赖算法实例。
 */
class DumpManager(private val context: Context) {

    private val TAG = "DumpManager"

    /** dump 源目录（应用私有：filesDir/debugDump）。 */
    private val dumpSourceDir: File by lazy { File(context.filesDir, DUMP_SOURCE_DIR_NAME) }

    /** 导出 dump 图像的目标目录（sdcard）。 */
    private val sdcardDumpDir = File(SDCARD_DUMP_DIR)

    /** dump 后台执行线程。 */
    private val dumpExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** 主线程 Handler，用于完成回调。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 已 dump 的帧计数（用于生成文件名 index）。 */
    @Volatile private var dumpFrameCount = 0

    /**
     * 最近一帧原始 UYVY 数据缓存（由渲染层每帧注入）。
     * 封装为不可变数据类并以单一 @Volatile 引用原子替换，避免 [cacheFrame]
     * 与 [triggerManualDump] 之间数据不一致（如"新帧 + 旧宽高"导致越界）。
     */
    @Volatile private var latestCache: FrameCache? = null

    /**
     * 由渲染层（FrameProcessor / PreviewActivity.cacheFrameForDump）每帧调用，
     * 缓存最近一帧原始 UYVY 数据，供 [triggerManualDump] 手动保存。
     */
    fun cacheFrame(uyvyData: ByteArray, width: Int, height: Int) {
        latestCache = FrameCache(uyvyData, width, height)
    }

    /** 最近一帧缓存（不可变快照，保证 [cacheFrame] 写入与 [triggerManualDump] 读取一致）。 */
    private data class FrameCache(
        val data: ByteArray,
        val width: Int,
        val height: Int
    )

    /**
     * dump 是否可用（系统属性 algorithm_face_dump_enable 已启用）。
     */
    fun isDumpAvailable(): Boolean = isPropEnabled(readProp(PROP_DUMP_ENABLE, "disable"))

    /**
     * 手动触发 dump：后台线程保存最近一帧原始图像为 PNG。
     * 文件名 dumpOrigin{index}.png，index 每次触发递增。
     * 完成后通过 [onResult] 回调（在主线程执行）。
     *
     * @param onResult 完成回调，参数为是否保存成功。
     */
    fun triggerManualDump(onResult: ((Boolean) -> Unit)? = null) {
        val cache = latestCache
        if (cache == null || cache.width <= 0 || cache.height <= 0) {
            onResult?.invoke(false)
            return
        }
        val frame = cache.data
        val w = cache.width
        val h = cache.height
        // 在后台线程执行转换 + 压缩，避免阻塞 UI 线程
        dumpExecutor.execute {
            val ok = try {
                val index = dumpFrameCount++
                val rgb = uyvyToRgb(frame, w, h)
                savePng(File(dumpSourceDir, "dumpOrigin$index.png"), rgb, w, h)
                Log.i(TAG, "triggerManualDump: saved dumpOrigin$index.png (${w}x${h})")
                true
            } catch (e: Exception) {
                Log.e(TAG, "triggerManualDump: failed", e)
                false
            }
            mainHandler.post { onResult?.invoke(ok) }
        }
    }

    /**
     * 清除 debugDump 文件夹内容，并删除 /sdcard/debugDmsDump 文件夹。
     * 在后台线程执行，完成后回调（主线程）。
     */
    fun clearDump(onResult: ((Boolean) -> Unit)? = null) {
        dumpExecutor.execute {
            val ok = try {
                // 1. 清空 debugDump 目录内容
                if (dumpSourceDir.exists()) {
                    dumpSourceDir.listFiles()?.forEach { it.delete() }
                }
                // 2. 删除 /sdcard/debugDmsDump 文件夹（递归）
                if (sdcardDumpDir.exists()) {
                    sdcardDumpDir.deleteRecursively()
                }
                dumpFrameCount = 0
                Log.i(TAG, "clearDump: cleared $dumpSourceDir & removed $SDCARD_DUMP_DIR")
                true
            } catch (e: Exception) {
                Log.e(TAG, "clearDump: failed", e)
                false
            }
            mainHandler.post { onResult?.invoke(ok) }
        }
    }

    /**
     * 将 debugDump 中的 png 图像移动到 /sdcard/debugDmsDump 文件夹。
     * 无该文件夹则创建。在后台线程执行，完成后回调（主线程）。
     */
    fun moveToSdcard(onResult: ((Boolean) -> Unit)? = null) {
        dumpExecutor.execute {
            val ok = try {
                if (!dumpSourceDir.exists()) {
                    Log.w(TAG, "moveToSdcard: dump dir not found")
                    false
                } else {
                    if (!sdcardDumpDir.exists()) {
                        sdcardDumpDir.mkdirs()
                    }
                    var moved = 0
                    dumpSourceDir.listFiles()?.forEach { f ->
                        if (f.isFile && f.name.endsWith(".png", ignoreCase = true)) {
                            val dest = File(sdcardDumpDir, f.name)
                            // 目标已存在则先删除，再移动（覆盖）
                            if (dest.exists()) dest.delete()
                            if (f.renameTo(dest)) moved++
                        }
                    }
                    Log.i(TAG, "moveToSdcard: moved $moved png to $SDCARD_DUMP_DIR")
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "moveToSdcard: failed", e)
                false
            }
            mainHandler.post { onResult?.invoke(ok) }
        }
    }

    /**
     * 获取 dump 源目录（渲染层据此得到 dump 路径，通过 Binder 下发给算法进程）。
     */
    fun getDumpDir(): File = dumpSourceDir

    // ============================================================
    // 系统属性工具（仅读：判断 dump 是否启用）
    // ============================================================

    private fun readProp(key: String, def: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java, String::class.java)
            method.invoke(null, key, def) as String
        } catch (_: Exception) {
            null
        }
    }

    private fun isPropEnabled(value: String?): Boolean {
        return value.equals("1", ignoreCase = true) ||
            value.equals("true", ignoreCase = true)
    }

    // ============================================================
    // 帧数据工具：UYVY → RGB888 → PNG
    // ============================================================

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

    /** 释放后台线程资源。 */
    fun release() {
        dumpExecutor.shutdownNow()
    }

    companion object {
        private const val DUMP_SOURCE_DIR_NAME = "debugDump"

        /** 导出 dump 图像的目标目录（sdcard）。 */
        const val SDCARD_DUMP_DIR = "/sdcard/debugDmsDump"

        /** 是否启用 dump 的系统属性。 */
        const val PROP_DUMP_ENABLE = "algorithm_face_dump_enable"
    }
}
