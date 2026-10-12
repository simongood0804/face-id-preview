# 提案：迁移算法库为 AAR 集成 — 清理 JNI / CMake / Native 依赖

> 提案编号：FACEP-006  
> 创建日期：2026-07-28  
> 状态：已实现  
> **更新（2026-08-24）**：本提案 §2.1 的 `FaceResult` 字段为**早期 AAR（v1.1.x 早期）快照**。当前 `face-sdk-v1.1.4.aar` 的实际 `FaceResult` 已**新增头姿/视线/分区字段**（`headPitch/headYaw/headRoll`、`gaze*`、`zoneId`、`distractionScore` 等），完整字段清单见 `proposals/FACEP-013-直连libface绕过AAR探索.md` §2.4 及 `docs/设计方案.md` §18.1。

---

## 1. 动机

当前算法库以预编译 `.so` + JNI 桥接代码的形式集成。算法团队已提供 `face-sdk-v1.1.4.aar`，作为标准 Android 库替代旧的 JNI 桥接方式。

集成 AAR 后：
- 不再需要维护 JNI 桥接代码（`faceid_jni.cpp`）
- 不再需要 CMake 原生构建
- 不再需要手动管理 `.so` 文件路径
- 算法调用方只需通过标准的 Kotlin/Java 接口调用

## 2. AAR 内部结构分析

```
face-sdk-v1.1.4.aar (1.84 MB)
├── classes.jar
│   └── atlas.face.sdk.
│       ├── FaceSDK          ← 主入口类
│       ├── FaceImage        ← 图像输入封装
│       ├── FaceResult       ← 检测结果
│       └── FaceFlag         ← 配置标志位
├── jni/arm64-v8a/
│   ├── libc++_shared.so     (1.7 MB)
│   ├── libface.so           (3.5 MB) ← 算法核心库
│   └── libface_vision_jni.so (4.1 MB) ← JNI 桥接（AAR 内部管理）
└── AndroidManifest.xml      (minSdk=24)
```

### 2.1 API 详解

#### FaceSDK — 主入口

```java
public class FaceSDK {
    // 初始化，传入 manifest.json 路径，返回 SDK 实例
    public static FaceSDK init(String manifestPath);

    // 配置启用哪些模型（bitwise OR of FaceFlag）
    public void configure(int flags);

    // 推理检测
    public int infer(FaceImage image, FaceResult[] results, int maxFaces);

    // 比对两个 embedding 的余弦相似度
    public static float compare(float[] emb1, float[] emb2);

    // 释放资源
    public void destroy();
}
```

#### FaceImage — 图像输入

```java
public class FaceImage {
    public byte[] data;    // 图像数据
    public int width;      // 图像宽度
    public int height;     // 图像高度
    public int stride;     // 行字节数
    public int format;     // 图像格式

    // 格式常量
    public static final int FACE_FMT_RGB;
    public static final int FACE_FMT_BGR;
    public static final int FACE_FMT_GRAY;
    public static final int FACE_FMT_NV21;
    public static final int FACE_FMT_NV12;
    // 注意：没有 FACE_FMT_UYVY！当前我们使用的 UYVY 格式可能需要转换
}
```

#### FaceResult — 检测结果

```java
public class FaceResult {
    public float[] box;          // [x1, y1, x2, y2]
    public float score;          // 检测置信度
    public float[][] keypoints;  // 5 个关键点 [5][2]
    public float liveness;       // 活体得分
    public float[][] landmarks;  // 106 密集地标 [106][2]
    public float[] embedding;    // 512-D 特征向量
    public int flags;            // 结果包含哪些模型输出
}
```

#### FaceFlag — 配置标志

```java
public class FaceFlag {
    public static final int DETECTION   = 1;
    public static final int LIVENESS    = 2;
    public static final int LANDMARK    = 4;
    public static final int RECOGNITION = 8;
    public static final int ALL         = 15;
}
```

### 2.2 与旧 API 的映射关系

| 旧接口 | 新 AAR 接口 | 说明 |
|--------|------------|------|
| `faceid_init(manifest)` | `FaceSDK.init(manifest)` | 返回 FaceSDK 实例而非 long handle |
| `faceid_configure(handle, flags)` | `sdk.configure(flags)` | 参数和语义一致 |
| `faceid_detect(handle, img, ...)` | `sdk.infer(image, results, maxFaces)` | 输入输出略有不同 |
| `faceid_compare(emb1, emb2)` | `FaceSDK.compare(emb1, emb2)` | 静态方法，完全一致 |
| `faceid_destroy(handle)` | `sdk.destroy()` | 调用方式不同 |
| `faceid_version()` | — | AAR 未提供版本接口，需确认 |

### 2.3 关键差异点

1. **FaceImage 无 UYVY 格式**：当前我们使用 UYVY 输入，AAR 支持 RGB/BGR/GRAY/NV21/NV12，需增加格式转换
2. **FaceResult.box 是 float[4]**：旧接口是独立的 x1/y1/x2/y2 字段
3. **FaceResult.keypoints 是 float[5][2]**：旧接口是 float[10] 扁平数组
4. **FaceResult.landmarks 是 float[106][2]**：旧接口是 float[212] 扁平数组
5. **FaceSDK 实例管理**：不再持有 long handle，直接持有 FaceSDK 对象

## 3. 需要清理的文件

### 3.1 可删除的文件

| # | 文件 | 大小 | 说明 |
|---|------|------|------|
| 1 | `app/src/main/cpp/faceid_jni.cpp` | — | JNI 桥接代码，由 AAR 内部实现替代 |
| 2 | `app/src/main/cpp/faceid_api.h` | — | C API 头文件，不再直接使用 |
| 3 | `app/src/main/cpp/faceid_types.h` | — | C 类型定义，由 AAR 的类替代 |
| 4 | `app/src/main/cpp/CMakeLists.txt` | — | CMake 构建配置，不再需要原生编译 |
| 5 | `app/src/main/cpp/lib/arm64-v8a/libfaceid.so` | 2.5 MB | 算法 so（CMake 链接用副本） |
| 6 | `app/src/main/cpp/lib/arm64-v8a/libonnxruntime.so` | 15.3 MB | ONNX Runtime so（CMake 链接用副本） |
| 7 | `app/src/main/jniLibs/arm64-v8a/libfaceid.so` | 2.5 MB | 算法 so（APK 打包用） |
| 8 | `app/src/main/jniLibs/arm64-v8a/libonnxruntime.so` | 15.3 MB | ONNX Runtime so（APK 打包用） |

### 3.2 需要修改的文件

| # | 文件 | 修改内容 |
|---|------|---------|
| 9 | `app/libs/face-sdk-v1.1.4.aar` | **新增**（已放置） |
| 10 | `app/build.gradle.kts` | 移除 CMake/NDK 配置；新增 AAR 依赖 |
| 11 | `FaceIDAlgorithmImpl.kt` | 改用 FaceSDK API 替代 JNI external fun |
| 12 | `PreviewActivity.kt` | 移除 System.loadLibrary |

### 3.3 无需修改的文件

| 文件 | 原因 |
|------|------|
| `IFaceIDAlgorithm.kt` | 抽象接口不变 |
| `FrameProcessor.kt` | 帧处理逻辑不变 |
| `FaceIDCameraController.kt` | 摄像头控制逻辑不变 |
| `CameraManager.kt` | 摄像头管理不变 |
| `FaceEnrollmentManager.kt` | 人脸录入管理不变 |
| `FaceOverlayView.kt` | UI 绘制不变 |

## 4. 架构变化

### 4.1 修改前

```
App Module
  ├── FaceIDAlgorithmImpl.kt  ← System.loadLibrary("faceid_jni")
  │     └── external fun nativeInit / nativeDetect / ...
  │
  ├── cpp/                    ← JNI 桥接 + CMake 编译
  │     ├── faceid_jni.cpp
  │     ├── faceid_api.h
  │     ├── faceid_types.h
  │     ├── CMakeLists.txt
  │     └── lib/arm64-v8a/
  │           ├── libfaceid.so
  │           └── libonnxruntime.so
  │
  └── jniLibs/arm64-v8a/      ← APK 打包用
        ├── libfaceid.so
        └── libonnxruntime.so
```

### 4.2 修改后

```
App Module
  ├── libs/face-sdk-v1.1.4.aar  ← AAR 依赖（算法库由 AAR 内部管理）
  │
  ├── FaceIDAlgorithmImpl.kt  ← 调用 FaceSDK API
  │     └── FaceSDK.init() / sdk.infer() / sdk.destroy()
  │
  └── build.gradle.kts
        └── implementation(files("libs/face-sdk-v1.1.4.aar"))
```

## 5. 详细修改方案

### 5.1 build.gradle.kts

**删除的内容：**

```diff
- // CMake Native 编译
- externalNativeBuild {
-     cmake {
-         path = file("src/main/cpp/CMakeLists.txt")
-         version = "3.18.1"
-     }
- }

- // 仅编译 arm64-v8a
- defaultConfig.ndk {
-     abiFilters.add("arm64-v8a")
- }

- // jniLibs 目录
- sourceSets {
-     getByName("main") {
-         jniLibs.srcDirs("src/main/jniLibs")
-     }
- }

- // 去重
- packagingOptions {
-     jniLibs.pickFirsts.add("lib/arm64-v8a/libfaceid.so")
- }
```

**新增的内容：**

```kotlin
dependencies {
    // AAR 算法库（替换原生 so + JNI）
    implementation(files("libs/face-sdk-v1.1.4.aar"))
    // ... 其他依赖保持不变
}
```

### 5.2 FaceIDAlgorithmImpl.kt

**移除：**
- `System.loadLibrary("faceid_jni")` init 块
- 6 个 `external fun` 声明

**修改后的核心逻辑：**

```kotlin
class FaceIDAlgorithmImpl : IFaceIDAlgorithm {
    private var mFaceSDK: FaceSDK? = null  // 替代 mNativeHandle: Long

    override fun initialize(context: Context?, config: MutableMap<String, Any>): Boolean {
        if (mInitialized) return true
        return try {
            mModelDir = resolveModelDir(context)
            Log.i(TAG, "initialize: model_dir=$mModelDir")

            // FaceSDK.init() 替代 nativeInit()
            val sdk = FaceSDK.init("$mModelDir/manifest.json") ?: return false

            // sdk.configure() 替代 nativeConfigure()
            sdk.configure(FaceFlag.ALL)

            mFaceSDK = sdk
            mInitialized = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "initialize: failed", e)
            false
        }
    }

    override fun processFrame(frameData: ByteArray?, width: Int, height: Int, format: Int): FaceIDResult {
        val sdk = mFaceSDK
        if (!mInitialized || sdk == null) { ... }

        // 构建 FaceImage（注意格式转换）
        // 当前 UYVY 需转为 NV21 或 RGB
        val image = FaceImage(frameData, width, height, 0, FaceImage.FACE_FMT_NV21)

        // sdk.infer() 替代 nativeDetect()
        val results = arrayOfNulls<FaceResult>(MAX_FACES)
        for (i in 0 until MAX_FACES) results[i] = FaceResult()
        val n = sdk.infer(image, results, MAX_FACES)

        // 转换 FaceResult → FaceIDNativeResult
        // FaceResult.box[4] → x1, y1, x2, y2
        // FaceResult.keypoints[5][2] → kps[10]
        // FaceResult.landmarks[106][2] → landmarks[212]
        // FaceResult.embedding → emb[512]
        ...
    }

    override fun release() {
        mFaceSDK?.destroy()  // 替代 nativeDestroy()
        mFaceSDK = null
        mInitialized = false
    }

    fun compare(emb1: FloatArray, emb2: FloatArray): Float {
        return FaceSDK.compare(emb1, emb2)  // 静态方法，调用方式不变
    }
}
```

### 5.3 FaceIDNativeResult 的数据转换

AAR 的 `FaceResult` 使用二维数组，需要转换为一维数组以保持对上层 `IFaceIDAlgorithm.FaceIDResult` 的兼容：

```kotlin
// 从 FaceResult 转换到 FaceIDNativeResult
private fun convertResult(src: FaceResult, dst: FaceIDNativeResult) {
    if (src.box != null && src.box.size >= 4) {
        dst.x1 = src.box[0]
        dst.y1 = src.box[1]
        dst.x2 = src.box[2]
        dst.y2 = src.box[3]
    }
    dst.score = src.score
    dst.liveness = src.liveness

    // keypoints: float[5][2] → float[10]
    if (src.keypoints != null) {
        dst.kps = FloatArray(10)
        for (i in 0 until 5) {
            dst.kps[i * 2]     = src.keypoints[i][0]
            dst.kps[i * 2 + 1] = src.keypoints[i][1]
        }
    }

    // landmarks: float[106][2] → float[212]
    if (src.landmarks != null) {
        dst.landmarks = FloatArray(212)
        for (i in 0 until 106) {
            dst.landmarks[i * 2]     = src.landmarks[i][0]
            dst.landmarks[i * 2 + 1] = src.landmarks[i][1]
        }
        dst.landmarksValid = true
    }

    dst.emb = src.embedding
}
```

### 5.4 UYVY → NV21 格式转换

当前 Camera HAL 输出 UYVY 格式，AAR 不支持 UYVY，需要转换。在 `processFrame` 或 `nativeReadHardwareBuffer` 中增加转换：

```kotlin
// 简化版 UYVY → NV21 转换
private fun uyvyToNv21(uyvy: ByteArray, width: Int, height: Int): ByteArray {
    val nv21 = ByteArray(width * height * 3 / 2)
    val ySize = width * height
    // Y 分量：UYVY 的 Y 在偶数位（Y0 U0 Y1 V0）
    var yIdx = 0
    for (i in uyvy.indices step 2) {
        nv21[yIdx++] = uyvy[i + 1]  // 提取 Y
    }
    // UV 分量：每 2x2 取一组
    var uvIdx = ySize
    for (row in 0 until height / 2) {
        for (col in 0 until width / 2) {
            val srcPos = (row * 2 * width + col * 2) * 2
            nv21[uvIdx++] = uyvy[srcPos]       // U
            nv21[uvIdx++] = uyvy[srcPos + 2]   // V
        }
    }
    return nv21
}
```

> 注：格式转换会引入额外的 CPU 开销，建议后续推动 AAR 直接支持 UYVY 输入。

### 5.5 PreviewActivity.kt

```diff
- init {
-     try {
-         System.loadLibrary("faceid_jni")
-     } catch (_: UnsatisfiedLinkError) { }
- }
- private external fun nativeReadHardwareBuffer(...): ByteArray?
```

`nativeReadHardwareBuffer` 的功能（读取 HardwareBuffer → ByteArray）需要保留，但不再通过 JNI 调用。方案有二：

**方案 A（推荐）**：将 `nativeReadHardwareBuffer` 内联到 `processWithAlgorithm` 中，使用标准 Android API `HardwareBuffer.lock()` 读取。Kotlin 侧直接操作 `ByteBuffer`。

**方案 B**：将读取逻辑提取为一个独立的 Kotlin 工具类，放在 `algorithm` 包下。

## 6. 删除的文件

```bash
git rm -r app/src/main/cpp/
git rm -r app/src/main/jniLibs/
```

## 7. 验证方案

1. **编译验证**：`./gradlew assembleRelease` 成功
2. **初始化验证**：`FaceSDK.init()` 返回非 null 实例
3. **格式转换验证**：UYVY → NV21 转换后推理结果正确
4. **功能验证**：人脸检测/识别结果与之前一致
5. **回归验证**：自动预览、算法开关、帧率均正常

## 8. 风险与注意事项

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| UYVY 格式转换开销 | 额外 ~5ms CPU 处理 | 推动 AAR 支持 UYVY |
| AAR 内部版本与 manifest 兼容性 | 初始化失败 | 确认 AAR 对应的 manifest 版本 |
| FaceSDK 线程安全模型 | 多线程调用问题 | 确认 AAR 的线程安全策略 |
