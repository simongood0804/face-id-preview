package com.skyworth.faceid.util

import android.hardware.HardwareBuffer

/**
 * HardwareBuffer 读取辅助类（JNI 封装）。
 *
 * 主进程 `PreviewActivity` 与 `:algo` 进程 `AlgoEngineService` **共用同一
 * native 方法**，避免在多个类重复声明 `external`（否则 JNI 库需为每个类提供
 * 独立的符号，且类路径/类名变化会导致 UnsatisfiedLinkError）。
 *
 * JNI 实现见 `app/src/main/cpp/hardware_buffer_reader.cpp`，符号：
 * `Java_com_skyworth_faceid_util_HardwareBufferReader_nativeReadHardwareBuffer`
 *
 * @return UYVY 数据；黑帧或读取失败返回 null。
 */
object HardwareBufferReader {

    /**
     * 从 HardwareBuffer 读取 UYVY 字节（快速 memcpy，JNI 侧完成）。
     */
    fun read(hwBuffer: HardwareBuffer, width: Int, height: Int): ByteArray? {
        return nativeReadHardwareBuffer(hwBuffer, width, height)
    }

    /**
     * 用 @JvmStatic 保证 JNI 符号为静态绑定（无实例签名后缀），
     * 与 C 侧 `Java_com_skyworth_faceid_util_HardwareBufferReader_nativeReadHardwareBuffer`
     * 精确匹配，避免实例方法后缀导致 UnsatisfiedLinkError。
     */
    @JvmStatic
    private external fun nativeReadHardwareBuffer(
        hwBuffer: HardwareBuffer, width: Int, height: Int
    ): ByteArray?

    init {
        try {
            System.loadLibrary("hardware_buffer_reader")
        } catch (_: UnsatisfiedLinkError) { }
    }
}
