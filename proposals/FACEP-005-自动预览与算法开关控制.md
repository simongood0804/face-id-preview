# 提案：自动预览 + 算法开关控制

> 提案编号：FACEP-005  
> 创建日期：2026-07-10  
> 状态：已实现

---

## 1. 动机

当前应用启动后停留在黑屏界面，需要用户手动点击"开始预览"按钮才能打开摄像头。在产品场景下，应用启动后应**立即显示画面**，无需用户干预。

同时，算法人脸检测/识别并非所有场景都需要——有时只需查看摄像头画面（纯预览模式）。需要一个开关让用户或调用方控制算法是否运行，节省 CPU/DSP 资源。

## 2. 修改一：进入应用后自动预览

### 2.1 当前行为

```
应用启动 (onCreate)
  → initViews() + initCoreModules()
  → 停在"空闲"状态，画面黑屏 ← 需要用户点击按钮
```

### 2.2 修改后行为

```
应用启动 (onCreate)
  → initViews() + initCoreModules()
  → startPreview()            ← 自动启动摄像头
  → 立即显示 EVS 画面         ← 无需用户操作
```

### 2.3 修改点

在 `PreviewActivity.onCreate()` 末尾添加 `startPreview()`：

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_preview)

    initViews()
    initCoreModules()

    // 自动开始预览
    startPreview()

    Log.i(TAG, "onCreate: done")
}
```

### 2.4 生命周期适配

当前 `onPause()` 中调用 `stopPreview()`，`onResume()` 中未重新开启。修改后需要在 `onResume()` 中自动恢复预览：

```kotlin
override fun onResume() {
    super.onResume()
    mPreviewSurface.onResume()
    if (!mIsPreviewing) {
        startPreview()  // 从后台恢复时自动重开预览
    }
}
```

## 3. 修改二：算法开关控制

### 3.1 当前行为

`onFrameData` 回调中始终调用 `processWithAlgorithm`，每帧都执行人脸检测 + 识别，无法关闭。

### 3.2 修改后行为

新增一个**算法开关按钮**，控制算法是否运行：

- **算法开启**：当前行为不变，每帧执行人脸检测/识别
- **算法关闭**：仅显示摄像头画面，不调用 `processWithAlgorithm`，零算法开销

### 3.3 UI 复用与修改

**不复增按钮**，直接复用现有的 `btn_toggle`（左下角）。

按钮的语义从"预览控制"改为**算法开关控制**。按钮文本资源修改：

| 资源名 | 旧值 | 新值 |
|--------|------|------|
| `btn_start_preview` | "Start Preview" | **"Algo: ON"**（算法开启） |
| `btn_stop_preview` | "Stop Preview" | **"Algo: OFF"**（算法关闭） |

按钮的 `onClick` 从 `togglePreview()` 改为 `toggleAlgorithm()`。

### 3.4 逻辑修改

#### PreviewActivity.kt

新增算法开关状态，复写按钮点击事件：

```kotlin
/** 算法是否开启。 */
private var mAlgorithmEnabled = true

private fun initViews() {
    // ... 原有初始化 ...
    
    // 按钮改为算法开关
    mToggleButton.setOnClickListener { toggleAlgorithm() }
    mToggleButton.setText(R.string.btn_start_preview)  // 默认"Algo: ON"
}

/**
 * 切换算法开启/关闭状态。
 */
private fun toggleAlgorithm() {
    mAlgorithmEnabled = !mAlgorithmEnabled
    mToggleButton.setText(
        if (mAlgorithmEnabled) R.string.btn_start_preview
        else R.string.btn_stop_preview
    )
    Log.i(TAG, "algorithm ${if (mAlgorithmEnabled) "enabled" else "disabled"}")
}
```

`processWithAlgorithm` 中添加开关判断：

```kotlin
private fun processWithAlgorithm(hwBuffer: HardwareBuffer, frameW: Int, frameH: Int) {
    if (!mAlgorithmEnabled) return  // 算法关闭，跳过处理

    val fp = mFrameProcessor ?: return
    // ... 原有逻辑 ...
}
```

#### FrameworkProcessor.kt

当算法关闭时，`processLoop` 不应空转。但当前设计是 `onFrameData` 不再调用 `submitFrame`，所以 `processLoop` 不会被触发，不需额外修改。

### 3.5 状态恢复

算法开关状态建议用 `SharedPreferences` 持久化，下次启动保持上次的选择：

```kotlin
private val PREFS_NAME = "faceid_prefs"
private val KEY_ALGO_ENABLED = "algorithm_enabled"

private fun loadAlgorithmState() {
    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    mAlgorithmEnabled = prefs.getBoolean(KEY_ALGO_ENABLED, true)
}

private fun saveAlgorithmState() {
    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_ALGO_ENABLED, mAlgorithmEnabled)
        .apply()
}
```

## 4. 修改范围汇总

| 文件 | 修改 | 说明 |
|------|------|------|
| `PreviewActivity.kt` | `onCreate` 末尾调用 `startPreview()` | 自动预览 |
| `PreviewActivity.kt` | `onResume` 中自动恢复预览 | 生命周期适配 |
| `PreviewActivity.kt` | 新增 `mAlgorithmEnabled` + 复用 `toggleAlgorithm()` | 复用预览按钮为算法开关 |
| `PreviewActivity.kt` | `processWithAlgorithm` 开头加开关判断 | 跳过算法处理 |
| `PreviewActivity.kt` | `loadAlgorithmState` / `saveAlgorithmState` | 状态持久化 |
| `strings.xml` | 修改 `btn_start_preview` / `btn_stop_preview` 文本值 | 改为算法开关文案 |

## 5. 数据流对比

```
算法开启时:
  onFrameData → processWithAlgorithm → nativeReadHardwareBuffer → submitFrame → 推理

算法关闭时:
  onFrameData → processWithAlgorithm → return (跳过)
  ↓
  渲染器继续工作，画面正常显示
  DSP/CPU 零负载
```

## 6. 效果预期

- **自动预览**：应用启动即显示摄像头画面，无需用户操作
- **算法关闭**：零算法负载，仅渲染画面（适用于纯显示场景）
- **算法开启**：行为不变，全管线运行
- **后台恢复**：自动重开预览，无需再次点击
