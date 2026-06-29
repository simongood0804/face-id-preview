# FaceID Pipeline — SO 对接与修改说明

> 版本: 1.0 | 日期: 2026-06-29 | 平台: QCS6125 + SNPE v1.50 + DSP V66

---

## 一、概述

`libfaceid.so` 是一个运行在 QCS6125 DSP 上的人脸识别 pipeline 库，整合了 4 个 INT8 量化模型：

| 模型 | 功能 | 输入 | 输出 |
|------|------|------|------|
| det_500m | 人脸检测 + 5关键点 | 640x640 RGB NHWC | bbox, score, 5 kps |
| face_antispoof | 活体检测 | 128x128 RGB NHWC (全图) | [fake, real] logit |
| 2d106det | 106点2D关键点 | 192x192 RGB NHWC (人脸对齐) | 212 floats |
| w600k_mbf | 人脸识别 | 112x112 RGB NHWC (5点对齐) | 512-D embedding |

### 交付物

```
snpe_cpp/
├── faceid_api.h              # 对接方唯一需要 #include 的头文件
├── build/libfaceid.so        # SO 动态库 (103KB)
├── build/*.dlc               # 4个INT8模型文件 (部署到设备)
│   ├── det_500m_int8.dlc          (811KB)
│   ├── face_antispoof_int8.dlc    (668KB)
│   ├── 2d106det_int8.dlc          (1.6MB)
│   └── w600k_mbf_int8.dlc         (3.5MB)
└── build/snpe_face           # 命令行测试工具 (可选)
```

---

## 二、对接指南

### 2.1 快速开始

```c
#include "faceid_api.h"

// 1. 启动时初始化 (仅一次)
FaceIDHandle h = faceid_init("/data/faceid/models", "dsp");
faceid_configure(h, FACEID_FLAG_ALL);

// 2. 每帧调用
FaceResult results[10];
int n = faceid_detect(h, uyvy_frame, 640, 480, 0, FACEID_FMT_UYVY, results, 10, NULL);

for (int i = 0; i < n; i++) {
    // 活体判断
    if (results[i].liveness > 0.5f) { /* 真人 */ }
    // 身份比对
    float sim = faceid_compare(results[i].emb, registered_emb);
    if (sim > 0.25f) { /* 匹配 */ }
}

// 3. 退出时释放
faceid_destroy(h);
```

### 2.2 接口详细说明

#### faceid_init — 初始化

```c
FaceIDHandle faceid_init(const char* model_dir, const char* runtime);
```

| 参数 | 说明 |
|------|------|
| `model_dir` | DLC 模型文件所在目录 |
| `runtime` | `"dsp"` (QCS6125 DSP加速) 或 `"cpu"` (CPU兜底) |
| **返回** | 不透明句柄，后续所有调用传入。失败返回 NULL |

只初始化结构，**不加载模型**（模型在首次 `faceid_detect` 时懒加载）。

---

#### faceid_configure — 配置子模型

```c
int faceid_configure(FaceIDHandle handle, uint32_t flags);
```

`flags` 是位掩码，决定跑哪些模型：

| 常量 | 含义 | 不启用时的输出 |
|------|------|-----|
| `FACEID_FLAG_DET` | 人脸检测 | 无输出 (必选) |
| `FACEID_FLAG_LIVENESS` | 活体检测 | `liveness = -1` |
| `FACEID_FLAG_LANDMARK` | 106点关键点 | `landmarks_valid = 0` |
| `FACEID_FLAG_RECOG` | 人脸识别 | `emb_valid = 0` |
| `FACEID_FLAG_ALL` | 全部 | — |

常用组合：
```c
faceid_configure(h, FACEID_FLAG_ALL);                          // 全开
faceid_configure(h, FACEID_FLAG_DET);                           // 只检测
faceid_configure(h, FACEID_FLAG_DET | FACEID_FLAG_LIVENESS);    // 检测+活体
faceid_configure(h, FACEID_FLAG_DET | FACEID_FLAG_RECOG);       // 检测+识别
```

---

#### faceid_detect — 推理 (每帧)

```c
int faceid_detect(FaceIDHandle handle,
                  const uint8_t* img_data,
                  int width, int height,
                  int stride,
                  FaceIDFormat format,
                  FaceResult* results,
                  int max_faces,
                  FaceIDTiming* timing);
```

**输入**：

| 参数 | 说明 | 示例 |
|------|------|------|
| `img_data` | 图像 buffer 指针 | 相机帧 YUV/RGB buffer |
| `width` / `height` | 图像分辨率 | 640 x 480 |
| `stride` | 行字节数 (0=自动计算) | 通常传 0 |
| `format` | 输入格式 (见下表) | `FACEID_FMT_UYVY` |
| `max_faces` | 最多检测的人脸数 | 建议 10，上限 16 |
| `timing` | 耗时统计 (不需要传 NULL) | — |

**输入格式**：

| 枚举值 | 说明 | 字节/像素 |
|--------|------|----------|
| `FACEID_FMT_UYVY` | **IR 相机默认格式** | 2 bytes |
| `FACEID_FMT_RGB` | RGB 交错 | 3 bytes |
| `FACEID_FMT_BGR` | BGR 交错 (OpenCV 默认) | 3 bytes |
| `FACEID_FMT_GRAY8` | 8位灰度 | 1 byte |

IR 相机 UYVY 格式说明：`[U0 Y0 V0 Y1] [U2 Y2 V2 Y3] ...`，Y 通道即红外灰度值。

**返回**：检测到的人脸数量。0 = 无人脸，-1 = 错误。`results[0] ~ results[n-1]` 有效。

---

#### FaceResult — 输出结构体

```c
typedef struct {
    // ── 检测 (必出) ──
    float x1, y1, x2, y2;    // 人脸框 (原图坐标)
    float score;              // 置信度 [0,1]
    float kps[5][2];          // 5关键点 (原图坐标)
                              //   0=左眼, 1=右眼, 2=鼻尖, 3=左嘴角, 4=右嘴角

    // ── 活体 (可选, 不跑时=-1) ──
    float liveness;           // 0=fake(翻拍), 1=real(真人)

    // ── 106关键点 (可选, landmarks_valid=1时有效) ──
    float landmarks[106][2];  // 原图坐标
    int landmarks_valid;

    // ── 识别embedding (可选, emb_valid=1时有效) ──
    float emb[512];           // 已L2归一化
    int emb_valid;
} FaceResult;
```

所有坐标均在**原始图像空间** (0~width, 0~height)，上层可直接用于绘图/裁剪。

---

#### faceid_compare — 识别比对

```c
float faceid_compare(const float* emb1, const float* emb2);
```

不需要 Handle，直接在 CPU 上计算余弦相似度。

| 返回值 | 含义 |
|--------|------|
| > 0.30 | 高质量匹配 |
| 0.25～0.30 | 可接受匹配 |
| 0.15～0.25 | 不确定区域 |
| < 0.15 | 不同人 |

### 2.3 完整对接示例

```c
#include "faceid_api.h"
#include <string.h>

// 注册库：预先提取并存储 embedding
typedef struct {
    char name[32];
    float emb[512];
} RegisteredUser;

RegisteredUser g_users[] = {
    {"张三", {0.12, -0.34, ...}},  // 来自 faceid_detect 的输出
    {"李四", {0.56, ...}},
};
int g_num_users = 2;

void on_camera_frame(const uint8_t* uyvy_640x480) {
    FaceResult results[10];
    int n = faceid_detect(g_handle, uyvy_640x480, 640, 480, 0,
                          FACEID_FMT_UYVY, results, 10, NULL);

    for (int i = 0; i < n; i++) {
        // 1. 活体过滤
        if (results[i].liveness < 0.5f) {
            printf("Face %d: SPOOF detected (liveness=%.2f)\n", i, results[i].liveness);
            continue;
        }

        // 2. 身份比对
        float best_sim = 0;
        const char* best_name = "unknown";
        for (int j = 0; j < g_num_users; j++) {
            float sim = faceid_compare(results[i].emb, g_users[j].emb);
            if (sim > best_sim) { best_sim = sim; best_name = g_users[j].name; }
        }

        printf("Face %d: %s (sim=%.2f, bbox=[%.0f,%.0f,%.0f,%.0f])\n",
               i, best_name, best_sim,
               results[i].x1, results[i].y1, results[i].x2, results[i].y2);
    }
}
```

### 2.4 线程安全

- **每个 Handle 绑定一个线程**，不保证多线程共享
- 如需多线程，创建多个 Handle：
  ```c
  h1 = faceid_init("/data/models", "dsp");  // 线程A
  h2 = faceid_init("/data/models", "dsp");  // 线程B
  ```
- `faceid_compare()` 不依赖 Handle，任意线程安全调用

### 2.5 部署步骤

```bash
# 1. 推送 SO 和模型到设备
adb push build/libfaceid.so /system/lib64/
adb push build/*.dlc /data/faceid/models/

# 2. 编译时链接
# Android.mk / CMakeLists.txt 中:
#   LOCAL_LDLIBS += -lfaceid
#   或将 libfaceid.so 放入 jniLibs

# 3. 代码中加载
#   System.loadLibrary("faceid");
```

---

## 三、代码结构与修改说明

### 3.1 文件架构

```
snpe_cpp/
├── faceid_api.h             # [对外] C API 头文件
├── faceid_api.cpp           # [对外] SO 封装层
├── faceid_internal.hpp      # [内部] 共享声明
├── faceid_internal.cpp      # [内部] 共享实现 (pipeline核心)
├── main.cpp                 # [CLI] 命令行工具
├── build.sh                 # [构建] 编译脚本
├── stb/                     # [第三方] 图片读写库 (仅CLI用)
└── build/                   # [产物] 编译输出
```

**依赖关系**：
```
main.cpp ──→ faceid_internal ──→ SNPE SDK (libSNPE.so)
faceid_api.cpp ──→ faceid_internal ──→ SNPE SDK
```

### 3.2 修改内容

#### 本次改动 vs 原始代码

原始 `main.cpp` 将所有逻辑（SNPE加载、预处理、推理、后处理、绘图）混在一个文件里。本次重构做了：

**1. 抽取 faceid_internal（共享层）**

从 `main.cpp` 提取了所有模型无关的 pipeline 逻辑到 `faceid_internal.hpp/.cpp`：

| 模块 | 函数 | 原位置 |
|------|------|--------|
| SNPE 加载 | `build_snpe_from_file()` `create_snpe_context()` | main.cpp |
| 预处理 | `preprocess_det()` `warp_affine()` `preprocess_liveness_full_image()` | main.cpp |
| 仿射变换 | `compute_landmark_transform()` `compute_norm_transform()` `invert_affine()` | main.cpp |
| 检测 | `detect_faces()` `nms()` | main.cpp |
| Pipeline | `run_liveness()` `run_landmark2d()` `run_recog()` | main.cpp |
| 比对 | `cosine_similarity()` `compare_embeddings()` `load_ref_embeddings()` | main.cpp |

**2. 重写 main.cpp（CLI 工具）**

只保留 CLI 相关代码（参数解析、图片读写 `stb_image`、绘图渲染），pipeline 调用改用 `faceid_internal` 接口。

**3. 新增 faceid_api（SO 封装层）**

| 功能 | 实现位置 |
|------|---------|
| UYVY/BGR/GRAY8 → RGB 转换 | `convert_to_rgb()` in faceid_api.cpp |
| 懒加载模型 (首次调用才加载DLC) | `FaceIDContext` struct |
| 共用 scratch buffer (避免每帧 malloc) | `rgb_scratch` in FaceIDContext |
| 结果填充到 FaceResult 结构体 | Step 5 in `faceid_detect()` |

**4. 修改 build.sh**

新增两步：
- Step 2: 编译 `faceid_internal.o` (共享目标文件)
- Step 4: 链接 `libfaceid.so` (SO 动态库)

### 3.3 Pipeline 执行流程

```
faceid_detect(img_data, w, h, format, ...)
  │
  ├─ [Step 0] convert_to_rgb()
  │     UYVY/BGR/GRAY8 → RGB buffer (scratch复用)
  │
  ├─ [Step 1] detect_faces() → det_500m
  │     preprocess_det()        全图 resize 640x640, (v-127.5)/128
  │     execute_snpe(DSP)       9个输出张量 (3 stride x 3 branch)
  │     decode anchors          distance2bbox + distance2kps
  │     nms(0.4)                去重
  │     → faces[] (原图坐标)
  │
  ├─ [Step 2] run_liveness() → face_antispoof (如果启用)
  │     preprocess_liveness_full_image()  全图 resize 128x128
  │     execute_snpe(DSP)                 输出 [fake, real]
  │     softmax → liveness score
  │     → 同一个分数赋给所有检测到的人脸
  │
  ├─ [Step 3] run_landmark2d() → 2d106det (如果启用)
  │     每人脸:
  │       compute_landmark_transform()  bbox中心+1.5倍缩放
  │       warp_affine()                 双线性插值对齐到192x192
  │       execute_snpe(DSP)              输出 212 floats
  │       逆仿射 → 原图坐标
  │
  ├─ [Step 4] run_recog() → w600k_mbf (如果启用)
  │     每人脸:
  │       compute_norm_transform()  5关键点最小二乘相似变换
  │       warp_affine()              双线性+归一化对齐到112x112
  │       execute_snpe(DSP)          输出 512-D
  │       L2 normalize embedding
  │
  └─ [Step 5] Fill FaceResult[]
       faces[i] + liveness + landmarks + emb → 一个结构体
```

### 3.4 各模型预处理对照

| 模型 | 输入尺寸 | 对齐方式 | 归一化 | 插值 |
|------|---------|---------|--------|------|
| det_500m | 640x640 | 全图resize | `(v-127.5)/128` | 最近邻 |
| face_antispoof | 128x128 | 全图resize | `(v-127.5)/128` | 最近邻 |
| 2d106det | 192x192 | bbox中心+1.5x缩放 | 不归一化(raw) | 双线性 |
| w600k_mbf | 112x112 | 5点仿射+ArcFace模板 | `(v-127.5)/127.5` | 双线性 |

关键差异：
- det 和 antispoof 用全图（不裁剪人脸），其他两个用人脸区域对齐
- w600k_mbf 的 std=127.5，其他=128.0
- 2d106det 预处理不做归一化（归一化在 ONNX 模型内部）

### 3.5 关键设计决策

| 决策 | 说明 |
|------|------|
| **模型懒加载** | 首次 `faceid_detect()` 时才加载DLC，避免初始化卡顿 |
| **Scratch buffer 复用** | RGB 转换缓冲区每帧复用，无 malloc/free |
| **活体结果共享** | face_antispoof 是全图推理，同一得分赋给所有人脸 |
| **坐标空间统一** | 所有输出坐标均为原始图像空间，上层无需转换 |
| **C ABI 接口** | `extern "C"` + 不透明 Handle，跨编译器兼容 |

### 3.6 性能数据 (QCS6125 DSP)

640x480 UYVY 输入，1 张人脸：

| 阶段 | 耗时 | 备注 |
|------|------|------|
| det_500m 预处理 | 8.8ms | CPU |
| det_500m 推理 | 26.2ms | DSP INT8 |
| det_500m 后处理 | 0.0ms | CPU |
| face_antispoof | 7.1ms | DSP |
| 2d106det | 10.0ms | DSP (per face) |
| w600k_mbf | 14.2ms | DSP (per face) |
| **总计 (全开)** | **~67ms** | |

---

## 四、FAQ

**Q: 如果只跑检测，需要部署所有 DLC 吗？**
A: 不需要。只部署启用的模型。但 `faceid_detect` 总是需要 `det_500m_int8.dlc`。

**Q: 活体得分突然变成 -1 怎么办？**
A: 检查 `face_antispoof_int8.dlc` 是否存在且没损坏。`-1` 表示模型加载或推理失败。

**Q: 如何注册新用户？**
A: 用 `faceid_detect` 提取 `FaceResult.emb`，保存到你的数据库。比对时调用 `faceid_compare()`。

**Q: 支持同时检测多个人脸吗？**
A: 支持，`max_faces` 上限 16。但 2d106det 和 w600k_mbf 的耗时随人数线性增长。

**Q: UYVY 输入需要做什么预处理？**
A: 不需要。`faceid_detect` 内部自动完成 UYVY → RGB 转换。
