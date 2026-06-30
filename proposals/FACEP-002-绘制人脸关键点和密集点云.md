# 提案：绘制人脸关键点（5点）和密集点云（106点）

> 提案编号：FACEP-002  
> 创建日期：2026-06-30  
> 状态：已实现

---

## 1. 动机

当前 `FaceOverlayView` 仅绘制人脸框（detected=绿、spoof=红）。算法实际上已输出 5 个面部关键点和 106 个密集地标点，但 UI 层未使用。将这些点绘制出来可以直观验证算法精度，也有助于后续业务需求（如视线估计、表情分析）。

## 2. 方案

### 2.1 点位说明

| 点位 | 数量 | 颜色 | 来源模型 | 说明 |
|------|------|------|----------|------|
| 面部关键点 | 5 | **蓝色** | det_500m（检测模型自带） | 左眼、右眼、鼻尖、左嘴角、右嘴角 |
| 密集地标 | 106 | **黄色** | 2d106det（独立模型） | 面部轮廓、五官轮廓等 |

### 2.2 数据流改造

```
C FaceResult                       JNI                  Java FaceIDNativeResult            FaceIDResult              FaceOverlayView
├── kps[5][2]     ──────────────→  ├── kps: FloatArray   ───────→  ├── keypoints: List<PointF>  ──→ 蓝色圆圈绘制
├── landmarks[106][2]     ──────→  ├── landmarks: FloatArray ──→  ├── landmarks: List<PointF>  ──→ 黄色圆圈绘制
└── landmarks_valid       ──────→  └── landmarksValid: Boolean
```

### 2.3 修改范围

| 文件 | 修改 | 说明 |
|---|---|---|
| `FaceIDNativeResult` | + `landmarks: FloatArray?`、`landmarksValid: Boolean` | JNI 接收 106 点 + 有效性标志 |
| `faceid_jni.cpp` | `nativeDetect` 中拷贝 `landmarks[106][2]` → flat FloatArray | 需新增 fieldID 查找（"landmarks", "[F"）和 `landmarksValid` |
| `IFaceIDAlgorithm.FaceIDResult` | + `keypoints: List<PointF>?` | 5 关键点传给 UI |
| `FaceIDAlgorithmImpl.processFrame()` | 将 `r.kps` 和 `r.landmarks` 转成 `List<PointF>` 塞入 result | 行 186 处扩展构造参数 |
| `FaceOverlayView.FaceBox` | + `keypoints: List<PointF>?`、`denseLandmarks: List<PointF>?` | 携带点数据 |
| `FaceOverlayView` | + bluePaint、yellowPaint；`onDraw()` 中画圆圈 | 蓝色 5 点（稍大）、黄色 106 点（稍小） |
| `PreviewActivity.handleAlgorithmResult()` | 传递 keypoints/landmarks 到 `FaceBox` | ~3 行 |

### 2.4 坐标处理

关键点和密集点的坐标均为原图像素空间（同 `faceRect`），在 `FaceOverlayView.onDraw()` 中使用已有的缩放比例 `scaleX = vw/imgW`、`scaleY = vh/imgH` 将其映射到 View 空间。

绘制方式：

```
每个关键点（蓝色）：canvas.drawCircle(x * scaleX, y * scaleY, 6f, bluePaint)   // 半径 6px
每个密集点（黄色）：canvas.drawCircle(x * scaleX, y * scaleY, 3f, yellowPaint)  // 半径 3px
```

蓝色使用 `Color.BLUE`，黄色使用 `Color.YELLOW`，均为实心填充。

### 2.5 性能

- 106 点 + 5 点 = 111 次 `drawCircle` 调用，每帧最多一次
- Canvas 绘制 111 个小圆点对 CPU 开销极小（远小于人脸框矩形绘制开销）
- 仅在检测到人脸时绘制，无人脸时不绘制

### 2.6 预览效果示意

```
┌─────────────────────────────────┐
│       ╔══════════════╗          │
│    ╔══╣  detected 92%╠══╗      │
│  ○ ║  ║    ●  ●     ║  ║  ○   │  ← 蓝色关键点（眼、鼻、口角）
│    ║  ║       ●     ║  ║      │
│  ○ ║  ║    ●    ●   ║  ║  ○   │
│    ╚══╣             ╠══╝      │
│       ╚══════════════╝         │
│  · · · · · · · · · · · · · ·   │  ← 黄色密集地标（面部轮廓）
└─────────────────────────────────┘
```

## 3. 数据类变更明细

### 3.1 `FaceIDNativeResult`（追加字段）

```kotlin
class FaceIDNativeResult {
    @JvmField var x1: Float = 0f
    @JvmField var y1: Float = 0f
    @JvmField var x2: Float = 0f
    @JvmField var y2: Float = 0f
    @JvmField var score: Float = 0f
    @JvmField var liveness: Float = -1f
    @JvmField var emb: FloatArray? = null
    @JvmField var kps: FloatArray? = null
    // +++ 新增
    @JvmField var landmarks: FloatArray? = null   // 106×2 = 212 floats
    @JvmField var landmarksValid: Boolean = false
}
```

### 3.2 `FaceIDResult`（追加字段）

```kotlin
class FaceIDResult @JvmOverloads constructor(
    faceId: String? = "",
    confidence: Float = 0f,
    val faceRect: RectF? = null,
    processedData: ByteArray? = null,
    val landmarks: List<PointF>? = null,
    val isNewEnrollment: Boolean = false,
    // +++ 新增
    val keypoints: List<PointF>? = null
)
```

### 3.3 `FaceBox`（追加字段）

```kotlin
data class FaceBox(
    val rect: RectF,
    val type: FaceType,
    val confidence: Float,
    val label: String? = null,
    // +++ 新增
    val keypoints: List<PointF>? = null,
    val denseLandmarks: List<PointF>? = null
)
```

## 4. 影响分析

- **JNI 改动最小**：仅追加两个 fieldID 查找 + 循环拷贝 212 个 float，内存分配一次 `NewFloatArray(212)`
- **无性能风险**：106 点仅在 `landmarks_valid==1` 时拷贝，`kps` 固定 5 点，数据量极小
- **向后兼容**：`FaceIDResult.landmarks` 原有字段名不变，新增 `keypoints` 可选字段
- **无算法精度影响**：仅 UI 展示，不改变任何识别/录入逻辑
