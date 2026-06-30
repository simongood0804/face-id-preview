# Face ID 渲染模块功能说明书

> 版本：1.0.0  
> 最后更新：2026-06-30  
> 平台：QCS6125 + Android 10 + EVS Camera

---

## 一、概述

Face ID 预览应用通过 EVS（External View System）摄像头获取 DMS IR 视频帧，经 SNPE DSP 加速的 4 模型 Pipeline，实现实时人脸检测、活体判断、身份识别与自动录入，并在 GLSurfaceView 上叠加绘制人脸框。

---

## 二、整体架构

```
┌──────────────────────────────────────────────────────────────────┐
│                      PreviewActivity                            │
│  ┌──────────┐  ┌──────────────┐  ┌─────────────┐  ┌───────────┐ │
│  │ Camera   │  │ FaceIDCamera │  │ EvsGL20     │  │ FaceID    │ │
│  │ Manager  │──│ Controller   │──│ Camera      │  │ Algorithm │ │
│  │          │  │ (EvsBuffer   │  │ Renderer    │  │ Impl      │ │
│  │          │  │  Provider)   │  │             │  │           │ │
│  └──────────┘  └──────┬───────┘  └──────┬──────┘  └─────┬─────┘ │
│                        │                │                │       │
│                        ▼                ▼                ▼       │
│  ┌──────────┐  ┌──────────────┐  ┌─────────────┐  ┌───────────┐ │
│  │ JNI      │  │ GLSurfaceView│  │ FaceOverlay │  │ Face      │ │
│  │ faceid_  │  │ (preview)    │  │ View        │  │ Enrollment│ │
│  │ jni.cpp  │  │              │  │ (画框)       │  │ Manager   │ │
│  └──────────┘  └──────────────┘  └─────────────┘  └───────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

### 模块职责

| 模块 | 职责 |
|---|---|
| `PreviewActivity` | 应用入口，协调各模块生命周期 |
| `FaceIDCameraController` | EVS 摄像头取流、帧队列管理、自动重连 |
| `CameraManager` | 摄像头打开/关闭封装，帧率统计 |
| `EvsGL20CameraRenderer` | GLSurfaceView 渲染器（EvsSDK 提供，闭源）|
| `FaceIDAlgorithmImpl` | 算法 Pipeline JNI 桥接 |
| `FaceEnrollmentManager` | 人脸特征录入、持久化、识别比对 |
| `FaceOverlayView` | 人脸框覆盖层绘制 |
| `faceid_jni.cpp` | JNI 原生桥接：HardwareBuffer 读取 + 算法调用 |

---

## 三、渲染管线

### 3.1 数据流

```
[EVS Camera HAL]
       │
       ▼
[onFrameEvent]  ─── 回调，帧到达
       │
       ▼
[FaceIDCameraController.queue()]  ─── 入帧队列
       │
       ▼
[EvsGL20CameraRenderer.onDrawFrame()]
       │
       ├──→ getNewFrame()  ─── 从队列取帧
       │       │
       │       ├──→ onFrameSizeChanged()  ─── 调整 GLSurfaceView 尺寸
       │       │
       │       └──→ onFrameData()  ─── 每 5 帧触发一次算法
       │               │
       │               ▼
       │       [processWithAlgorithm()]
       │          GL 线程
       │               │
       │               ├──→ nativeReadHardwareBuffer()
       │               │       AHardwareBuffer_lock → memcpy → byte[]
       │               │
       │               └──→ mAlgoExecutor.submit { }  ─── AlgoProcessor 线程
       │                       │
       │                       ▼
       │               [mAlgorithm.processFrame()]
       │                       │
       │                       ├──→ nativeDetect()  ─── faceid_detect() on DSP
       │                       │       returns: bbox + liveness + emb[512]
       │                       │
       │                       ├──→ mEnrollmentManager.recognize()
       │                       │       比对已有特征，或自动录入
       │                       │
       │                       └──→ runOnUiThread {
       │                               mFaceIdText.text = 显示人名
       │                               mFaceOverlay.setFaces()  ─── 画框
       │                               Toast (新录入)
       │                           }
       │
       └──→ GL 渲染 —— 将帧绘制到屏幕
```

### 3.2 线程模型

```
Main Thread (UI)
├── Activity 生命周期 (onCreate, onPause, onDestroy...)
├── UI 更新 (runOnUiThread)
│   ├── mFaceIdText.text
│   ├── mFaceOverlay.setFaces()
│   └── Toast
├── 帧尺寸回调 (runOnUiThread 内)
│   └── resizePreviewSurface()
└── Camera 控制 (startPreview/stopPreview)

GLThread (渲染线程)
├── EvsGL20CameraRenderer.onDrawFrame()
├── FaceIDCameraController.getNewFrame()
└── processWithAlgorithm() 中的 nativeReadHardwareBuffer()

AlgoProcessor (单线程)
├── FaceIDAlgorithmImpl.processFrame()
│   ├── nativeDetect()  ─── DSP 推理
│   └── 后续处理
└── 结果分发到 UI 线程
```

### 3.3 帧率控制

- 算法处理跳帧：每 5 帧处理一次（`FRAME_SKIP = 5`）
- 渲染帧率：由 EVS 系统帧率控制，`GLSurfaceView.RENDERMODE_CONTINUOUSLY`
- 算法耗时目标：< 100ms（单帧，含检测 + 活体 + 关键点 + 特征提取）

---

## 四、各模块详细说明

### 4.1 摄像头取流 — FaceIDCameraController

**文件：** `app/src/main/java/com/skyworth/faceid/camera/FaceIDCameraController.kt`

实现 `EvsBufferProvider` 接口，管理 EVS 帧缓冲区队列。

**关键配置：**
| 参数 | 值 | 说明 |
|---|---|---|
| `MAX_RECEIVE_FRAME` | 6 | 帧队列最大容量 |
| `RETRY_INTERVAL_MS` | 1000ms | 断线重连检查间隔 |
| `RETRY_WAIT_MS` | 600ms | 重连前等待 |
| `FRAME_SKIP` | 5 | 每 N 帧触发一次算法 |
| `mConnectCount` 上限 | 65533 | 最大重试次数 |

**核心方法：**

#### getNewFrame()
```
线程：GLThread
触发：EvsGL20CameraRenderer.onDrawFrame()
流程：
1. 遍历 buffers，找到状态为 DEQUEUE 的 desc
2. 若为首次获取帧，记录尺寸并通过 onFrameSizeChanged 回调
3. 跳帧计数器自增，每 FRAME_SKIP 次触发 onFrameData 回调
   └→ 传递 HardwareBuffer + width + height 给算法处理层
4. 回收旧 descriptor，设置新 descriptor
5. 返回 descriptor 供 GL 渲染
```

#### onFrameEvent()
```
线程：EVS HAL 回调线程
流程：
1. buffer 非空检查
2. returnBuffers() 回收上一批 buffer
3. 首帧到达时启动帧率统计
4. 重置重连计时器
5. frameRate.post() 记录帧
6. 将帧入队到 buffers 的空闲 slot
7. 若队列满则 drop frame
```

**断线重连机制：**
```kotlin
mHandler.postDelayed(mConnectRunnable, RETRY_INTERVAL_MS)
// mConnectRunnable: 关闭→释放→等待→重新打开
// 最大重试 65533 次
// 收到新帧时重置计时器
```

### 4.2 算法 Pipeline — FaceIDAlgorithmImpl

**文件：** `app/src/main/java/com/skyworth/faceid/algorithm/FaceIDAlgorithmImpl.kt`

JNI 桥接类，封装原生 `libfaceid.so` 的完整 Pipeline。

#### 初始化流程

```
1. extractModels()      —— 从 assets/models/ 解压 DLC 文件到 filesDir/models/
2. nativeInit()          —— faceid_init()，创建 DSP 推理上下文
3. nativeConfigure()     —— faceid_configure(FACEID_FLAG_ALL = 0x0F)
                          └→ 启用全部 4 个模型：DET | LIVENESS | LANDMARK | RECOG
4. nativeVersion()       —— 获取版本号
```

**模型文件：**
| 模型 | 文件 | 功能 | 输出 |
|---|---|---|---|
| DET | `det_500m_int8.dlc` | 人脸检测 | bbox + 5 keypoints + score |
| LIVENESS | `face_antispoof_int8.dlc` | 活体检测 | liveness [0,1] |
| LANDMARK | `2d106det_int8.dlc` | 106 点关键点 | landmarks[106][2] |
| RECOG | `w600k_mbf_int8.dlc` | 人脸识别 | emb[512] |

#### processFrame() 处理流程

```
输入: byte[] UYVY, width, height, format=0
1. 调用 nativeDetect() → faceid_detect() 在 DSP 上运行全部 4 个模型
2. 取 results[0]（首个人脸）
3. 若 EnrollmentManager 存在且 emb 有效（512-D）：
   └→ enrollMgr.recognize(emb, score, liveness)
       ├── 与已录入特征比对，找最佳匹配
       ├── 阈值 > 0.30 → 返回匹配人名
       └── 未匹配且高置信度 → 自动录入
4. 若无 EnrollmentManager 或 emb 无效：
   └→ liveness < 0 或 > 0.5 → "detected"
      liveness <= 0.5 → "spoof"
5. 返回 FaceIDResult(faceId, confidence, faceRect, isNewEnrollment)
```

#### JNI 函数映射

| Java native 方法 | C++ 实现 | 调用原生 API |
|---|---|---|
| `nativeInit()` | `Java_com_skyworth_faceid_algorithm_FaceIDAlgorithmImpl_nativeInit` | `faceid_init()` |
| `nativeConfigure()` | `Java_com_skyworth_faceid_algorithm_FaceIDAlgorithmImpl_nativeConfigure` | `faceid_configure()` |
| `nativeDetect()` | `Java_com_skyworth_faceid_algorithm_FaceIDAlgorithmImpl_nativeDetect` | `faceid_detect()` |
| `nativeCompare()` | `Java_com_skyworth_faceid_algorithm_FaceIDAlgorithmImpl_nativeCompare` | `faceid_compare()` |
| `nativeDestroy()` | `Java_com_skyworth_faceid_algorithm_FaceIDAlgorithmImpl_nativeDestroy` | `faceid_destroy()` |
| `nativeVersion()` | `Java_com_skyworth_faceid_algorithm_FaceIDAlgorithmImpl_nativeVersion` | `faceid_version()` |

### 4.3 HardwareBuffer 读取 — faceid_jni.cpp

**文件：** `app/src/main/cpp/faceid_jni.cpp`

**关键函数：** `Java_com_skyworth_faceid_ui_PreviewActivity_nativeReadHardwareBuffer`

```cpp
流程：
1. AHardwareBuffer_fromHardwareBuffer(env, hwBuffer)
   └→ 标准 NDK 桥接 API，获取原生 AHardwareBuffer* 指针
2. AHardwareBuffer_lock(buffer, CPU_READ_OFTEN, fence=-1, rect=NULL, &data)
   └→ 获取 CPU 可读指针（此时 GPU 已完成写入）
3. memcpy(dst, src, width * 2 * height)
   └→ UYVY 格式：2 bytes/pixel，紧密排列
4. AHardwareBuffer_unlock(buffer, NULL)
```

**依赖的 NDK 库：**
```cmake
target_link_libraries(faceid_jni
    nativewindow   # AHardwareBuffer_lock / unlock
    ${android-lib} # AHardwareBuffer_fromHardwareBuffer
    log)
```

### 4.4 人脸录入管理 — FaceEnrollmentManager

**文件：** `app/src/main/java/com/skyworth/faceid/algorithm/FaceEnrollmentManager.kt`

#### 关键参数

| 参数 | 值 | 说明 |
|---|---|---|
| `MATCH_THRESHOLD` | 0.30 | 余弦相似度识别阈值 |
| `ENROLL_CONFIDENCE` | 0.60 | 自动录入所需最低置信度 |
| 山海经名称数 | 35 | 可分配的名称数量 |

#### recognize() 逻辑

```
输入: emb[512], score, liveness
1. 匹配阶段（遍历 mGallery）
   └→ 对每个已录入特征调用 faceid_compare()
   └→ 取最佳匹配（最高余弦相似度）
   └→ 若 >= 0.30 → 返回匹配人名（isNewEnroll=false）
2. 自动录入阶段（未匹配 + 高置信度 + 非 spoof）
   └→ pickName():
       ├── 优先选未使用的山海经名称
       └── 全用完后 → 替换 mGallery 中最早录入的人
   └→ enroll(name, emb) → save() → 返回（isNewEnroll=true）
3. 都不满足 → 返回 null
```

#### 持久化

- **路径：** `context.filesDir/face_enrollments.json`
- **格式：**
```json
[
  {
    "name": "饕餮",
    "emb": [0.0123, -0.0456, ...]  // 512 个 float
  }
]
```
- **加载时机：** 构造函数 `init` 块
- **保存时机：** 每次 `enroll()` 调用后

### 4.5 人脸框绘制 — FaceOverlayView

**文件：** `app/src/main/java/com/skyworth/faceid/ui/FaceOverlayView.kt`

#### 坐标映射

```
算法返回的坐标：原图空间 (1600x1300)
Overlay View 坐标：View 空间

scaleX = overlayView.width / imgW
scaleY = overlayView.height / imgH

绘制坐标：
  left   = face.rect.left   * scaleX
  top    = face.rect.top    * scaleY
  right  = face.rect.right  * scaleX
  bottom = face.rect.bottom * scaleY
```

#### 绘制内容

```
┌─────────────────────────────┐
│ detected / 饕餮             │ ← 标签文字（白字+半透明黑底）
│                             │
│        ┌─────────┐          │ ← 4px 色框
│        │         │          │   绿色 = detected / 已识别
│        │         │          │   红色 = spoof
│        │         │          │
│        └─────────┘          │
│             53.7%           │ ← 置信度文字
└─────────────────────────────┘
```

### 4.6 视口适配 — resizePreviewSurface()

**文件：** `PreviewActivity.kt` 第 204-232 行

```
约束：保持原始宽高比，靠左上方显示，黑色背景填充未覆盖区域

frameAspect = frameW / frameH   (如 1600/1300 ≈ 1.23)
parentAspect = parentW / parentH

若 frameAspect > parentAspect（画面更宽）：
  宽度占满 parent，高度按比例缩放
否则（画面更高）：
  高度占满 parent，宽度按比例缩放

Layout 约束：
  topToTop + startToStart = parent
  rightToRight = UNSET     （保持左侧贴边）
  bottomToTop = UNSET      （高度由计算值决定）
```

---

## 五、配置与常量汇总

| 常量 | 位置 | 值 | 说明 |
|---|---|---|---|
| `FRAME_SKIP` | `FaceIDCameraController` | 5 | 算法跳帧间隔 |
| `MATCH_THRESHOLD` | `FaceEnrollmentManager` | 0.30 | 人脸识别阈值 |
| `ENROLL_CONFIDENCE` | `FaceEnrollmentManager` | 0.60 | 自动录入置信度阈值 |
| `FACEID_FLAG_ALL` | `FaceIDAlgorithmImpl` | 0x0F | 启用全部 4 个模型 |
| `MAX_RECEIVE_FRAME` | `FaceIDCameraController` | 6 | EVS 帧队列容量 |
| Camera 分辨率 | EVS HAL | 1600×1300 | DMS IR 摄像头 |
| 图像格式 | EVS HAL | UYVY | 2 bytes/pixel |

---

## 六、构建与部署

### 6.1 构建命令

```bash
make build        # 编译 debug APK
make push-system  # 编译 release + 推送 /system/app/ + 重启
make run          # 启动应用
```

### 6.2 依赖

- **NDK：** 25.2.9519653
- **CMake：** 3.18.1
- **libfaceid.so：** 预编译，`arm64-v8a` 架构
- **EvsSDK：** `com.android.car:evs:1.0.7`（AOSP 系统库）

### 6.3 文件结构

```
app/
├── src/main/
│   ├── assets/models/
│   │   ├── det_500m_int8.dlc        # 人脸检测模型
│   │   ├── face_antispoof_int8.dlc  # 活体检测模型
│   │   ├── 2d106det_int8.dlc        # 106 点关键点模型
│   │   └── w600k_mbf_int8.dlc       # 人脸识别模型
│   ├── cpp/
│   │   ├── CMakeLists.txt           # CMake 构建配置
│   │   ├── faceid_api.h             # 原生 API 头文件
│   │   ├── faceid_jni.cpp           # JNI 桥接实现
│   │   └── lib/arm64-v8a/libfaceid.so  # 预编译算法库
│   ├── jniLibs/arm64-v8a/libfaceid.so  # APK 打包用
│   ├── java/com/skyworth/faceid/
│   │   ├── ui/
│   │   │   ├── PreviewActivity.kt   # 主界面
│   │   │   └── FaceOverlayView.kt   # 画框覆盖层
│   │   ├── algorithm/
│   │   │   ├── IFaceIDAlgorithm.kt  # 算法接口
│   │   │   ├── FaceIDAlgorithmImpl.kt # 算法实现 + JNI
│   │   │   └── FaceEnrollmentManager.kt # 录入管理
│   │   └── camera/
│   │       ├── CameraManager.kt     # 摄像头管理器
│   │       └── FaceIDCameraController.kt # EVS 取流控制
│   └── res/layout/activity_preview.xml
├── build.gradle.kts
└── docs/
    ├── FaceID_SO对接说明.md
    └── FaceID_渲染模块说明书.md      # 本文档
```
