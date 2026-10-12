# 提案：基于 106 点位的眼睛睁闭与嘴巴开合状态判定

> 提案编号：FACEP-010  
> 创建日期：2026-08-18  
> 状态：阶段一 ~ 五已实现；阶段六（阈值动态校准，结合驾驶门开关信号）设计完成，待实施

> **更新（2026-08-24）**：AAR 算法升级，地标模型由 `2d106det_int8.dlc`（106 点）切换为 `pipnet68_int8.dlc`（**PIPNet 68 点 / 300W 标准**）。`LandmarkIndexMapping` 默认映射已重写为 68 点（见 **§2.5，当前生效**）；原 §2.4 为 v3.2 及以前的 106 点历史定义。其余判定逻辑（语义区域 + 索引映射解耦）不变。
>
> **更新（2026-08-24，随设计方案 v3.4 落地）**：眼睛开合度由经典 EAR（分母=单眼角距）改为**脸宽归一化 aperture（睑距/双眼外眼角距离，脸宽代理）**，消除"近处睁眼、远处闭眼"的距离漂移；归一化基准 0.10（睁眼）/ 0.02（闭眼残差）。闭眼判定阈值调整：静态/默认 `CLOSE_RATIO` 演进 `0.18 → 0.12 → 0.10 → 0.08 → **0.10**`（0.08 时实车反馈过难触发，回退放宽）、动态下界因子 0.35 → **0.10**；睁眼候选默认阈值放宽 `0.35 → **0.30**`（退出闭眼更容易）、确认时长、嘴巴阈值不变。**退出方向改为不对称防抖**：状态机闭眼/张嘴确认后，打开候选满足即即时解除（不再要求连续确认）；疲劳告警退出用短清除阈值（500ms，见设计方案 §18.6）。详见 §3.5/§3.7.2/§3.7.6 与设计方案 §18.5/§18.6。
>
> **更新（2026-08-24，随设计方案 v3.5 落地，§3.7.6 修订）**：校准器改为跟踪**未归一化 aperture/MAR（真实几何量）**——`update(eyeAperture, mouthMar)` 不再接收 0~1 ratio，阈值在原始量纲计算；新增 `normalizeEye/normalizeMouth`，用该驾驶员**实测高/低基准**（分位数 + EWMA）做归一化端点，完全睁眼恒映射 ≈1.0，取代静态 0.10/0.02 端点错配导致的饱和失真（修复"睁大眼睛仍判闭眼"）；滞回阈值经同一基准归一化后等价于因子位置（上界 0.70 / 下界 0.10），并增加**单态保护**（持续闭眼/闭嘴时保留睁开记忆，避免区间塌缩把残差"正常化"）。同日修复两类隐患：**数据真实性防线**（`valid`/`faceWidth>0`/`mar>0` 分轴喂校准，缺失 0 哨兵与 NaN 一律忽略，防 EAR 回退帧量纲混入）；**阈值语义统一**（眼睛阈值恒为因子位置、校准激活无 0.30→0.70 跳变；嘴巴阈值固定 0.60/0.35 不随动态校准漂移，`CalibratedThresholds` 全部字段诚实生效）。详见设计方案 §18.7。

---

## 1. 背景与动机

当前算法链路（`algorithm/`，基于 insightFace / face-sdk-v1.1.4 AAR）每帧已通过 `2d106det_int8.dlc` 输出 **106 个面部密集地标**（`FaceResult.landmarks[106][2]`，原图坐标），并通过 `IFaceIDAlgorithm.FaceIDResult.landmarks: List<PointF>?`（106 个点）透出到渲染层（`FaceOverlayView` 已绘制黄色 106 点）。

但算法目前**只输出了点位，没有做任何语义判定**——即"眼睛是睁开还是闭上、嘴巴是张开还是闭上"这些基础状态，算法尚未输出。

> **本提案范围（聚焦基础状态，先确保正确性）**：在算法模块内部，基于 106 点中**眼睛、嘴巴区域点位**增加一步推理，**先只输出"睁眼 / 闭眼 / 张嘴 / 闭嘴"四个基础状态**（及可选开合度），作为 `FaceIDResult` 新增字段透出。
>
> **暂不涉及**（后续另行扩展）：疲劳判定（闭眼持续时长）、打哈欠/说话检测等**上层业务判定**。本提案把基础状态本身判定正确作为第一目标——先保证每个状态输出稳定、不闪变、无系统偏差，后续业务才在其上叠加。

> 业务动机（供后续参考）：眼睛睁闭是疲劳检测的核心输入，嘴巴开合是打哈欠/说话检测的辅助输入；但**本提案暂不实现这些业务**。

---

## 2. 现状分析

### 2.1 数据来源

| 模型 | 输入 | 输出 | 说明 |
|------|------|------|------|
| `2d106det_int8.dlc` | 192x192 RGB（bbox 中心+1.5x 缩放仿射对齐） | 212 floats（106×2） | 每张人脸输出 106 个关键点，原图坐标 |

### 2.2 现有数据流

```
FaceSDK.infer()
  → FaceResult.landmarks[106][2]（原图坐标，含 cropOffset 修正）
  → FaceIDResult.landmarks: List<PointF>?（106 点）
  → FaceOverlayView（黄色小点绘制 + 视线/头姿绘制复用其中索引）
```

### 2.3 既有索引线索

- **5 关键点（keypoints）**（det 模型自带，非 106 点）：`0=左眼, 1=右眼, 2=鼻尖, 3=左嘴角, 4=右嘴角`，独立字段 `keypoints`。
  - **其中 `keypoints[0]`、`keypoints[1]` 即左右眼瞳孔位置**，由 det 模型直接回归，**比 106 点的瞳孔索引更准确**。
- **瞳孔（106 点）**：`FaceOverlayView.drawGaze` 现有注释写"左瞳 index 88、右瞳 index 89"。

> ✅ **本提案明确：若需使用瞳孔，一律用 5 关键点（`keypoints[0]` 左眼、`keypoints[1]` 右眼）**，不使用 106 点瞳孔索引，从而规避 106 点瞳孔索引定义不一致的隐患。106 点的 `38`/`88` 等瞳孔索引不作为判定依据。

### 2.4（历史）106 点位索引定义（v3.2 及以前，已切换 68 点）

> **镜像说明**：以下"左/右"以**观察者视角**描述（用户看到的屏幕左/右），**不**代表人脸生理学左右。观察者左眼 ≈ 人脸右眼，反之亦然。镜像不影响判定逻辑。

#### 眼睛

| 区域 | 点位索引（观察者视角） | 眼尾 / 眼角 | 上眼睑 | 下眼睑 |
|------|------------------------|-------------|--------|--------|
| 左眼（**逆时针**） | `35（眼尾）→ 36 → 33 → 37 → 39（眼角）→ 42 → 40 → 41` | 眼尾`35`、眼角`39` | `42, 40, 41` | `36, 33, 37` |
| 左瞳孔 | `38` | — | — | — |
| 左睛明穴 | `75` | 左眼内眼角（睛明穴） | — | — |
| 右眼（**顺时针**） | `93（眼尾）→ 91 → 87 → 90 → 89（眼角）→ 95 → 94 → 96` | 眼尾`93`、眼角`89` | `95, 94, 96` | `91, 87, 90` |
| 右瞳孔 | `88` | — | — | — |
| 右睛明穴 | `81` | 右眼内眼角（睛明穴） | — | — |

> ⚠️ 表中瞳孔 `38`/`88` 仅为记录 106 点定义，**本提案不使用它们做判定**。瞳孔一律采用 5 关键点（`keypoints[0]` 左眼、`keypoints[1]` 右眼），见 §2.3。

**上下眼睑逐点对应关系（同一"列"，用于开合度计算）：**
- 左眼：`42↔36`、`40↔33`、`41↔37`（上↔下）
- 右眼：`95↔91`、`94↔87`、`96↔90`（上↔下）

> **推导依据（务必理解，避免编码时想当然）**：
> - 观察者左眼眼尾在**屏幕左侧**，按**逆时针**从眼尾出发：左→下→右→上，即**先经下眼睑（36/33/37）→ 眼角 39 → 上眼睑（42/40/41）**。
> - 观察者右眼眼尾在**屏幕右侧**，按**顺时针**从眼尾出发：右→下→左→上，同样**先经下眼睑（91/87/90）→ 眼角 89 → 上眼睑（95/94/96）**。
> - 两眼的眼尾都在各自外侧，从眼尾绕行都先走下方弧线，因此**上眼睑都在点序的后半段**。

#### 嘴巴（分内边缘 / 外边缘，均从人中起，顺时针一圈）

| 区域 | 点位索引 |
|------|---------|
| 外边缘（12 点） | `71, 67, 68, 61, 58, 59, 53, 56, 55, 52, 64, 63` |
| 内边缘（8 点） | `62, 70, 69, 57, 60, 54, 65, 66` |

> 语义（外边缘）：`71`=人中（上唇中央）、`53`=下唇中央、`61`/`52`=左右嘴角；上唇 `71/67/68/63/64`、下唇 `58/59/53/56/55`。

### 2.5（v3.3 更新）68 点位索引定义（当前生效）

> **2026-08-24**：AAR 地标模型切换为 `pipnet68_int8.dlc`（PIPNet 68 点 / 300W 标准，见 FACEP-013 / 设计方案 §18.4）。`LandmarkIndexMapping.default68Mapping()` 即为以下定义。
>
> 镜像说明同 §2.4：以下"左/右"以**观察者视角**描述。观察者左眼 ≈ 300W 定义中的 right eye（36~41），观察者右眼 ≈ 300W 定义中的 left eye（42~47）。

#### 眼睛

| 区域 | 点位索引（观察者视角） | 眼尾 / 眼角 | 上眼睑 | 下眼睑 |
|------|------------------------|-------------|--------|--------|
| 左眼（300W right eye 36~41） | `36（眼尾）→ 37 → 38 → 39（眼角）→ 40 → 41` | 眼尾`36`、眼角`39` | `37, 38` | `41, 40` |
| 右眼（300W left eye 42~47） | `42（眼角）→ 43 → 44 → 45（眼尾）→ 46 → 47` | 眼尾`45`、眼角`42` | `43, 44` | `47, 46` |

> 68 点（PIPNet/300W）**不含瞳孔点**；瞳孔/眼中心一律使用 5 关键点（`keypoints[0]` 左眼、`keypoints[1]` 右眼），见 §2.3。

**上下眼睑逐点对应关系（同一"列"，用于开合度计算）：**
- 左眼：`37↔41`、`38↔40`（上↔下，由外到内）
- 右眼：`43↔47`、`44↔46`（上↔下，由内到外）

#### 嘴巴（外边缘）

| 区域 | 点位索引 |
|------|----------|
| 左嘴角 | `48` |
| 右嘴角 | `54` |
| 上唇中央（人中） | `51` |
| 下唇中央 | `57` |

> 68 点下 `MOUTH_UPPER_LIP=[51]`、`MOUTH_LOWER_LIP=[57]`：`upper[0]`=上唇中央（人中）、`lower[0]`=下唇中央，与 §3.6 MAR 公式兼容。
>
> **注意**：68 点眼睛上/下睑各 2 列（106 点为 3 列），垂直均值由 3 列变 2 列，几何量数值略有差异。v3.4 起眼睛开合度已改为**脸宽归一化 aperture（睑距/双眼外眼角距离）**，默认归一化基准（睁眼 0.10 / 闭眼残差 0.02），设备端不准时经 `EyeMouthCalibrator` 动态校准（见 §3.5）。

---

## 3. 方案设计

### 3.1 核心原则：语义区域 + 索引映射，避免写死索引

> 用户明确要求：**点位需要做映射，不要考虑后续可能更换算法**，用映射方式避免后续修改麻烦。

即：判定逻辑**只依赖"语义区域"（如"左眼上眼睑""下唇中央"），不直接出现 `35/39` 这类模型索引**。模型索引通过一张**可配置的映射表**（`LandmarkIndexMapping`）与语义区域解耦。后续若更换算法/点位定义（如换 106 点、5 点，或更换模型），**只需改这张映射表，判定逻辑与渲染层零改动**。

```
判定逻辑 ──只依赖──▶ 语义区域（enum，稳定）
                        │
                        │ 映射表 LandmarkIndexMapping（可配置，唯一接触模型索引的地方）
                        ▼
              当前模型的 68 点索引（v3.3 起，见 §2.5）
```

### 3.2 语义区域定义（enum，稳定不变）

```kotlin
/** 语义区域：判定逻辑只依赖这些稳定概念，不依赖具体模型索引。 */
enum class LandmarkRegion {
    // 左眼（观察者视角）
    LEFT_EYE_UPPER_LID,      // 左眼上眼睑点集
    LEFT_EYE_LOWER_LID,      // 左眼下眼睑点集
    LEFT_EYE_OUTER_CANTHUS,  // 左眼眼尾
    LEFT_EYE_INNER_CANTHUS,  // 左眼眼角
    // 右眼（观察者视角）
    RIGHT_EYE_UPPER_LID,
    RIGHT_EYE_LOWER_LID,
    RIGHT_EYE_OUTER_CANTHUS, // 右眼眼尾
    RIGHT_EYE_INNER_CANTHUS, // 右眼眼角
    // 嘴巴
    MOUTH_UPPER_LIP,         // 上唇（外边缘）
    MOUTH_LOWER_LIP,         // 下唇（外边缘）
    MOUTH_LEFT_CORNER,       // 左嘴角
    MOUTH_RIGHT_CORNER;      // 右嘴角
}
```

### 3.3 映射表（唯一接触模型索引的模块，可配置）

```kotlin
/**
 * 106 点位 → 语义区域的映射。
 * 判定逻辑不直接引用任何具体索引，全部经由本映射。
 * 更换算法/点位定义时，仅需修改此映射。
 */
class LandmarkIndexMapping(
    /** 语义区域 → 该区域在 106 点中的索引数组（顺序与"上下眼睑对应列"一致）。 */
    private val regions: Map<LandmarkRegion, IntArray> = default106Mapping()
) {
    fun indices(region: LandmarkRegion): IntArray = regions[region] ?: IntArray(0)

    companion object {
        /** 基于 insightFace 2d106det 的默认 106 点映射（见 §2.4 已确认定义）。 */
        fun default106Mapping(): Map<LandmarkRegion, IntArray> = mapOf(
            // 左眼：上眼睑 42/40/41，下眼睑 36/33/37，眼尾35、眼角39
            LandmarkRegion.LEFT_EYE_UPPER_LID   to intArrayOf(42, 40, 41),
            LandmarkRegion.LEFT_EYE_LOWER_LID   to intArrayOf(36, 33, 37),
            LandmarkRegion.LEFT_EYE_OUTER_CANTHUS to intArrayOf(35),
            LandmarkRegion.LEFT_EYE_INNER_CANTHUS to intArrayOf(39),
            // 右眼：上眼睑 95/94/96，下眼睑 91/87/90，眼尾93、眼角89
            LandmarkRegion.RIGHT_EYE_UPPER_LID  to intArrayOf(95, 94, 96),
            LandmarkRegion.RIGHT_EYE_LOWER_LID  to intArrayOf(91, 87, 90),
            LandmarkRegion.RIGHT_EYE_OUTER_CANTHUS to intArrayOf(93),
            LandmarkRegion.RIGHT_EYE_INNER_CANTHUS to intArrayOf(89),
            // 嘴巴：上唇 71/67/68/63/64，下唇 58/59/53/56/55，嘴角 61/52
            LandmarkRegion.MOUTH_UPPER_LIP      to intArrayOf(71, 67, 68, 63, 64),
            LandmarkRegion.MOUTH_LOWER_LIP      to intArrayOf(58, 59, 53, 56, 55),
            LandmarkRegion.MOUTH_LEFT_CORNER    to intArrayOf(61),
            LandmarkRegion.MOUTH_RIGHT_CORNER   to intArrayOf(52)
        )
    }
}
```

> 映射表可配置性：既作默认实现，也可在 `EyeMouthStateEstimator` 构造时注入自定义映射（如换算法后传入新映射），判定逻辑无需改动。

### 3.4 状态定义与输出字段

在 `IFaceIDAlgorithm.FaceIDResult` 新增字段：

```kotlin
/** 眼睛是否睁开（true=睁眼，false=闭眼）。 */
val eyeOpen: Boolean = false,
/** 嘴巴是否张开（true=张嘴，false=闭嘴）。 */
val mouthOpen: Boolean = false,
```

> 为便于渲染层做渐进展示，可额外提供连续值：
> ```kotlin
> /** 眼睛开合度（0.0=完全闭眼 ~ 1.0=完全睁眼）。 */
> val eyeOpenRatio: Float = 1f,
> /** 嘴巴开合度（0.0=完全闭嘴 ~ 1.0=完全张嘴）。 */
> val mouthOpenRatio: Float = 0f,
> ```

**判定逻辑下沉 `algorithm/`（而非渲染层）**——理由：
- 判定属于"算法语义"，应下沉到 `algorithm/`，渲染层只消费结果；
- 算法进程可复用已有 106 点，渲染层无需重复计算；
- 后续如需换成更复杂的模型（如 `pfld_eye` 回归），对外字段不变。

### 3.5 眼睛睁闭判定（睑距/脸宽 aperture）

> **v3.4 修订**：由经典 EAR（分母=单眼角距）改为**脸宽归一化 aperture（分母=双眼外眼角距离，脸宽代理）**。经典 EAR 分母太短，远处人脸变小时地标绝对误差占比放大、EAR 系统性偏低，实测出现"近距离判睁眼、远距离判闭眼"；aperture 的分子（睑距）与分母（脸宽）随距离**同比例缩放**，比值与距离解耦，且分母远大于单眼角距、抗噪更好。

**几何判据（aperture）**：

1. 经映射取**左右眼的上下眼睑点集**与**双眼外眼角**（`LEFT_EYE_OUTER_CANTHUS` / `RIGHT_EYE_OUTER_CANTHUS`，观察者视角）；
2. 上下眼睑点**按对应列**计算纵向距离均值（见 §2.5 对应关系）；
3. 除以**双眼外眼角水平距离（脸宽代理）**，得到眼睛开合度：

```
aperture = avg( |上睑[i].y − 下睑[i].y| )  /  (|右外眼角.x − 左外眼角.x|)
```

- 睁眼时 aperture 接近正常值（约 0.10 量级）；
- 闭眼时 aperture 显著变小（趋近闭眼残差 0.02 左右）。

归一化与回退（`EyeMouthStateEstimator` 当前实现）：
1. `eyeOpenRatio = (aperture − closedEyeAperture) / (referenceEyeAperture − closedEyeAperture)`，截断到 0~1；默认 `referenceEyeAperture=0.10`、`closedEyeAperture=0.02`（构造参数，可校准）；
2. 同时输出诊断字段 `ear`（经典 EAR，保留）与 `faceWidth`（脸宽像素），供设备端 `[EYE-CAL]` 日志实测标定；
3. **外眼角缺失**（自定义映射未配外眼角）时自动**回退经典 EAR**，兼容旧行为；
4. 双眼 aperture 均值 ≤ 动态阈值（§3.7.2，当前默认 0.10）判为 **闭眼候选**，否则睁眼候选。

> 可选增强：项目已有 `pfld_eye_int8.dlc`（眼睑模型，见 `REQUIRED_MODEL_FILES`）。若纯几何 aperture 在强侧脸/遮挡下不稳，可在后续阶段切换为由 `pfld_eye` 回归输出开合度，几何法作为 fallback。

### 3.6 嘴巴开合判定（MAR）

**开合度（MAR，Mouth Aspect Ratio）**：

1. 经映射取**上下唇中央点**与**左右嘴角**；
2. 上下唇中央点的纵向距离 / 左右嘴角水平距离：

```
MAR = |上唇中央.y − 下唇中央.y|  /  (|左嘴角.x − 右嘴角.x|)
```

- 闭嘴时 MAR 趋近 0（上下唇贴合）；
- 张嘴时 MAR 增大。

建议实现：
1. 上唇中央取映射中 `MOUTH_UPPER_LIP` 首点（`71`=人中），下唇中央取 `MOUTH_LOWER_LIP` 中点（`53`）；
2. 计算 MAR；
3. `MAR > 阈值`（如 `0.3`）判为 **张嘴**，否则 **闭嘴**；
4. `mouthOpenRatio = MAR / 基准MAR`。

### 3.7 基础状态的稳定输出：防闪变处理（保证判定正确性）

> 即使只输出"睁眼/闭眼/张嘴/闭嘴"四个基础状态，单帧 aperture/MAR 的布尔判断（§3.5/§3.6）仍存在两个**直接影响正确性**的工程问题：
> 1. **闭眼时上下眼睑点位不会完全重合**——模型在定位时，闭眼状态下上睑点与下睑点仍有**最小残差距离**（不会都叠到 0），若只用"aperture < 阈值"布尔判断，阈值与残差边界抖动会产生**闪变（flicker）**，导致闭眼/睁眼状态在阈值附近来回跳；
> 2. **单帧判定不稳定**——光照/姿态/噪声导致单帧误判，输出的状态会肉眼可见地闪烁，影响渲染层展示。

> 因此引入轻量的 **`EyeMouthStateMachine` 状态防抖器**（对齐项目现有 `DistractionStateMachine` 的成熟模式：单调时钟按**持续时间**累计、确认/清除双阈值、时钟可注入便于测试）。它**不承担任何业务判定**（不做疲劳/哈欠），**只为保证四个基础状态的输出稳定正确**。

> 说明：本提案当前将触发/解除阈值设为"短暂确认"级（见 §3.7.3），目标是让状态**及时但稳定**地反映睁闭/开合；若后续要扩展为疲劳/哈欠业务，只需上调确认时长，无需改动本节结构。

#### 3.7.1 输入：连续开合度 + 人脸有效标志

状态机每帧接收：
```kotlin
data class EyeMouthFrameInput(
    val hasFace: Boolean,        // 本帧是否检测到人脸
    val eyeOpenRatio: Float,     // 本帧眼睛开合度 0~1（aperture 归一化）
    val mouthOpenRatio: Float    // 本帧嘴巴开合度 0~1（MAR 归一化）
)
```
- `hasFace=false` 时视为无效帧：重置累计，不产生误判。
- 开合度是**连续值**（§3.4 的 `eyeOpenRatio`/`mouthOpenRatio`），状态机在连续值上做时间累计，天然抗"闭眼残差"与单帧抖动。

#### 3.7.2 针对"闭眼不完全闭合"的处理

**方案 A（推荐）：双阈值 + 滞回（hysteresis）** —— 不使用单一布尔阈值，而是用**开/关两个阈值**消除残差边界抖动：

| 阈值 | 含义 |
|------|------|
| `CLOSE_RATIO` | 开合度 ≤ 此值（当前默认 `0.10`）判定进入"闭眼候选" |
| `OPEN_RATIO` | 开合度 ≥ 此值（当前默认 `0.30`）判定回到"睁眼候选" |
| （两者之间为**滞回区间**，状态保持不变） | 吸收残差抖动 |

即：
- 只有开合度**降到 `CLOSE_RATIO` 以下**才启动"闭眼累计"；
- 只有开合度**升到 `OPEN_RATIO` 以上**才清除闭眼状态；
- 在 `CLOSE_RATIO ~ OPEN_RATIO` 区间内（当前 0.10~0.30），**维持上一状态**，不会因残差在阈值附近跳动而闪变。

**方案 B（正式设计）：动态基准校准** —— 残差会随人头距摄像头远近、姿态而变化，静态阈值可能误判。**完整方案见 §3.7.6 `EyeMouthCalibrator`**：维护每个驾驶员的"睁/闭眼、张/闭嘴"基准（`REF_EYE_OPEN_APERTURE` 等），动态换算滞回阈值，并由**驾驶门开关信号**触发换人复位重校。核心思路：
```
CLOSE_RATIO = REF_CLOSED + (REF_OPEN - REF_CLOSED) * 0.10   // v3.4 收紧：下界因子 0.35 → 0.10
OPEN_RATIO  = REF_CLOSED + (REF_OPEN - REF_CLOSED) * 0.70
```
> 即阈值随基准整体平移/缩放，从而适配不同人、不同时段，避免"闭眼被判睁眼 / 此人总是闭眼"等误判。

#### 3.7.3 状态防抖：按持续时间确认

参考 `DistractionStateMachine`，防抖器维护 `eyeClosed` / `mouthOpen` 两个稳定状态，各自用**单调时钟 `clockMs` 累计持续时间**（不用帧数，因帧率可能跳帧）：

```
（眼睛为例）
1. hasFace=false → reset
2. 若 eyeOpenRatio 进入闭眼候选（≤ CLOSE_RATIO）：
     持续累计闭眼时长；累计 ≥ EYE_CLOSE_CONFIRM_MS → 确认 eyeClosed=true
3. 若回到睁眼候选（≥ OPEN_RATIO）或已确认后持续睁眼：
     累计睁眼时长；≥ EYE_OPEN_CONFIRM_MS → eyeClosed=false
4. 处于滞回区间 → 状态保持不变，计时重置（吸收抖动）
```

阈值建议（可配置，工程初值——**以"短确认、及时反馈"为准，非业务判定口径**）：

| 参数 | 初值 | 说明 |
|------|------|------|
| `EYE_CLOSE_CONFIRM_MS` | 80ms | 连续闭眼达 80ms 才确认"闭眼"（滤除单帧尖刺，保证状态稳定） |
| `EYE_OPEN_CONFIRM_MS` | 80ms | 连续睁眼达 80ms 才确认"睁眼" |
| `MOUTH_OPEN_CONFIRM_MS` | 80ms | 连续张嘴达 80ms 才确认"张嘴" |
| `MOUTH_CLOSE_CONFIRM_MS` | 80ms | 连续闭嘴达 80ms 才确认"闭嘴" |

> 参数设计说明：
> - 采用**对称的短确认时长**（如 80ms ≈ 2~3 帧），既滤除单帧尖峰/残差抖动，又能及时反映真实状态，**不掺入疲劳/哈欠等业务口径**；
> - 后续如需业务判定（疲劳/哈欠），只需在上层提高确认时长，本节结构不变。

> 状态机对外输出**稳定基础状态**（`eyeClosed`/`mouthOpen`），供 `FaceIDResult` 填充；瞬时 `eyeOpenRatio`/`mouthOpenRatio` 仍可作为附加字段供渲染层做渐变展示。

#### 3.7.4 为什么用"持续时间"而非"帧数"

项目现有 `DistractionStateMachine` 已明确：**单槽替换 + 跳帧导致帧率不稳定**，因此一律按**单调时钟（`SystemClock.elapsedRealtime`）累计 ms**，而非累计帧数。本提案沿用同一约定，并同样**注入 `clockMs`** 便于单元测试。

#### 3.7.5 新增组件

| 组件 | 职责 |
|------|------|
| `algorithm/EyeMouthStateMachine.kt`（新增） | 状态防抖器：输入每帧开合度 + hasFace，按持续时间确认，双阈值滞回，输出稳定的 `eyeClosed`/`mouthOpen` 基础状态。纯逻辑、时钟与阈值可注入，**不含任何业务判定** |
| `EyeMouthStateEstimator`（§3.5/3.6 的单帧几何计算） | 输出连续开合度 `eyeOpenRatio`/`mouthOpenRatio`，喂给防抖器 |
| `algorithm/EyeMouthCalibrator.kt`（新增，见 §3.7.6） | 阈值动态校准器：维护睁/闭眼、张/闭嘴基准，随运行自适应更新，并可被"驾驶门开关"信号触发复位重校 |

> 分层：`Estimator`（单帧几何）→ `Calibrator`（动态基准/阈值）→ `StateMachine`（防抖）→ `FaceIDResult`（基础状态）。

### 3.7.6 阈值动态校准（结合驾驶门开关信号）

> **为什么必须动态校准**：静态阈值（§3.7.2 的 `CLOSE_RATIO`/`OPEN_RATIO`）只对特定人脸有效。实际中：
> - **每人眼/嘴大小不同**——aperture/MAR 的绝对范围因人而异，同一阈值下 A 的全睁眼 aperture 可能小于 B 的全闭眼 aperture；
> - **距摄像头距离 / 姿态 / 光照变化**——同一人 aperture/MAR 也会整体漂移；
> - 若用固定阈值，会出现"某人总是睁眼 / 某人总是闭眼"的误判。
>
> 因此阈值必须是**每个驾驶员、每个行车时段自适应的动态基准**。

#### A. 校准的基准量（Calibrator 维护）

校准器（`EyeMouthCalibrator`）对眼睛、嘴巴各维护一组"开/闭"基准，用它们动态换算防抖阈值：

| 基准量 | 含义 | 来源 |
|--------|------|------|
| `REF_EYE_OPEN_APERTURE` | 该驾驶员"睁眼"时 aperture（睑距/脸宽）的典型值 | 校准窗口内睁眼帧中位数 |
| `REF_EYE_CLOSED_APERTURE` | 该驾驶员"闭眼"时 aperture 的典型值（残差） | 校准/运行中闭眼帧中位数（或按开放比例推导） |
| `REF_MOUTH_OPEN_MAR` | 该驾驶员"张嘴"时 MAR 的典型值 | 校准窗口内张嘴帧中位数 |
| `REF_MOUTH_CLOSED_MAR` | 该驾驶员"闭嘴"时 MAR 的典型值（残差） | 校准/运行中闭嘴帧中位数 |

基于基准量，防抖阈值动态换算（以眼睛为例，嘴巴同理）：
```
CLOSE_RATIO  = REF_EYE_CLOSED_APERTURE + (REF_EYE_OPEN_APERTURE - REF_EYE_CLOSED_APERTURE) * 0.10   // 滞回下界（v3.4：0.35 → 0.10）
OPEN_RATIO   = REF_EYE_CLOSED_APERTURE + (REF_EYE_OPEN_APERTURE - REF_EYE_CLOSED_APERTURE) * 0.70   // 滞回上界
```
即阈值随基准量整体平移/缩放，从而适配不同人、不同时段。

> **v3.5 量纲修订（当前生效）**：上式中的基准量必须是**未归一化 aperture/MAR**（本提案设计初衷即如此；v3.4 实现曾误在已归一化 0~1 开合度上做分位数跟踪，导致阈值被顶到 ~0.65 且受静态基准错配的饱和失真影响，见设计方案 §18.7）。v3.5 起校准器跟踪原始量纲，且输入开合度经 `normalizeEye/normalizeMouth`（同一 low/high 基准）归一化——因此上式阈值经归一化后**等价于因子本身**（下界 0.10 / 上界 0.70），`CalibratedThresholds` **恒输出眼睛因子**（样本不足阶段输入走静态端点归一化、输出同为因子，校准激活无跳变）；**嘴巴阈值固定**（张嘴 0.60 / 闭嘴 0.35，状态机设计为不随动态校准漂移，`mouth*` 字段输出同值并生效）。

#### B. 校准的两条路径

**路径 1：初始化/复位校准（结合驾驶门开关信号）**

> 用户关键诉求：**"阈值动态调整的关键点需要和驾驶门的开关信号做关联"**——即**换驾驶员时应重新校准**。

- 新增**驾驶门开关信号**（如 Car VHAL `DOOR_OPEN` / `DOOR_LOCK` 属性，见下文 §D 接入），信号源与现有 `VehicleSignalSource`（车速）同构；
- **门开信号**（驾驶员可能下车/换人）→ 触发 `Calibrator.reset()`：清空当前基准，进入"重校窗口"；
- **重校窗口**（门开后人脸重新稳定的一段时间，如 3~5s）内，采集新驾驶员基准确认：
  - 假定新驾驶员在窗口内处于**正常驾驶状态（睁眼、闭嘴）**，采集该段 aperture/MAR 的高位区（睁眼 aperture、闭嘴 MAR）作为 `REF_EYE_OPEN_APERTURE` / `REF_MOUTH_CLOSED_MAR`；
  - `REF_EYE_CLOSED_APERTURE` / `REF_MOUTH_OPEN_MAR` 若无法在窗口强制采集，则用**经验比例**（如闭眼残差 ≈ 睁眼基准的 40%）、或由后续运行中的动态跟踪补齐；
- **门关信号 + 车速上电** → 确认驾驶员已就位，锁定校准结果，进入运行状态。

**路径 2：运行中持续自适应（滑动窗口分位数）**

- 不依赖门信号时，校准器在运行中持续跟踪 aperture/MAR 的滑动分布：
  - 维护**滑动窗口**（如最近 300 帧）的 aperture 分位数：取 **P90（高位）= 睁眼 aperture 候选**、**P10（低位）= 闭眼 aperture 候选**；
  - 用**移动平均/EWMA** 缓慢更新 `REF_EYE_OPEN_APERTURE` / `REF_EYE_CLOSED_APERTURE`（嘴巴同理）；
- 这样即使没有门信号，也能随驾驶员、距离、光照缓慢自适应，且不会突变。
- **两种路径协同**：门信号负责"硬复位"（换人瞬间清基准），运行中自适应负责"软漂移补偿"（同一人长时间内的缓慢变化）。

#### C. 校准器对外接口（草案）

```kotlin
/** 阈值动态校准器：维护睁/闭眼、张/闭嘴基准，并随门信号/运行自适应更新。 */
class EyeMouthCalibrator(
    /** 复位/重校触发回调（驾驶门开时调用，清空基准进入重校）。 */
    fun reset()
    /** 更新一帧（v3.5 起收**未归一化** aperture/MAR），内部做滑动分位数跟踪，返回当前换算好的防抖阈值。 */
    fun update(eyeAperture: Float, mouthMar: Float): CalibratedThresholds
    /** 用当前个人高/低基准把原始 aperture 归一化为 0~1 开合度（v3.5 新增）。 */
    fun normalizeEye(aperture: Float): Float
    /** 用当前个人高/低基准把原始 MAR 归一化为 0~1 开合度（v3.5 新增）。 */
    fun normalizeMouth(mar: Float): Float
)

/** 动态换算后的防抖阈值（喂给 EyeMouthStateMachine 构造参数）。 */
data class CalibratedThresholds(
    val eyeCloseRatio: Float,   // CLOSE_RATIO（滞回下界；v3.5 起=下界因子 0.10）
    val eyeOpenRatio: Float,    // OPEN_RATIO（滞回上界；v3.5 起=上界因子 0.70）
    val mouthCloseRatio: Float,
    val mouthOpenRatio: Float
)
```

- `EyeMouthStateMachine` 改为**每帧接收动态阈值**（`update` 时传入 `CalibratedThresholds`），而非构造时写死，以便随校准实时调整；
- 校准器与防抖器解耦：防抖器只认阈值，不关心阈值从哪来。

#### D. 驾驶门开关信号的接入

- 在 `signal/` 层新增**车门开关信号源**（对齐现有 `VehicleSignalSource` 模式），通过 Car VHAL `CarPropertyManager` 订阅车门属性（如 `VehiclePropertyIds.DOOR_LOCK` / `DOOR_OPEN`，具体属性 ID 以实际车载 HAL 为准）；
- 定义新信号类型：`SignalTypes.DoorState(isOpen: Boolean, valid: Boolean)`；
- 经总线 topic 分发，`SignalDispatcher`（或新增处理器）监听门开信号 → 回调 `EyeMouthCalibrator.reset()`；
- 门信号**不参与眼嘴判定本身**，仅作为**校准触发信号**，与车速信号地位相同（可复用健康检查/线程机制）。

> 说明：门开关信号的**具体 VHAL 属性 ID 与总线接入**需在实施时确认车载平台能力；本提案先明确"门信号 → 复位重校"的调用契约，不依赖具体属性。

---

## 4. 数据流改造（改造后）

```
FaceSDK.infer()
  → FaceResult.landmarks[106][2]（原图坐标，含 cropOffset 修正）
  → [新增] EyeMouthStateEstimator（单帧几何计算）
       ├─ LandmarkIndexMapping：语义区域 → 106 索引（可配置）
       ├─ 从 106 点提取 眼睛/嘴巴 区域点
       └─ 计算 aperture（睑距/脸宽） / MAR → 输出连续开合度 eyeOpenRatio / mouthOpenRatio
  → [新增] EyeMouthCalibrator（动态基准/阈值校准，§3.7.6）
       ├─ 运行中滑动分位数自适应（软漂移补偿）
       ├─ 驾驶门开关信号 → reset()（换驾驶员硬复位重校）
       └─ 输出 CalibratedThresholds（动态换算的滞回阈值）
  → [新增] EyeMouthStateMachine（状态防抖器，保证基础状态稳定）
       ├─ 输入：每帧开合度 + hasFace + 动态阈值（Calibrator 输出）
       ├─ 双阈值滞回 + 按持续时间确认（单调时钟，对齐 DistractionStateMachine）
       └─ 输出稳定基础状态 eyeClosed / mouthOpen（不做业务判定）
  → FaceIDResult.landmarks + eyeOpen/mouthOpen (+ eyeOpenRatio/mouthOpenRatio)
  → FaceOverlayView（可绘制睁闭/开合状态标签或遮罩）
```

---

## 5. 改动文件清单

| # | 文件/目录 | 类型 | 改动内容 |
|---|-----------|------|---------|
| 1 | `algorithm/LandmarkRegion.kt` | 新增 | 语义区域 enum（稳定，判定逻辑只依赖它） |
| 2 | `algorithm/LandmarkIndexMapping.kt` | 新增 | 语义区域→106 索引映射表（唯一接触模型索引的地方，可配置注入） |
| 3 | `algorithm/EyeMouthStateEstimator.kt` | 新增 | 单帧几何判定器：依赖 `LandmarkRegion` + 注入 `LandmarkIndexMapping`，计算 aperture（睑距/脸宽）/MAR，输出连续开合度 `eyeOpenRatio`/`mouthOpenRatio`。纯计算、阈值可配，便于单测 |
| 4 | `algorithm/EyeMouthStateMachine.kt` | 新增 | 状态防抖器：输入每帧开合度 + hasFace + **动态阈值**（`Calibrator` 输出），双阈值滞回 + 按持续时间确认（单调时钟可注入），输出稳定基础状态 `eyeClosed`/`mouthOpen`。不含业务判定，对齐 `DistractionStateMachine` 模式 |
| 5 | `algorithm/EyeMouthCalibrator.kt` | 新增 | 阈值动态校准器（§3.7.6）：维护睁/闭眼、张/闭嘴基准，运行中滑动分位数自适应，`reset()` 由驾驶门信号触发，输出动态换算的 `CalibratedThresholds`。纯逻辑、可注入，便于单测 |
| 6 | `signal/DoorSignalSource.kt` + `SignalTypes.DoorState` | 新增 | 驾驶门开关信号源（对齐 `VehicleSignalSource`）：订阅 Car VHAL 车门属性，输出 `DoorState(isOpen, valid)`，作为校准器的复位触发 |
| 7 | `algorithm/IFaceIDAlgorithm.kt` | 修改 | `FaceIDResult` 新增 `eyeOpen` / `mouthOpen`（+ `eyeOpenRatio` / `mouthOpenRatio`）字段 |
| 8 | `algorithm/FaceIDAlgorithmImpl.kt` | 修改 | `processFrame()` 中调用 `EyeMouthStateEstimator` → `EyeMouthCalibrator` → `EyeMouthStateMachine`，将结果填入 `FaceIDResult`；确认 `landmarks` 开启标志 |
| 9 | `algorithm/EyeMouthStateMachineTest.kt` | 新增 | 单测：短确认防抖、滞回防闪变、时钟推进、hasFace=false 重置、闭眼残差边界 |
| 10 | `algorithm/EyeMouthStateEstimatorTest.kt` | 新增 | 单测：睁眼/闭眼/张嘴/闭嘴 四态 + 阈值边界 + 映射索引正确性 |
| 11 | `algorithm/EyeMouthCalibratorTest.kt` | 新增 | 单测：滑动分位数自适应、门信号 reset 复位重校、阈值换算正确性 |
| 12 | `render/FaceOverlayView.kt` | 修改 | 消费 `eyeOpen` / `mouthOpen`，可选展示状态标签 |
| 13 | `docs/FaceID_SO对接说明.md` | 修改 | 补充 106 点眼睛/嘴巴索引约定、语义区域映射、基础状态判定、防抖与动态校准说明 |

---

## 6. 实施步骤（分阶段）

> **状态：阶段一 ~ 阶段五 均已实现并验证通过**，在分支 `dev_ddaw` 上落地。

### 阶段一：语义区域与映射表落地 ✅
- 新建 `LandmarkRegion` enum 与 `LandmarkIndexMapping` 默认映射（采用 §2.4 已确认索引）；
- 用现有 dump 帧**验证映射索引正确性**（人工比对 106 点标注与眼睛/嘴巴区域）；瞳孔使用 5 关键点（`keypoints[0]`/`keypoints[1]`），不做 106 点瞳孔核对；
- 将确认结果沉淀为映射表注释与文档。
- 测试：`LandmarkIndexMappingTest`（12 例通过）。

### 阶段二：单帧几何判定器（Estimator）实现 ✅
- 新建 `EyeMouthStateEstimator`，依赖 `LandmarkRegion`、注入 `LandmarkIndexMapping`，实现 aperture/MAR 计算 + 连续开合度输出；
- 阈值与基准校准做成可配置（构造参数），便于标定；
- 单测覆盖睁眼/闭眼/张嘴/闭嘴四态、阈值边界、映射索引正确性。
- 测试：`EyeMouthStateEstimatorTest`（11 例通过）。

### 阶段三：基础状态防抖器（StateMachine）实现 ✅
- 新建 `EyeMouthStateMachine`，对齐 `DistractionStateMachine`：双阈值滞回 + 按持续时间确认（单调时钟注入）；
- 重点处理"闭眼上下睑残差不完全闭合"——用 `CLOSE_RATIO/OPEN_RATIO` 滞回区间吸收阈值附近抖动；
- 确认时长采用短确认级（如 80ms），保证基础状态及时且稳定，不掺入业务口径；
- 单测覆盖：短确认防抖、滞回防闪变、时钟推进、`hasFace=false` 重置、闭眼残差边界。
- 测试：`EyeMouthStateMachineTest`（11 例通过）。

### 阶段四：接入 FaceIDResult ✅
- `FaceIDResult` 新增字段（`eyeOpen`/`mouthOpen`，默认值，向后兼容）；
- `FaceIDAlgorithmImpl.processFrame()` 接入 `Estimator → StateMachine`（106 点展平为 FloatArray 供 Estimator，无人脸时重置状态机）；
- 编译 + 单测验证通过（`IFaceIDAlgorithmTest` 16 例通过，确认接口向后兼容）。

### 阶段五：渲染层消费 ✅
- `FaceOverlayView.FaceBox` 新增 `eyeOpen`/`mouthOpen`；
- `PreviewActivity.handleAlgorithmResult` 填充字段；
- `FaceOverlayView.onDraw` 在视线信息下方展示眼睛/嘴巴状态文字（如 `E:OPEN  M:CLOSED`），与 106 点绘制、视线/头姿绘制共存。

### 阶段六：阈值动态校准（结合驾驶门开关信号）
- 新建 `EyeMouthCalibrator`，实现 §3.7.6：维护睁/闭眼、张/闭嘴基准，运行中滑动分位数自适应（软漂移），`reset()` 由门信号触发（换人硬复位重校）；
- `EyeMouthStateMachine` 改为每帧接收动态阈值（`CalibratedThresholds`）；
- 新建 `DoorSignalSource` + `SignalTypes.DoorState`，订阅 Car VHAL 车门属性，门开 → `Calibrator.reset()`；
- `FaceIDAlgorithmImpl` 链路改为 `Estimator → Calibrator → StateMachine`；
- 单测：`EyeMouthCalibratorTest`（滑动自适应、门信号复位、阈值换算）。

---

## 7. 风险与注意事项

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 106 点眼睛/嘴巴索引定义理解偏差（含上下眼睑顺逆时针） | 判定结果错误 | §2.4 已给出推导依据；阶段一仍用 dump 帧实测复核，索引收敛到映射表 |
| 强侧脸/遮挡/低光照下几何法不准 | 误判 | 几何法作为 v1；后续可切换 `pfld_eye` 回归模型增强；阈值可调 |
| 阈值选取偏经验 / 不同人眼嘴大小差异 | 误判（此人总是睁眼/闭眼） | **动态校准**（§3.7.6）：每个驾驶员独立基准 + 门信号复位重校 + 运行中滑动自适应 |
| 换驾驶员后旧基准不适配 | 误判 | 驾驶门开信号 → `Calibrator.reset()` 清基准，进入重校窗口（§3.7.6-B） |
| 基准漂移（距离/光照缓慢变化） | 阈值失配 | 运行中滑动分位数（EWMA）软漂移补偿，缓慢自适应不突变 |
| 门信号 VHAL 属性不确定 / 平台差异 | 校准触发不可用 | 门信号仅作复位触发，缺省时退化为纯运行中自适应（§3.7.6-D）；属性 ID 实施时确认 |
| 校准窗口误采（重校时驾驶员并非睁眼闭嘴） | 基准偏差 | 校准窗口采集取分位数高位（P90）稳健估计，剔除离群帧 |
| 新增计算增加算法耗时 | 性能 | aperture/MAR 仅涉及十几点的简单距离运算，开销可忽略（<0.1ms）；校准为滑动统计，无显著开销 |
| 判定放算法层是否与"渲染层职责"冲突 | 职责 | 判定属算法语义，下沉 `algorithm/` 符合分层；渲染层只消费布尔结果 |
| 后续更换算法/点位定义 | 改动成本 | **映射表解耦**：仅改 `LandmarkIndexMapping`，判定逻辑与渲染层零改动 |
| 瞳孔索引定义（106 点 38/88 vs 代码注释 88/89 不一致） | 视线绘制偏差 | **已规避**：本提案不使用 106 点瞳孔，统一改用 5 关键点（`keypoints[0]`/`keypoints[1]`） |
| 闭眼时上下眼睑残差不完全闭合 | 闭眼被误判为睁眼 / 闪变 | **双阈值滞回**（`CLOSE_RATIO`/`OPEN_RATIO`）+ 基准动态校准（§3.7.2），吸收阈值附近抖动 |
| 单帧误判导致基础状态不稳定 | 状态闪烁 | **按持续时间确认**的防抖器（§3.7.3），确认/清除均需持续达标，天然防抖 |
| 帧率不稳定（跳帧）导致计数失真 | 误判 | 沿用项目 `DistractionStateMachine` 约定：按**单调时钟累计 ms**，不累计帧数 |
| 防抖器状态漂移/边界 | 状态卡死 | 时钟与阈值可注入 + 单测覆盖确认/清除/滞回/重置/边界；`hasFace=false` 强制重置 |

---

## 8. 结论

本提案聚焦**基础状态判定的正确性**：在算法模块内部，基于已输出的地标（v3.3 起 68 点）中**眼睛、嘴巴区域点位**，通过 **aperture（睑距/脸宽，眼睛）/ MAR（嘴巴）几何判据**增加一步推理，**先只输出"睁眼 / 闭眼 / 张嘴 / 闭嘴"四个基础状态**（及可选开合度），作为 `FaceIDResult` 新增字段透出。**暂不涉及**疲劳（闭眼持续时长）、打哈欠/说话等上层业务判定，待基础状态稳定正确后再行扩展。

**关键设计（围绕基础状态正确性）**：
1. **映射解耦**：判定逻辑通过 `LandmarkRegion` 语义区域 + `LandmarkIndexMapping` 索引映射解耦，不写死任何模型索引，后续更换算法只需改映射表；
2. **单帧几何 + 状态防抖**：`EyeMouthStateEstimator`（单帧 aperture/MAR 出连续开合度，aperture=睑距/脸宽，距离解耦）→ `EyeMouthStateMachine`（短确认防抖，对齐 `DistractionStateMachine`，不含业务判定）→ 稳定基础状态；
3. **抗残差与防抖**：双阈值滞回（`CLOSE_RATIO`/`OPEN_RATIO`），解决闭眼时上下睑残差不完全闭合导致的闪变；按单调时钟累计持续时间（非帧数）抗跳帧；
4. **阈值动态校准（§3.7.6）**：`EyeMouthCalibrator` 维护每个驾驶员的睁/闭眼、张/闭嘴基准，运行中滑动分位数自适应（软漂移补偿）；**驾驶门开关信号**触发换人复位重校（`reset()`），适配不同人脸型与行车时段，避免静态阈值导致的"此人总是睁眼/闭眼"误判；
5. 瞳孔一律使用 5 关键点（`keypoints[0]`/`keypoints[1]`），规避 106 点瞳孔索引不一致；
6. 上下眼睑对应关系已按"顺/逆时针 + 眼尾方位"严格推导（见 §2.4），并在阶段一用 dump 帧实测复核。
