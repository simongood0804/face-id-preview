# Framework & EvsSDK 集成方案

> 版本：v1.0  
> 适用项目：Face ID Preview (`com.skyworth.faceid`)  
> 目标平台：Android 10 (API 29) arm64-v8a  
> 部署路径：`/system/app/FaceIDPreview/`

---

## 目录

1. [背景](#1-背景)
2. [依赖项分析](#2-依赖项分析)
3. [EvsSDK 集成方案](#3-evssdk-集成方案)
4. [Framework 集成方案](#4-framework-集成方案)
5. [系统应用部署方案](#5-系统应用部署方案)
6. [常见问题](#6-常见问题)

---

## 1. 背景

本项目的 Face ID 预览功能依赖车载系统的 **EvsSDK**（Embedded Visualization System SDK）
来控制 DMS（Driver Monitoring System）摄像头。EvsSDK 是 **AOSP 框架层**的一部分，
运行在 `system` 进程中，需要系统级权限和平台签名才能访问。

### 1.1 设备环境

| 参数 | 值 |
|------|-----|
| Android 版本 | 10 (API 29) |
| SDK 版本 | 29 |
| 架构 | arm64-v8a |
| 用户 | system (UID 1000) |
| 签名 | platform 签名 |

### 1.2 依赖图谱

```
FaceIDPreview (com.skyworth.faceid)
├── com.android.car:evs:1.0.7          ← EvsSDK 依赖
├── com.android.car:lib:1.0.6          ← Android Car 库
├── com.android:framework:1.0.0        ← AOSP framework bootclasspath
├── androidx.appcompat:appcompat:1.3.1
├── androidx.constraintlayout:constraintlayout:2.1.4
├── androidx.lifecycle:lifecycle-livedata:2.3.1
└── androidx.lifecycle:lifecycle-extensions:2.2.0
```

---

## 2. 依赖项分析

### 2.1 EvsSDK (`com.android.car:evs:1.0.7`)

**来源**：`coolwell:van233_snap` Maven 仓库  
**内容**：EvsSDK 的 AAR/JAR 包，包含：

| 类 | 用途 |
|----|------|
| `com.android.car.evs.EvsCameraController` | 摄像头控制器，核心入口 |
| `com.android.car.evs.EvsBufferDesc` | Buffer 描述（id, width, height, HardwareBuffer, state） |
| `com.android.car.evs.EvsHalWrapper` | HAL 层封装接口 |
| `com.android.car.evs.EvsHalWrapperImpl` | HAL 层实现（JNI → `libevsservicejni.so`） |
| `com.android.car.evs.EvsFrameRate` | 帧率统计工具 |
| `com.android.car.evs.CameraIds` | 摄像头 ID 常量（RVC, FVC, DMS, SVC） |

**依赖链**：

```
EvsCameraController
  → EvsHalWrapperImpl
    → System.loadLibrary("evsservicejni")   ← libevsservicejni.so
      → /system/lib64/libevsservicejni.so
```

### 2.2 Android Car 库 (`com.android.car:lib:1.0.6`)

**来源**：`coolwell:common` Maven 仓库  
**内容**：AOSP `android.car` 框架 API 的编译时存根

**用途**：
- `useLibrary("android.car")` — Gradle 编译时引入系统框架类
- 提供 `android.car.*` API 的编译支持

### 2.3 AOSP Framework (`com.android:framework:1.0.0`)

**来源**：`coolwell:common` Maven 仓库  
**集成方式**：**bootclasspath 注入**

```
-Xbootclasspath/p:/path/to/framework-1.0.0.jar
```

**用途**：
- 提供隐藏 API（如 HardwareBuffer, Evs 相关内部类）
- 在编译时以 `bootclasspath` 方式加载，优先级高于 SDK 默认 framework

---

## 3. EvsSDK 集成方案

### 3.1 Gradle 依赖管理

由 `maven-repo-plugin`（`io.github.oxsource:maven-repo-plugin:1.0.3`）统一管理：

```kotlin
// 根 build.gradle.kts
val aosp_evs_lib: String by extra("com.android.car:evs")
val aosp_evs_lib_version: String by extra("1.0.7")
val aosp_car_lib: String by extra("com.android.car:lib")
val aosp_car_lib_version: String by extra("1.0.6")
val aosp_framework: String by extra("com.android:framework")
val aosp_framework_version: String by extra("1.0.0")
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("${rootProject.extra["aosp_evs_lib"]}:${rootProject.extra["aosp_evs_lib_version"]}")
    implementation("${rootProject.extra["aosp_car_lib"]}:${rootProject.extra["aosp_car_lib_version"]}")
}
```

### 3.2 bootclasspath 注入

```kotlin
// 根 build.gradle.kts — afterEvaluate 中执行
val fwk = listOf(aosp_framework, aosp_framework_version)
val path = handle.invokeMethod("jar", fwk) as? String ?: ""
handle.invokeMethod("setXbootclasspath", listOf(project, listOf(path)))
```

编译时输出：
```
Jars invoke: `-Xbootclasspath/p:/Users/simon/.m2repo/contents/coolwell/van233_snap/.../framework-1.0.0.jar`
```

### 3.3 编译配置

```kotlin
android {
    compileSdk = 31   // 编译 SDK 版本可以高于目标平台

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // 引入 android.car 系统 API
    useLibrary("android.car")
}
```

### 3.4 Maven Repo 同步流程

```
gradle build
  → maven-repo-plugin 初始化
    → 读取 manifest (gitee.com/oxsource_mavenrepo.manifest_raw_main_manifest.xml)
    → 收集 namespace (coolwell:van233_snap, coolwell:common)
    → git pull 同步 Maven 仓库 (ssh://git@10.14.101.201:9005/skylink2.0/skylink_maven.git)
    → 转换成本地文件 Maven (file:///Users/simon/.m2repo/contents/coolwell/*/)
    → 注入依赖
```

---

## 4. Framework 集成方案

### 4.1 系统 API 使用

```java
// useLibrary("android.car") 提供的系统 API
import com.android.car.evs.CameraIds;
import com.android.car.evs.EvsCameraController;
import com.android.car.evs.EvsBufferDesc;
import com.android.car.evs.EvsFrameRate;
import com.android.car.evs.EvsHalWrapper;

// Android 框架 API（通过 bootclasspath 注入）
import android.hardware.HardwareBuffer;
```

### 4.2 运行时约束

由于依赖 `libevsservicejni.so`（位于 `/system/lib64/`），应用必须：

| 条件 | 原因 |
|------|------|
| **平台签名** | 需要 `android.uid.system` sharedUserId |
| **系统应用** | 需部署到 `/system/app/` 或 `/system/priv-app/` |
| **系统权限** | 访问 HAL 层需要系统级 SELinux 策略 |

### 4.3 签名配置

```kotlin
signingConfigs {
    create("platform") {
        storeFile = file("keystore/skytv/platform.keystore")
        keyAlias = "platform"
        keyPassword = "android"
        storePassword = "android"
    }
}

buildTypes {
    debug {
        signingConfig = signingConfigs.getByName("platform")
    }
    release {
        signingConfig = signingConfigs.getByName("platform")
    }
}
```

**Manifest 声明**：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:sharedUserId="android.uid.system"
    tools:ignore="Deprecated">
```

---

## 5. 系统应用部署方案

### 5.1 方案对比

| 方式 | 适用场景 | 优点 | 缺点 |
|------|----------|------|------|
| **`adb install`** | 开发调试 | 快速迭代 | 需要 adb，不能访问系统库 |
| **push 到 `/system/app/`** | 集成部署 | 可访问系统库 | 需要 root 或 remount |
| **AOSP 源码编译** | 正式发布 | 原生系统应用 | 需要完整 AOSP 构建环境 |

开发阶段使用 `adb install`（平台签名）验证 UI 和逻辑；
集成联调时需要 **push 到 `/system/app/`** 才能正常加载 `libevsservicejni.so`。

### 5.2 手动部署到 /system/app/

```bash
# 1. 编译
./gradlew assembleDebug

# 2. remount system 分区（需要 root 设备）
adb root
adb remount

# 3. 创建目标目录并推送 APK
adb shell mkdir -p /system/app/FaceIDPreview/
adb push app/build/outputs/apk/debug/app-debug.apk /system/app/FaceIDPreview/FaceIDPreview.apk

# 4. 设置权限
adb shell chmod 644 /system/app/FaceIDPreview/FaceIDPreview.apk
adb shell chown root:root /system/app/FaceIDPreview/FaceIDPreview.apk

# 5. 重启
adb reboot

# 6. 验证
adb shell dumpsys package com.skyworth.faceid | grep installer
```

### 5.3 使用 Makefile 简化（参见项目根目录 Makefile）

```bash
# 一键编译 + 部署到 /system/app/ + 重启
make push-system

# 查看实时日志
make log

# 监控 GPU
make gpu
```

---

## 6. 常见问题

### Q1: `UnsatisfiedLinkError: dlopen failed: library libevsservicejni.so not accessible`

**原因**：应用未以系统 UID 运行，被 linker namespace 限制。
**解决**：确保使用平台签名 + `android:sharedUserId="android.uid.system"`，
且 APK 部署在 `/system/app/` 目录下。

### Q2: `SecurityException: Permission Denial`

**原因**：系统 API 需要系统级权限。
**解决**：使用平台签名，manifest 中声明 `sharedUserId="android.uid.system"`。

### Q3: `Failure [DELETE_FAILED_INTERNAL_ERROR]`

**原因**：adb install 更新时签名不一致或已安装包为系统应用。
**解决**：先卸载旧包：`adb uninstall com.skyworth.faceid`。

### Q4: Maven 仓库同步失败

**原因**：无法连接内部 git 服务器 `10.14.101.201:9005`。
**解决**：检查 VPN/内网连接，确认 SSH 密钥已授权。

### Q5: 编译时 `Jars invoke: -Xbootclasspath/p` 警告

**原因**：bootclasspath 覆盖了部分标准 SDK 类。
**解决**：可以忽略，这是正常的集成方式。

---

## 附录 A: 测试 Stub 实现

在 `app/src/test/java/com/android/car/evs/` 下提供了 EvsSDK 的 Mock 实现，
用于 Robolectric 单元测试。

| 测试 Stub | 用途 |
|-----------|------|
| `CameraIds.java` | 摄像头 ID 常量模拟 |
| `EvsBufferDesc.java` | Buffer 描述模拟 |
| `EvsBufferProvider.java` | Buffer 提供接口模拟 |
| `EvsCameraController.java` | 摄像头控制器模拟 |
| `EvsFrameRate.java` | 帧率统计模拟 |
| `EvsHalWrapper.java` | HAL 层接口模拟 |

## 附录 B: 相关文件

| 文件 | 说明 |
|------|------|
| `build.gradle.kts` | 根构建配置，Maven repo 插件 + bootclasspath 注入 |
| `app/build.gradle.kts` | 应用构建配置，签名 + 依赖管理 |
| `app/src/main/AndroidManifest.xml` | 系统应用声明 |
| `keystore/skytv/platform.keystore` | 平台签名证书 |
| `Makefile` | 开发便捷命令 |
