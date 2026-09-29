#ifndef FACEID_AHARDWAREBUFFER_UTIL_H_
#define FACEID_AHARDWAREBUFFER_UTIL_H_

// AHardwareBuffer 尺寸/布局的公共小工具（多处复用，见 hardware_buffer_reader.cpp、
// media_record_jni.cpp、encoder_surface_renderer.cpp）。
//
// 像素字节序（**最易错的地方，务必按此读**）：
//   相机帧为 UYVY，每 4 字节两个像素，顺序是 `[U][Y0][V][Y1]`（**U 在第 0 字节**）。
//   依据：docs/FaceID_SO对接说明.md（`[U0 Y0 V0 Y1] [U2 Y2 V2 Y3] ...`）以及算法路径
//   algo/src/main/java/com/skyworth/faceid/algorithm/FrameProcessor.kt 的
//   convertUyvyToRgb888()（实际在跑且人脸识别正确的那份实现）。
//   注意 FrameProcessor.kt 的 KDoc 把顺序写成 `Y0 U Y1 V`，是注释笔误，以实现为准。
//   按 `[Y0][U][Y1][V]` 去读会「亮度/色度错位」：IR 画面（U/V≈128）表现为
//   **整幅纯绿但人物轮廓清晰**。
//
// 真机实测（DMS 摄像头，2 字节/像素 = UYVY）：
//   - AHardwareBuffer 分配尺寸 **1600x3900**，而相机有效图像只有 **1600x1300**；
//     有效画面位于 buffer **顶部 1/3**，其余是全 0（YUV 全 0 转 RGB 呈暗绿）。
//   - 每行 3200 字节（= 1600 x 2）。
//
// 因此「读帧」必须区分两个高度：
//   buffer 高度（gralloc 分配）  ← AHardwareBuffer_Desc::height
//   图像高度（相机有效画面）      ← EvsBufferDesc 解出的分辨率（OpaqueIdentifier.RESOLUTION）

#include <android/hardware_buffer.h>

/// 源每行字节数（相机帧 2 字节/像素）。
///
/// 注意：`AHardwareBuffer_Desc::stride` 头文件注释写的是「像素」，但真机上返回的是
/// **字节**（width=1600 时 stride=3200）。这里按「>= width*2 即视为字节」归一化，
/// 两种实现都能正确处理。
inline int hbRowStrideBytes(const AHardwareBuffer_Desc& desc) {
    const long long minBytes = static_cast<long long>(desc.width) * 2;
    const long long stride = static_cast<long long>(desc.stride);
    if (stride <= 0) return static_cast<int>(minBytes);
    return static_cast<int>((stride >= minBytes) ? stride : stride * 2);
}

/// 有效画面占 buffer 的比例上界（0..1]，用于纹理采样窗口 uMax/vMax。
/// buffer 可能远高于图像（实测 3900 vs 1300 → 1/3）。
inline float hbValidFraction(int imageSize, unsigned int bufferSize) {
    if (imageSize <= 0 || bufferSize == 0) return 1.f;
    const float f = static_cast<float>(imageSize) / static_cast<float>(bufferSize);
    return (f < 1.f) ? f : 1.f;
}

#endif  // FACEID_AHARDWAREBUFFER_UTIL_H_
