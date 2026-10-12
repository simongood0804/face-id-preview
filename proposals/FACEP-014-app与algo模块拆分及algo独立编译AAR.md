# 提案：app 与 algo 模块拆分 —— 渲染留在 app，其余逻辑下沉 algo 并独立编译 AAR

> 提案编号：FACEP-014
> 创建日期：2026-08-25
> 状态：**已实施**（算法/总线/逻辑下沉 `:algo`，独立编译 AAR；app 只留渲染/取流/UI。实施差异与决策见 §9）
> 关联：FACEP-006（AAR 集成）、FACEP-009（分层架构）、FACEP-011（上层模块化）

---

## 1. 背景与动机

当前项目是**单 module**（`:app`，`com.android.application`），所有代码（算法、总线、相机、帧处理、管线、渲染、信号、UI）同仓耦合在一个可执行模块里。随着功能累积，出现几类诉求：

1. **算法与渲染耦合**：`algorithm` 包（FaceID 推理、眼嘴状态机）与 `render` 包（View/Canvas 绘制）、`ui` 包（Activity）在同一个 app 模块，算法无法独立复用、独立升级、独立测试；
2. **算法团队与 App 团队边界不清**：算法团队本应只交付算法 AAR（如 `face-sdk-v1.1.4.aar`），但本项目在 app 内部自建了 `IFaceIDAlgorithm` 的实现、眼嘴/疲劳/分心等**业务判定逻辑**，这些应抽成独立的 `algo` 库模块，供 app（以及未来的其他宿主）依赖；
3. **无法单独编译**：当前 `:app` 依赖 EvsSDK/AOSP 系统类（`android.car`、HardwareBuffer）、AndroidX、签名配置等，算法逻辑无法脱离 App 工程独立构建、无法发布为库；
4. **AAR 消费需求**：需要 `algo` 能**单独编译成 AAR**，供其他工程（或未来 DMS 应用）以依赖形式接入，而非拷贝源码。

> 诉求：**将除了"渲染绘制"之外的所有逻辑（算法推理、眼嘴/疲劳/分心判定、总线、信号处理、帧处理调度）下沉到新的 `algo` module；`app` module 只保留渲染绘制与 UI 装配。`algo` 支持单独编译、发布 AAR 供外部依赖。**

---

## 2. 现状分析

### 2.1 当前模块与依赖

- `settings.gradle.kts`：仅 `include(":app")`；
- `:app` 依赖：`face-sdk-v1.1.4.aar`、AOSP `com.android.car:lib/evs`（maven-repo-plugin 注入）、`com.android:framework`（xbootclasspath）、androidx appcompat/constraintlayout/lifecycle；
- 根 `build.gradle.kts` 已接入 `io.github.oxsource:maven-repo-plugin`（`pizzk.gradle.maven.repo`），已有发布 AAR 的基础设施。

### 2.2 包级归属分析（逐包评估）

| 包 | 关键类 | Android / 系统依赖 | 归属建议 |
|----|--------|-------------------|---------|
| **`algorithm`** | `IFaceIDAlgorithm`、`FaceIDAlgorithmImpl`、`FrameProcessor`、`FaceEnrollmentManager`、`EyeMouthStateEstimator/StateMachine/Calibrator`、`LandmarkIndexMapping/Region` | 混合：`android.graphics.RectF/PointF`、`Context`、`Bitmap`、`Handler/Looper`、**反射隐藏 API `SystemProperties`**、face-sdk AAR | **进 algo**（需去 Android 化，见 §3.2） |
| **`bus`** | `BusHub`、`BusQueue`、`BusPublisher`、`BusSubscriber`、`HealthMonitor`、`ServiceRegistry` | **纯 JVM**（注释明确"不依赖 Android 系统库"） | **进 algo**（原样迁移） |
| **`core`** | `AlgoSession`、`FaceOverlayBridge`、`FrameSession`、`NativeFrameReader` | `AlgoSession` 依赖 Context+algorithm；`FrameSession`/`NativeFrameReader` 强依赖 EvsSDK/GLSurfaceView/HardwareBuffer/JNI；`FaceOverlayBridge` 偏算法结果桥接 | **拆解**：`AlgoSession` 进 algo（去 Context）；`FrameSession`/`NativeFrameReader` 留 app（取流渲染强 Android）；`FaceOverlayBridge` 留 app（面向渲染） |
| **`frame`** | `FrameSource`、`FrameDistributor` | `FrameSource` 依赖 `android.hardware.HardwareBuffer` 接口；`FrameDistributor` 依赖 HardwareBuffer + `FrameProcessor` + Log | **需权衡**：`FrameDistributor` 核心调度可进 algo，但 `FrameSource`/`readFrame` 的 HardwareBuffer 是其输入源。建议**接口抽象**后 `FrameDistributor` 进 algo，HardwareBuffer 读取留在 app |
| **`pipeline`** | `PipelineConfig`、`BufferManager` | `BufferManager` 依赖 EvsSDK `EvsBufferDesc` + Log；`PipelineConfig` 纯 JVM | **拆解**：`PipelineConfig` 进 algo；`BufferManager`（EvsSDK 缓冲管理）留 app |
| **`render`** | 绘制相关（View、Canvas、Paint） | **纯 Android 绘制** | **留 app**（本次唯一确定留在 app 的部分） |
| **`signal`** | `DistractionStateMachine`、`SignalTypes`、`SignalDispatcher`、`VehicleSignalSource`、`DoorSignalSource` | `DistractionStateMachine`/`SignalTypes` 纯逻辑（仅 `SystemClock` 可注入）；`VehicleSignalSource`/`DoorSignalSource` 强依赖 `android.car` VHAL | **拆解**：`DistractionStateMachine`、`SignalTypes` 进 algo；VHAL 信号源留 app |
| **`camera`** | `CameraManager`、`FaceIDCamera*` | 强依赖 EvsSDK（`com.android.car.evs.*`） | **留 app**（取流强 Android） |
| **`ui`** | `HomeActivity`、`RecognitionActivity`、`FatigueActivity`、`DistractionActivity`、`PreviewActivity` | Android Activity | **留 app** |

### 2.3 结论

**"除渲染绘制外全部进 algo" 并非字面可实现**——因为除渲染外，`camera`（EvsSDK 取流）、`signal` 的 VHAL 信号源、`frame` 的 HardwareBuffer 读取、`pipeline` 的 EvsBufferDesc 都属于**强 Android/AOSP 系统依赖**的取流与系统桥接层。这些是"与硬件/系统打交道"的逻辑，不属于"算法/业务判定"。

因此提案采用**务实边界**：

> **algo 承接"算法推理 + 业务判定 + 纯逻辑基础设施"（可脱离 Android 编译）；app 承接"渲染绘制 + UI + 相机取流 + 系统桥接"（依赖 Android/EvsSDK）。** 中间层（帧调度、会话装配）通过**接口抽象**打破 HardwareBuffer/Context 强依赖后进 algo。

---

## 3. 目标架构

### 3.1 双模块结构

```
FaceIDPreview（根工程）
├── :algo（com.android.library）        # 可独立编译、发布 AAR
│   ├── algorithm/                       # 算法接口 + 实现 + 眼嘴/疲劳/分心判定
│   ├── bus/                             # 纯 JVM 总线
│   ├── pipeline/                        # PipelineConfig（纯逻辑）
│   ├── frame/                           # FrameDistributor（接口化后）
│   ├── core/                            # AlgoSession（去 Context 后）
│   ├── signal/                          # DistractionStateMachine、SignalTypes
│   └── api/                             # 对外统一门面（供 app 消费）
└── :app（com.android.application）      # 只保留渲染与装配
    ├── render/                          # View/Canvas 绘制
    ├── ui/                              # Activity
    ├── camera/                          # EvsSDK 取流
    ├── core/                            # FrameSession、NativeFrameReader、FaceOverlayBridge
    ├── frame/                           # FrameSource（HardwareBuffer 接口）
    ├── pipeline/                        # BufferManager（EvsBufferDesc）
    └── signal/                          # VehicleSignalSource、DoorSignalSource
```

依赖方向：`:app` → `:algo`（implementation）。

### 3.2 algo 内部"去 Android 化"改造点

algo 要独立编译为 AAR，必须消除对 Android/AOSP 运行时的强依赖。逐点列出改造：

| 现状 | 问题 | 改造 |
|------|------|------|
| `IFaceIDAlgorithm.FaceIDResult` 用 `android.graphics.RectF/PointF` | algo 依赖 android.graphics | 自建 `FloatRect`/`FloatPoint`（或复用 `java.awt.geom`），app 渲染层做一次坐标转换 |
| `FaceIDAlgorithmImpl` 用 `Context` 取 assets/模型路径 | 脱离 Context | 改为构造注入 `File`（模型目录/模型路径）与 `AssetProvider` 接口；或 `initialize(File, ...)` |
| `FaceIDAlgorithmImpl` dump 用 `Bitmap`/`Handler/Looper` | 依赖 android.graphics/looper | 把 dump 拆成独立可选接口（`DumpSink`），由 app 侧实现；algo 只暴露原始 ByteArray |
| `FaceIDAlgorithmImpl` 反射 `android.os.SystemProperties`（隐藏 API） | 脱离 Android 无法访问 | 抽成 `SystemPropertyProvider` 接口注入；app 侧提供真实现，algo 侧提供空实现/测试桩 |
| `EyeMouthStateMachine`、`DistractionStateMachine` 默认 `SystemClock.elapsedRealtime()` | 依赖 android.os | 构造函数已支持 `clockMs` 注入，algo 内提供 `SystemClockProvider` 或 JVM 版 `nanoTime` 时钟；默认参数改走注入 |
| `FaceEnrollmentManager` 用 `Context` 取 filesDir 做 JSON 持久化 | 依赖 Context | 改为注入目录 `File`；`Context` 不在 algo 层出现 |
| `FrameProcessor`、`FrameDistributor` 用 `android.util.Log` | 依赖 android.util | 抽 `Logger` 接口注入（默认空实现/`println`）；或改用 `java.util.logging` |

> 上述改造后，`algo` 的 `android.*` 依赖面收敛为 **0**（或仅剩可注入的抽象），可被纯 JVM 测试、可发布 AAR 供任意 Android 工程消费。

### 3.3 渲染与取流留在 app 的理由

- **render**：View/Canvas/Paint 是 Android 专属绘制，无复用价值，天然留 app；
- **camera / FrameSession / NativeFrameReader / BufferManager / FrameSource / VHAL 信号源**：强依赖 EvsSDK、`android.hardware.HardwareBuffer`、GLSurfaceView、`android.car` VHAL——这些是**硬件取流与系统桥接**，不属"算法/业务判定"，且依赖 AOSP 系统类（`com.android.car`）无法在普通 Android 库中独立发布（需系统编译环境）。留 app 降低 algo 的发布门槛。

### 3.4 对外统一门面（api 包）

algo 对外提供**稳定的门面接口**，屏蔽内部拆分细节，供 app（及外部宿主）消费：

```kotlin
// api/ (algo 对外发布的核心接口)
interface FaceIdAlgorithm {           // 原 IFaceIDAlgorithm 去 Android 化后的版本
    fun initialize(modelsDir: File, config: Map<String, Any>): Boolean
    fun processFrame(frameData: ByteArray, width: Int, height: Int, format: Int): FaceIdResult
    fun setCropOffset(x: Int, y: Int)
    fun configure(flag: Int)
    fun release()
    // ... enrollment、eye/mouth 等透传
}

data class FaceIdResult(              // 纯 JVM 数据类，替代 RectF/PointF
    val faceId: String,
    val confidence: Float,
    val faceRect: FloatRect?,         // 自建矩形
    val keypoints: List<FloatPoint>?, // 自建点
    val landmarks: List<FloatPoint>?,
    val eyeOpen: Boolean,
    val mouthOpen: Boolean,
    // ... 头姿/视线/分心字段
)

class AlgoSessionFactory {            // 装配器：替代 AlgoSession 的 Context 依赖
    fun create(config: AlgoConfig): AlgoSession
}
```

app 侧做一次适配（`FaceIdResult` → 渲染层需要的 `FaceBox`/绘制坐标），渲染与算法彻底解耦。

---

## 4. 独立编译 AAR

### 4.1 模块构建配置

`settings.gradle.kts`：

```kotlin
rootProject.name = "FaceIDPreview"
include(":app", ":algo")
```

`:algo/build.gradle.kts`（核心配置）：

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.skyworth.faceid.algo"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
        // AAR 对外无 applicationId
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }

    // algo 不依赖 AOSP/EvsSDK，无需 useLibrary("android.car")、无需 xbootclasspath
    testOptions {
        unitTests.isIncludeAndroidResources = false   // 纯 JVM 测试，无需 Robolectric
    }
}

dependencies {
    // 唯一依赖：face-sdk AAR（算法库）
    api(files("libs/face-sdk-v1.1.4.aar"))
    // 如需 JVM 时钟/日志，可引入无 Android 依赖的实现或留空
    testImplementation("junit:junit:4.13.2")
}
```

**要点**：
- 用 `com.android.library` 而非 `application`，产出 `.aar`；
- **不** `useLibrary("android.car")`、**不**设置 xbootclasspath——证明 algo 可脱离 AOSP 系统编译环境；
- 测试用纯 JUnit（`isIncludeAndroidResources=false`），Robolectric 不需要。

### 4.2 AAR 发布

项目根已接入 `io.github.oxsource:maven-repo-plugin`（见根 `build.gradle.kts`），沿用其发布体系：

```kotlin
// :algo/build.gradle.kts 尾部
afterEvaluate {
    // 注册到 maven-repo-plugin，group:artifact = 例如 com.skyworth.faceid:algo:1.0.0
}
```

发布物：
- `algo.aar`（含 `classes.jar` + 资源 + `AndroidManifest`）；
- POM 声明依赖 `face-sdk-v1.1.4.aar`（`<type>aar</type>`），确保消费者拉取 algo 时**传递依赖**正确；
- 版本号与仓库现有约定对齐（可独立于 app 的 versionCode）。

### 4.3 app 侧消费

```kotlin
// :app/build.gradle.kts
dependencies {
    implementation(project(":algo"))                 // 本地源码依赖（开发期）
    // OR
    implementation("com.skyworth.faceid:algo:1.0.0")  // 远程 AAR 依赖（算法独立发布后）
}
```

切换方式仅改依赖坐标，`app` 代码不变——证明 algo 具备"源码依赖 ⇄ AAR 依赖"的无缝切换能力。

---

## 5. 迁移步骤

按"先建 algo、再迁逻辑、后瘦身 app"的渐进策略，每步可独立编译验证：

| 步骤 | 内容 | 验证 |
|------|------|------|
| **1** | 新建 `:algo` 模块（`com.android.library`），搭好 build.gradle.kts、namespace、依赖 face-sdk AAR | `:algo:assembleDebug` 产出 AAR |
| **2** | 迁入**纯 JVM** 包：`bus` 全部、`algorithm` 下 `EyeMouth*`/`Landmark*`、`pipeline/PipelineConfig`、`signal` 下 `DistractionStateMachine`/`SignalTypes` | 迁移后 `:algo:test` 通过（纯 JUnit） |
| **3** | **去 Android 化改造**：自建 `FloatRect/FloatPoint` 替换 RectF/PointF；`IFaceIDAlgorithm` 改为纯 JVM 接口；`FrameProcessor`/`FrameDistributor` 抽 `Logger`；状态机时钟注入 JVM 实现 | `:algo` 编译无 `android.*` 依赖 |
| **4** | 改造并迁入 `FaceIDAlgorithmImpl`（模型路径注入 File、dump 拆 `DumpSink`、`SystemProperties` 抽接口）、`FaceEnrollmentManager`（目录注入）、`core/AlgoSession`（去 Context）；新增 `api` 门面 + `AlgoSessionFactory` | 全量 `:algo:test` 通过 |
| **5** | app 侧删除已迁出源码，改 `implementation(project(":algo"))`；app 保留 render/ui/camera/frame(取流)/pipeline(BufferManager)/signal(VHAL)/core(会话桥接适配) | `:app:assembleDebug` 正常，功能回归 |
| **6** | 用 maven-repo-plugin 发布 `algo.aar` 到仓库；app 切换为远程坐标验证 | 消费者工程 `implementation(...:algo)` 可构建 |

> 每步都保持工程可编译（避免大爆炸式一次性迁移）。

---

## 6. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| **AAR 无法传递 face-sdk AAR 依赖** | 消费者缺算法库 | POM 正确声明 `<type>aar</type>` 的 face-sdk 依赖；发布时校验 POM |
| **`FaceIDAlgorithmImpl` 反射 SystemProperties 在 algo 无 Android 环境** | 编译/运行失败 | 抽 `SystemPropertyProvider` 接口，algo 内空实现，app 注入真实现 |
| **接口化破坏现有渲染层**（RectF/PointF → 自建类型） | render 需适配 | app 侧提供 `FaceIdResult → FaceBox` 一次适配，渲染层不动算法细节 |
| **`frame/pipeline` 拆分的边界争议** | 反复返工 | 采纳 §2.3 务实边界：取流/缓冲（EvsSDK/HardwareBuffer）留 app，调度/逻辑进 algo |
| **双模块引入重复命名空间/类路径** | 类冲突 | 明确 namespace 与包名边界（`com.skyworth.faceid.algo` vs `com.skyworth.faceid`），冲突类用包名区分 |
| **algo 发布后 app 仍需系统编译环境**（car 依赖） | app 发布受限 | app 保留系统依赖是既有限制，与本次 algo 解耦目标无关；algo 已独立 |

---

## 7. 影响范围

- **新增**：`:algo` 模块、`settings.gradle.kts` include、根/子构建配置、`api` 门面包；
- **迁移**：`bus`、`algorithm`（去 Android 化后）、`pipeline/PipelineConfig`、`signal` 部分、`core/AlgoSession`、`frame/FrameDistributor`；
- **保留 app**：`render`、`ui`、`camera`、`core/FrameSession`/`NativeFrameReader`/`FaceOverlayBridge`、`frame/FrameSource`、`pipeline/BufferManager`、`signal` VHAL 源；
- **测试**：algo 迁移到纯 JUnit；app 保留需 Robolectric 的测试；
- **文档**：设计方案同步模块结构；FACEP-006（AAR 集成）补充 algo 独立发布说明。

---

## 8. 验收标准

1. `./gradlew :algo:assembleDebug` 产出可用的 `algo.aar`，且 `:algo` **不依赖** `android.car`/xbootclasspath/AOSP 系统编译环境；
2. `./gradlew :algo:test`（纯 JUnit）全部通过——`bus`、`EyeMouth*`、`DistractionStateMachine`、`FaceIDAlgorithm` 逻辑在 algo 内独立验证；
3. `:app` 改为 `implementation(project(":algo"))` 后编译通过、渲染/取流/UI 功能回归正常；
4. 发布 `algo.aar` 到仓库后，用一个**独立测试工程**（不含本项目）`implementation("com.skyworth.faceid:algo:x.y.z")` 能成功构建并调用算法接口——验证"单独编译、AAR 提供依赖"的诉求。

---

## 9. 实施结果与差异（2026-08-25）

### 9.1 已落地

- **新建 `:algo` 模块**（`com.android.library`，namespace `com.skyworth.faceid.algo`），`settings.gradle.kts` 改为 `include(":app", ":algo")`；
- **模块归属**（保持包名 `com.skyworth.faceid.*`，仅物理移动）：
  - **进 algo**：`algorithm/`（全部 9 文件）、`bus/`（全部 7 文件）、`core/AlgoSession`、`frame/`（FrameSource+FrameDistributor）、`pipeline/PipelineConfig`、`signal/SignalTypes`+`DistractionStateMachine`；
  - **留 app**：`render/FaceOverlayView`、`ui/`（全部）、`camera/`（全部）、`core/FrameSession/NativeFrameReader/FaceOverlayBridge`、`pipeline/BufferManager`、`signal/DoorSignalSource/VehicleSignalSource/SignalDispatcher`；
- **`face-sdk` 采用方案 B：消费方自供**——`:algo` 用 `compileOnly(files("libs/face-sdk-v1.1.4.aar"))`（仅编译期需要其类定义，产物 AAR **不含** face-sdk，**不传递**）；`:app` 作为 application 模块自己 `implementation(files("libs/face-sdk-v1.1.4.aar"))` 打包 face-sdk。不依赖 mavenLocal / 私有仓库，构建可复现；
- **`:app` 改为 `implementation(project(":algo"))`**，渲染/取流/UI/系统桥接依赖 algo 提供的算法类；
- **测试分离**：`bus`/`EyeMouth*`/`Landmark`/`PipelineConfig`/`DistractionStateMachine` 测试迁至 `:algo` 独立运行（纯 JUnit）；依赖 Robolectric 的 `IFaceIDAlgorithmTest`/`FrameProcessingBenchmarkTest`（测 FaceIDResult 的 RectF/PointF）留在 app；`FaceIDPreviewTestSuite` 裁剪为仅聚合 app 侧测试；
- **`:algo:assembleRelease` 产出 124KB `algo-release.aar`**（含全部算法类，`classes.jar` 验证）；`:app:assembleDebug` 打包含 face-sdk native so（libface/libonnxruntime/libface_vision_jni/libc++），传递依赖正确。

### 9.2 关键实施差异（相对提案初稿）

1. **FaceIDResult 未去 Android 化（保持接口稳定）**：实测 `face-sdk` 本身纯 JVM（字节码零 android 引用，API 全为 `String/int/float[]/FaceImage/FaceResult`），但**真正绑定 Android 的是我们自己的 `FaceIDAlgorithmImpl`**（Context 模型加载、Bitmap dump、SystemProperties 反射、Handler 回调）。完全去 Android 化需重写该实现并破坏 `FaceIDResult` 的 `RectF/PointF → FloatRect/FloatPoint` 契约（波及 app 全部消费方）。经评审**保留现状**：algo 保留 android 依赖（`com.android.library` 下合法），达成"算法下沉 + 独立 AAR"，而非"0 android 依赖"。`FaceIDAlgorithmImpl`/`IFaceIDAlgorithm`/`FaceEnrollmentManager` 未重写。
2. **`api` 门面包未落地**：因不破坏 FaceIDResult 契约，`api/FaceIdAlgorithm` 门面接口与 `AlgoSessionFactory` 未创建；app 直接经 `project(":algo")` 引用原 `com.skyworth.faceid.algorithm` 包。
3. **状态机时钟已 JVM 化**：`EyeMouthStateMachine`/`DistractionStateMachine` 默认时钟由 `SystemClock.elapsedRealtime()` 改为 `System.nanoTime()/1_000_000`；`FrameProcessor` 由 `android.util.Log` 改 `java.util.logging`——这三处轻量去 Android 化已做。
4. **跨模块 smart cast**：`FaceIDResult` 跨模块后 Kotlin 禁止对公共 API 属性 smart cast，`PreviewActivity` 一处改用局部变量承接。
5. **AAR 不含 face-sdk（方案 B）**：algo AAR 不含 face-sdk classes/native so（`compileOnly` 所致）。**消费方必须自行提供 face-sdk**——`:app` 用 `implementation(files("libs/face-sdk-v1.1.4.aar"))` 打包；外部消费方需同时依赖 algo + face-sdk（详见 §9.4）。已验证 algo AAR 无 `atlas/face` 类、app APK 含全部 so。

### 9.3 验证结论

- `:algo:testDebugUnitTest` 通过（独立运行，不依赖 app）；
- `:app:testDebugUnitTest` 仅 `BufferManagerTest` 失败（预存在问题：Robolectric 下静态 `EvsBufferDesc.recycle()` 无法触达 mock 实例，与本次迁移无关）；
- `:algo:assembleRelease` / `:app:assembleDebug` 均 BUILD SUCCESSFUL，lint 0 错误。

### 9.4 后续可选（未做）

**方案 B 下 algo 独立发布后的供给方式**（别人如何依赖）：
- 由于 algo AAR 不含 face-sdk，消费方必须**同时依赖 algo 和 face-sdk**：
  ```kotlin
  dependencies {
      implementation("com.skyworth.faceid:algo:x.y.z")   // 你的算法 AAR（不含 face-sdk）
      implementation("com.atlas:face-sdk:1.1.4")         // 或消费方自有的 face-sdk（本工程用本地 aar）
  }
  ```
- 风险：消费方**容易漏加 face-sdk**，运行时报 `NoClassDefFoundError`。建议 algo 内做**运行时缺失检测**（捕获 `ClassNotFoundException`，给出"缺少 face-sdk 依赖"的明确报错）；交付时在 README 写明依赖组合；
- 当 face-sdk 授权/分发确定后，可切换**方案 A**：把 face-sdk 发布到私有仓库（`coolwell` / 既有 `maven-repo-plugin`），algo 改回 `api("com.atlas:face-sdk:...")` 坐标依赖（AGP 生成 POM 传递依赖），消费方只需 `implementation("...:algo")` 一行即可——届时按需调整。

**其他未做项**：
- 若需"0 android 依赖"的 algo：重写 `FaceIDAlgorithmImpl` 的模型加载（Context→File+资产接口）、dump（拆 `DumpSink`）、`SystemProperties`（抽接口），并接受 `FaceIDResult` 契约破坏；
- 用 maven-repo-plugin 发布 `com.skyworth.faceid:algo:x.y.z` 到远程仓库，app 切换远程坐标；用独立测试工程验证远程 AAR 消费（提案验收标准 4）。
