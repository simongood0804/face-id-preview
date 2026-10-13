cmake_minimum_required(VERSION 3.18)
project("faceid_native")

# ============================================================
# HardwareBuffer 读取器（取流侧，供 NativeFrameReader 使用）
#
# 注意：推流相关的 native（media_record_jni、encoder_surface_renderer）与
# media_record 预编译库（libmedia_record_core_shared.so / libdatachannel.so.0.21）
# 已下沉到 :stream 模块（见 FACEP-019），本模块只保留取流所需的极小 JNI。
# ============================================================
add_library(hardware_buffer_reader SHARED
    hardware_buffer_reader.cpp
)

target_link_libraries(hardware_buffer_reader
    android
    nativewindow
    log
)
