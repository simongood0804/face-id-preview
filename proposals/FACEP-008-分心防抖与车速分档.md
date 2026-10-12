# 提案：分心判定多帧防抖 + 对接车速分档触发时间

> 提案编号：FACEP-008  
> 创建日期：2026-08-12  
> 状态：已实现

---

## 1. 背景与动机

当前 `gazeDistracted`（分心标志）是**算法单帧**输出的结果，应用层直接透传显示（`FaceOverlayView` 中 `if (face.gazeDistracted > 0f)` 即显示 "DISTRACTED"）。存在两个问题：

1. **误检抖动**：单帧结果在视线偶发扫过仪表盘/后视镜/中控边缘时，容易瞬时误判，导致 "DISTRACTED" 提示频繁闪烁，影响驾驶体验。
2. **无合规时间窗口**：欧盟 GSR 法规要求分心警告需按**视线持续停留在分心区域的时间**来触发，当前无任何时间累计逻辑。

因此需要：
- 在应用端做**多帧/时间综合防抖**，抑制误检抖动；
- 按 **GSR ADDW (EU) 2023/2590** 法规要求，依据**车速分档**决定分心触发时间；
- **对接车速信号**（VHAL），无车速数据时按最严格档（≥50km/h）处理，避免迟报。

---

## 2. 法规依据（GSR ADDW (EU) 2023/2590）

| 车速区间 | 视线持续停留分心区域（Area 3） | 系统要求 |
|---------|-------------------------------|---------|
| ≥ 50 km/h | 超过 **3.5 秒** | 必须立即发出警告 |
| 20 ~ 50 km/h | 超过 **6 秒** | 必须立即发出警告 |
| 非标称条件（戴眼镜/光线差/遮挡） | 上述基础上延长 **1.5 秒** | 最长 5.0s / 7.5s |

> 关键理解：法规给出的是**最迟报警时间（上限）**，允许更早报警。因此加快触发时间不违反法规，只要不"迟报"（超过上限才响）即可。

### 2.1 本项目采用的触发阈值

| 车速状态 | 触发阈值 | 依据 |
|---------|---------|------|
| **无车速数据**（默认） | **1.5 s** | 无数据按 ≥50km/h 档，快速响应 |
| **≥ 50 km/h** | **1.5 s** | 法规上限 3.5s，取约 40%，规避迟报 |
| **< 50 km/h** | **3.0 s** | 法规上限 6s，取 1/2 |
| 解除阈值（各档通用） | **0.5 s** | 法规未强制，工程防抖用；驾驶员回看后快速解除 |

> 说明：触发用 1.5s/3.0s 均 **低于** 法规上限（3.5s/6s），在任何工况下报警"只早不晚"，绝不会被判迟报不合格；同时 1.5s 能过滤掉大部分"扫一眼"（<1s）的误检。

---

## 3. 方案设计

### 3.1 总体架构

```
VHAL 车速信号 (PERF_VEHICLE_SPEED, m/s)
   │  CarPropertyManager.registerCallback()
   ▼
mVehicleSpeedKmh (@Volatile, km/h；-1 = 无数据)
   │
   └──▶ 分心触发阈值分档
          ├─ 无数据/≥50km/h → 1.5s
          └─ <50km/h        → 3.0s
                │
算法单帧 gazeDistracted ──▶ updateDistraction() 时间累计状态机
                                ├─ 连续分心 ≥ 阈值 → 触发 (mDistractActive=true)
                                └─ 连续非分心 ≥ 0.5s → 解除
                                      │
                                      ▼
                            FaceBox.gazeDistracted (防抖后)
                                └─▶ Overlay 显示 "DISTRACTED"
```

### 3.2 分心防抖状态机（时间累计，非帧数）

核心方法：`PreviewActivity.updateDistraction(result)`

```
状态：mDistractActive（是否已确认分心）
      mDistractAccumStart（最近一次状态累积起始时间戳）

【未触发态】
  单帧 gazeDistracted=true
    ├─ mDistractAccumStart==0 → 记录起始时间 now
    └─ now - 起始时间 ≥ 当前车速阈值 → mDistractActive=true（触发）
  单帧 gazeDistracted=false → 重置累积计时
  阈值随车速动态更新：>=50 或无数据用 1.5s，<50 用 3.0s

【已触发态】
  单帧 gazeDistracted=false
    ├─ 起始时间==0 → 记录 now
    └─ now - 起始时间 ≥ 0.5s → mDistractActive=false（解除）
  单帧 gazeDistracted=true → 重置解除计时（保持分心）
```

**为什么用时间戳（elapsedRealtime）而非帧数：**
- 当前 `FrameProcessor` 采用**单槽替换 + 跳帧**，算法实际帧率约 13.7FPS 且波动；
- 用帧数做阈值会随帧率变化而抖动（如帧率从 15→10，同样的 1.5s 对应不同帧数）；
- `SystemClock.elapsedRealtime()` 是单调时钟，不受系统时间调整影响，最稳定。

### 3.3 车速信号对接（VHAL）

通过 `Car.createCar()` + `CarPropertyManager` 订阅 `VehiclePropertyIds.PERF_VEHICLE_SPEED`：

```kotlin
// 连接 Car 服务（异步，系统应用有权限）
mCar = Car.createCar(this, mCarServiceConnection)

// onServiceConnected 中：
val pm = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
pm.registerCallback(mVehicleSpeedCallback,
    VehiclePropertyIds.PERF_VEHICLE_SPEED,  // 车速属性
    CarPropertyManager.SENSOR_RATE_NORMAL)   // 1Hz 订阅

// 回调：PERF_VEHICLE_SPEED 为 Float，单位 m/s → km/h
mVehicleSpeedKmh = speedMs * 3.6f
```

**容错设计：**
- 连接失败 / 服务断开 / 属性错误 → `mVehicleSpeedKmh = -1` → 分心判定按 **≥50km/h 档（1.5s）** 处理；
- 通过 `@Volatile` 跨线程安全共享，车速回调线程与算法结果回调（主线程）解耦。

### 3.4 集成点

在 `handleAlgorithmResult()` 有人脸分支调用 `updateDistraction()`，并将防抖结果写入 `FaceBox.gazeDistracted` 与 Overlay 的分心提示（固定位置）：

```kotlin
// 有人脸分支
val distractActive = updateDistraction(result)
...
gazeDistracted = if (distractActive) 1f else 0f,  // 保留在 FaceBox（日志/坐标用）
mFaceOverlay.setDistracted(distractActive)        // 固定位置分心提示（见 §5.5）

// 无人脸分支
resetDistraction()      // 重置分心状态
mFaceOverlay.setDistracted(false)  // 清除分心提示
```

---

## 4. 改动文件清单

| # | 文件 | 改动内容 |
|---|------|---------|
| 1 | `PreviewActivity.kt` | 新增 Car VHAL 车速订阅、分心防抖状态机、onDestroy 释放 |
| 2 | `FaceOverlayView.kt` | 分心提示绘制逻辑：固定位置显示、不跟随人脸移动（见 §5.5） |
| 3 | `activity_preview.xml` | 新增 `tv_speed` 显示车速与当前分心档位 |
| 4 | `strings.xml` | 新增 `speed_label` 字符串资源 |

**无需改动**：`IFaceIDAlgorithm.kt`、`FaceIDAlgorithmImpl.kt`（分心原始值仍保留在 `FaceIDResult` 中）。

---

## 5. 详细实现（PreviewActivity.kt 关键代码）

### 5.1 常量

```kotlin
companion object {
    // 分心触发-快速档（≥50km/h 或无车速数据）：1.5s
    private const val DISTRACT_TRIGGER_MS_FAST = 1500L
    // 分心触发-慢速档（<50km/h）：3.0s
    private const val DISTRACT_TRIGGER_MS_SLOW = 3000L
    // 分心解除阈值：0.5s
    private const val DISTRACT_CLEAR_MS = 500L
    // 分档车速阈值（km/h）
    private const val SPEED_FAST_THRESHOLD_KMH = 50f
}
```

### 5.2 字段

```kotlin
@Volatile private var mVehicleSpeedKmh = -1f   // 无数据默认 -1
@Volatile private var mDistractActive = false  // 防抖后分心状态
private var mDistractAccumStart = 0L           // 时间累计起始
private var mDistractTriggerMs = DISTRACT_TRIGGER_MS_FAST  // 当前生效阈值
```

### 5.3 防抖状态机

```kotlin
private fun updateDistraction(result: IFaceIDAlgorithm.FaceIDResult): Boolean {
    val now = SystemClock.elapsedRealtime()
    val distracted = result.gazeDistracted > 0f

    // 车速分档：无数据(<0)或高速(≥50)用快速档，低速用慢速档
    mDistractTriggerMs = if (mVehicleSpeedKmh >= 0f && mVehicleSpeedKmh < SPEED_FAST_THRESHOLD_KMH) {
        DISTRACT_TRIGGER_MS_SLOW
    } else {
        DISTRACT_TRIGGER_MS_FAST
    }

    if (mDistractActive) {
        // 已触发：连续非分心达到解除阈值才解除
        if (!distracted) {
            if (mDistractAccumStart == 0L) mDistractAccumStart = now
            else if (now - mDistractAccumStart >= DISTRACT_CLEAR_MS) {
                mDistractActive = false
                mDistractAccumStart = 0L
            }
        } else {
            mDistractAccumStart = 0L  // 仍分心，重置解除计时
        }
    } else {
        // 未触发：连续分心达到触发阈值才触发
        if (distracted) {
            if (mDistractAccumStart == 0L) mDistractAccumStart = now
            else if (now - mDistractAccumStart >= mDistractTriggerMs) {
                mDistractActive = true
                mDistractAccumStart = 0L
            }
        } else {
            mDistractAccumStart = 0L  // 非分心，重置触发计时
        }
    }
    return mDistractActive
}
```

### 5.4 车速订阅

```kotlin
private val mCarServiceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val car = mCar ?: return
        if (!car.isConnected) return
        val pm = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return
        mCarPropertyManager = pm
        pm.registerCallback(mVehicleSpeedCallback,
            VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
    }
    override fun onServiceDisconnected(name: ComponentName?) {
        mCarPropertyManager = null
        mVehicleSpeedKmh = -1f  // 断开则按高速档处理
    }
}

private val mVehicleSpeedCallback = object : CarPropertyManager.CarPropertyEventCallback {
    override fun onChangeEvent(value: CarPropertyValue<*>) {
        val speedMs = value.value as? Float ?: return
        mVehicleSpeedKmh = speedMs * 3.6f  // m/s → km/h
    }
    override fun onErrorEvent(propertyId: Int, zoneId: Int) {
        mVehicleSpeedKmh = -1f
    }
}
```

### 5.5 分心提示绘制逻辑修改（FaceOverlayView.kt）

#### 问题描述

当前 "DISTRACTED" 提示的绘制位置在 `drawGaze()` 内，跟随人脸框（`FaceOverlayView.kt:334-338`）：

```kotlin
// 分心提示（显示在人脸框上方）
if (face.gazeDistracted > 0f) {
    canvas.drawText("DISTRACTED",
        face.rect.left * scaleX, face.rect.top * scaleY - 10f, mDistractedPaint)
}
```

存在两个问题：
1. **跟随人脸移动**：提示文字锚定在人脸框左上角上方，人脸在画面中移动时，提示随之漂移，观感不稳定；
2. **遮挡视线区域**：提示位于人脸框正上方（面部/头姿坐标系区域），会遮挡头部姿态箭头、视线方向线等有效调试信息。

#### 修改方案

将分心提示改为**固定屏幕位置**显示（不随人脸移动），并让出人脸/视线区域。推荐位置：**画面右侧中部**（避开左侧 zone 面板与中部人脸视线区）。

**改动 A：`FaceOverlayView` 新增分心状态的固定绘制**

```kotlin
// 新增字段：分心提示（固定屏幕位置，不随人脸移动）
@Volatile private var mDistractShown = false

fun setDistracted(shown: Boolean) {
    mDistractShown = shown
    postInvalidate()
}
```

**改动 B：从 `drawGaze()` 中移除旧的分心绘制**

```kotlin
// 删除 drawGaze() 中跟随人脸的绘制：
- if (face.gazeDistracted > 0f) {
-     canvas.drawText("DISTRACTED",
-         face.rect.left * scaleX, face.rect.top * scaleY - 10f, mDistractedPaint)
- }
```

**改动 C：在 `onDraw()` 末尾绘制固定位置的分心提示**

```kotlin
// onDraw() 中，绘制完所有人脸/zone 面板之后：
if (mDistractShown) {
    // 固定位置：画面右侧中部（避开左侧 zone 面板与中部人脸视线区）
    val text = "DISTRACTED"
    val x = width * 0.70f
    val y = height * 0.45f
    // 背景 + 文字（复用 mBgPaint / mDistractedPaint）
    val w = mDistractedPaint.measureText(text)
    canvas.drawRect(x - 8f, y - mDistractedPaint.textSize - 8f,
        x + w + 8f, y + 8f, mBgPaint)
    canvas.drawText(text, x, y, mDistractedPaint)
}
```

#### 集成联动

- `PreviewActivity` 在 `handleAlgorithmResult` 中，把防抖后的分心状态通过新接口传给 Overlay：

```kotlin
// 有人脸分支：更新分心显示（防抖后）
mFaceOverlay.setDistracted(distractActive)

// 无人脸分支：清除分心显示
mFaceOverlay.setDistracted(false)
```

> 说明：`FaceBox.gazeDistracted` 字段仍保留（供坐标/日志使用），但分心**绘制不再依赖**它，改由 `setDistracted()` 单独控制固定位置提示。

#### 位置选型说明

| 位置 | 优点 | 缺点 |
|------|------|------|
| **画面右侧中部（推荐）** | 避开左侧 zone 面板、中部人脸/视线/头姿区；醒目 | 需确认右侧无其他元素 |
| 画面底部 | 不遮挡上半部信息 | 离视线区远，不够醒目 |
| 顶部标题栏 | 最显眼 | 可能遮挡 FPS/状态文本 |

采用**右侧中部**，兼顾醒目与不遮挡核心调试区域。

---

## 6. 验证方案

1. **编译验证**：`make build` 编译成功（已通过 `compileReleaseKotlin`）。
2. **车速读取验证**：Logcat 观察 `PERF_VEHICLE_SPEED` 订阅成功；`tv_speed` 显示实际车速。
3. **分档验证**：分别测试 `无车速`、`<50km/h`、`≥50km/h` 三档，观察 `tv_speed` 中 `fast/slow` 档位切换。
4. **防抖验证**：
   - 快速扫视（<1.5s）分心区域 → 不应触发 "DISTRACTED"；
   - 持续凝视（≥1.5s）分心区域 → 触发，且**延迟约 1.5s 后响应**（非迟报）；
   - 回看前方 → 约 0.5s 内解除。
5. **分心绘制位置验证**：
   - 触发分心时，提示显示在**固定位置（右侧中部）**，不随人脸框移动；
   - 提示区域**不遮挡**左侧 zone 面板、人脸框、头部姿态箭头、视线方向线等有效信息；
   - 解除分心后提示消失。
6. **回归验证**：人脸检测/识别/zone/头姿/视线功能正常。

---

## 7. 风险与注意事项

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 无法访问 Car VHAL（非系统环境） | 车速读不到 | 已做容错：`mVehicleSpeedKmh=-1` → 按 ≥50km/h 档（1.5s），不迟报 |
| `Car.createCar(Context, ServiceConnection)` 已弃用 | 编译 warning | 功能正常；后续可改用 lifecycle listener 消除 warning |
| 阈值是工程经验值，非法规强制 | 测试可能觉得偏快/偏慢 | 已抽成常量，可快速微调；当前 1.5s 既响应快又合规 |
| 解除时间法规未强制 | 无标准参考 | 用 0.5s，兼顾及时解除与防抖 |
| 车速瞬时抖动跨档（如 49.9↔50.1） | 档位频繁切换 | 阈值已留余量；如需可加迟滞（hysteresis），暂未加 |
| 分心提示改为固定位置后与其它 UI 元素重叠 | 遮挡有效信息 | 右侧中部已避开左侧 zone 面板与中部人脸视线区；需实测确认 |
| 分心显示状态与 Overlay 清空时序不一致 | 无人脸时提示残留 | `setDistracted(false)` 在无人脸分支与 `clearFaces()` 同步调用 |

---

## 8. 结论

本提案在应用端实现：
1. **分心多帧/时间防抖**，抑制单帧误检导致的 "DISTRACTED" 闪烁；
2. **对接车速信号**，按 GSR ADDW 法规要求分档触发时间（≥50km/h 或无限速 1.5s，<50km/h 3.0s）；
3. **无车速数据时按最严格档处理**，保证任何工况下都不迟报；
4. **分心提示改为固定位置绘制**（右侧中部），不再跟随人脸移动，避免遮挡视线/头姿等有效区域。

触发时间均低于法规上限，**合规且响应及时**，满足测试人员"不要迟报"的诉求。
