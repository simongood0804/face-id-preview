# 提案：直连 libface.so 绕过 AAR 的可行性探索与结论

> 提案编号：FACEP-013  
> 创建日期：2026-07-30  
> 状态：已探索，未落地（回退至 AAR 方案）  
> 关联提案：FACEP-006（AAR 集成）、FACEP-012（人脸识别细化）

---

## 1. 动机与背景

### 1.1 起因

当前算法库以 `face-sdk-v1.1.4.aar`（AAR）形式集成（见 FACEP-006）。为排查"绘制点位是否来自底层真实数据"（特别是 **106 轮廓点**，其次为 5 关键点/头姿），期望**绕过 AAR 的 Java/JNI 包装层**，通过 JNI 直接调用 AAR 内携带的底层 `libface.so` 的 `face_vision_*` C API，读取原始 `FaceResult` C 结构体，以确定数据是否正确。

### 1.2 目标

1. 验证 `libface.so` 底层返回的 106 轮廓点/关键点数据是否正确；
2. 探索能否让"所有绘制点位来自自研 JNI"（即完全绕过 AAR 的 Java 层）；
3. 评估该方案的可行性、代价与风险。

---

## 2. AAR 内部分层分析

### 2.1 包结构

```
face-sdk-v1.1.4.aar
├── classes.jar
│   └── atlas.face.sdk.
│       ├── FaceSDK          ← 主入口（init/configure/infer/compare/destroy）
│       ├── FaceImage        ← 图像输入封装
│       ├── FaceResult       ← 检测结果
│       └── FaceFlag         ← 配置标志位
└── jni/arm64-v8a/
    ├── libc++_shared.so     (1.7 MB)
    ├── libface.so           (3.5 MB) ← 算法核心库（导出 face_vision_*）
    └── libface_vision_jni.so (4.1 MB) ← AAR 的 JNI 桥接（动态注册 JNI_OnLoad）
```

### 2.2 `libface.so` 导出符号

通过 `readelf -s` 确认 `libface.so` 导出的 `face_vision_*` C API：

```c
void*   face_vision_init(const char* model_dir, const char* runtime);   // 初始化
int     face_vision_configure(void* handle, int flags);                  // 配置模型
int     face_vision_infer(void* handle, const uint8_t* img, int w,int h,
                          int stride,int format, CFaceResult* results,
                          int max_faces, void* timing);                  // 推理
void    face_vision_destroy(void* handle);                               // 销毁
float   face_vision_compare(const float* emb1, const float* emb2);       // 比对
const char* face_vision_version(void);                                   // 版本
```

> **关键结论 1**：`libface.so` 直接接受原始图像数据 + 输出 `CFaceResult` 结构体数组，**不经过** `FaceImage`/`FaceResult` Java 对象。理论上可自研 JNI 直连。

### 2.3 C 层 `CFaceResult` 结构体布局

参考 `docs/FaceID_SO对接说明.md` 及反汇编分析，`CFaceResult`（`#pragma pack(4)`）为：

```c
typedef struct {
    float x1, y1, x2, y2;        // 0-15    人脸框
    float score;                  // 16-19   置信度
    float kps[5][2];              // 20-59   5 关键点
    float liveness;               // 60-63   活体得分
    float landmarks[106][2];      // 64-911  106 轮廓点
    int   landmarks_valid;        // 912-915 轮廓点有效标志
    float emb[512];               // 916-2963 embedding
    int   emb_valid;              // 2964-2967 embedding 有效标志
} CFaceResult;                    // sizeof = 2968
```

> **关键结论 2**：C 层 `CFaceResult` 本身**不含** `headPitch/Yaw/Roll`、`gaze`、`zone` 等字段。这些高级结果（头姿/视线/分区）由 AAR 的 **JNI 层或 Java 层**基于关键点/地标进一步计算，或在更高版本 AAR 中通过扩展结构提供。

### 2.4 AAR Java 层 `FaceResult` 字段

`javap` 反编译确认 Java `atlas.face.sdk.FaceResult` 实际字段（**新版 AAR**，与 FACEP-006 §2.1 的早期记录不同）：

```
float[]    box;         // [x1,y1,x2,y2]
float      score;       // 置信度
float[][]  keypoints;   // [5][2]
float      liveness;    // 活体
float[][]  landmarks;   // [106][2]
float[]    embedding;   // [512]
int        flags;
float      headPitch, headYaw, headRoll;   // 头姿
float      gazeYaw, gazePitch, gazeDistracted, gazeCalibrated;  // 视线
int        zoneId;      // DMS 分区
float      zoneConfidence;
float      distractionScore, distractionHpScore, distractionGazeScore;
int        gazeValid;
```

> **关键结论 3**：**新版 AAR 的 Java `FaceResult` 实际是包含头姿/视线/分区数据的**。这修正了早期"新 AAR 移除了头姿字段"的误判——`FaceIDAlgorithmImpl.kt` 中 `r.headPitch/r.headYaw/r.headRoll`、`r.gaze*`、`r.zoneId` 等均能正常取值（见当前 `processFrame` 实现）。

---

## 3. 直连 libface.so 的多种尝试

以下为本次对话中实际尝试的**全部加载方案**及结果。

### 3.1 方案一：`dlopen("libface.so")` + `dlsym`

```cpp
// native_face_sdk.cpp
g_libface = dlopen("libface.so", RTLD_LAZY | RTLD_GLOBAL);
PFN_init fn_init = (PFN_init)dlsym(g_libface, "face_vision_init");
```

- **结果**：`dlopen` 报错 `library "/system/lib64/libSNPE.so" is not accessible for the namespace "classloader-namespace"`。
- **原因**：`libface.so` 通过 `NEEDED` 依赖高通的 `libSNPE.so`，而 `libSNPE.so` 位于 `/system/lib64/`，受 **Android linker namespace（命名空间）隔离**，普通 app 的 `classloader-namespace` 无权加载系统命名空间中的库。

### 3.2 方案二：先 `dlopen("/system/lib64/libSNPE.so")` 再 `dlopen("libface.so")`

- **结果**：同样失败，`libSNPE.so` 从 `/system/lib64/` 全路径加载也被命名空间拒绝。

### 3.3 方案三：`RTLD_LAZY` / `RTLD_NOW` / `RTLD_NOLOAD` 变体

- 尝试 `RTLD_LAZY`（延迟解析）、`RTLD_NOW`（立即解析）、`RTLD_NOLOAD`（复用内存中已加载的句柄）。
- **结果**：只要 `libface.so` 的 `NEEDED` 依赖 `libSNPE.so` 需要解析，就必然触碰命名空间隔离，失败。

### 3.4 方案四：`android_dlopen_ext` + `ANDROID_DLEXT_USE_NAMESPACE`

```cpp
android_dlextinfo ext = {};
ext.flags = ANDROID_DLEXT_USE_NAMESPACE;
ext.library_namespace = "sphal";  // 尝试 system/default/sphal
android_dlopen_ext("libface.so", RTLD_LAZY | RTLD_GLOBAL, &ext);
```

- **结果**：`library_namespace` 字段实为 `android_namespace_t*` 指针而非字符串，无法从非公开 API 直接获得合法命名空间句柄；改用字符串形式不可行。**不可行**。

### 3.5 方案五：编译期链接 `libface.so`（`target_link_libraries`）

- 将 `libface.so` 作为预置 IMPORTED 库，`native_face_sdk` 通过 `NEEDED` 静态依赖它，由 Android linker 在加载时处理依赖链。
- **结果**：
  - `libface.so` 的 `SONAME` 是 `libfacevision.so`（而非 `libface.so`），导致 NEEDED 记录为 `libfacevision.so`，找不到对应库。
  - 复制一份命名为 `libfacevision.so` 后，NEEDED 匹配成功，但加载时仍卡在 `libSNPE.so` 命名空间隔离。
  - 即便 `dlopen` 成功、`dlsym` 取到函数指针，`face_vision_init` 调用即崩溃（PC≈0x1908，近 null 地址），疑似 `libface.so` 内部重定位/依赖未完全就绪或 runtime 参数不匹配。

### 3.6 方案六：把 `libSNPE.so` 从设备打包进 APK

```bash
adb pull /system/lib64/libSNPE.so app/libs/arm64-v8a/
```

- 将 `libSNPE.so`（约 10MB）放入 app 的 `jniLibs`，使 linker 在 app 命名空间内能找到它。
- **结果**：`libface.so` 终于 `dlopen` 成功（`lazy_load: dlopen libface.so (NOW) OK`）、`face_vision_version()` 返回 `1.0.0`、`face_vision_init` 函数指针正常取出。
- **但**：调用 `face_vision_init("/vendor/etc/faceid/manifest.json", "cpu")` 后**进程崩溃**（`face_vision_init` 内部发生 native crash，Tombstone 显示 PC 在低位地址）。
- **推断**：`libSNPE.so` 版本 / runtime 选择（`dsp`/`cpu`）/ manifest 路径格式 与自研 JNI 直接调用方式不匹配；`libSNPE.so` 作为系统库还依赖大量系统命名空间符号，仅打包 SO 无法完整还原其运行环境。

---

## 4. 结论

### 4.1 可行性判断

| 维度 | 结论 |
|------|------|
| **API 可调用性** | ✅ `face_vision_*` 符号确实导出，可 `dlsym` 取出 |
| **106 轮廓点获取** | ✅ C 层 `CFaceResult.landmarks[106][2]` 存在，理论上可自研 JNI 读取 |
| **依赖环境** | ❌ 强依赖高通 `libSNPE.so`，受 Android linker 命名空间隔离，普通 app 无法直接加载 |
| **运行时稳定性** | ❌ 即便打包 `libSNPE.so`，`face_vision_init` 调用仍崩溃 |
| **头姿/视线/分区** | ❌ C 层 `CFaceResult` 不含这些字段，自研 JNI 直连**拿不到**头姿/视线/分区数据 |

### 4.2 最终决定：回退至 AAR 方案

- **放弃直连 `libface.so` 的路线**，所有自研 JNI 实验代码（`native_face_sdk.cpp`、`NativeFaceSDK.kt`）**已回退删除**。
- 当前代码库维持 **FACEP-006 的 AAR 集成方案**：`FaceIDAlgorithmImpl.kt` 通过 `FaceSDK.init` / `sdk.infer` 调用 AAR，Java `FaceResult` 完整提供 106 轮廓点、头姿、视线、分区数据。
- 106 轮廓点/关键点/头姿数据来源均为 AAR 底层，**无需也无法**通过自研 JNI 直接读取。

### 4.3 对原始诉求的回答

"绕过 AAR，让所有绘制点位来自自研 JNI" —— **不可行**，原因：
1. 底层依赖 `libSNPE.so` 的系统命名空间隔离无法在 app 内绕过；
2. C 层 `CFaceResult` 不含头姿/视线/分区，自研 JNI 只能拿到检测/关键点/地标，反而**丢失**更高层的业务数据；
3. 直接调 `face_vision_init` 崩溃，无稳定运行方案。

因此绘制点位继续来自 AAR 的 Java `FaceResult`（即当前实现），这是最稳定、数据最完整的方案。

---

## 5. 经验教训与后续建议

1. **AAR 是唯一可靠入口**：算法库的 Java/JNI/底层 SO 是一体交付物，不应拆开单独直连底层 SO。
2. **`libSNPE.so` 依赖**：任何直接调用底层 SO 的尝试都会受高通 SNPE 运行时 + Android linker 命名空间的双重约束。
3. **头姿/视线/分区在 Java 层**：若需调试这些数据，应从 AAR 的 `FaceResult` 读取，而非底层 C 结构。
4. **后续验证方向**：
   - 若需确认 106 轮廓点正确性，建议在 AAR 侧增加 dump/日志（AAR 已提供 `FaceSDK.setDebugDumpPath`），导出原始 landmarks 比对；
   - 若需更高性能，应推动算法团队在 AAR 层优化，而非绕过 AAR。

---

## 6. 变更记录

| 日期 | 内容 |
|------|------|
| 2026-07-30 | 完成全部 6 种加载方案尝试，确认直连不可行，回退 AAR 方案；记录本提案 |
