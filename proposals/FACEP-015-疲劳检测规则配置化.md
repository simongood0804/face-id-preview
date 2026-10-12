# 提案：疲劳检测规则配置化 —— 从 JSON 文件读取规则

> 提案编号：FACEP-015
> 创建日期：2026-08-25
> 状态：**提案**（待评审）
> 关联：FACEP-011（上层模块化）、FACEP-014（app 与 algo 模块拆分）

---

## 1. 背景与动机

当前疲劳检测的**规则全部硬编码**在 `FatigueActivity` 的 companion object 里：

```kotlin
private const val EYE_CLOSE_ALERT_MS = 3000L   // 持续闭眼告警阈值
private const val EYE_CLOSE_CLEAR_MS = 500L    // 退出闭眼告警阈值
private const val YAWN_ALERT_MS = 2000L        // 持续哈欠告警阈值
private const val YAWN_CLEAR_MS = 500L         // 退出哈欠告警阈值
private const val NO_FACE_RESET_MS = 3000L     // 无人脸复位阈值
```

问题：

1. **改规则要改代码重新编译**——业务/标定人员想调"打哈欠多久判疲劳"，必须改 Kotlin 源码、重新出 APK，无法在真机快速标定；
2. **规则与逻辑耦合**——阈值硬编码在 UI Activity 里，且疲劳判定逻辑（`onAlgorithmResult` 里的计时/状态机）也在 `FatigueActivity`，违反"业务逻辑下沉"的架构方向（FACEP-014 刚把算法/判定逻辑下沉到 `:algo`，疲劳判定却还留在 app Activity 里）；
3. **不可热更新/不可按车型差异化配置**——不同车型、不同客户可能要求不同的疲劳判定阈值，硬编码无法适配；
4. **缺乏分级能力**——当前只有"闭眼/哈欠"二元告警，**无法表达疲劳的严重程度分级**（轻度/中度/重度），也没有"高等级覆盖低等级"的升级语义。

> 诉求：**将疲劳检测规则从 JSON 文件读取，做到规则配置化**。规则内容包括：
> - **打哈欠判断**：基于**嘴部开合度** + **嘴部持续打开的时间**；
> - **疲劳分三级**：轻度 / 中度 / 重度，**高等级覆盖低等级**（升级式）；
> - **每个等级都有独立的进入和退出判断条件**（可在 JSON 配置）；
> - **进入条件是"或"关系**：每级进入 = 多个子条件任一满足，子条件涉及**闭眼次数**（时间窗内）、**单次闭眼时长**、**连续闭眼时长**、**哈欠次数**（时间窗内）等多信号源（用户给定规则示例见 §3.2）。

---

## 2. 现状分析

### 2.1 疲劳判定逻辑（`FatigueActivity.onAlgorithmResult`）

当前流程：
```
算法结果 FaceIDResult (eyeOpen / mouthOpen / faceId)
   │
   ├─ 无人脸(faceId空+faceRect空) → 持续 NO_FACE_RESET_MS 才 resetFatigue()
   ├─ eyeOpen=false 持续 ≥ EYE_CLOSE_ALERT_MS → setFatigue(1) 闭眼告警
   ├─ mouthOpen=true 持续 ≥ YAWN_ALERT_MS  → setFatigue(2) 哈欠告警
   ├─ 退出：恢复正常持续 ≥ *_CLEAR_MS → clearFatigue()
   └─ 告警展示到 TextView（红/绿）
```

所有阈值是 `private const val`，**写死在 Activity**。

### 2.2 项目已有的 JSON 读取先例（可复用模式）

| 位置 | 用途 | 解析方式 |
|------|------|---------|
| `FaceIDAlgorithmImpl` | 读取 `dms_calibration.json`（相机内参/外参） | `org.json.JSONObject` + `optDouble/optJSONObject`（带默认值，容错） |
| `FaceEnrollmentManager` | 持久化 `face_enrollments.json`（人脸库） | `org.json.JSONObject/JSONArray` |

结论：**复用 `org.json`（Android 内置，无需新依赖）**，读取规则 JSON 时用 `optXxx(默认值)` 做**容错**（字段缺失/类型错误时回退默认值，不崩溃）。

### 2.3 规则归属：应随业务判定逻辑下沉 `:algo`

FACEP-014 已把 `EyeMouth*` 状态机、`DistractionStateMachine` 等业务判定下沉到 `:algo`。但疲劳判定（触发阈值 + 计时状态机）仍留在 app 的 `FatigueActivity`。本提案建议**一并把疲劳判定逻辑抽到 `:algo`**（新建 `FatigueEventDetector` / `SlidingWindowCounter` / `FatigueStateMachine` 疲劳引擎，见 §3.4），规则配置类也放 `:algo`，app 只做装配与展示。

---

## 3. 目标设计

> 规则需求（2026-08-25 补充）：
> 1. **打哈欠判断**：基于**嘴部开合度**（`mouthOpenRatio`，0~1）+ **嘴部持续打开的时间**；
> 2. **疲劳分三级**：轻度疲劳、中度疲劳、重度疲劳，**高等级覆盖低等级**（升级式，重度到达即取代中度/轻度）；
> 3. **每个等级都有对应的进入和退出判断条件**（可在 JSON 配置）；
> 4. **进入条件是"或"关系**：每级进入 = 多个子条件**任一满足**即触发；条件涉及**闭眼次数**（时间窗内）、**单次闭眼时长**、**连续闭眼时长**、**哈欠次数**（时间窗内）等多信号源。

### 3.1 疲劳等级与覆盖模型

状态机状态：`NONE → LIGHT(轻度) → MODERATE(中度) → SEVERE(重度)`。

- **升级覆盖**：后一等级条件满足时，**直接切换**到更高级别，无需先退出低级别；
- **进入=OR**：每级 `enter` 是**条件数组**，任一条件满足即进入该级（多信号源：闭眼计数/哈欠计数/连续闭眼时长）；
- **退出（直接回 NONE）**：每级独立退出条件（示例统一为"20s 内无超过 0.75s 的闭眼或哈欠"）；退出条件满足后**直接回正常（NONE）**，不再逐级降（三级是覆盖关系，退出即恢复正常）；
- **信号源**：`eyeOpen`（闭眼）+ `mouthOpenRatio`（嘴部开合度，打哈欠）；由 `EyeMouthStateEstimator` 提供连续量，经事件检测器转化为**闭眼/哈欠事件**（含持续时长）。

### 3.2 规则 JSON 格式（示例 = 用户给定规则）

```json
{
  "schema_version": 1,
  "yawn": {
    "enabled": true,
    "mouth_open_ratio": 0.3,        // 打哈欠开合度阈值（嘴部开合度 ≥ 此值视为张嘴）
    "min_duration_ms": 2000         // 嘴部持续打开 ≥ 此时长记一次"哈欠事件"
  },
  "eye_close": {
    "enabled": true,
    "min_count_duration_ms": 200    // 单次闭眼 ≥ 此时长才计入"有效闭眼次数"
  },
  "levels": [
    {
      "level": "LIGHT",
      "enabled": true,
      "enter": [                                   // 或关系：任一满足即进入
        { "type": "eye_close_count", "window_ms": 60000, "count_min": 9,  "min_duration_ms": 200 },
        { "type": "yawn_count",      "window_ms": 60000, "count_min": 2 }
      ],
      "exit": { "type": "clean_clear", "window_ms": 20000, "clear_duration_ms": 750 }
    },
    {
      "level": "MODERATE",
      "enabled": true,
      "enter": [
        { "type": "eye_close_count",   "window_ms": 60000, "count_min": 10, "min_duration_ms": 200 },
        { "type": "eye_close_count",   "window_ms": 20000, "count_min": 2,  "min_duration_ms": 750 },
        { "type": "eye_close_duration","min_ms": 1500, "max_ms": 2400 },
        { "type": "yawn_count",        "window_ms": 60000, "count_min": 3 }
      ],
      "exit": { "type": "clean_clear", "window_ms": 20000, "clear_duration_ms": 750 }
    },
    {
      "level": "SEVERE",
      "enabled": true,
      "enter": [
        { "type": "eye_close_count",   "window_ms": 20000, "count_min": 2,  "min_duration_ms": 1200 },
        { "type": "eye_close_duration","min_ms": 2400 }
      ],
      "exit": { "type": "clean_clear", "window_ms": 20000, "clear_duration_ms": 750 }
    }
  ],
  "no_face": { "enabled": true, "reset_ms": 3000 }
}
```

**条件类型**（`type`）：

| type | 语义 | 关键参数 |
|------|------|---------|
| `eye_close_count` | 时间窗内**闭眼次数**（单次 ≥ `min_duration_ms` 才计数） | `window_ms`、`count_min`、`min_duration_ms` |
| `yawn_count` | 时间窗内**哈欠次数**（嘴开合 ≥ `mouth_open_ratio` 且持续 ≥ `min_duration_ms`） | `window_ms`、`count_min` |
| `eye_close_duration` | **连续闭眼时长**落在区间 `[min_ms, max_ms)` | `min_ms`、`max_ms`（缺省 `max_ms`=无穷大） |
| `clean_clear`（仅退出） | 时间窗内**无超过 `clear_duration_ms` 的闭眼/哈欠事件** | `window_ms`、`clear_duration_ms` |

> **张嘴量纲核实（2026-08-25）**：`mouth_open_ratio` 为 `EyeMouthStateEstimator` 输出的**连续嘴部开合度**（0~1），线性归一化
> `mouthOpenRatio = ((MAR - 0.35) / (0.62 - 0.35)).coerceIn(0, 1)`。
> - 规则中的 `yawn.mouth_open_ratio = 0.3` **直接作用于该连续量**（对应原始 MAR ≈ 0.431，即"嘴部微张起"）；
> - **与状态机 `mouthOpen` 的阈值 0.60（MAR≈0.512，"明显张嘴"）不同**——疲劳打哈欠判定用更敏感的门槛 0.3，不复用布尔 `mouthOpen`，二者用途独立、互不影响；
> - 因此疲劳引擎必须消费 `FaceIDResult` 的连续 `mouthOpenRatio` 字段（见 §3.6），而非布尔 `mouthOpen`。

> **闭眼量纲核实（2026-08-25）**：`eye_close_count` / `eye_close_duration` 基于**连续眼睛开合度** `eyeOpenRatio`（0~1，1=全睁），
> 线性归一化 `eyeOpenRatio = ((aperture - 闭眼残差) / (睁眼 - 闭眼残差)).coerceIn(0, 1)`。
> - 判"闭眼"：`eyeOpenRatio ≤ 0.10`（复用 `EyeMouthStateMachine.DEFAULT_EYE_CLOSE_RATIO`，即"闭眼候选"）；
> - 睁眼退出：`eyeOpenRatio ≥ 0.30`（`DEFAULT_EYE_OPEN_RATIO`）；
> - 规则中的"单次闭眼时长"= `eyeOpenRatio ≤ 0.10` 的**连续持续时长**，"闭眼次数"= 一次闭眼事件（时长 ≥ `min_duration_ms`）。

**示例解读**（与用户规则一一对应）：
- 轻度进入：60s 内闭眼 ≥9 次（单次 ≥0.2s）**或** 60s 内哈欠 ≥2 次；
- 中度进入：60s 内闭眼 ≥10 次（单次 ≥0.2s）**或** 20s 内闭眼 ≥2 次（单次 ≥0.75s）**或** 连续闭眼 1.5s~2.4s **或** 60s 内哈欠 ≥3 次；
- 重度进入：20s 内闭眼 ≥2 次（单次 ≥1.2s）**或** 连续闭眼 ≥2.4s；
- 各级退出（统一）：20s 内无超过 0.75s 的闭眼/哈欠；
- 所有字段带默认值、容错；`schema_version` 预留。

### 3.3 数据模型（`:algo` 新增）

```kotlin
// algo/src/main/java/com/skyworth/faceid/fatigue/FatigueRule.kt
data class FatigueRule(
    val schemaVersion: Int = 1,
    val yawn: YawnRule = YawnRule(),             // 打哈欠基础条件（嘴开合阈值 + 最短时长）
    val eyeClose: EyeCloseRule = EyeCloseRule(), // 闭眼计数基础条件
    val levels: List<LevelRule> = DEFAULT_LEVELS,// 三级（LIGHT/MODERATE/SEVERE）
    val noFace: NoFaceRule = NoFaceRule()
) {
    data class YawnRule(
        val enabled: Boolean = true,
        val mouthOpenRatio: Float = 0.3f,        // 嘴部开合度阈值（≥此视为张嘴）
        val minDurationMs: Long = 2000L          // 持续打开 ≥ 此时长记一次哈欠
    )

    data class EyeCloseRule(
        val enabled: Boolean = true,
        val minCountDurationMs: Long = 200L      // 单次闭眼 ≥ 此时长计入有效闭眼
    )

    /** 单个进入/退出条件。 */
    sealed class Condition {
        data class EyeCloseCount(val windowMs: Long, val countMin: Int, val minDurationMs: Long) : Condition()
        data class YawnCount(val windowMs: Long, val countMin: Int) : Condition()
        data class EyeCloseDuration(val minMs: Long, val maxMs: Long? = null) : Condition()
        data class CleanClear(val windowMs: Long, val clearDurationMs: Long) : Condition()
    }

    data class LevelRule(
        val level: Level,                // 枚举 LIGHT/MODERATE/SEVERE
        val enabled: Boolean = true,
        val enter: List<Condition>,      // 进入：OR（任一满足）
        val exit: Condition              // 退出：CleanClear 等
    )

    enum class Level { LIGHT, MODERATE, SEVERE }

    data class NoFaceRule(
        val enabled: Boolean = true,
        val resetMs: Long = DEFAULT_NO_FACE_RESET_MS
    ) { companion object { const val DEFAULT_NO_FACE_RESET_MS = 3000L } }

    companion object {
        val DEFAULT_LEVELS: List<LevelRule> = ...   // 对应 §3.2 用户示例规则
    }
}

// algo/src/main/java/com/skyworth/faceid/fatigue/FatigueRuleLoader.kt
// 解析 fatigue_rules.json → FatigueRule（org.json，optXxx 容错；文件缺失/损坏时返回默认规则）
object FatigueRuleLoader {
    fun load(jsonText: String?): FatigueRule          // 解析字符串
    fun loadFromFile(file: File): FatigueRule          // 读文件（不存在→默认）
    fun loadFromAssets(context: Context): FatigueRule  // 读 assets（兜底）
}
```

### 3.4 疲劳判定引擎（`:algo` 新增，多级 + 覆盖 + 窗口统计）

疲劳引擎分两层：**事件检测器**（把连续量转成事件）+ **等级状态机**（事件计数/时长 → 疲劳等级）。纯 JVM，**不依赖 Android/UI**，规则由外部注入。

```kotlin
// ① 事件检测器：将逐帧 eyeOpenRatio/mouthOpenRatio 转化为"闭眼/哈欠事件"
// algo/src/main/java/com/skyworth/faceid/fatigue/FatigueEventDetector.kt
class FatigueEventDetector(
    private val rule: FatigueRule,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    /** 输入逐帧眼睛/嘴连续开合度与是否有人脸，产出到达/结束的事件（含持续时长）。 */
    fun onFrame(eyeOpenRatio: Float, mouthOpenRatio: Float, hasFace: Boolean): List<Event>
    // 闭眼判定：eyeOpenRatio ≤ 0.10（EyeMouthStateMachine.DEFAULT_EYE_CLOSE_RATIO）
    // 张嘴判定：mouthOpenRatio ≥ yawn.mouth_open_ratio（0.3）
    // Event: EyeCloseStart/EyeCloseEnd(带 durationMs) / YawnStart/YawnEnd(带 durationMs)
}

// ② 滑动窗口计数器：统计时间窗内事件次数
// algo/src/main/java/com/skyworth/faceid/fatigue/SlidingWindowCounter.kt
class SlidingWindowCounter(windowMs: Long) {
    fun record(eventTimeMs: Long)         // 记录一次事件
    fun countInWindow(nowMs: Long): Int   // 当前窗口内事件数
}

// ③ 等级状态机：消费事件 → 判定疲劳等级
// algo/src/main/java/com/skyworth/faceid/fatigue/FatigueStateMachine.kt
class FatigueStateMachine(
    private val rule: FatigueRule = FatigueRule(),
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    /** 更新一帧眼睛/嘴连续开合度与是否有人脸，返回当前疲劳等级。 */
    fun update(eyeOpenRatio: Float, mouthOpenRatio: Float, hasFace: Boolean): FatigueOutput

    data class FatigueOutput(
        val level: Level,        // NONE/LIGHT/MODERATE/SEVERE
        val active: Boolean,     // level != NONE
        val matchedCondition: String?  // 命中的进入条件（诊断用）
    )
}
```

**状态转移**（升级覆盖 + 直接退出）：
```
[各等级] --该级 enter 任一条件满足--> 切换/升级到该级（重度覆盖中度/轻度）
[各等级] --该级 exit(CleanClear) 满足--> 直接回 NONE（不再逐级降）
[任意] --无人脸持续 reset_ms--> NONE
```

**引擎工作流**：
1. `FatigueEventDetector.onFrame(eyeOpenRatio, mouthOpenRatio, hasFace)` 产出闭眼/哈欠事件的**开始/结束**（带持续时长）；
2. 事件结束入 `SlidingWindowCounter`（按各自 `window_ms`，如 60s/20s）；
3. `FatigueStateMachine` 检查当前等级 `enter` 条件（OR）：`eye_close_count`/`yawn_count` 查窗口计数，`eye_close_duration` 查当前连续闭眼时长；任一满足 → 进入/升级该级；
4. 退出检查 `exit`（`CleanClear`：窗口内无超过阈值的闭眼/哈欠事件）→ **直接回 NONE**（不再逐级降）；
5. 无人脸持续 `reset_ms` → 复位 NONE。

**关键实现细节**：
- **单次闭眼时长**：`EyeCloseEnd` 事件的 `durationMs` 与 `min_duration_ms`（200/750/1200）比较，达标才计入对应条件的计数；
- **连续闭眼时长**：当前 `EyeCloseStart→现在` 的持续时长，与 `eye_close_duration` 的 `[min_ms, max_ms)` 区间比较（中度 1.5~2.4s、重度 ≥2.4s）；
- **哈欠事件**：嘴开合 ≥ `mouth_open_ratio`（0.3）且持续 ≥ `min_duration_ms`（2s）记一次哈欠；
- 纯 JVM（仅 `clockMs` 注入），可单元测试覆盖各级进入/覆盖/直接退出/无人脸复位；
- `enabled` 开关：某级 `enabled=false` 不参与升级；`yawn.enabled=false` / `eyeClose.enabled=false` 关闭对应信号。

### 3.5 app 侧接入（`FatigueActivity` 瘦身）

```
onCreate：FatigueRuleLoader.loadFromAssets(this)   // 读 assets/fatigue_rules.json
        → FatigueStateMachine(rule)
onAlgorithmResult：eyeOpenRatio/mouthOpenRatio = result 新增字段（连续量）
        → machine.update(eyeOpenRatio, mouthOpenRatio, hasFace)
        → 根据 FatigueOutput.level 更新 UI（轻度/中度/重度 分级告警文案）
```

- `FatigueActivity` 只保留**渲染展示**（分级告警文案、颜色）与**装配**，判定逻辑全部移入 `FatigueStateMachine`；
- **需补充**：当前 `FaceIDResult` 只有布尔 `mouthOpen`/`eyeOpen`，没有连续 `mouthOpenRatio`/`eyeOpenRatio`。疲劳规则需要**嘴部开合度**（哈欠）与**眼睛连续开合度**（闭眼时长/次数），因此 `FaceIDResult` 需增加这两个连续量（见 §3.6）。引擎内部用连续量判定闭眼（`eyeOpenRatio ≤ 0.10`）/张嘴（`mouthOpenRatio ≥ 0.3`），而非布尔值。

### 3.6 补充：FaceIDResult 透传眼/嘴连续开合度

`EyeMouthStateEstimator` 已计算 `eyeOpenRatio`/`mouthOpenRatio`（0~1），但当前 `FaceIDAlgorithmImpl.processFrame` 只把布尔 `eyeOpen`/`mouthOpen` 塞进 `FaceIDResult`。本提案需在 `FaceIDResult` 增加字段：

```kotlin
data class FaceIDResult(
    ...
    val eyeOpen: Boolean,           // 现有
    val mouthOpen: Boolean,         // 现有
    val eyeOpenRatio: Float = 1f,   // 新增：眼睛连续开合度（闭眼时长/次数用，0~1，1=全睁）
    val mouthOpenRatio: Float = 0f  // 新增：嘴部连续开合度（打哈欠用，0~1，1=全张）
)
```

在 `FaceIDAlgorithmImpl` 透传 `eyeEst?.eyeOpenRatio` / `eyeEst?.mouthOpenRatio`（地标缺失时回退默认 1f/0f）。`FaceOverlayBridge`/渲染层不消费这两个字段，仅疲劳引擎使用，不破坏现有契约。闭眼判定用 `eyeOpenRatio ≤ 闭眼阈值`（复用现有 `EyeMouthStateMachine` 的 `eyeCloseRatio`）判断是否闭眼。

### 3.7 规则来源优先级

支持多级规则来源，便于生产部署与标定调试：

```
1. 外部配置文件（如 /vendor/etc/fatigue_rules.json 或 /sdcard，可热更新/按车型下发）
   ↓ 不存在则
2. assets/fatigue_rules.json（随 APK 内置默认规则，发布时打包）
   ↓ 解析失败则
3. 代码内置默认值（FatigueRule 默认构造，最终兜底）
```

> 生产建议：默认规则放 `assets/fatigue_rules.json`；如需按车型差异化或热更新，可后续支持从 `/vendor/etc` 或远程下发（本提案先落地"从 json 读取 + 三级优先级"，远程下发留作扩展）。

---

## 4. 配置示例与效果

### 4.1 默认配置（用户给定规则）

`app/src/main/assets/fatigue_rules.json` 默认内容即 §3.2 示例，与用户给定规则一一对应：
- 打哈欠：`mouth_open_ratio=0.3`、持续 ≥2s 记一次哈欠；
- 轻度：60s 内闭眼 ≥9 次（单次 ≥0.2s）或 60s 内哈欠 ≥2 次；退出 = 20s 内无超 0.75s 闭眼/哈欠；
- 中度：60s 闭眼 ≥10 次(≥0.2s) / 20s 闭眼 ≥2 次(≥0.75s) / 连续闭眼 1.5~2.4s / 60s 哈欠 ≥3 次；退出同上；
- 重度：20s 闭眼 ≥2 次(≥1.2s) / 连续闭眼 ≥2.4s；退出同上。

### 4.2 配置化带来的能力

- **快速标定**：调整任意阈值（次数/时长/窗口），只需改 JSON，无需改 Kotlin；
- **进入 OR 条件**：每级 enter 是条件数组，任一满足即进入，新增条件只需在 JSON 加一条；
- **多信号源**：闭眼计数 / 哈欠计数 / 连续闭眼时长 / 退出清空，全部可配；
- **覆盖关系自动生效**：重度/中度条件满足即覆盖低等级（升级式，JSON 无需额外配置）；
- **单等级/单信号开关**：如某车型不要重度告警，SEVERE 级 `enabled:false`；不要哈欠判定，`yawn.enabled:false`；
- **按车型/客户差异化**：不同车型发布不同的 `fatigue_rules.json`。

### 4.3 渲染页面设计（疲劳监测页）

疲劳监测页 UI 分三块：**左上角状态指示灯**、**闭眼/哈欠状态描述区**、**规则诊断统计区**（三行文档，不跳变）。

#### 4.3.1 布局结构

```
┌─────────────────────────────────────────┐
│ [● 状态指示灯]  状态文本   ← 左上角      │
│                                         │
│                    (预览 GLSurfaceView) │
│                                         │
│ ┌─────────────────────────────────────┐ │
│ │ 闭眼状态：◯ 睁眼 / ● 闭眼(持续 xxx ms)│ │  ← 闭眼描述
│ │ 哈欠状态：◯ 无 / ● 哈欠(持续 xxx ms)  │ │  ← 哈欠描述
│ │ 疲劳等级：正常 / 轻度 / 中度 / 重度    │ │  ← 等级描述
│ └─────────────────────────────────────┘ │
│            [返回主页]                    │
└─────────────────────────────────────────┘
```

#### 4.3.2 状态指示灯（左上角）

一个圆形指示灯（`View`/`TextView` 加圆形背景），颜色随疲劳等级切换：

| 等级 | 颜色 | 十六进制 |
|------|------|---------|
| 正常（NONE） | 绿色 | `#00FF00` |
| 轻度（LIGHT） | 黄色 | `#FFFF00` |
| 中度（MODERATE） | 橙色 | `#FFA500` |
| 重度（SEVERE） | 红色 | `#FF0000` |

旁侧状态文本同步显示"正常 / 轻度疲劳 / 中度疲劳 / 重度疲劳"。

#### 4.3.3 闭眼 / 哈欠状态描述（下方两行）

实时文本（**不闪烁**，仅随状态变化更新）：

```
闭眼状态：睁眼                        （或 "闭眼 已持续 1.2s"）
哈欠状态：无                          （或 "哈欠 嘴开合 0.62 已持续 2.5s"）
```

- **闭眼状态**：`eyeOpenRatio` 实时值 + 是否闭眼（≤0.10）+ 当前持续时长；
- **哈欠状态**：`mouthOpenRatio` 实时值 + 是否张嘴（≥0.3）+ 当前持续时长。

#### 4.3.4 规则诊断统计区（三行，**不跳变**，观察规则影响）

用户需求："能看到当前的判断条件对结果的影响"。下方显示**当前命中的判断条件**与关键统计，用于验证规则配置效果：

```
疲劳等级：中度  命中条件：60s内闭眼10次(单次≥0.2s)
[统计] 60s闭眼: 12次  20s闭眼: 3次  60s哈欠: 1次
[统计] 当前连续闭眼: 1.8s  当前哈欠: 0s  无人脸: 0s
```

- **等级 + 命中条件**：显示当前 `FatigueOutput.level` 与 `matchedCondition`（§3.4）；
- **窗口计数**：60s/20s 内闭眼次数、哈欠次数实时统计（来自 `SlidingWindowCounter`）；
- **连续状态**：当前连续闭眼时长、哈欠时长、无人脸时长；
- **不跳变**：文本稳定显示（不闪烁/不闪动），仅数据变化时刷新——便于持续观察"某条规则是否被触发、阈值调整对判定结果的影响"。

> 诊断区为调试用途（验证规则影响），正式发布可隐藏或仅保留状态指示灯 + 闭眼/哈欠描述。`FatigueOutput` 需额外暴露统计字段（窗口计数、命中条件、各连续时长），见 §3.4 扩展。

#### 4.3.5 FatigueOutput 扩展（渲染诊断用）

`FatigueStateMachine.FatigueOutput` 增加诊断字段，供渲染区展示：

```kotlin
data class FatigueOutput(
    val level: Level,
    val active: Boolean,
    val matchedCondition: String?,   // 命中的进入条件
    val eyeOpenRatio: Float,         // 当前眼睛开合度（实时）
    val mouthOpenRatio: Float,       // 当前嘴部开合度（实时）
    val eyeCloseCount60s: Int, val eyeCloseCount20s: Int,   // 窗口闭眼计数
    val yawnCount60s: Int,                                   // 窗口哈欠计数
    val curEyeCloseMs: Long, val curYawnMs: Long, val curNoFaceMs: Long  // 连续时长
)
```

---

## 5. 迁移步骤

| 步骤 | 内容 | 验证 |
|------|------|------|
| **1** | `:algo` 新增 `fatigue` 包：`FatigueRule`（三级模型+条件类型）、`FatigueRuleLoader`（org.json 容错解析） | `:algo:test` 通过 |
| **2** | 新增 `FatigueEventDetector`（连续量→闭眼/哈欠事件）+ `SlidingWindowCounter`（窗口计数，纯 JVM） | `:algo:test` 通过 |
| **3** | 新增 `FatigueStateMachine`（多级+覆盖+进入 OR+直接退出） | `:algo:testDebugUnitTest` 通过 |
| **4** | `FaceIDResult` 增加 `eyeOpenRatio`/`mouthOpenRatio` 字段，`FaceIDAlgorithmImpl` 透传 `eyeEst` 对应值 | `:algo:test` 通过 |
| **5** | 新增 `FatigueEngineTest`：用户给定规则全量用例（各等级各条件/覆盖/直接退出/窗口计数/开关/无人脸复位） | `:algo:testDebugUnitTest` 通过 |
| **6** | `app` 新增 `assets/fatigue_rules.json`（§3.2 用户规则） | 资源打包正确 |
| **7** | `FatigueActivity` 接入：加载规则 → `FatigueStateMachine`，删硬编码阈值，按 `FatigueOutput.level` 分级展示 | `:app:assembleDebug` + 功能回归 |
| **8** | **渲染落地**（§4.3）：新增 `activity_fatigue.xml` 布局（状态指示灯 + 闭眼/哈欠描述 + 诊断统计区），指示灯按等级变色，文本不跳变 | `:app:assembleDebug` + 真机目视 |
| **9** | 三级优先级（外部文件→assets→默认）落地；文档同步设计方案 | 真机验证改配置生效 |

---

## 6. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| **规则 JSON 被误改/损坏** | 疲劳判定异常 | `optXxx(默认值)` 容错 + 解析失败回退代码默认；`enabled=false` 安全降级 |
| **条件类型解析错误/未知 type** | 条件失效 | loader 对未知 `type` 跳过并记日志；解析失败回退默认规则 |
| **窗口计数内存膨胀** | 长时间运行内存增长 | 滑动窗口按时间淘汰过期事件；窗口上限保护 |
| **`FaceIDResult` 新增字段破坏消费方** | 编译回归 | `eyeOpenRatio`/`mouthOpenRatio` 带默认值（1f/0f），`FaceOverlayBridge`/渲染层不消费，兼容现有契约 |
| **单次闭眼/哈欠事件时长判定边界** | 计数偏差 | 事件结束时刻判定 duration 达标才入窗；边界用例单测覆盖（==min_duration 等） |
| **行为回归** | 迁移后判定与硬编码不一致 | 默认 JSON 值=用户给定规则；引擎单测覆盖用户规则全部条件/覆盖/退出分支 |
| **判定逻辑下沉改变 Activity 结构** | 重构风险 | 引擎逻辑与用户规则逐条对齐；UI 展示不变 |
| **外部配置文件路径/权限** | 读不到配置 | 三级优先级兜底；外部文件读不到自动回退 assets→默认 |
| **阈值类型（Long vs Int/Float）** | 解析错位 | `optLong`/`optDouble` 显式类型；schema_version 预留 |

---

## 7. 影响范围

- **新增**：`:algo/fatigue/`（`FatigueRule`、`FatigueRuleLoader`、`FatigueEventDetector`、`SlidingWindowCounter`、`FatigueStateMachine`）+ 测试；`app/src/main/assets/fatigue_rules.json`；`activity_fatigue.xml` 渲染布局（状态指示灯 + 闭眼/哈欠描述 + 诊断统计区）；
- **修改**：
  - `FaceIDResult` 增加 `eyeOpenRatio` / `mouthOpenRatio` 字段（透传眼/嘴连续开合度）；
  - `FaceIDAlgorithmImpl.processFrame` 透传 `eyeEst.eyeOpenRatio` / `eyeEst.mouthOpenRatio`；
  - `FatigueActivity`（删硬编码阈值、改消费状态机分级输出、按等级渲染指示灯/描述/诊断文本）；
- **依赖**：复用 `org.json`（Android 内置，无新依赖）；
- **文档**：设计方案同步疲劳规则配置化；README 说明 JSON 格式、条件类型、三级模型、三级来源与渲染页面。

---

## 8. 验收标准

1. 修改 `fatigue_rules.json` 任意阈值（次数/时长/窗口），**不重新编译**即改变疲劳判定行为；
2. **打哈欠判定**：嘴部开合度 ≥ `yawn.mouth_open_ratio`（0.3）且持续 ≥ `min_duration_ms`（2s）记一次哈欠事件；
3. **进入 OR 条件**：某级 `enter` 数组任一条件满足即进入该级（多信号源：闭眼计数/哈欠计数/连续闭眼时长）；
4. **三级覆盖**：重度/中度条件满足即覆盖低等级（升级式，无需先退低等级）；
5. **直接退出**：各等级 `exit`（20s 内无超 0.75s 闭眼/哈欠）满足即**直接回正常（NONE）**，不再逐级降；
6. **窗口计数**：60s/20s 滑动窗口内闭眼/哈欠次数统计正确（事件结束入窗、超窗自动过期）；
7. `fatigue_rules.json` 缺失/损坏时，回退默认规则正常，不崩溃；
8. 某等级/某信号 `enabled:false` 时对应判定关闭，其余正常；
9. `FatigueStateMachine` 在 `:algo` 内纯 JVM 单测通过，覆盖各级进入（OR 各条件）/覆盖/直接退出/窗口计数/无人脸复位/开关；
10. **状态指示灯**按等级变色：正常绿 / 轻度黄 / 中度橙 / 重度红（§4.3.2）；
11. **闭眼/哈欠状态描述**实时显示开合度与持续时长，**不跳变**（仅随状态变化刷新，不闪烁）；
12. **诊断统计区**显示当前等级 + 命中的进入条件 + 窗口计数（60s/20s 闭眼、哈欠）+ 连续时长，稳定展示，能观察到"当前判断条件对结果的影响"（§4.3.4）；
13. 疲劳 UI 按等级展示，与配置等级一致；修改 JSON 阈值后真机可立即看到等级判定与诊断统计相应变化。
