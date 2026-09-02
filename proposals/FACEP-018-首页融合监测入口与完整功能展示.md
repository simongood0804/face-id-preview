# 提案：首页新增「融合监测」入口 —— 单页面聚合展示算法完整功能

> 提案编号：FACEP-018  
> 创建日期：2026-09-02  
> 状态：提案中（待评审）

---

## 1. 背景与动机

当前项目已具备**人脸识别**（`RecognitionActivity`）、**疲劳监测**（`FatigueActivity`）、**分心监测**（`DistractionActivity`）、**行为监测**（`BehaviorMonitorActivity`）四个独立功能模块，首页以田字型四入口进入各模块。

现状痛点：
1. **功能割裂**：一次只能进入一个模块，无法在同一页面同时观察算法全部能力；
2. **重复取流**：四个模块各自 acquire 算法、各自预览，无法共享一次取流看到多维度结果；
3. **联动缺失**：无法在同一画面中对照"识别 + 疲劳 + 分心 + 行为"的完整状态。

需求：
1. 首页**新增「融合监测」入口**（田字型扩展或独立入口）；
2. 新增 **`FusionMonitorActivity`**，单页面聚合展示算法完整功能：
   - **预览**（实时画面）
   - **人脸识别**（faceId/confidence）
   - **疲劳检测**（疲劳等级/开合度/时长）
   - **分心监测**（分心状态/注意区域）
   - **行为监测**（behaviorClass/吸烟/打电话）
3. 在**预览图像上**只绘制：
   - 人脸 **68 点密集地标**（`result.landmarks`）
   - **头部坐标系**（headpose 三轴箭头）
   - **分心注意列表**（DMS zone 分区面板）
4. 在**左侧面板**展示：
   - 人脸识别结果
   - 疲劳结果
   - 行为监测结果

---

## 2. 现状分析

### 2.1 各功能模块 acquire 的算法 flag

| 模块 | Activity | flag 组合 | 依赖的输出 |
|------|----------|-----------|-----------|
| 人脸识别 | `RecognitionActivity` | `DETECTION \| RECOGNITION \| LIVENESS \| LANDMARK` | faceId、confidence、landmarks |
| 疲劳监测 | `FatigueActivity` | `DETECTION \| LANDMARK` | eyeOpenRatio、mouthOpenRatio |
| 分心监测 | `DistractionActivity` | `DETECTION \| HEADPOSE \| GAZE \| LANDMARK` | headpose、gaze、landmarks |
| 行为监测 | `BehaviorMonitorActivity` | `BEHAVIOR`（=128） | behaviorClass、behaviorProbs |

> **融合监测 flag 组合**：为一次推理同时获得上述全部能力，需组合
> `DETECTION | RECOGNITION | LIVENESS | HEADPOSE | GAZE | LANDMARK | BEHAVIOR`
> （DETECTION/LANDMARK 为识别、疲劳、分心共用；BEHAVIOR=128 为独立位）

### 2.2 关键复用组件

- **`FrameSession`**：单例，管理取流（`acquire`/`configureSurface`/`open`）、FrameDistributor、帧尺寸适配（`fitFixedAspect` 等比缩放 surface + overlay）；
- **`AlgoSession`**：单例，管理算法 acquire/release 引用计数；
- **`FaceOverlayBridge`**：`setFaces(result, showLabels, module, imgW, imgH)` 统一把算法结果映射为 `FaceOverlayView` 可绘制的 `FaceBox`；
- **`FaceOverlayView`**：Canvas 绘制人脸框/关键点/密集地标/头姿/视线/zone 面板/分心提示；
- **`DistractionStateMachine` / `GazeFallpointDetector` / `SignalDispatcher`**：分心防抖与注意区域判定链路；
- **`FatigueStateMachine` / `FatigueRuleLoader`**：疲劳等级状态机（NONE→LIGHT→MODERATE→SEVERE）；
- **`DoorSignalSource`**：车门信号（门开重置疲劳）。

### 2.3 首页布局 `activity_home.xml`（FACEP-017 田字型）

```
LinearLayout (垂直)
 ├─ 标题/副标题
 ├─ 2×2 网格：btn_recognition / btn_fatigue / btn_distraction / btn_behavior
 ├─ 摄像头选择 btn_camera_select
 └─ 版本信息 home_version
```

### 2.4 预览绘制能力（FaceOverlayView）

- **人脸框**：DETECTED（绿）/ SPOOF（红）
- **关键点**：5 关键点（紫）
- **68 点密集地标**：`FaceBox.denseLandmarks`（= `result.landmarks`，68 点眼嘴点位），粉色画笔逐点绘制（`DRAW_MODE_FATIGUE` 中 `forEach drawCircle` 已有实现）
- **头部坐标系**：头姿三轴箭头（RGB）
- **视线**：左右眼视线方向线（橙）
- **DMS zone 面板（分心注意列表）**：左侧 15 分区中英文 + 当前朝向高亮
- **分心提示**：固定位置（右侧中部）

---

## 3. 目标行为

### 3.1 首页新增「融合监测」入口

将首页田字型扩展为包含融合监测入口（布局方案见 §4.1）。

### 3.2 融合监测页 `FusionMonitorActivity`

单页面聚合，布局示意：

```
┌───────────────────────────────────────────────────────────┐
│ 左上：模块标题「融合监测」    右上：返回                     │
├──────────────┬────────────────────────────────────────────┤
│  左侧信息面板  │        预览区（FrameLayout）               │
│  人脸识别结果  │  ├─ GLSurfaceView（实时画面，等比缩放）      │
│  疲劳结果      │  ├─ FaceOverlayView（叠加绘制）            │
│  行为监测结果  │  └─ 绘制内容：                             │
│              │      · 人脸 68 点                           │
│              │      · 头部坐标系（headpose 三轴）          │
│              │      · 分心注意列表（DMS zone 面板）         │
├──────────────┴────────────────────────────────────────────┤
│ 左下：状态行（当前融合状态汇总）                            │
└───────────────────────────────────────────────────────────┘
```

### 3.3 预览绘制内容（精简）

在预览图像上**只绘制**以下三类（其他如人脸框、5 关键点、视线线、分心提示等**不绘制**，避免遮挡）：

1. **人脸 68 点**：即 `result.landmarks`（68 点眼嘴点位，映射到 `FaceBox.denseLandmarks`），用 `DRAW_MODE_FATIGUE` 已有的逐点绘制；
2. **头部坐标系**：`result.headposeYaw/Pitch/Roll` 三轴箭头；
3. **分心注意列表**：`drawZonePanel`（DMS zone 分区面板 + 当前朝向高亮）。

> 需要 `FaceOverlayView` 提供"仅绘制 68 点 + 头姿 + zone 面板"的绘制模式开关，或 `FusionMonitorActivity` 构造自定义 `FaceBox` 集合（只带 landmarks 与头姿字段）。

### 3.4 左侧信息面板

| 区域 | 内容 | 数据来源 |
|------|------|---------|
| 人脸识别 | `faceId`（含 "detected"/"spoof"/"unregistered"/姓名）+ confidence + 已录入数 | `result.faceId`/`confidence` + `getEnrolledCount()` |
| 疲劳结果 | 疲劳等级（正常/轻度/中度/重度）+ 眼睛/嘴巴开合度 + 连续闭眼/哈欠时长 | `FatigueStateMachine` 输出 |
| 行为监测 | behaviorClass（正常/吸烟中/打电话中/未知） | `result.behaviorClass`/`behaviorProbs` |

### 3.5 融合逻辑

- 一次算法推理（融合 flag）同时产出全部结果，`resultCallback` 内并行喂给：识别展示 / 疲劳状态机 / 分心链路 / 行为展示；
- 各模块结果独立刷新，互不阻塞（保持解耦）。

---

## 4. 设计要点

### 4.1 首页入口

- **方案 A（推荐）**：田字型扩展为 3 行（前 4 个 + 融合监测独占一行，或 5 个按钮网格布局）；
- **方案 B**：融合监测作为**主打入口**，置于田字型网格上方单独一个醒目按钮。

推荐 **方案 A**：在现有 2×2 田字型下方新增一行 `btn_fusion`（宽屏满宽按钮），进入 `FusionMonitorActivity`。

### 4.2 融合监测页布局

- 布局 `activity_fusion_monitor.xml`：根 `LinearLayout`（horizontal，横屏）；
  - **左面板**（固定宽度，如 380dp）：`FacePanel`（识别/疲劳/行为分区，各分区用 `LinearLayout` + TextView 展示）；
  - **右预览区**（weight=1 的 `FrameLayout`）：`preview_surface`（GLSurfaceView）+ `face_overlay`（FaceOverlayView）；
  - 左上标题、右上返回、左下状态行。
- 复用 `FrameSession` / `AlgoSession`：`acquire` + `configureSurface`（传入 face_overlay，保证等比缩放）+ `open()`。

### 4.3 算法 acquire 与结果分发

```kotlin
// 融合 flag：一次推理获得识别/疲劳/分心/行为全部能力
private val FUSION_FLAG =
    FaceFlag.DETECTION or FaceFlag.RECOGNITION or FaceFlag.LIVENESS or
    FaceFlag.HEADPOSE or FaceFlag.GAZE or FaceFlag.LANDMARK or FaceFlag.BEHAVIOR
```

```kotlin
// resultCallback 并行分发（每帧）
private fun onAlgorithmResult(result: FaceIDResult) {
    // 1. 人脸识别展示（左侧）
    updateFacePanel(result.faceId, result.confidence)
    // 2. 疲劳状态机（leftEye/mouth 开合 → 疲劳等级）
    val fatigueOut = mFatigueMachine?.update(result.eyeOpenRatio, result.mouthOpenRatio, hasFace, nowMs)
    updateFatiguePanel(fatigueOut)
    // 3. 分心链路（headpose/gaze → 注意区域）
    mDispatcher?.processAlgorithmResult(result)
    // 4. 行为展示（behaviorClass）
    updateBehaviorPanel(result.behaviorClass, result.behaviorProbs)
    // 5. 预览 overlay：仅 68 点 + 头姿 + zone 面板
    mBridge?.setFusionFaces(result, frameW, frameH)
}
```

### 4.4 FaceOverlayView 增加"融合绘制"模式

新增一个绘制模式（或复用 DRAW_MODE），**只绘制**：
- 68 点密集地标（`face.denseLandmarks`，即 `result.landmarks` 68 点眼嘴点位）；
- 头部坐标系（`pitch/yaw/roll` 三轴）；
- DMS zone 面板（`drawZonePanel`）。

提供 `setFusionMode(boolean)` 或独立方法 `setFusionFaces(result, imgW, imgH)`，复用现有 68 点/头姿/zone 绘制函数。

### 4.5 分心注意列表（DMS zone 面板）

- 复用 `GazeFallpointDetector` 计算视线落点 region；
- `SignalDispatcher` 输出当前注意的 zone；
- `FaceOverlayView.drawZonePanel` 绘制 15 分区面板并高亮当前 zone。

---

## 5. 修改范围（草案）

| # | 文件 | 类型 | 内容 |
|---|------|------|------|
| 1 | `res/layout/activity_home.xml` | 修改 | 新增 `btn_fusion`（融合监测入口） |
| 2 | `res/values/strings.xml` | 修改 | 新增 `home_btn_fusion`（"融合监测"）、fusion 相关文案 |
| 3 | `ui/HomeActivity.kt` | 修改 | 绑定 `btn_fusion` → 跳转 `FusionMonitorActivity` |
| 4 | `ui/FusionMonitorActivity.kt` | 新增 | 融合监测页（聚合识别/疲劳/分心/行为） |
| 5 | `res/layout/activity_fusion_monitor.xml` | 新增 | 左信息面板 + 右预览区布局 |
| 6 | `render/FaceOverlayView.kt` | 修改 | 新增"融合绘制"模式（仅 68 点 + 头姿 + zone 面板） |
| 7 | `core/FaceOverlayBridge.kt` | 修改 | 新增 `setFusionFaces`（构造融合模式 FaceBox） |
| 8 | `AndroidManifest.xml` | 修改 | 注册 `FusionMonitorActivity`（非 exported，横屏） |

> 复用现有：`FrameSession`、`AlgoSession`、`FatigueStateMachine`、`GazeFallpointDetector`、`SignalDispatcher`、`FaceOverlayBridge`、`FaceOverlayView`。

---

## 6. 风险与注意事项

| 风险 | 影响 | 缓解 |
|------|------|------|
| 融合 flag 组合后单帧推理耗时上升 | 帧率下降 | 组合 flag 均为基础检测/关键点/头姿/视线，复用 DETECTION；实测帧率，必要时按需降采样 |
| 预览绘制元素多可能遮挡画面 | 观感差 | **只绘制 68 点 + 头姿 + zone 面板**（用户明确要求），不绘制人脸框/点云/视线线 |
| 各模块状态机同帧并行喂入 | 相互影响？ | 各状态机独立、只读 result 字段，无共享可变状态；分心用独立 SignalDispatcher |
| FaceOverlayView 新增绘制模式改动面 | 影响现有模块 | 用独立方法 `setFusionFaces` / 模式开关，不改现有 DRAW_MODE 逻辑 |
| 左面板布局在横屏下高度 | 信息展示不全 | 左面板固定宽度、内部可滚动（ScrollView），分区标题+内容 |
| FrameSession/AlgoSession 引用计数 | 泄漏 | onDestroy 严格 acquire/release 平衡 |

---

## 7. 实施步骤（建议）

1. **入口**：`activity_home.xml` + `HomeActivity` 新增 `btn_fusion`（跳转 `FusionMonitorActivity`）；
2. **页面骨架**：新建 `FusionMonitorActivity` + `activity_fusion_monitor.xml`（左面板 + 右预览），复用 `FrameSession`/`AlgoSession` 打通取流预览；
3. **融合 flag**：`FUSION_FLAG = DETECTION|RECOGNITION|LIVENESS|HEADPOSE|GAZE|LANDMARK|BEHAVIOR`，acquire 后 resultCallback 并行分发；
4. **左面板展示**：实现 `updateFacePanel` / `updateFatiguePanel` / `updateBehaviorPanel`（识别/疲劳/行为结果）；
5. **预览绘制**：`FaceOverlayView` 新增融合模式（仅 68 点 + 头姿 + zone 面板），`FaceOverlayBridge` 新增 `setFusionFaces`；
6. **Manifest** 注册 + strings；
7. **设备验证**：融合入口跳转、一次推理同时刷新识别/疲劳/分心/行为、预览仅绘制三类元素、返回/生命周期正常。

---

## 8. 结论

首页新增**「融合监测」入口**，进入新的 **`FusionMonitorActivity`**，单页面聚合展示算法完整功能：

- **预览**：实时画面 + 精简 overlay（人脸 68 点、头部坐标系、分心注意列表）；
- **人脸识别 / 疲劳检测 / 分心监测 / 行为监测**：通过**融合 flag 一次推理**同时获取，左侧面板分别展示识别、疲劳、行为结果，分心通过 zone 面板展示注意区域。

复用现有 `FrameSession`/`AlgoSession`/`FatigueStateMachine`/`GazeFallpointDetector`/`SignalDispatcher`/`FaceOverlayBridge`/`FaceOverlayView`，新增一个 Activity + 布局 + 融合绘制模式，改动可控、解耦清晰。
