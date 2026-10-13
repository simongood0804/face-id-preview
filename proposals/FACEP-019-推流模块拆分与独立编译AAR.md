# 提案：推流能力下沉 `:stream` 模块并独立编译 AAR

> 提案编号：FACEP-019
> 创建日期：2026-10-12
> 状态：**层次 A 已实施**（推流下沉 `:stream` 并独立编译 AAR；层次 B 远程发布未做，见 §10）
> 关联：FACEP-014（app/algo 拆分与 algo 独立 AAR）、FACEP-009（分层架构）、FACEP-011（上层模块化）

---

## 1. 背景与动机

当前「推流」能力（录制 / WHIP 推流 / 摄像头切换）全部散落在 `:app`（`com.android.application`）内，跨 `media/`、`core/`、`camera/`、`ui/`、`cpp/`、`libs/`、`assets/` 多个目录。诉求：

1. **解耦**：推流引擎与 UI 宿主的耦合度高，测试/替换成本大；
2. **复用**：希望推流能以**独立依赖（AAR）**形式被其他车机工程/应用直接使用，而非拷贝源码；
3. **与 FACEP-014 对齐**：算法已成功下沉 `:algo` 并独立编译 AAR，推流作为另一块「可剥离能力」，评估是否采用同一模式。

> 核心问题：**推流能否像 `:algo` 一样抽成独立 module 并打包成 AAR？** 本提案给出边界、切法、风险与工作量评估。

---

## 2. 现状分析

### 2.1 推流代码分布（全部在 `:app`，`:algo` 零推流代码）

**Kotlin 业务层**（`app/src/main/java/com/skyworth/faceid/`）：

| 文件 | 职责 | 关键依赖 |
|---|---|---|
| `media/MediaRecordNative.kt` | media_record 的 JNI 声明层（`System.loadLibrary` + `external fun`） | JNI so |
| `media/MediaRecordSession.kt` | 会话封装：`open/start/pushHardwareBuffer/pushUyvyFrame/pushEof/stop/close`、surface 路径、诊断 stats | `HardwareBuffer`、`android.util.Log` |
| `core/PushTarget.kt` | 推流对端（PC）地址配置 + 持久化 | **`Context`（SharedPreferences）** |
| `core/RelayDiscovery.kt` | 同网段扫描发现摄像头切换中继 | 网络（`java.net`） |
| `camera/CameraSwitchClient.kt` | 轮询中继 `/state`、POST `/switch` 切换摄像头 | 网络 |
| `ui/StreamTestActivity.kt` | 推流测试校验页（49KB，最大宿主） | app 内多包 + EvsSDK 取流 |

**Native / JNI**（`app/src/main/cpp/`）：

| 文件 | 职责 |
|---|---|
| `media_record_jni.cpp` | JNI 封装 `mr_session_*` C ABI，含异步推帧线程池 |
| `encoder_surface_renderer.cpp/.h` | 宿主侧 EGL 渲染器（surface host-drawn 路径，35KB） |
| `ahardwarebuffer_util.h` | HardwareBuffer 读取工具 |
| `CMakeLists.txt` | 构建 `media_record_jni` / `encoder_surface_renderer`（另含 `hardware_buffer_reader`，属取流） |

**预编译库与头文件**（`app/libs/arm64-v8a/`）：

```
app/libs/arm64-v8a/
├── libdatachannel.so.0.21            (8.4 MB  WebRTC 后端, SONAME=libdatachannel.so.0.21)
├── libmedia_record_core_shared.so    (32.27 MB 录制/推流引擎, C ABI mr_*)
└── include/media_record/
    ├── media_record.h
    ├── media_record_driver.h         (主 API + mr_stats)
    ├── media_record_export.h
    └── node.h
```

**管线配置**（`app/src/main/assets/`）：`external_source_push.json`、`external_source_push_record.json`、`external_source_record.json`、`stream_push.json`、`dashcam_record*.json`。

### 2.2 外部依赖

| 依赖 | 形态 | 是否随推流下沉 |
|---|---|---|
| `libmedia_record_core_shared.so`（32MB） | 预编译 so，C ABI `mr_*` | **是**（推流引擎本体） |
| `libdatachannel.so.0.21`（8.4MB） | 预编译 so，WebRTC 后端 | **是** |
| `include/media_record/*.h` | 头文件 | **是** |
| `System.loadLibrary` 顺序 | 硬约束：`datachannel` → `media_record_core_shared` → `media_record_jni` | 随之迁移 |
| `android.hardware.HardwareBuffer` | Android 框架类 | 可在 `com.android.library` 中使用 |
| EvsSDK / `android.car` / `libevsservicejni.so` | 取流侧（**非推流**） | **否**，留在 `:app` |

### 2.3 耦合点

| 耦合点 | 现状 | 影响 |
|---|---|---|
| `Context` | `PushTarget.init/setHost(context)`；`StreamTestActivity` 在 `onCreate` 调用 | library 合法持有；也可抽配置接口 |
| `HardwareBuffer` | `pushHardwareBuffer` / `enqueueToSurface` 吃 HardwareBuffer | Android 框架类，library 可用 |
| **相机帧源** | `FaceIDCameraController`（EvsSDK + `android.car`）提供 buffer | **不可下沉**，改为上层注入 |
| native CMake | 在 `:app` 内构建 | 需在 `:stream` 自带 `externalNativeBuild` |
| Manifest 权限 | `INTERNET`、`cleartext` | 由宿主的 manifest merge 提供 |
| `ui/StreamTestActivity` | 同时引用 app 内 8+ 类（`FrameSession`、`EvsCameraCatalog`、`CameraPreference`…） | **留 app**，仅调用 `:stream` 的 API |

**关键结论**：推流**推理层**（`MediaRecord*`、`PushTarget`、`RelayDiscovery`、`CameraSwitchClient`）**不 import 任何 `algorithm`/`bus`/`AlgoSession`**（对比 FACEP-014：推流与 algo 本就基本解耦），这是剥离的有利条件。

---

## 3. 目标架构

### 3.1 三模块结构

```
FaceIDPreview（根工程）
├── :algo（com.android.library）      # 算法（FACEP-014 已落地）
├── :stream（com.android.library）    # 【本提案新增】推流能力，可独立编译 AAR
│   ├── media/                        # MediaRecordNative / MediaRecordSession
│   ├── config/                       # PushTarget（去 Context 化）/ 推流配置模型
│   ├── relay/                        # RelayDiscovery / CameraSwitchClient
│   ├── cpp/                          # media_record_jni.cpp / encoder_surface_renderer.*
│   ├── libs/arm64-v8a/               # libmedia_record_core_shared.so / libdatachannel.so.0.21 / include/
│   ├── assets/                       # external_source_push*.json 等推流图谱
│   └── api/                          # 对外门面 StreamSession / StreamConfig（可选）
└── :app（com.android.application）   # 渲染 / UI / 取流 / 系统桥接
    ├── render/  ui/  camera/         # 保留
    ├── core/                         # FrameSession / NativeFrameReader / FaceOverlayBridge
    └── ui/StreamTestActivity         # 保留并改为消费 :stream
```

依赖方向：`:app` → `:stream`、`:algo`。

### 3.2 `:stream` 的"去宿主化"改造点

| 现状 | 问题 | 改造 |
|---|---|---|
| `PushTarget` 用 `Context` + SharedPreferences | library 强绑宿主存储 | 抽 `PushConfigStore` 接口（`load/save`），默认给 `SharedPreferences` 实现；或直接由宿主传入 host 字符串 |
| `libraries` 用 `System.loadLibrary` | so 随 AAR 分发，加载顺序敏感 | 保留 init 顺序；AAR 以 `jniLibs` 打包 so，宿主无需额外配置 |
| 推流图谱 JSON 在 app assets | 需随模块走 | 迁移到 `:stream` 的 `src/main/assets/`，AAR 自动合并 assets |
| `StreamTestActivity` 强引用 app 内类 | 无法入库 | **留 app**；`:stream` 只暴露 `MediaRecordSession` + 配置，宿主自行组装 |
| 日志用 `android.util.Log` | 无碍（library 可用） | 保持；可选抽 `Logger` 便于跨端复用 |

### 3.3 对外门面（备选）

为屏蔽内部细节，`:stream` 可提供稳定门面（类似 FACEP-014 里未落地的 `api` 包）：

```kotlin
// :stream/api
data class StreamConfig(
    val pushUrl: String,
    val width: Int, val height: Int, val fps: Int,
    val bitrate: Int, val codec: String = "h264",
    val surfaceMode: Int = 0
)

interface StreamSession {
    fun start(): Boolean
    fun pushFrame(buffer: HardwareBuffer, srcW: Int, srcH: Int, tsUs: Long): Int
    fun stopAndFinalize()
    fun stats(): LongArray?
    companion object { fun open(ctx: Context, cfg: StreamConfig): StreamSession? }
}
```

> 是否需要门面取决于复用目标（见 §4）。若仅项目内解耦，可暂不建，直接暴露 `MediaRecordSession`。

---

## 4. 打包的两个层次（决定工作量）

### 层次 A：项目内拆模块（不发布远程仓库）
- 新增 `:stream`（`com.android.library`），内容下沉，`:app` 用 `implementation(project(":stream"))`。
- 产出本地 `stream-release.aar`，与当前 `algo-release.aar` 同一模式。
- **工作量：中（约 2~4 天）**。

### 层次 B：发布远程 AAR 供外部工程消费
在 A 之上额外需要：
1. 接入 `maven-repo-plugin` 发布配置（`:algo` 目前也**未**接入，见 FACEP-014 §9.4）；
2. 处理 **约 40MB** 的 so 分发（32MB core + 8.4MB datachannel），消费方包体显著增大；
3. abiFilter 仅 `arm64-v8a`，需在 POM/README 声明；
4. 消费方的 Manifest 需带 `INTERNET`/`usesCleartextTraffic`，且推流对端地址配置需自行提供。
- **工作量：中~高（约 5~8 天，含远程仓库联调与独立工程验证）**。

---

## 5. 迁移步骤（渐进，每步可编译）

| 步骤 | 内容 | 验证 |
|---|---|---|
| **1** | 新建 `:stream`（`com.android.library`），配置 namespace、`externalNativeBuild`（复用现有 CMakeLists，去掉 `hardware_buffer_reader`）、`jniLibs.srcDirs("libs")`、assets | `:stream:assembleDebug` 产出 AAR |
| **2** | 下沉 `media/MediaRecordNative`、`media/MediaRecordSession` | `:stream` 编译通过 |
| **3** | 下沉 `core/PushTarget`（去 Context：抽 `PushConfigStore`）、`core/RelayDiscovery`、`camera/CameraSwitchClient` | 编译通过 |
| **4** | 下沉 `cpp/media_record_jni.cpp`、`cpp/encoder_surface_renderer.*`、`cpp/ahardwarebuffer_util.h`；so 与头文件移至 `:stream/libs/` | native 构建成功，so 正确打包进 AAR |
| **5** | 迁移推流图谱 JSON（`external_source_push*.json` 等）到 `:stream` assets | 运行期可解析 |
| **6** | `:app` 删除已迁出源码，改 `implementation(project(":stream"))`；`StreamTestActivity` 保留并改为消费 `:stream`；`hardware_buffer_reader` 相关留在 `:app` | `:app:assembleDebug` 正常，推流功能回归 |
| **7** | （可选，层次 B）发布 `stream.aar` 到远程仓库；独立工程验证消费 | 外部工程可构建并推流 |

> 关键：`MediaRecordNative` 的 `System.loadLibrary` 顺序（`datachannel` → `media_record_core_shared` → `media_record_jni`）**必须保持**，否则运行时报 `UnsatisfiedLinkError`。

---

## 6. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| **so 体积大（~40MB）** | 消费方包体激增；AAR 分发笨重 | 评估是否按需裁剪 datachannel；或 so 走独立渠道（宿主自行提供，`compileOnly` 模式，参考 FACEP-014 方案 B） |
| **`System.loadLibrary` 顺序 / SONAME 约束** | 运行期崩溃 | 保持顺序；so 文件名 **不可改**（`libdatachannel.so.0.21` 的 SONAME 与 core 的 DT_NEEDED 绑定） |
| **CMake `-l` 链接 + jniLibs 打包** | AAR 内 so 重复/缺失 | 沿用现有做法（`link_directories` + `-l`，打包交 jniLibs），避免 IMPORTED target 冲突；迁移后重新验证 AAR 内 so 齐全 |
| **`PushTarget` 去 Context 化** | 破坏现有调用 | 提供 `PushConfigStore` 默认实现（SharedPreferences），app 侧只需 `PushTarget.init(context)` 语义不变 |
| **`StreamTestActivity` 依赖 app 内取流类** | 无法直接入库 | 不强迁 UI；取流（EvsSDK/HardwareBuffer 源）留在 app，推流库仅吃 `HardwareBuffer` |
| **消费方漏配权限/地址** | 运行期推流失败 | README 与门面文档写明；必要时做运行期前置校验与明确报错 |
| **与 `:algo` 的边界重叠** | 争议/返工 | 明确：`:algo` = 算法/判定；`:stream` = 录制/推流/中继；取流与渲染留 `:app` |

---

## 7. 影响范围

- **新增**：`:stream` 模块、`settings.gradle.kts` include、其 build.gradle.kts（含 native 构建与 jniLibs）；
- **迁移**：`media/`、`core/PushTarget`/`RelayDiscovery`、`camera/CameraSwitchClient`、`cpp/media_record_jni.cpp`/`encoder_surface_renderer.*`、`libs/arm64-v8a`（media_record 部分）、推流 assets；
- **保留 app**：`ui/`（含 `StreamTestActivity`）、`camera/`（取流）、`core/FrameSession`/`NativeFrameReader`、`cpp/hardware_buffer_reader.cpp`；
- **构建**：`:app` 去掉 media_record 的 CMake 目标与 jniLibs 大 so，改为依赖 `:stream`；
- **文档**：设计方案补模块结构；更新 `docs/推流联调手动启动手册.md` 的构建/依赖说明。

---

## 8. 验收标准

1. `./gradlew :stream:assembleRelease` 产出 `stream-release.aar`，且 AAR 内**含** `libmedia_record_core_shared.so`、`libdatachannel.so.0.21`、`libmedia_record_jni.so` 与推流图谱 assets；
2. `:app` 改为 `implementation(project(":stream"))` 后编译通过，推流/录制/摄像头切换功能回归正常（设备实测）；
3. `:stream` 不依赖 EvsSDK / `android.car` / xbootclasspath（取流不属于 `:stream`）；
4. （若做层次 B）独立测试工程 `implementation("com.skyworth.faceid:stream:x.y.z")` 可构建并成功推流。

---

## 9. 工作量与结论

**可行性结论：可以剥离，推荐先做层次 A（项目内拆 `:stream`）。**

| 方案 | 工作量 | 收益 | 建议 |
|---|---|---|---|
| **层次 A**：项目内拆 `:stream` | 2~4 天 | 解耦、可测试、可替换 | **推荐先做** |
| **层次 B**：发布远程 AAR | 5~8 天 | 跨工程复用 | 依赖授权/分发确定后再做；需评估 40MB so 分发 |

**主要成本集中在**：① native so 与 CMake 迁移到新模块；② `PushTarget` 去 Context 化；③ UI 宿主（`StreamTestActivity`）保持留 app 并适配；④ so 体积与加载顺序的稳定性验证。

**不建议**：把 EvsSDK 取流一起塞进 `:stream`——那会让 `:stream` 也依赖 AOSP 系统环境，丧失"可独立发布"的核心价值（与 FACEP-014 §3.3 判据一致）。

---

## 10. 实施结果（层次 A，2026-10-12）

### 10.1 已落地

- **新建 `:stream` 模块**（`com.android.library`，namespace `com.skyworth.faceid.stream`），`settings.gradle.kts` 改为 `include(":app", ":algo", ":stream")`；
- **包名保持不变**（物理移动，包名沿用 `com.skyworth.faceid.*`）。**关键约束**：`MediaRecordNative` 的 JNI 符号在 `media_record_jni.cpp` 中硬绑定为 `Java_com_skyworth_faceid_media_MediaRecordNative_*`（17 处），故 **Kotlin 包名必须保持 `com.skyworth.faceid.media`**，未改包名；
- **迁入 `:stream`**：
  - `media/MediaRecordNative.kt`、`media/MediaRecordSession.kt`；
  - `core/PushTarget.kt`、`core/RelayDiscovery.kt`（同包耦合，一起迁）；
  - `camera/CameraSwitchClient.kt`；
  - `cpp/media_record_jni.cpp`、`cpp/encoder_surface_renderer.cpp/.h`、`cpp/ahardwarebuffer_util.h`（**与 app 侧共用，故两边各留一份**）；
  - `libs/arm64-v8a/`：`libmedia_record_core_shared.so`、`libdatachannel.so.0.21`（实体）+ `libdatachannel.so`（软链）；
  - `assets/`：`external_source_push.json`、`external_source_push_record.json`、`external_source_record.json`、`stream_push.json`；
  - **新增 `core/PushConfigStore.kt`**：抽出持久化接口（默认 `SharedPreferencesPushConfigStore` + `InMemoryPushConfigStore` 供单测）。
- **`PushTarget` 改造**：基于 `PushConfigStore`，`init(context)`/`init(store)`/`setHost(context, value)`/`setHost(value)` 四种入口，**app 侧调用零改动**（`PushTarget.init(this)`、`PushTarget.setHost(this, value)` 均保留）；
- **`:app` 侧调整**：
  - `build.gradle.kts`：新增 `implementation(project(":stream"))`；删除 `jniLibs.srcDirs("libs")` 与 media_record 相关注释；
  - `cpp/CMakeLists.txt`：**只保留 `hardware_buffer_reader`（取流）**，删除 `media_record_jni`/`encoder_surface_renderer` 目标与 `link_directories`；
  - 删除空的 `app/libs/`；
- **取流全部留在 app**：`camera/`（EvsSDK）、`core/FrameSession`/`NativeFrameReader`、`cpp/hardware_buffer_reader.cpp`、`ui/StreamTestActivity`（仅消费 `:stream`）。

### 10.2 验证结论

- `./gradlew :stream:assembleRelease` **BUILD SUCCESSFUL**，产出 `stream-release.aar`（约 9.3 MB 压缩后；解压含 `jni/arm64-v8a/` 三个 so + `classes.jar` + 4 个推流 assets）；
  - AAR 内 so：`libdatachannel.so`(6.9MB)、`libmedia_record_core_shared.so`(19.9MB)、`libmedia_record_jni.so`(63KB)；
- `./gradlew :app:assembleDebug` 与 `:app:assembleRelease` 均 **BUILD SUCCESSFUL**（含 `lintVitalRelease`）；
- release APK 校验：推流 so 齐全（来自 `:stream`）、取流 `libhardware_buffer_reader.so`（来自 `:app`）、算法 so（face-sdk）齐全，**无 so 冲突**；推流 4 个 json 与 app 的 assets 正常合并；
- **`:stream` 不依赖** EvsSDK / `android.car` / xbootclasspath（满足验收标准 3）。

### 10.3 遗留项

- **设备实测**：推流/录制/摄像头切换功能需实机回归（本机仅完成编译与产物校验）；
- **延时/配置持久化**：`PushTarget` 已可注入 `PushConfigStore`，app 侧仍是 SharedPreferences，行为不变；
- **层次 B 未做**：尚未接入 `maven-repo-plugin` 发布 `stream.aar` 到远程仓库，未用独立工程验证远程消费。
