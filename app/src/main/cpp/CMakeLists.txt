cmake_minimum_required(VERSION 3.18)
project("faceid_native")

# ============================================================
# HardwareBuffer 读取器（原有，供 NativeFrameReader 使用）
# ============================================================
add_library(hardware_buffer_reader SHARED
    hardware_buffer_reader.cpp
)

target_link_libraries(hardware_buffer_reader
    android
    nativewindow
    log
)

# ============================================================
# media_record 预编译库（app/libs/arm64-v8a）
#   - libmedia_record_core_shared.so：C ABI（mr_*）录制/推流引擎（无 SONAME）
#   - libdatachannel.so：WebRTC 推流后端（SONAME = libdatachannel.so.0.21）
#   - include/：公开头文件（media_record/media_record_driver.h 等）
#
# 链接方式说明（重要）：
#   这里**故意不用 IMPORTED target**，而是 link_directories + `-l` 普通链接：
#   1) 打包交给 jniLibs（build.gradle.kts 的 sourceSets.jniLibs.srcDirs("libs")）——
#      IMPORTED 会让 AGP 也去打包，导致 mergeDebugNativeLibs 同路径冲突；
#      且实测 release 下 IMPORTED 的 core 库未被稳定打包（APK 缺库 → 运行时报找不到）。
#   2) `-l` 形式让 linker 按**文件名**写入 DT_NEEDED，避免无 SONAME 的 core 库被写成
#      构建机绝对路径（真机 dlopen 会 not found）。
# ============================================================
set(MR_LIB_DIR "${CMAKE_SOURCE_DIR}/../../../libs/arm64-v8a")
set(MR_INCLUDE_DIR "${MR_LIB_DIR}/include")

link_directories("${MR_LIB_DIR}")

# ============================================================
# media_record JNI 封装（Kotlin <-> mr_* C ABI）
# ============================================================
add_library(media_record_jni SHARED
    media_record_jni.cpp
    encoder_surface_renderer.cpp
)

target_include_directories(media_record_jni PRIVATE
    "${MR_INCLUDE_DIR}"
)

# GLESv2：两条路径都要自己画（路 A 绑外部纹理、CPU 回退上传 RGBA 纹理），都在
#   mr_render_frame 的 draw 回调里执行（库已 makeCurrent）。
# EGL：路 A 需要 eglGetCurrentDisplay / eglGetNativeClientBufferANDROID / eglCreateImageKHR。
#   display/config/context/window surface 与 PTS/swap 仍全部归库，我们只借用它的 context。
target_link_libraries(media_record_jni
    media_record_core_shared
    datachannel
    android
    nativewindow
    EGL
    GLESv2
    log
)
