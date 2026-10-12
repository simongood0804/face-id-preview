# 提案：基于头位置与头姿法向量的视线落点区域判定

> 提案编号：FACEP-016  
> 创建日期：2026-08-26  
> 状态：待审核（方案 v2：三维几何投影，替代 v1 的角度锥方案）

---

## 1. 背景与动机

当前项目的分心判定（`DistractionActivity` / `DistractionStateMachine`）**完全依赖算法 SDK 已经算好的单帧标志 `gazeDistracted`**：

```
SignalDispatcher.distractionExtractor = { r -> AlgoDistractionInput(r.faceId.isNotEmpty(), r.gazeDistracted) }
```

即：业务层拿到的只有"算法是否认为分心"这一个布尔/单帧值，再叠加时间防抖 + 车速分档。存在局限：

1. **黑盒不可控**：zone 命中、专注判断逻辑都在算法 SDK 内部，业务层无法按自己的规则（如自定义区域形状/范围/阈值）调整。
2. **算法返回了丰富的几何量但被闲置**：AAR `FaceResult` 已提供 `headHwT`（头部世界坐标位置）和 `headDir`（头姿朝向法向量），当前业务逻辑未消费。

> 诉求：**在业务层用算法返回的头部位置 `headHwT` + 头姿法向量 `headDir`，自己做"视线落点"计算与"专注/分心区域"判定**，获得可配置、可控、可调优的专注判断能力。
>
> **方案采用算法方标准（v2）**：在世界坐标系中，以 **xz 平面为基准**，将头姿法向量射线与 xz 平面的交点作为**视线落点**，在 xz 平面上设置区域判断落点归属。

---

## 2. 方案原理（算法方标准）

### 2.1 输入：算法返回的两个几何量

| 字段 | 值示例 | 含义 |
|------|--------|------|
| `headHwT` | `(322.4, -574.5, 1207.5)` mm | **头部在世界坐标系的 3D 位置**（世界系 W，F→W 平移） |
| `headDir` | `(-0.0901, 0.9934, -0.0710)` | **头姿朝向的单位法向量**（世界系，Hopenet 头前向） |
| `headDirValid` | `1.0` | 上述量是否有效（需 `cam_transform_enabled`） |
| `flags` | 含 `HEADFRAME` | 有效性门控位 |

> 世界系约定（由算法方示例反推验证）：`X`=右、`Y`=前、`Z`=上（右手系）。头前向法向量默认指向 `+Y`（前），`yaw` 为绕 Y 偏转、`pitch` 为绕 X 俯仰。

### 2.2 视线落点：射线与 xz 平面求交

头部位置 `P = (Px, Py, Pz)`，头姿法向量 `d = (dx, dy, dz)`（单位向量）。

**头朝向射线**：`R(λ) = P + λ·d`，`λ ≥ 0`

**xz 平面**（即 `y = 0` 平面）交点，令 `R_y = 0`：

```
λ = -Py / dy
落点 x = Px + λ·dx
落点 z = Pz + λ·dz
```

**用算法方示例验证**（`P=(322.4, -574.5, 1207.5)`，`d=(-0.0901, 0.9934, -0.0710)`）：

```
λ = -(-574.5) / 0.9934 ≈ 578.3
x = 322.4 + 578.3×(-0.0901) ≈ 270.3
z = 1207.5 + 578.3×(-0.0710) ≈ 1166.4
落点 = (x≈270.3, z≈1166.4)   // 在 xz 平面 (y=0) 上
```

> 结果落点是二维平面上的点 `(x, z)`（世界系，mm 单位）。该点即"驾驶员头朝向"打到的基准平面位置，近似其注意力/视线落点。

### 2.3 区域判定：xz 平面上设置区域

在 **xz 平面（(x,z) 二维平面）**上设置若干**区域（region）**，每个区域定义其几何范围（如矩形：`x∈[x_min,x_max]`、`z∈[z_min,z_max]`），判断落点 `(x, z)` 落在哪个区域：

```
落点 (x, z) 在区域内 ⟺  x_min ≤ x ≤ x_max  且  z_min ≤ z ≤ z_max
```

每个区域属性：
- `id` / `name`：区域标识（如 forward / addw_drv_left_knee / ...）
- `rect`：xz 平面上的矩形范围（`x_min, x_max, z_min, z_max`，mm）
- `is_distraction`：该区域是否分心区

> 例：`forward`（正前方专注区）是 xz 平面中央的一个矩形；`addw_drv_left_knee`（左膝）是左下方的矩形。

### 2.4 与 v1（角度锥）的区别

| | v1（已废弃） | v2（本方案） |
|---|---|---|
| 输入 | `headDirYaw/Pitch` 角度 | `headHwT` 位置 + `headDir` 法向量 |
| 判定 | 角度锥形距离 | **射线与 xz 平面求交**，平面区域判定 |
| 区域 | 角度锥（yaw,pitch 中心+半径） | **xz 平面矩形**（(x,z) mm 坐标） |
| 几何 | 纯角度比较 | **三维投影 + 平面判定** |

---

## 3. 区域配置（可配置区域）

### 3.1 配置格式

复用/新增一份 JSON 配置文件，定义 xz 平面上的区域（建议独立于 `dms_calibration.json`，或在其 `zones` 扩展，见 §6）：

```json
{
  "schema_version": 1,
  "regions": [
    { "id": 0, "name": "forward",
      "points": [
        { "x": -300.0, "z": 900.0 },
        { "x":  300.0, "z": 900.0 },
        { "x":  300.0, "z": 1300.0 },
        { "x": -300.0, "z": 1300.0 }
      ],
      "is_distraction": false },
    { "id": 1, "name": "addw_drv_left_knee",
      "points": [
        { "x": -500.0, "z": -100.0 },
        { "x": -200.0, "z": -100.0 },
        { "x": -200.0, "z": 300.0 },
        { "x": -500.0, "z": 300.0 }
      ],
      "is_distraction": true },
    ...  // 共 15 个（1 个 forward + 14 个 ADDW 分心区）
  ]
}
```

- **`points`**：xz 平面上的**四边形 4 个顶点** `(x, z)`（mm，按逆时针/顺时针依次给出）。区域由这 4 点围成的四边形定义，支持任意四边形（不必是轴对齐矩形）。
- **`is_distraction`**：是否分心区。

### 3.2 区域数据模型 `RegionConfig`

```kotlin
/** 可配置的 xz 平面四边形区域（算法方标准：4 点绘制四边形）。 */
data class RegionConfig(
    val id: Int,
    val name: String,
    /** 四边形 4 个顶点 (x, z)（mm，按顺序围成四边形）。 */
    val points: List<Point2D>,
    val isDistraction: Boolean
) {
    /** 判断落点 (x,z) 是否落在四边形内（射线法 point-in-polygon）。 */
    fun contains(x: Float, z: Float): Boolean {
        var inside = false
        val n = points.size
        var j = n - 1
        for (i in 0 until n) {
            val pi = points[i]
            val pj = points[j]
            if ((pi.z > z) != (pj.z > z) &&
                x < (pj.x - pi.x) * (z - pi.z) / (pj.z - pi.z) + pi.x) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}

/** xz 平面上的一个点（mm）。 */
data class Point2D(val x: Float, val z: Float)
```

> 落点判定用**射线法（crossing number / even-odd rule）**判断点是否在四边形内，支持任意凸/凹四边形。

### 3.3 区域配置加载器 `RegionConfigLoader`

参照项目现有 `FatigueRuleLoader`（FACEP-015 org.json 容错模式）：

```kotlin
object RegionConfigLoader {
    fun load(jsonText: String?): List<RegionConfig>   // 容错，损坏回退默认
    fun loadFromFile(file: File): List<RegionConfig>
    fun loadFromAssets(context: Context): List<RegionConfig>
}
```

- **容错**：字段缺失 / 类型错误 / JSON 损坏 → 回退 `DEFAULT_REGIONS`，不崩溃，记日志。
- **解析规则**：每个 region 的 `points` 必须 ≥3 个点（本项目约定 4 点四边形）；`points` 缺失/不足 → 跳过该 region。
- **默认区域**：内置 15 个区域（与算法方区域一致，四边形坐标需算法方提供，见 §6）。

---

## 4. 判定引擎 `GazeFallpointDetector`

纯 JVM 类（无 Android 依赖，便于单测）：

```kotlin
data class FallpointPrediction(
    val regionId: Int,          // -1 = 无区域命中（transition）
    val isDistracted: Boolean   // 是否落在分心区
)

class GazeFallpointDetector(
    val regions: List<RegionConfig>
) {
    /**
     * 由头位置 + 头姿法向量求 xz 平面落点，并判定所属区域。
     * @param headPos  头部世界坐标 (Px, Py, Pz) mm（headHwT）
     * @param dir      头姿单位法向量 (dx, dy, dz)（headDir）
     * @param valid    headDirValid==1 且 flags&HEADFRAME
     */
    fun update(headPos: FloatArray, dir: FloatArray, valid: Boolean): FallpointPrediction {
        if (!valid || headPos.size < 3 || dir.size < 3) {
            return FallpointPrediction(regionId = -1, isDistracted = false)
        }
        val px = headPos[0]; val py = headPos[1]; val pz = headPos[2]
        val dx = dir[0];    val dy = dir[1];    val dz = dir[2]
        if (dy == 0f) return FallpointPrediction(-1, false)   // 与平面平行，无交点

        // 射线 R(λ)=P+λ·d 与 y=0 (xz 平面) 交点
        val lambda = -py / dy
        if (lambda < 0f) return FallpointPrediction(-1, false) // 落点在身后（朝反方向）
        val fx = px + lambda * dx
        val fz = pz + lambda * dz

        // 区域判定（取命中且分心权重最高者；简单实现取第一个命中）
        val hit = regions.firstOrNull { it.contains(fx, fz) }
        return if (hit != null) {
            FallpointPrediction(hit.id, hit.isDistraction)
        } else {
            FallpointPrediction(-1, false)
        }
    }
}
```

**核心流程**：
1. **有效性门控**：`headDirValid==1` 且 `flags & HEADFRAME`；无效返回 `regionId=-1`。
2. **射线求交**：`λ = -Py/dy`，落点 `(fx, fz)`。`dy==0`（平行）或 `λ<0`（落点向后）→ 无命中。
3. **区域判定**：`(fx, fz)` 命中哪个 `RegionConfig`（四边形内判定，射线法 point-in-polygon）。
4. **分心标记**：命中区域的 `isDistraction`。

> 后续可加 softmax/EMA/滞回抑制落点抖动（可选，见 §7）。

---

## 5. 与现有 `DistractionStateMachine` 对接 + 数据源切换

### 5.1 数据源切换开关（仅分心监测模块）

新增**手动切换开关** `DistractionSource`，决定 `DistractionStateMachine`（**分心监测模块**）的分心输入用**算法返回结果**还是**自研判断结果**：

```kotlin
/** 分心数据源：算法 SDK 返回 vs 业务层自研。作用域：分心监测模块。 */
enum class DistractionSource {
    SDK,    // 用算法返回的 result.gazeDistracted（默认，现状黑盒）
    SELF    // 用自研 GazeFallpointDetector 算出的落点区域分心
}
```

- **作用域**：仅**分心监测模块**（`DistractionStateMachine` / `DistractionActivity`）用此开关；疲劳、识别等模块不受影响。
- **默认值**：`SDK`（用算法返回结果）。
- **切换方式**：
  1. **运行时手动切换**：`SignalDispatcher.setDistractionSource(src)`，可在 UI 调试面板/日志开关手动切。
  2. 切换**即时生效**（每帧读取开关），无状态迁移成本（两个源都只产出 `gazeDistracted` 喂给同一状态机）。
- **持久化**：切换状态**持久化保存**，重启后保留（见 §5.2）。

### 5.2 切换逻辑（含持久化）

```kotlin
// SignalDispatcher 侧：新增 GazeFallpointDetector + 数据源开关
val fallpointDetector = GazeFallpointDetector(loadRegionsFromJson(...))

// 数据源开关：默认 SDK，运行时切换 + 持久化
@Volatile
var distractionSource: DistractionSource =
    loadPersistedSource() ?: DistractionSource.SDK   // 启动时读持久化值，默认 SDK

fun setDistractionSource(src: DistractionSource) {
    distractionSource = src
    persistSource(src)   // 持久化，重启后保留
}

// 每帧：
val valid = (result.flags and FaceFlag.HEADFRAME) != 0 && result.headDirValid >= 1.0f
val pred = fallpointDetector.update(result.headHwT, result.headDir, valid)

// 按开关选择分心输入
val distracted = when (distractionSource) {
    DistractionSource.SDK  -> result.gazeDistracted > 0f                       // 算法返回（默认）
    DistractionSource.SELF -> valid && pred.isDistracted                        // 自研落点区域
}

AlgoDistractionInput(
    hasFace = result.faceId.isNotEmpty(),        // 有人脸
    gazeDistracted = if (distracted) 1f else 0f
)
```

- **持久化载体**：建议用应用 `SharedPreferences`（key 如 `distraction_source`）保存枚举；`loadPersistedSource()` 启动读取，`persistSource()` 切换时写入。
- 时间防抖（`TRIGGER_MS_FAST=1.5s / TRIGGER_MS_SLOW=3s`）、车速分档、`CLEAR_MS`、`NO_FACE_RESET_MS` 逻辑**全部复用**，不改。

### 5.3 渲染对照

- `FaceOverlayView` zone 面板：`SELF` 模式下高亮自研判定的区域；`SDK` 模式仍用 `result.zoneId`（SDK 判的），便于**对照两套结果的差异**。
- 切换时可在渲染层标注当前数据源（如角标 `SRC:SDK/SELF`）。

---

## 6. 待确认事项（实施前必须澄清）

1. **xz 平面位置**：算法方说"以 xz 平面作为基准"，需确认 xz 平面是 `y=0`（过世界原点）还是某一常数平面（如 `y=c`）。若是 `y=c`，交点公式 `λ = (c - Py)/dy`。**本提案暂按 `y=0` 写**，需算法方确认。
2. **世界系原点**：`headHwT` 和落点 `(x,z)` 都是世界系坐标。**世界系原点在哪**（车辆某参考点）决定区域坐标，需算法方提供。
3. **区域坐标数据**：xz 平面上的 15 个区域（forward + 14 ADDW）的**四边形 4 个顶点 `(x,z)`** 需算法方提供（或从驾驶舱几何标定）。本提案 §3 的示例值为示意，非真实。
4. **四边形顶点顺序/方向**：`points` 4 点需按**逆时针（或统一顺时针）**依次给出，避免自交导致射线法判定异常；需与算法方确认方向约定。
5. **落点与视线的关系**：本方案用**头姿朝向**（headDir）而非瞳孔视线。算法方确认用 headDir 法向量即可（作为注意力方向近似）。
6. **法向量有效性**：`headDir` 需底层 `cam_transform_enabled` 开启才有效（`headDirValid==1`）。`DistractionActivity` 初始化需确保已开启（现有 `applyCameraCalibration` 已配置）。

---

## 7. 可选增强（后续）

- **落点抖动抑制**：若落点在区域边界抖动，可加时间滞回/EMA（参照 faceid_sdk `PredictZone` 的 `hold_ms`/`ema`）。
- **区域支持任意多边形**：`contains` 的射线法天然支持任意 N 点多边形，四边形是特例，可按需扩展更多顶点。

---

## 8. 验证方案

1. **几何单测**（algo 模块，纯 JVM）：
   - 用算法方示例数据（`P=(322.4,-574.5,1207.5)`、`d=(-0.0901,0.9934,-0.0710)`）验证落点 `(270.3, 1166.4)` 计算正确。
   - 落点命中 forward / 分心区 / 无命中（`regionId=-1`）各场景。
   - `headDirValid=0` 或 `flags` 无 HEADFRAME → 无效返回。
   - `dy==0`（平行）→ 无命中。
2. **区域配置单测**：`RegionConfigLoader` 解析正确 / 损坏回退默认。
3. **实车对照**：同一帧，自研落点区域与 SDK `FaceResult.zoneId` 对比，验证区域坐标正确性。

### 8.4 每帧处理耗时分析

**本方案新增部分（`GazeFallpointDetector`）每帧计算量**：

| 步骤 | 操作 | 计算量 |
|------|------|--------|
| 有效性检查 | `headDirValid`/`flags` 判断 | 常数 |
| 射线求交 | `λ=-Py/dy`、`fx`、`fz` | ~3 次浮点乘加 |
| 区域判定 | 遍历 **15 区域** × 每区域**射线法 4 点** | ~60 次浮点比较/乘除 |
| 数据源切换 | 枚举 `when` 判断 | 常数 |
| 状态机更新 | 时间戳比较 | 常数 |

总量约**几十~上百次浮点运算 + 一次轻量对象分配**（`FallpointPrediction`）。纯算术、无 IO、无 native 调用、无锁，估算耗时**微秒级（约 1~10 μs）**。

**与算法 SDK 每帧耗时对比**：

- 算法 SDK 帧处理（人脸检测 + 68 地标 + HEADPOSE + GAZE，分心模块 flag）：**约 10~30 ms**（全开约 ~67ms，见 `设计方案.md`；分心不含 RECOG，更少）。
- 自研判定（本方案）：**约 1~10 μs**，占比 **< 0.1%**，相对算法帧处理可忽略。

**结论**：当前方案的帧处理瓶颈仍是**算法 SDK 推理**，业务层新增的几何判定不会成为瓶颈。区域数量固定（15）且随帧数不增长，复杂度稳定 O(15×4)。仅当区域数大幅增加（>100）或需更严格实时性时才考虑空间索引等优化，当前无需。

---

## 9. 实施范围

| 项 | 说明 |
|----|------|
| ✅ 已完成 | `FaceIDResult` 透传 `headHwT`/`headDir`/`headDirYaw`/`headDirPitch`/`headDirValid`（`FaceIDAlgorithmImpl` 已透传） |
| 新增 ① | `algo` 模块 `RegionConfig` + `Point2D`（xz 平面**四边形**区域数据模型 + 射线法 `contains`） |
| 新增 ② | `algo` 模块 `RegionConfigLoader`（JSON 容错解析 4 点四边形，回退默认区域） |
| 新增 ③ | `algo` 模块 `GazeFallpointDetector`（射线-xz 平面求交 + 四边形区域判定） |
| 新增 ④ | `DistractionSource` 枚举 + 切换开关（默认 `SDK`），`SignalDispatcher` 按开关选源，切换状态用 `SharedPreferences` **持久化** |
| 修改 | `SignalDispatcher.distractionExtractor` 输入切换为"按 `DistractionSource` 选源"（作用域：分心监测模块） |
| 复用 | `DistractionStateMachine`（时间防抖/车速分档）、`FaceOverlayView` zone 面板 |
| 待确认 | §6：xz 平面位置、世界系原点、15 个区域的**四边形 4 点坐标**、顶点顺序（需算法方提供） |
| 不改 | 算法 SDK、`FaceIDAlgorithmImpl` 透传、`FaceIDResult` 已有字段 |
