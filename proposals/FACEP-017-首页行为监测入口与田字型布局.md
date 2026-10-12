# 提案：新增「行为监测」功能 —— 四入口田字型布局 + 吸烟/打电话监测（分阶段）

> 提案编号：FACEP-017  
> 创建日期：2026-09-01  
> 状态：**阶段一、阶段二均已实施**（见 §9 实施结果）

---

## 1. 背景与动机

当前主页（`HomeActivity`）提供三个功能入口：人脸识别、疲劳监测、分心监测，采用**横向单排排列**（适配横屏）。

需求：
1. 在首页**新增「行为监测」入口**，作为第四个功能模块（新增 `BehaviorMonitorActivity`）；
2. 四个入口改排布为**田字型（2×2 网格）**；
3. 行为监测**分阶段实施**：
   - **阶段一（本次）**：仅做**预览**——摄像头取流 + 画面显示，打通取流与页面生命周期；
   - **阶段二（后续）**：对接更新后的算法，增加**吸烟**和**打电话**监测，在预览上叠加行为识别结果。

---

## 2. 现状分析

### 2.1 首页布局 `activity_home.xml`

```
LinearLayout (垂直, 居中)
 ├─ 标题 home_title
 ├─ 副标题 home_subtitle
 ├─ 三入口 LinearLayout（horizontal）：btn_recognition / btn_fatigue / btn_distraction
 │   每个 Button width=0dp, weight=1, minHeight=120dp
 ├─ 摄像头选择 btn_camera_select
 └─ 版本信息 home_version
```

三个入口为**横向单排**，权重等分宽度。

### 2.2 `HomeActivity`

- 三个按钮分别 `startActivity` 到 `RecognitionActivity` / `FatigueActivity` / `DistractionActivity`
- 摄像头选择（`CameraPreference`）、版本信息

---

## 3. 目标行为

### 3.1 首页四入口田字型

```
┌───────────┬───────────┐
│  人脸识别  │  疲劳监测  │
├───────────┼───────────┤
│  分心监测  │  行为监测  │
└───────────┴───────────┘
```

- 4 个入口排布为 **2×2 网格（田字型）**
- 每个入口等宽等高，可点击进入对应模块
- 摄像头选择、版本信息保留（位于网格下方）

### 3.2 行为监测入口（分阶段）

**阶段一（本次）— 仅预览**：
- 点击「行为监测」→ 进入新页面 `BehaviorMonitorActivity`
- 摄像头取流 + GLSurfaceView 显示实时画面，打通取流/预览/生命周期

**阶段二（后续）— 吸烟 & 打电话监测**：
- 对接更新后的算法（新增行为识别能力）
- 在预览上叠加**吸烟 / 打电话**状态检测与提示
- 具体行为判定、flag、UI 提示在阶段二细化

---

## 3A. 行为监测业务规划（分阶段）

### 阶段一：预览（本次落地）

| 项 | 内容 |
|----|------|
| 入口 | 首页新增 `btn_behavior`（"行为监测"） |
| 页面 | `BehaviorMonitorActivity`（摄像头取流 + 预览） |
| 算法 | 暂不 acquire（仅 FrameSession 取流渲染，节省资源） |
| 目标 | 打通取流/预览/页面生命周期，验证田字型入口跳转 |

### 阶段二：吸烟 & 打电话监测（后续，算法对接后）

| 项 | 内容 |
|----|------|
| 算法 | 对接更新后的算法，新增行为识别（吸烟/打电话）输出 |
| 判定 | 基于算法行为结果，判定吸烟/打电话状态 |
| UI | 在 `BehaviorMonitorActivity` 预览上叠加状态文案/图标提示（如"吸烟中""打电话中"） |
| 待确认 | 算法侧行为识别接口、flag、输出字段、阈值——阶段二对接时细化 |

---

## 4. 设计要点

### 4.1 首页布局改造（田字型）

方案 A（推荐，纯布局）：用 `GridLayout`（androidx.gridlayout 或原生）实现 2×2 网格。

```
GridLayout（2 列，rowCount=2, columnCount=2）
 ├─ btn_recognition   (column 0, row 0)
 ├─ btn_fatigue       (column 1, row 0)
 ├─ btn_distraction   (column 0, row 1)
 └─ btn_behavior      (column 1, row 1)
```

或方案 B：两个嵌套的横向 LinearLayout（每行 2 个按钮），外层垂直排列——更简单、无额外依赖。

**推荐方案 B**（两个 2 列的行 + 外层垂直），改动最小、不引入 gridlayout 依赖：
- 行 1：`btn_recognition` + `btn_fatigue`
- 行 2：`btn_distraction` + `btn_behavior`
- 每个按钮 `width=0dp, weight=1, height=120dp`，行间距 12dp

### 4.2 行为监测页 `BehaviorMonitorActivity`

- 布局 `activity_behavior_monitor.xml`：FrameLayout（preview_container）+ GLSurfaceView + FaceOverlayView（可空）+ 返回按钮
- 复用 `FrameSession` / `AlgoSession` 单例：`acquire` + `configureSurface`（按实际帧尺寸等比适配）+ `open()`
- **阶段一（本次）**：暂不处理算法结果（仅驱动相机取流显示画面），页面预留状态提示区域
- **阶段二（后续）**：在页面叠加吸烟/打电话状态提示（文案/图标）
- 进入/退出生命周期与 `RecognitionActivity` 一致（onStart/onStop、onPause/onResume、onDestroy 释放引用计数）

### 4.3 入口文案与 flag

| 入口 | Activity | 算法 flag | 备注 |
|------|----------|-----------|------|
| 人脸识别 | `RecognitionActivity` | 识别模块 flag | 既有 |
| 疲劳监测 | `FatigueActivity` | 疲劳模块 flag | 既有 |
| 分心监测 | `DistractionActivity` | 分心模块 flag | 既有 |
| **行为监测** | `BehaviorMonitorActivity` | 阶段一：空/仅取流；阶段二：行为识别 flag（待算法） | **新增**，阶段一只预览 |

阶段一只取流显示，**不 acquire 算法**（仅 FrameSession 取相机帧渲染），减少资源占用；阶段二对接算法后按行为识别 flag acquire。

### 4.4 Manifest

- 注册 `BehaviorMonitorActivity`（非 exported，横屏）

---

## 5. 修改范围（草案）

### 阶段一（本次：入口 + 预览）

| # | 文件 | 类型 | 内容 |
|---|------|------|------|
| 1 | `res/layout/activity_home.xml` | 修改 | 三入口横向排列改为 **2×2 田字型**，新增 `btn_behavior` |
| 2 | `res/values/strings.xml` | 修改 | 新增 `home_btn_behavior`（"行为监测"） |
| 3 | `ui/HomeActivity.kt` | 修改 | 绑定 `btn_behavior` → 跳转 `BehaviorMonitorActivity` |
| 4 | `ui/BehaviorMonitorActivity.kt` | 新增 | 行为监测页（暂只预览取流） |
| 5 | `res/layout/activity_behavior_monitor.xml` | 新增 | 行为监测预览布局（预留状态提示区） |
| 6 | `AndroidManifest.xml` | 修改 | 注册 `BehaviorMonitorActivity` |

### 阶段二（后续：吸烟/打电话监测，算法对接后）

| # | 文件 | 类型 | 内容 |
|---|------|------|------|
| 7 | 算法接口对接 | 修改/新增 | 行为识别（吸烟/打电话）输出字段、flag、阈值 |
| 8 | `ui/BehaviorMonitorActivity.kt` | 修改 | acquire 行为识别 flag，解析吸烟/打电话结果 |
| 9 | `res/layout/activity_behavior_monitor.xml` | 修改 | 吸烟/打电话状态提示 UI |

> 阶段二的具体接口（算法侧行为识别输出、flag、阈值）需与算法团队确认后细化。

---

## 6. 风险与注意事项

| 风险 | 影响 | 缓解 |
|------|------|------|
| 田字型在横屏下高度可能不足（4 个 120dp 按钮 + 标题 + 摄像头/版本） | 内容溢出/挤压 | 按钮高度改为 100dp，或整体缩放；必要时隐藏副标题 |
| 行为监测暂只预览，无业务反馈 | 用户点击后仅看到画面，可能困惑 | 页面上方显示模块名 + 返回按钮；标注"预览" |
| FrameSession 引用计数 | 行为监测与识别/疲劳/分心共用单例 | 严格 acquire/release 平衡，`onDestroy` 释放，避免计数泄漏 |
| 是否 acquire 算法 | 若 acquire 则占用算法资源 | 暂不 acquire（仅取流渲染），节省资源 |
| 阶段二算法未就绪 | 行为监测无法提供吸烟/打电话识别 | 阶段一只做预览，算法更新后再对接；界面预留状态提示位 |
| 吸烟/打电话判定准确性 | 误报/漏报 | 阶段二与算法团队确认输出字段与阈值后，再做判定与防抖 |

---

## 7. 实施步骤（建议）

### 阶段一（本次）
1. 布局：`activity_home.xml` 改为田字型 + 新增 `btn_behavior`；
2. strings：新增 `home_btn_behavior`；
3. 新建 `BehaviorMonitorActivity` + 布局（复用 `FrameSession` 仅取流预览，预留状态提示区）；
4. `HomeActivity` 绑定跳转 + Manifest 注册；
5. 设备验证：四入口田字型布局、行为监测页可预览摄像头画面、返回正常。

### 阶段二（后续，算法对接后）
1. 与算法团队确认行为识别（吸烟/打电话）接口、flag、输出字段与阈值；
2. `BehaviorMonitorActivity` acquire 行为识别 flag，解析吸烟/打电话结果；
3. 在预览上叠加吸烟/打电话状态提示，做判定防抖；
4. 设备验证吸烟/打电话识别准确性与稳定性。

---

## 8. 结论

将首页从"三入口横向单排"改造为**四入口田字型（2×2 网格）**，新增**「行为监测」入口**与 **`BehaviorMonitorActivity`**。

行为监测**分阶段实施**：
- **阶段一（本次）**：仅预览——复用 `FrameSession` 摄像头取流显示画面，打通入口/取流/页面生命周期；
- **阶段二（后续）**：对接更新后的算法，在预览上叠加**吸烟**与**打电话**监测。

改动集中在首页布局、`HomeActivity` 跳转与新页面，风险可控；阶段二依赖算法侧行为识别能力。

---

## 9. 实施结果（2026-09-01）

> 记录 FACEP-017 阶段一、阶段二在代码库的落地情况。

### 9.1 阶段一（首页田字型 + 行为监测入口）

- `activity_home.xml`：三入口横向改为 **2×2 田字型**，新增 `btn_behavior`（行为监测）；
- `HomeActivity`：绑定 `btn_behavior` → 跳转 `BehaviorMonitorActivity`；
- 新增 `BehaviorMonitorActivity` + `activity_behavior_monitor.xml`（FrameLayout + GLSurfaceView + 标题 + 状态提示区 + 返回）；
- Manifest 注册 `BehaviorMonitorActivity`（非 exported，横屏）；
- strings：`home_btn_behavior`。

### 9.2 阶段二（吸烟/打电话监测，face-sdk 1.0.1 对接）

- **face-sdk 升级 1.0.1**：新增 `FaceFlag.BEHAVIOR=128`、`FaceResult.behaviorClass`（行为类别）、`FaceResult.behaviorProbs`（概率分布）；
- `IFaceIDAlgorithm.FaceIDResult`：新增 `behaviorClass`/`behaviorProbs`（含 `behaviorProbsSafe` 防御性拷贝）；
- `FaceIDAlgorithmImpl.processFrame`：透传 `r.behaviorClass`/`r.behaviorProbs` 到 `FaceIDResult`；
- `BehaviorMonitorActivity`：`BEHAVIOR_FLAG = FaceFlag.BEHAVIOR`，resultCallback 解析 `behaviorClass` 叠加状态提示（吸烟中/打电话中/正常/未知行为），状态变化才刷新 UI。

### 9.3 行为类别语义（算法头文件定义）

依据算法 C 头文件（`FaceResult.reserved[19]`、`behaviorProbs=reserved[20..22]`）：

| behaviorClass | 语义 | UI 文案 |
|---------------|------|---------|
| 0 | normal（正常） | 「正常」 |
| 1 | smoking（吸烟） | 「吸烟中！」 |
| 2 | phone（打电话） | 「打电话中」 |
| 其他 | 未知 | 「未知行为」 |

- `reserved[19]` = behavior 类别（逐帧结果；**持续时长/报警由 app 侧做时序逻辑**，SDK 不计时）；
- `reserved[20..22]` = 三类概率 P(normal)/P(smoking)/P(phone)；
- AAR 层 `FaceResult.behaviorClass`/`behaviorProbs` 分别对应 reserved[19]/[20..22]。
