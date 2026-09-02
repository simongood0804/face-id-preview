package com.skyworth.faceid.core

import android.hardware.HardwareBuffer
import android.util.Log

/**
 * HardwareBuffer 帧读取器（JNI）。
 *
 * 把相机 HardwareBuffer 读取为 UYVY ByteArray（RGB 转换在算法线程异步完成，
 * 黑帧检测在 JNI 侧完成）。
 *
 * 作为公共基础设施，供三个功能模块（人脸识别/疲劳监测/分心监测）复用，
 * 避免 JNI 帧读取能力绑定在某个 Activity（FACEP-011 §4.6 公共化）。
 *
 * native 库 `hardware_buffer_reader` 由 CMake 构建（app/src/main/cpp/hardware_buffer_reader.cpp），
 * JNI 符号绑定本类。
 */
object NativeFrameReader {

    private const val TAG = "NativeFrameReader"

    init {
        try {
            System.loadLibrary("hardware_buffer_reader")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "loadLibrary hardware_buffer_reader failed", e)
        }
    }

    /**
     * 读取 HardwareBuffer 为 UYVY ByteArray。
     *
     * @return UYVY 数据（width*height*2 字节）；黑帧或失败返回 null。
     */
    fun readHardwareBuffer(hwBuffer: HardwareBuffer, width: Int, height: Int): ByteArray? {
        return try {
            nativeReadHardwareBuffer(hwBuffer, width, height)
        } catch (e: Exception) {
            Log.w(TAG, "readHardwareBuffer error", e)
            null
        }
    }

    private external fun nativeReadHardwareBuffer(
        hwBuffer: HardwareBuffer, width: Int, height: Int
    ): ByteArray?
}
