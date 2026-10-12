# EVS SDK 对接接口说明

> 版本：2026-08-21  
> 范围：DMS 摄像头（EVS HAL）的取帧、渲染与缓冲管理对接  
> 包：`com.android.car.evs`

---

## 1. EVS SDK 概览

EVS（Enhanced Vision System）SDK 封装了车载摄像头（EVS HAL）的取帧、渲染与缓冲管理。项目对接使用的核心类：

| 类 | 职责 |
|----|------|
| `EvsHalWrapper` / `EvsHalWrapperImpl` | 封装 CarEvsService 连接、打开/关闭摄像头、流控、帧回调 |
| `EvsBufferProvider` | 帧缓冲提供者接口（渲染层取帧） |
| `EvsBufferDesc` | 帧缓冲描述符（含 HardwareBuffer 与状态机） |
| `EvsGL20CameraRenderer` | GLES20 渲染器（把帧渲染到 GLSurfaceView） |
| `EvsExecutorService` | EVS 专用线程池（单线程调度） |
| `EvsFrameRate` | 帧率统计 |
| `CameraIds` | 摄像头 ID 定义（如 `DMS`） |
| `OpaqueIdentifier` | 相机扩展信息键（如 `RESOLUTION`） |

---

## 2. 相机源对接（FaceIDCameraController）

### 2.1 核心实现

`FaceIDCameraController`（`camera/FaceIDCameraController.kt`）同时实现：

```kotlin
class FaceIDCameraController : EvsBufferProvider, FrameSource {
```

- **`EvsBufferProvider`**：供 GL 渲染层取帧（`getNewFrame()`）
- **`FrameSource`**：供帧分发层取帧（`start()`/`stop()`/`onFrameData`）

### 2.2 关键回调（HalEventCallback）

```kotlin
private val callback = object : EvsHalWrapper.HalEventCallback {
    override fun onFrameEvent(i: Int, buffer: HardwareBuffer?) {
        // HAL 每帧回调：
        // 1. returnBuffers() 回收 RECYCLE 状态的缓冲
        // 2. 从空闲缓冲池 queue 当前帧
        // 3. onFrameData?.invoke(buffer, w, h) 触发算法处理（独立于渲染取帧）
    }
    override fun onHalDeath() {
        // HAL 进程死亡：reset 缓冲，触发重连
    }
}
```

### 2.3 打开/关闭摄像头

```kotlin
// 打开（异步，支持断线自动重试）
controller.startCamera(CameraIds.DMS)

// 关闭
controller.stopCamera()

// 释放 EVS 服务
controller.release()
```

打开流程（`handleStartVideoStream`）：
1. `connectToHalServiceIfNecessary()` 连接 CarEvsService
2. `openCamera(cameraId)` 打开摄像头
3. `getExtendedInfo(OpaqueIdentifier.RESOLUTION)` 获取分辨率
4. `requestToStartVideoStream()` 开始推流
5. `resetBuffers()` 重置缓冲池

### 2.4 缓冲池管理（多线程安全）

缓冲池用 `synchronizedList` + `bufferLock` 保护。`EvsBufferDesc.state` 状态机：
```
NONE → QUEUE（HAL 入队）→ DEQUEUE（渲染取帧）→ RECYCLE（回收）→ NONE
```

- **HAL 回调线程**：`onFrameEvent` 中 `queue()`
- **GL 渲染线程**：`getNewFrame()` 中 `dequeue()`
- **调度线程**：`resetBuffers()`/`returnBuffers()` 中 `recovery()`

---

## 3. 相机管理（CameraManager）

`CameraManager`（`camera/CameraManager.kt`）封装控制器生命周期：

```kotlin
class CameraManager(private val controller: FaceIDCameraController) {
    fun openCamera(): Boolean   // 打开 DMS 摄像头 + 启动流
    fun stopCamera()            // 停止流
    fun close()                 // 释放
}
```

---

## 4. GL 渲染（EvsGL20CameraRenderer）

### 4.1 对接方式

```kotlin
val renderer = EvsGL20CameraRenderer().apply {
    setProvider(controller)   // 绑定相机源（FrameSource/EvsBufferProvider）
}
surface.setEGLContextClientVersion(2)   // 必须 GLES 2.0（shader 用 #version 300 es）
surface.setRenderer(renderer)
surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
```

### 4.2 渲染生命周期（GLSurfaceView.Renderer）

| 回调 | 作用 |
|------|------|
| `onSurfaceCreated` | 编译 shader + 生成纹理（shader 用 `#version 300 es`） |
| `onSurfaceChanged` | 设置 glViewport，按相机数重建视口分区 |
| `onDrawFrame` | 从 provider `getNewFrame()` 取帧 → `nUpdateTexture` 上传 → 绘制 |

### 4.3 关键注意事项

- **每个 GLSurfaceView 必须新建独立 renderer**：`EvsGL20CameraRenderer` 内部 shader/texture 是 GL 上下文相关的，跨 GLSurfaceView/GL 上下文复用会在 `onSurfaceCreated` 的 `loadShader` 崩溃（SIGSEGV）。
- **必须 `setEGLContextClientVersion(2)`**：shader 用 GLES 3.0（`#version 300 es`），若 EGL 上下文版本不对，`GLES20.glCreateShader` 会空指针崩溃。
- 帧格式：纹理按 YUV（UYVY）上传，fragment shader 内做 YUV→RGB 转换。

---

## 5. 缓冲描述符（EvsBufferDesc）

```kotlin
class EvsBufferDesc {
    enum class State { NONE, QUEUE, DEQUEUE, RECYCLE }

    val hardwareBuffer: HardwareBuffer?   // 帧数据
    val id: Int                           // 帧 ID
    val width / height: Int               // 分辨率
    val state: State                      // 状态机

    fun queue(i: Int, buf: HardwareBuffer, resolution: Long): Boolean  // HAL 入队
    fun dequeue(): Boolean                                            // 渲染取帧
    fun recovery()                                                    // 重置
    companion object { fun recycle(value: EvsBufferDesc) }            // 标记回收
}
```

---

## 6. 帧分发链路（取帧 → 算法）

```
EVS HAL
  ↓ onFrameEvent(HardwareBuffer)
FaceIDCameraController（EvsBufferProvider/FrameSource）
  ├─ onFrameData → FrameDistributor（算法帧处理）→ FaceIDAlgorithmImpl
  └─ getNewFrame → EvsGL20CameraRenderer（GL 渲染预览）
```

### 6.1 算法取帧（FrameDistributor）

```kotlin
FrameDistributor(
    frameSource = controller,        // FaceIDCameraController
    frameProcessor = frameProcessor, // 算法帧处理
    readFrame = { hw, w, h ->       // JNI 读取 HardwareBuffer → UYVY ByteArray
        NativeFrameReader.readHardwareBuffer(hw, w, h)
    },
    algorithmEnabled = { true }
)
```

- `readFrame` 在 JNI 侧做 **HardwareBuffer → UYVY ByteArray**（快速 memcpy）+ 黑帧检测
- 算法线程里再 **UYVY → RGB** 并裁剪 900×900 ROI 供 `FaceIDAlgorithmImpl` 推理

### 6.2 JNI 读取（NativeFrameReader）

```kotlin
object NativeFrameReader {
    init { System.loadLibrary("hardware_buffer_reader") }  // CMake 构建

    fun readHardwareBuffer(hw: HardwareBuffer, w: Int, h: Int): ByteArray? =
        nativeReadHardwareBuffer(hw, w, h)

    private external fun nativeReadHardwareBuffer(
        hwBuffer: HardwareBuffer, width: Int, height: Int
    ): ByteArray?
}
```

native 实现（`app/src/main/cpp/hardware_buffer_reader.cpp`）：
- `ACameraBuffer_lock` 锁定 HardwareBuffer
- 快速 `memcpy` 到 ByteArray
- 黑帧检测（若全 0 返回 null）
- JNI 符号绑定 `NativeFrameReader`（非某 Activity，便于多模块复用）

---

## 7. 关键注意事项

### 7.1 线程模型
| 线程 | 职责 |
|------|------|
| HAL 回调线程 | `onFrameEvent`（入队 + 触发算法） |
| GL 渲染线程（GLThread） | `getNewFrame` + `onDrawFrame` |
| 算法线程（AlgoProcessor） | `readFrame` + UYVY→RGB + 推理 |
| 主线程 | 相机 open/stop、GLSurfaceView 配置 |

### 7.2 生命周期管理
- **GLSurfaceView**：`onResume`→`onResume()`，`onPause`→`onPause()`（停止 GLThread，避免渲染已释放资源崩溃）
- **相机**：模块 `onStop` → `CameraManager.stopCamera()`，计数归 0 → `release()`
- **`EvsGL20CameraRenderer`**：每 GLSurfaceView 独立新建（不可复用）

### 7.3 常见崩溃规避
| 问题 | 规避 |
|------|------|
| `EvsGL20CameraRenderer.loadShader` SIGSEGV | 每个 GLSurfaceView 独立 renderer + `setEGLContextClientVersion(2)` |
| 返回时 GLThread 渲染已释放资源 | `onPause` 调 `mSurface.onPause()` |
| 缓冲状态竞争 | `bufferLock` 统一保护 EvsBufferDesc.state |
| HardwareBuffer 泄漏 | `returnBuffer` 关闭非 lastFrame 的 buffer |

---

## 8. 模块化对接（FACEP-011）

三个功能模块通过 `FrameSession`（单例）复用相机源，`configureSurface` 统一配置渲染：

```kotlin
// FrameSession（相机+帧分发单例会话）
frame.configureSurface(surface, overlay) {
    // 内部：
    // - setEGLContextClientVersion(2)
    // - setRenderer(createRenderer())   // 每模块新建 renderer
    // - controller.onFrameSizeChanged → resizePreviewSurface  // 保持 1600×1300 比例
}
```

- **相机源（FaceIDCameraController）单例共享**（一个相机）
- **renderer 每模块新建**（避免 GL 上下文冲突）
- **overlay 随 surface 缩放**（人脸框/点位与画面对齐）

---

## 9. 详细 API 表格

> 本节为 `com.android.car:evs:1.0.7`（EvsSDK）与项目对接类的**完整接口清单**，
> 表格列说明：`签名`（返回值 + 方法名 + 参数）、`线程`（调用发生线程）、`用途`。

### 9.1 `EvsHalWrapper`（抽象类，HAL 封装基类）

> 生命周期：`init` → `connectToHalServiceIfNecessary` → `openCamera` → `requestToStartVideoStream` →（帧回调）→ `requestToStopVideoStream` → `closeCamera` → `release`。

| 方法 | 签名 | 线程 | 用途 |
|------|------|------|------|
| `init` | `boolean init()` | 任意 | 初始化 EVS HAL，成功返回 `true` |
| `release` | `void release()` | 任意 | 断开 CarEvsService 并释放资源 |
| `isConnected` | `boolean isConnected()` | 任意 | 查询是否已连接 CarEvsService |
| `connectToHalServiceIfNecessary` | `boolean connectToHalServiceIfNecessary()` | Worker | 按需连接 CarEvsService，未连接则建立连接 |
| `openCamera` | `boolean openCamera(String cameraId)` | Worker | 打开指定摄像头（cameraId 见 `CameraIds`） |
| `closeCamera` | `void closeCamera()` | Worker | 关闭当前摄像头 |
| `requestToStartVideoStream` | `boolean requestToStartVideoStream()` | Worker | 请求开始视频推流，成功返回 `true` |
| `requestToStopVideoStream` | `void requestToStopVideoStream()` | Worker | 请求停止视频推流 |
| `doneWithFrame` | `void doneWithFrame(int bufferId)` | 帧/调度 | 通知 HAL 该帧已处理完毕，可回收 |
| `getExtendedInfo` | `long getExtendedInfo(long infoType)` | Worker | 获取扩展信息，如 `OpaqueIdentifier.RESOLUTION` |

**`HalEventCallback` 接口：**

| 回调 | 签名 | 线程 | 用途 |
|------|------|------|------|
| `onFrameEvent` | `void onFrameEvent(int bufferId, HardwareBuffer buffer)` | HAL 回调线程 | 每收到一帧触发；入队缓冲池并驱动算法处理 |
| `onHalDeath` | `void onHalDeath()` | HAL 回调线程 | HAL 进程死亡；重置缓冲池、触发重连 |

### 9.2 `EvsHalWrapperImpl`（HAL 封装具体实现）

| 构造 | 签名 | 说明 |
|------|------|------|
| 构造器 | `EvsHalWrapperImpl(HalEventCallback callback)` | 传入帧/死亡回调，驱动上层取帧 |

> 其余方法签名与 `EvsHalWrapper` 基类一致（`openCamera`/`closeCamera`/`requestToStartVideoStream`/`requestToStopVideoStream`/`getExtendedInfo`/`connectToHalServiceIfNecessary`/`isConnected`/`doneWithFrame`/`release`）。

### 9.3 `EvsBufferProvider`（接口，帧缓冲提供者）

| 方法 | 签名 | 线程 | 用途 |
|------|------|------|------|
| `getNewFrame` | `EvsBufferDesc getNewFrame()` | GL 渲染线程 | 取一帧最新缓冲（无新帧返回 null）；供渲染层 `onDrawFrame` 调用 |

### 9.4 `EvsBufferDesc`（帧缓冲描述符 + 状态机）

| 字段/方法 | 签名 | 说明 |
|-----------|------|------|
| 枚举 `State` | `NONE / QUEUE / DEQUEUE / RECYCLE` | 缓冲状态机 |
| 字段 `id` | `public int id = -1` | 帧 ID（HAL 分配） |
| `getHardwareBuffer` | `HardwareBuffer getHardwareBuffer()` | 获取帧 HardwareBuffer（可能为 null/closed） |
| `getId` | `int getId()` | 帧 ID |
| `getWidth` | `int getWidth()` | 帧宽（由 `queue` 内分辨率或 buffer 尺寸决定） |
| `getHeight` | `int getHeight()` | 帧高 |
| `getState` | `State getState()` | 当前状态 |
| `queue` | `boolean queue(int i, HardwareBuffer buf, long resolution)` | HAL 入队：写入 id/buffer、解包分辨率（高 16 位宽、低 16 位高）置 `QUEUE` |
| `dequeue` | `boolean dequeue()` | 渲染取帧：仅 `QUEUE` 态可执行，置 `DEQUEUE` |
| `recovery` | `void recovery()` | 重置描述符（id/buffer/宽高/state 归零） |
| 静态 `recycle` | `void recycle(EvsBufferDesc value)` | 标记 `RECYCLE`（待 HAL 回收） |

**状态机流转：**
```
NONE →(queue) QUEUE →(dequeue) DEQUEUE →(recycle) RECYCLE →(recovery) NONE
```

### 9.5 `EvsGL20CameraRenderer`（GLES20 渲染器）

> 实现 `GLSurfaceView.Renderer`，shader 用 `#version 300 es`（GLES 3.0），因此**必须** `setEGLContextClientVersion(2)`。

| 方法/回调 | 签名 | 线程 | 用途 |
|-----------|------|------|------|
| 构造器 | `EvsGL20CameraRenderer()` | - | 新建渲染器（每 GLSurfaceView 独立） |
| `setProvider` | `void setProvider(EvsBufferProvider value)` | 主线程 | 绑定单个相机源 |
| `setProvider` | `void setProvider(List<EvsBufferProvider> values, int columns)` | 主线程 | 绑定多个相机源并按列网格分屏 |
| `onSurfaceCreated` | `void onSurfaceCreated(GL10, EGLConfig)` | GLThread | 编译 shader 程序 + 生成纹理（GL 上下文相关） |
| `onSurfaceChanged` | `void onSurfaceChanged(GL10, int w, int h)` | GLThread | 设 glViewport，按相机数重建视口分区 |
| `onDrawFrame` | `void onDrawFrame(GL10)` | GLThread | `getNewFrame()` 取帧 → `nUpdateTexture` 上传 → YUV→RGB → 绘制 |

**注意**：`nUpdateTexture` 为 JNI（`android.car.evs.jni` 库），内部将 UYVY HardwareBuffer 上传为纹理；fragment shader 内做 YUV→RGB 转换。

### 9.6 `EvsExecutorService`（EVS 专用调度器）

| 构造/方法 | 签名 | 用途 |
|-----------|------|------|
| 构造器 | `EvsExecutorService(String name, boolean scheduled)` | 单线程调度器（`scheduled=true` 用 ScheduledThreadPool） |
| 构造器 | `EvsExecutorService(String name, int threads, boolean scheduled)` | 多线程调度器 |
| `submit` | `void submit(Runnable runnable, long timeout, String mark)` | 提交任务，超时（默认 500ms）自动 `cancel(true)` 防阻塞 |
| `submit` | `void submit(Runnable runnable, String mark)` | 提交任务，使用默认超时 500ms |

> 用于相机打开/关闭、缓冲池重置等**需要防止阻塞**的调度。

### 9.7 `EvsFrameRate`（帧率统计，LiveData）

| 方法 | 签名 | 用途 |
|------|------|------|
| 构造器 | `EvsFrameRate()` | 默认 1s 统计窗口 |
| 构造器 | `EvsFrameRate(long ms)` | 自定义统计窗口（不小于 1s） |
| `getValue` | `LiveData<Integer> getValue()` | 观察帧率（主线程回调） |
| `post` | `void post()` | 收到帧时计数 |
| `start` | `void start()` | 启动统计（首帧后调用） |
| `stop` | `void stop()` | 停止并清零 |

### 9.8 `EvsCameraController`（SDK 自带控制器）

> 项目用 `FaceIDCameraController`（自定义，增加断线重试）**替换**了此类，此处列出供对照。

| 方法 | 签名 | 用途 |
|------|------|------|
| `startCamera` | `void startCamera(String cameraId)` | 打开摄像头（内部经 dExecutor 提交） |
| `stopCamera` | `void stopCamera()` | 关闭摄像头 |
| `release` | `void release()` | 断开 EVS 服务 |
| `track` | `void track(EvsFrameRate value)` | 绑定帧率跟踪器 |
| `getNewFrame` | `EvsBufferDesc getNewFrame()` | 取帧（`EvsBufferProvider`） |

### 9.9 常量类

**`CameraIds`**（摄像头 ID）：

| 常量 | 值 | 说明 |
|------|----|------|
| `RVC` | `"RVC"` | 后视摄像头 |
| `FVC` | `"FVC"` | 前视摄像头 |
| `LBS` | `"LBS"` | 左侧盲区 |
| `RBS` | `"RBS"` | 右侧盲区 |
| `DMS` | `"DMS"` | **驾驶员监测（项目使用）** |
| `AVMF/AVMB/AVML/AVMR` | `"AVMF"` 等 | 环视前/后/左/右 |
| `TVC` | `"TVC"` | 其他摄像头 |

**`OpaqueIdentifier`**（扩展信息键）：

| 常量 | 值 | 说明 |
|------|----|------|
| `RESOLUTION` | `0xAA00` | 查询摄像头分辨率（宽高各 16 位编码于 long） |

---

## 10. 项目自定义对接类（API 对照）

### 10.1 `FaceIDCameraController`（替换 `EvsCameraController`）

> 实现 `EvsBufferProvider` + `FrameSource`，缓冲池改为 `synchronizedList + bufferLock` 双重保护。

| 方法/属性 | 签名 | 线程 | 用途 |
|-----------|------|------|------|
| `frameWidth` | `var Int`（`@Volatile`） | 读任意/写 GL | 当前帧宽 |
| `frameHeight` | `var Int`（`@Volatile`） | 读任意/写 GL | 当前帧高 |
| `onFrameSizeChanged` | `var ((Int, Int) -> Unit)?` | 主线程 | 帧尺寸变化回调（调整 surface 比例） |
| `onFrameData` | `var ((HardwareBuffer, Int, Int) -> Unit)?` | HAL 回调 | 帧数据回调（算法处理入口） |
| `startCamera` | `fun startCamera(String cameraId)` | 任意 | 打开摄像头（异步，自动重试） |
| `stopCamera` | `fun stopCamera()` | 任意 | 关闭摄像头 |
| `release` | `fun release()` | 任意 | 断开 EVS 服务 |
| `track` | `fun track(EvsFrameRate)` | 主线程 | 绑定帧率跟踪器 |
| `getNewFrame` | `override fun getNewFrame(): EvsBufferDesc?` | GL 线程 | 取帧（含 buffer 有效性检查 + 首帧跳过） |
| `start` | `override fun start(String)` | 任意 | `FrameSource` 实现 → `startCamera` |
| `stop` | `override fun stop()` | 任意 | `FrameSource` 实现 → `stopCamera` |

### 10.2 `CameraManager`（相机生命周期管理）

| 方法 | 签名 | 用途 |
|------|------|------|
| `openCamera` | `fun openCamera(): Boolean` | 打开 DMS 摄像头 + 启动流 |
| `stopCamera` | `fun stopCamera()` | 停止流 + 释放 |
| `frameRate` | `val EvsFrameRate` | 帧率 LiveData，供 UI 观察 |

### 10.3 `FrameDistributor`（帧分发：相机 → 算法）

| 方法/属性 | 签名 | 用途 |
|-----------|------|------|
| `frameWidth/Height` | `var Int`（`@Volatile`） | 当前帧尺寸 |
| `attach` | `fun attach()` | 绑定帧源回调（链式保留既有回调） |
| `detach` | `fun detach()` | 解除绑定 |
| 构造 | `FrameDistributor(FrameSource, FrameProcessor, (HW,Int,Int)->ByteArray?, ()->Boolean)` | frameSource、帧处理、readFrame、算法开关 |

### 10.4 `FrameSession`（相机帧单例会话）

| 方法 | 签名 | 用途 |
|------|------|------|
| `get` | `fun get(readFrame): FrameSession` | 进程级单例获取 |
| `acquire` | `fun acquire(frameProcessor, algorithmEnabled): FrameSession` | 增加引用计数，首次装配帧分发 |
| `release` | `fun release()` | 减引用计数，归 0 停止相机并解绑 |
| `createRenderer` | `fun createRenderer(): EvsGL20CameraRenderer` | 新建渲染器（每模块独立） |
| `configureSurface` | `fun configureSurface(surface, overlay?, resizeEnabled)` | 配置 GLSurfaceView + 渲染器 + 尺寸自适应 |
| `open/stop` | `fun open(): Boolean` / `fun stop()` | 打开/停止相机 |
| `refCount` | `fun refCount(): Int` | 引用计数（诊断） |

---

*本文档基于 EVS SDK `com.android.car:evs:1.0.7` 源码与项目当前实现整理，用于指导 EVS SDK 的对接与排查。*
