# 提案：上层应用模块化重构 —— 主页 + 三大功能模块

> 提案编号：FACEP-011  
> 创建日期：2026-08-21  
> 状态：**已实施**（五阶段全部落地，见 §10 实施结果与差异）

---

## 1. 背景与动机

当前上层应用**只有一个 `PreviewActivity`**，同时承担预览、相机、算法装配、渲染、信号桥接、人脸识别展示、疲劳状态、分心监测**全部职责**。三大业务能力（人脸识别、疲劳监测、分心监测）在一个 Activity 里耦合：

- `PreviewActivity.handleAlgorithmResult` 同时做**人脸识别展示**、**眼嘴渲染**、**分心发布**——桥接逻辑集中；
- 各能力逻辑虽已下沉（`FaceEnrollmentManager`/`EyeMouth*`/`DistractionStateMachine`），但**缺少顶层功能边界**与**独立入口**；
- 渲染层 `FaceBox` 一个结构横跨识别（label）、疲劳（eyeOpen/mouthOpen）、分心（gazeDistracted/zoneId）；
- 信号层被疲劳（门信号→校准）与分心（车速→分档）两个模块共享，但**没有作为公共基础设施被清晰划分**。

> 诉求：将上层应用划分为 **三个功能模块**：**人脸识别、疲劳监测、分心监测**，从**一个主页**进入（三个入口）。做好**模块复用**与**功能区隔**，避免"一个 Activity 一把梭"。

---

## 2. 现状分析

### 2.1 三大业务能力现状

| 能力 | 已有实现 | 完整度 | 依赖信号层 |
|------|---------|--------|-----------|
| **人脸识别** | `FaceEnrollmentManager`（自动录入/识别/替换，512-D embedding 持久化） | 完整 | 否 |
| **疲劳监测** | `EyeMouthStateEstimator→Calibrator→StateMachine`（仅输出 `eyeOpen`/`mouthOpen`） | **半成品**（无"持续闭眼/哈欠"业务判定） | 门信号（校准复位） |
| **分心监测** | `DistractionStateMachine`+`SignalDispatcher`（防抖+车速分档） | 完整 | 是（车速分档） |

### 2.2 当前耦合点（拆分抓手）

1. **`PreviewActivity` 是三大能力的汇合点**——算法结果 → 识别/疲劳/分心的桥接全部在其中；
2. **`FaceOverlayView.FaceBox` 字段横跨三模块**（label/eyeOpen/mouthOpen/gazeDistracted/zoneId）；
3. **算法结果 `FaceIDResult` 是统一数据源**——三模块各自从 result 取所需字段；
4. **信号层**被分心（车速）+ 疲劳（门信号）共享，应为公共基础设施。

---

## 3. 目标架构

### 3.1 总体结构（主页 → 三模块）

```
┌──────────────────────────────────────────────┐
│                  HomeActivity（主页）          │
│    ┌───────────┐  ┌───────────┐  ┌─────────┐ │
│    │ 人脸识别   │  │ 疲劳监测   │  │ 分心监测 │ │
│    └───────────┘  └───────────┘  └─────────┘ │
└──────────────────────────────────────────────┘
```

- **主页（`HomeActivity`）**：三个功能入口按钮 + 版本信息。从主页分别进入三个独立功能界面。
- **三个功能模块**：各自独立 Activity，聚焦单一业务，内部复用公共组件。

### 3.2 模块划分与复用边界

| 模块 | 独立实现（模块私有） | 复用的公共组件 | 消费的算法输出 |
|------|---------------------|---------------|---------------|
| **人脸识别** | 录入/识别 UI、`FaceEnrollmentManager` 驱动、识别结果展示 | 算法、相机、帧层、渲染框 | `faceId`、`confidence`、`keypoints` |
| **疲劳监测** | 闭眼/哈欠业务判定状态机（新增）、报警展示、门信号复位 | 算法、相机、帧层、渲染眼嘴、`EyeMouth*` 管线 | `eyeOpen`、`mouthOpen`、`landmarks` |
| **分心监测** | 分心状态机驱动、车速分档、DISTRACTED 提示 | 算法、相机、帧层、渲染头姿/视线/分区、`SignalDispatcher` | `headpose`、`gaze`、`gazeDistracted`、`zoneId` |

### 3.3 分层原则（功能区隔）

```
UI 层（模块 Activity，业务判定 + 展示）
   ↓ 仅消费模块输入 DTO
模块逻辑层（各模块业务状态机，如疲劳闭眼判定、分心状态机）
   ↓ 仅依赖公共算法接口 + 信号接口
公共层（算法 / 相机 / 帧层 / 渲染 / 信号 / 总线）
   ↓
算法 SDK（AAR）
```

- **算法层/相机/帧层/渲染层/信号层/总线**：均为**公共基础设施**，不归属任何单一模块；
- **模块层**：各自业务状态机（疲劳的"持续闭眼/哈欠"、分心的"防抖分档"）独立；
- **UI 层**：三个模块 Activity 只做本模块的交互与展示，**不直接操作算法/渲染细节**。

---

## 4. 设计要点

### 4.1 主页（`HomeActivity`）
- 三个入口按钮：人脸识别、疲劳监测、分心监测；
- 每个入口 `startActivity` 到对应模块 Activity；
- 显示当前算法 SDK 版本、模型版本、功能状态。

### 4.2 公共基础设施的抽取（复用）

将 `PreviewActivity` 当前的"装配+桥接"能力抽成**可复用组件**，供三个模块共用：

| 抽取项 | 内容 |
|--------|------|
| **算法装配器** `AlgoSession` | 初始化/释放 `IFaceIDAlgorithm`、`FrameProcessor`、眼嘴管线、门信号复位，统一生命周期 |
| **渲染桥接器** `FaceOverlayBridge` | 接收 `FaceIDResult` → 组装各模块所需的渲染数据（识别/疲劳/分心分区） |
| **帧会话** `FrameSession` | 相机 + 帧分发 + 取帧，三模块共用 |
| **信号基础设施** | `VehicleSignalSource`/`DoorSignalSource`/`SignalDispatcher` 作为公共信号服务，模块按需订阅 |

### 4.3 三个模块的独立 Activity

**① 人脸识别模块 `RecognitionActivity`**
- 复用：`AlgoSession`、`FrameSession`、`FaceOverlayBridge`（仅识别部分）、`FaceEnrollmentManager`；
- 专属：录入流程 UI、识别结果（人名+置信度）展示、录入/替换交互；
- 不依赖：信号层（纯本地识别）。

**② 疲劳监测模块 `FatigueActivity`**
- 复用：`AlgoSession`（含眼嘴管线）、`FrameSession`、渲染眼嘴、`DoorSignalSource`（换人校准复位）；
- 专属：**新增"疲劳业务判定"**（当前缺失）：闭眼持续时长 → 疲劳告警；张嘴持续 → 哈欠告警；疲劳统计/展示；
- 关键补全：目前只有 `eyeOpen`/`mouthOpen` 布尔状态，**缺少"持续闭眼多久判疲劳"的持续时长状态机**（可复用 `DistractionStateMachine` 的"按持续时间累计"模式）。

**③ 分心监测模块 `DistractionActivity`**
- 复用：`AlgoSession`、`FrameSession`、渲染头姿/视线/分区、`SignalDispatcher`+`VehicleSignalSource`；
- 专属：分心状态展示、车速分档信息、DISTRACTED 提示交互；
- 现状最完整，主要做界面独立。

### 4.4 渲染层按模块分区（功能区隔）

当前 `FaceOverlayView.FaceBox` 字段横跨三模块。建议：
- **保留** `FaceBox` 作为渲染数据容器（公共）；
- 各模块通过 `FaceOverlayBridge` **只填充自己关注的部分**：
  - 人脸识别：`label`/`confidence`/`keypoints`
  - 疲劳：`eyeOpen`/`mouthOpen`
  - 分心：`gazeDistracted`/`zoneId`/头姿/视线
- 渲染绘制逻辑按模块**分区方法**（`drawRecognition`/`drawFatigue`/`drawDistraction`），各模块只调用自己的绘制区。

### 4.5 信号层作为公共基础设施

- `SignalDispatcher`、`VehicleSignalSource`、`DoorSignalSource` 归属**公共信号服务**；
- 模块通过**订阅**获取信号（对齐现有 topic 机制），不各自 new；
- 门信号：疲劳模块订阅（校准复位）；车速信号：分心模块订阅（分档）——**解耦，互不影响**。

### 4.6 统一复用与数据隔离（核心设计）

> 回答两个关键问题：**① 复用的模块如何统一、避免重复创建；② 如何避免数据污染。**

#### A. 统一复用：单例 + 引用计数生命周期（避免重复创建）

三个模块都连相机、算法、渲染、信号。若各自 new，会造成**重复创建 → 资源冲突**（多 CameraSession 抢设备、多算法实例各自占内存、模型重复加载）。因此采用 **进程级单例 + 引用计数**：

```
AlgoSession（进程级单例）
  ├─ IFaceIDAlgorithm   （唯一实例，模型只加载一次）
  ├─ FrameSession       （相机唯一实例，帧只取一份）
  ├─ FaceOverlayBridge  （渲染桥接唯一实例）
  └─ 引用计数 acquire()/release()
       └─ 计数归 0 才真正释放（模型卸载/相机释放）
```

- **`acquire()`**：模块 `onStart` 调用，计数 +1，若首次则真正初始化（加载模型/开相机）；
- **`release()`**：模块 `onStop` 调用，计数 -1，归 0 才销毁；
- 好处：**切页不重建**（识别→疲劳切换时，算法/相机复用），资源**只创建一份、只释放一次**；
- 线程安全：`acquire/release` 用锁保护，避免并发切换竞态。

#### B. 数据隔离：明确"状态归属"（避免污染）

核心原则：**共享的只有"数据生产源"，各模块的状态归属各自模块**。

| 组件 | 是否共享 | 内部状态归属 | 污染风险 |
|------|:-------:|-------------|---------|
| `IFaceIDAlgorithm`/眼嘴管线 | 共享单例 | 算法内部（EAR/MAR 校准、人脸库） | 见下方"边界" |
| `FrameSession` 帧数据 | 共享单例 | 无状态（帧临时） | 低（帧不跨模块留用） |
| `FaceOverlayBridge` 渲染数据 | 共享单例 | **临时对象，随算法结果重建** | 低 |
| 疲劳业务状态机 | 模块私有 | 疲劳模块 `FatigueActivity` 内 | 不共享 |
| 分心业务状态机 | 模块私有 | 分心模块 `DistractionActivity` 内 | 不共享 |
| 识别结果/人脸库 | 模块私有 | `FaceEnrollmentManager` 归属算法单例 | 见下方"边界" |

**算法实例共享但模块只读的边界**：
- 三个模块**共享同一个 `FaceIDAlgorithmImpl`**，它每帧产出 `FaceIDResult`（含 `faceId`/`eyeOpen`/`mouthOpen`/`gazeDistracted` 等全量字段）；
- **各模块只消费自己需要的字段，不修改算法内部状态**：
  - 识别模块读 `faceId`/`confidence`/`keypoints`；
  - 疲劳模块读 `eyeOpen`/`mouthOpen`；
  - 分心模块读 `headpose`/`gazeDistracted`/`zoneId`；
- 唯一允许修改算法状态的是**全局性事件**：门信号 `onDoorOpened()` 触发校准复位（这是所有模块共享的基础校准行为，非业务数据污染）。

#### C. 渲染数据隔离（防跨模块污染）

- `FaceBox` 是**每次算法结果生成的新对象**，不跨模块复用缓存；
- `FaceOverlayBridge` 按模块只填充对应字段，**不把识别 label 带入疲劳模块、不把分心 zoneId 带入识别模块**；
- 渲染绘制按模块分区（§4.4），各模块只调自己的 `draw*`，**互不读取对方的渲染字段**；
- 避免"识别模块改了 label 影响疲劳显示"这类污染。

#### D. 信号订阅隔离（防数据串扰）

- 信号服务是公共的，但**按 topic 隔离**：
  - `DOOR_STATE`：仅疲劳模块订阅（校准复位）；
  - `VEHICLE_SPEED`：仅分心模块订阅（分档）；
  - 订阅/退订跟随模块生命周期（`onStart` 订阅、`onStop` 退订），**切页时旧模块退订，防止残留回调**；
- 避免"疲劳模块收到车速、分心模块收到门信号"的串扰。

#### E. 生命周期与清理约定

| 时机 | 动作 |
|------|------|
| 模块 `onStart` | `AlgoSession.acquire()` + 订阅所需信号 |
| 模块 `onStop` | 退订信号 + `AlgoSession.release()` |
| 计数归 0 | 释放算法/相机/渲染，卸载模型 |
| 主页返回/退出 | 全部模块退出，计数归 0，完整释放 |

---

## 5. 模块复用矩阵

| 公共组件 | 人脸识别 | 疲劳监测 | 分心监测 |
|----------|:-------:|:-------:|:-------:|
| `AlgoSession`（单例+引用计数，§4.6） | ✅ | ✅ | ✅ |
| 算法 `IFaceIDAlgorithm` | ✅ | ✅ | ✅ |
| 相机/帧层 `FrameSession` | ✅ | ✅ | ✅ |
| `FaceOverlayBridge` 渲染桥接 | ✅ | ✅ | ✅ |
| `FaceOverlayView` 渲染 | 识别区 | 疲劳区 | 分心区 |
| `EyeMouth*` 眼嘴管线 | — | ✅ | — |
| `FaceEnrollmentManager` | ✅ | — | — |
| `DistractionStateMachine` | — | — | ✅ |
| `SignalDispatcher`（车速/门） | — | 门信号 | 车速 |

---

## 6. 实施步骤（分阶段）

### 阶段一：公共基础设施抽取
- 从 `PreviewActivity` 抽取 `AlgoSession`（算法+帧+眼嘴+门信号生命周期）、`FrameSession`、`FaceOverlayBridge`；
- 新建 `HomeActivity`（三入口 + 版本信息）；
- 保持现有 `PreviewActivity` 行为不变（作为过渡，后续拆为模块）。

### 阶段二：人脸识别模块
- 新建 `RecognitionActivity`，复用公共组件，迁移 `FaceEnrollmentManager` 驱动与识别 UI；
- 从 `PreviewActivity` 剥离识别相关逻辑。

### 阶段三：疲劳监测模块（含业务补全）
- 新建 `FatigueActivity`；
- **补全疲劳业务判定**：新增"持续闭眼/哈欠"时长状态机（复用 `DistractionStateMachine` 按时间累计模式）；
- 接入门信号校准复位；剥离 `PreviewActivity` 疲劳相关逻辑。

### 阶段四：分心监测模块
- 新建 `DistractionActivity`，复用 `SignalDispatcher`+车速分档；
- 剥离 `PreviewActivity` 分心相关逻辑；
- 渲染层按模块分区（`drawRecognition`/`drawFatigue`/`drawDistraction`）。

### 阶段五：收尾与验证
- 删除/保留过渡 `PreviewActivity`（作为诊断页或移除）；
- 各模块独立运行验证 + 回归测试；
- 更新 `AndroidManifest` 注册三模块 Activity + 主页为 LAUNCHER。

---

## 7. 改动文件清单（草案）

| # | 文件 | 类型 | 内容 |
|---|------|------|------|
| 1 | `ui/HomeActivity.kt` | 新增 | 主页，三入口 + 版本信息，LAUNCHER |
| 2 | `ui/RecognitionActivity.kt` | 新增 | 人脸识别模块 |
| 3 | `ui/FatigueActivity.kt` | 新增 | 疲劳监测模块（含业务判定） |
| 4 | `ui/DistractionActivity.kt` | 新增 | 分心监测模块 |
| 5 | `core/AlgoSession.kt` | 新增 | 算法/帧/眼嘴/门信号生命周期装配（公共） |
| 6 | `core/FrameSession.kt` | 新增 | 相机+帧分发会话（公共） |
| 7 | `core/FaceOverlayBridge.kt` | 新增 | 算法结果→渲染数据桥接（公共，按模块分区） |
| 8 | `algorithm/FatigueStateMachine.kt` | 新增 | 疲劳业务判定（持续闭眼/哈欠），补全现有缺口 |
| 9 | `render/FaceOverlayView.kt` | 修改 | 绘制按模块分区（drawRecognition/Fatigue/Distraction） |
| 10 | `ui/PreviewActivity.kt` | 修改 | 剥离三模块逻辑，转为诊断/过渡页或删除 |
| 11 | `AndroidManifest.xml` | 修改 | 注册主页+三模块 Activity |

---

## 8. 风险与注意事项

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 模块化改动大，破坏现有可用功能 | 功能回归 | **分阶段**实施（先抽公共，再逐模块），每阶段保留可运行状态 |
| `PreviewActivity` 桥接逻辑多，抽取易漏 | 遗漏耦合 | 用 `AlgoSession`/`FrameSession`/`FaceOverlayBridge` 收敛，迁移后对比行为 |
| 疲劳模块缺业务判定（现状仅布尔状态） | 疲劳功能不完整 | 阶段三补全"持续闭眼/哈欠"状态机，复用现有按时间累计模式 |
| 渲染层按模块分区改动大 | 绘制回归 | 分区方法保持原有绘制逻辑，仅拆方法调用 |
| 三个模块都连相机/算法，重复创建 | 资源冲突/内存浪费/多 Camera 抢设备 | `AlgoSession` 进程级单例 + 引用计数（§4.6-A），只创建一份、计数归0才释放 |
| 共享算法实例导致状态污染 | 识别结果影响疲劳/分心 | 明确状态归属（§4.6-B）：算法只产出数据，模块只消费不修改；业务状态归各自模块 |
| 渲染数据跨模块复用污染 | label/zoneId 串扰 | `FaceBox` 每次新生成（§4.6-C），`FaceOverlayBridge` 按模块只填所需字段 |
| 信号残留回调串扰 | 疲劳收到车速、分心收到门信号 | 按 topic 订阅 + 跟随模块生命周期退订（§4.6-D） |
| 引用计数并发竞态 | 释放/创建竞争 | `acquire/release` 加锁，切页串行化 |
| 信号层公共化后归属不清 | 耦合 | 明确 `SignalDispatcher` 为公共服务，模块按 topic 订阅解耦 |

---

## 9. 结论

将上层应用从"单一 `PreviewActivity`"重构为 **主页 + 三大功能模块（人脸识别/疲劳监测/分心监测）**：

1. **主页入口**：`HomeActivity` 提供三入口 + 版本信息；
2. **功能区隔**：三大能力各自独立 Activity，UI/模块逻辑/公共层分层清晰；
3. **模块复用（避免重复创建）**：算法、相机、帧层、渲染、信号、总线作为公共基础设施，经 **`AlgoSession` 进程级单例 + 引用计数** 收敛复用（§4.6-A），资源只创建一份、切页不重建、计数归 0 才释放；
4. **数据隔离（避免污染）**：明确状态归属（§4.6-B）——算法只产出数据、模块只消费不修改；渲染数据每次新生成（§4.6-C）、按模块填充；信号按 topic 订阅并跟随生命周期退订（§4.6-D）；
5. **补全缺口**：疲劳监测模块补全"持续闭眼/哈欠"业务判定（当前仅有布尔状态）；
6. **分阶段实施**：先抽公共，再逐模块拆分，保证每阶段可用、可回退。

---

## 10. 实施结果与差异（2026-08-21 落地）

> 本节记录 FACEP-011 在代码库的**实际落地情况**，以及与原始设计的差异，供后续迭代参考。

### 10.1 模块化落地概况

| 项 | 设计 | 实际落地 |
|----|------|---------|
| 主页 | `HomeActivity`（三入口 + 版本信息） | ✅ 已建，LAUNCHER（Makefile `ACTIVITY_NAME=.ui.HomeActivity`） |
| 人脸识别 | `RecognitionActivity` | ✅ 已建，flag=`DETECTION\|RECOGNITION\|LIVENESS\|LANDMARK` |
| 疲劳监测 | `FatigueActivity` | ✅ 已建，flag=`DETECTION\|LANDMARK` |
| 分心监测 | `DistractionActivity` | ✅ 已建，flag=`DETECTION\|HEADPOSE\|GAZE\|LANDMARK` |
| 算法会话 | `core/AlgoSession`（单例+引用计数） | ✅ 已建，`acquire(context, flag)`→`setFlagAndReset`，注入 `FaceEnrollmentManager` |
| 帧会话 | `core/FrameSession`（单例+引用计数） | ✅ 已建，`configureSurface`/`createRenderer`/`resizePreviewSurface` |
| 渲染桥接 | `core/FaceOverlayBridge`（按模块分区组装 FaceBox） | ✅ 已建 |
| 帧读取 | `core/NativeFrameReader`（JNI 公共化） | ✅ 已建，符号 `Java_com_skyworth_faceid_core_NativeFrameReader_*` |
| 旧 `PreviewActivity` | 过渡/删除 | 保留为诊断页（已非 LAUNCHER），模块化入口走 HomeActivity |

### 10.2 渲染按模块分区（`FaceOverlayView.drawMode`）

`FaceOverlayView` 增加 `drawMode`，各模块 `setFaces` 时由 `FaceOverlayBridge` 设置：

| drawMode | 模块 | 绘制内容 |
|----------|------|---------|
| `DRAW_MODE_RECOGNITION` | 人脸识别 | 人脸框（绿 detected / 红 spoof）+ 名称 + 置信度 |
| `DRAW_MODE_FATIGUE` | 疲劳监测 | **106 眼嘴点位** + 眼睛/嘴巴开合状态文字（`E:OPEN/CLOSED M:OPEN/CLOSED`），**不画**脸框/名称/置信度 |
| `DRAW_MODE_DISTRACTION` | 分心监测 | 头姿信息 + 视线信息 + **关键 5 点**（紫）+ 头姿/视线箭头 + DMS zone 面板 + 分心提示，**不画**脸框/名称/置信度/眼嘴状态 |

**实施差异**：
- 疲劳模块原设计"仅布尔状态"升级为同时绘制 **106 点位 + 眼嘴开合状态**；
- 分心模块**移除脸框**（与识别模块区分），但**补齐了关键 5 点**——原 `FaceOverlayBridge` 的 `DISTRACTION` 分支漏填 `keypoints`，导致关键点不绘制，已补上 `keypoints = result.keypoints`。

### 10.3 坐标映射：取消裁剪，改用全图推理

- 原设计（FACEP-003）在 `FrameProcessor` 做 900×900 ROI 裁剪 + `setCropOffset` 偏移修正回原图空间；
- 实际落地：**取消裁切**，`FrameProcessor` 直接把整个原图（1600×1300）UYVY→RGB888 后传入算法（详见 FACEP-003 变更记录 §8）；
- 因此 `setCropOffset(0,0)`，算法返回的框/关键点/106 地标**天然为原图坐标**，**无需点位映射转换**；overlay 直接按原图尺寸（1600×1300）缩放显示。
- 全图推理下有人脸每帧约 82ms（中位数）、无人脸约 35ms，偶发尖峰 160ms+。

### 10.4 预览尺寸：固定比例 + 居中（不依赖帧回调）

- 原设计/早期实现依赖 `onFrameSizeChanged` 帧回调动态 resize，存在首帧 `parent.width=0` 导致永不 resize → 拉伸；
- 实际落地：`FrameSession.configureSurface` **不再注册帧尺寸回调**，改为布局完成后基于**固定 1600×1300 常量** + 父容器尺寸算一次（`fitFixedAspect`），并用 `Gravity.CENTER` 居中；
- `resizeTo` 中 `lp.width/height` **必须无条件设置**（`ViewGroup.LayoutParams` 基类字段），否则 GLSurfaceView 的 `layoutParams` 非 `FrameLayout.LayoutParams` 时尺寸不生效 → 拉伸；`gravity` 仅对 FrameLayout 父容器设置居中。
- 父容器须为 **FrameLayout**（尊重 width/height 与 gravity；ConstraintLayout 忽略 width/height）。

### 10.5 生命周期修复：AlgoSession 单例执行器不可被 shutdown

**问题**：退出功能页 → `AlgoSession.release()` → 引用计数归 0 → `doRelease()` 原实现 `mAlgoExecutor.shutdown()` + `mAlgorithm.release()`。但 `mAlgoExecutor` 是**进程级单例字段，不会重建**，再次进入模块时 `FrameProcessor.submitFrame()` 调 `mExecutor.submit()` 抛 `RejectedExecutionException` → **算法无响应**（现象：第一次进入正常，退出再进入故障）。

**修复**：`doRelease()` 只重置 `mResultCallback`，**不销毁** `mAlgorithm` 与 `mAlgoExecutor`（模型常驻、执行器常驻），`mInitialized` 保持 true，下次 `acquire()` 直接复用：

```kotlin
private fun doRelease() {
    mResultCallback = null
    Log.i(TAG, "doRelease: reset callback, algo kept alive for reuse")
}
```

**差异**：原设计 §4.6-A 的"计数归 0 才真正释放（模型卸载）"与单例复用矛盾（executor 无法恢复），实际改为**单例常驻、仅重置回调**。

### 10.6 人脸识别：新增已导入数量展示

- 数据源：`FaceEnrollmentManager.getCount()`（JSON 持久化后加载）；
- 接口：`IFaceIDAlgorithm` 新增默认方法 `getEnrolledCount(): Int = 0`，`FaceIDAlgorithmImpl` 透传 `mEnrollmentManager?.getCount() ?: 0`；
- UI：`RecognitionActivity` 左上角 `tv_enrolled_count`，显示"已导入：N 人"；进入页面立即刷新 + 算法结果回调时**仅在数量变化时更新**（防每帧刷 UI）。

### 10.7 模块切换与 flag 控制

- 各模块 flag（§10.1）经 `AlgoSession.acquire(context, flag)` → `setFlagAndReset(flag)`（configure + reset 眼嘴管线），切换时正确重建状态，避免疲劳/分心状态残留；
- `AlgoSession`/`FrameSession` 均为**单例 + 引用计数**，切换模块时不重建（§4.6-A），资源只创建一份。

### 10.8 遗留/待办

- 疲劳模块"持续闭眼/哈欠"业务判定（§阶段三补全项）**尚未落地**——当前仅输出 `eyeOpen`/`mouthOpen` 布尔状态并绘制，未实现持续时长告警；
- 全图推理下每帧耗时（~82ms）高于原 900×900 裁剪（~70ms），如需提速可评估恢复裁剪 + 映射或算法侧优化；
- 各模块渲染效果需在设备端验证（106 点位、关键 5 点、头姿/视线箭头对齐）。
