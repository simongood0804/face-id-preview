# Face ID Preview 项目规范文档

> 版本：v1.0  
> 适用项目：Face ID Preview (`com.skyworth.faceid`)  
> 基于：Google Java Style + AOSP 编码规范 + Conventional Commits

---

## 目录

1. [代码规范](#1-代码规范)
2. [Git 提交规范](#2-git-提交规范)
3. [Bugfix 规范](#3-bugfix-规范)
4. [项目结构规范](#4-项目结构规范)
5. [命名规范速查表](#5-命名规范速查表)

---

## 1. 代码规范

### 1.1 语言与版本

- **语言**：Java 8（兼容 Android 10 API 29 平台）
- **源文件编码**：UTF-8
- **缩进**：4 空格（不使用 Tab）

### 1.2 文件组织

每个 Java 源文件按以下顺序组织：

```java
// 1. 许可证 / 版权声明（如适用）
/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

// 2. package 声明
package com.skyworth.faceid.pipeline;

// 3. import 语句（按 Google 风格分组排序）
//    - 静态导入在上
//    - Android 框架类
//    - 第三方库
//    - 项目内部类
import android.util.Log;

import com.android.car.evs.EvsBufferDesc;

import com.skyworth.faceid.algorithm.IFaceIDAlgorithm;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// 4. 类声明（一个文件一个顶级类）
/**
 * 类的 Javadoc 描述
 */
public class FramePipeline {
    // ...
}
```

### 1.3 命名规范

#### 1.3.1 包命名

- **全小写**，连字符连接（`com.skyworth.faceid.*`）
- 遵循 `com.skyworth.faceid.<module>` 模式

| 模块 | 包名 |
|------|------|
| 算法接口 | `com.skyworth.faceid.algorithm` |
| 摄像头管理 | `com.skyworth.faceid.camera` |
| 流水线 | `com.skyworth.faceid.pipeline` |
| 渲染 | `com.skyworth.faceid.render` |
| UI | `com.skyworth.faceid.ui` |

#### 1.3.2 类命名

- **帕斯卡命名法**（`PascalCase`）
- 接口：`IFaceIDAlgorithm`（`I` 前缀表示接口）
- 抽象类：`BasePreviewRenderer`
- 实现类：`FaceIDAlgorithmImpl`
- 测试类：`FramePipelineTest`

#### 1.3.3 方法命名

- **小驼峰**（`camelCase`）
- 动词开头，表达清晰意图
- 遵循 Android AOSP 前缀约定：

| 前缀 | 用途 | 示例 |
|------|------|------|
| `init*` | 初始化方法 | `initViews()` |
| `start*` / `stop*` | 启动/停止 | `startPreview()`, `stopPreview()` |
| `open*` / `close*` | 打开/关闭资源 | `openCamera()`, `closeCamera()` |
| `get*` / `set*` | Getter/Setter | `getMaxPendingFrames()` |
| `is*` / `has*` | 布尔判断 | `isRunning()`, `hasPendingFrames()` |
| `on*` | 事件回调 | `onFrameAvailable()`, `onHalDeath()` |
| `create*` / `destroy*` | 创建/销毁 | `createPipeline()` |
| `register*` / `unregister*` | 注册/注销 | `registerBuffer()` |
| `handle*` | 事件处理 | `handleFrameTimeout()` |

#### 1.3.4 常量命名

- **大写蛇形**（`UPPER_SNAKE_CASE`）
- `static final` 基本类型或不可变对象

```java
public static final int DEFAULT_MAX_PENDING_FRAMES = 3;
public static final long DEFAULT_PROCESS_TIMEOUT_MS = 500;
private static final String TAG = "FramePipeline";
```

#### 1.3.5 成员变量命名

- **非静态**：`m` 前缀 + 小驼峰（AOSP 风格）
- **静态**：`s` 前缀 + 小驼峰

```java
private final CameraManager mCameraManager;       // 非静态成员
private static final String TAG = "FramePipeline"; // 常量
private static Paint sDefaultPaint;                // 静态成员
```

#### 1.3.6 参数与局部变量

- **小驼峰**，无前缀
- 避免单字母命名（循环变量 `i`, `j` 除外）

```java
public void setMaxPendingFrames(int maxPendingFrames) {
    this.maxPendingFrames = maxPendingFrames;
}

// 好的
for (int i = 0; i < count; i++) { ... }

// 不好的
for (int index = 0; index < count; index++) { ... }  // 过长
```

### 1.4 Javadoc 规范

#### 1.4.1 类级 Javadoc

每个**公开类**必须有 Javadoc，说明类的职责和线程安全性：

```java
/**
 * 帧处理流水线 —— 核心协调层
 *
 * 三线程模型：
 *   Capture Thread → Process Thread → Render Thread
 *
 * 职责：
 *   1. 从 CameraManager 获取原始帧
 *   2. 调用 IFaceIDAlgorithm 处理帧
 *   3. 将处理结果交给 PreviewRenderer 渲染
 *   4. 通过 BufferManager 安全归还 Buffer
 *
 * 线程安全：状态通过 AtomicBoolean 保护，内部队列为线程安全
 */
public class FramePipeline {
```

#### 1.4.2 方法级 Javadoc

所有 `public` / `protected` 方法必须有 Javadoc：

```java
/**
 * 注册一个新获取的 Buffer
 *
 * @param buffer 从 EvsCameraController.getNewFrame() 获取的 Buffer，
 *               不能为 null
 * @throws IllegalArgumentException 如果 buffer 为 null
 */
public void registerBuffer(EvsBufferDesc buffer) {
```

#### 1.4.3 行内注释

- 解释「为什么」而不是「是什么」
- 使用 `//` 单行注释
- 复杂逻辑用段落注释说明

```java
// 跳过的帧直接渲染原始数据，不进入算法处理
task.processed = true;
task.result = new IFaceIDAlgorithm.FaceIDResult(
        "", 0f, null, frameData, null);
```

### 1.5 代码风格

#### 1.5.1 大括号

- **K&R 风格**（行首大括号）
- 单行代码块也必须使用大括号

```java
// 正确
if (buffer == null) {
    return null;
}

// 错误
if (buffer == null) return null;  // 缺少大括号（除非同一行）
```

#### 1.5.2 空行

- 方法之间：1 空行
- 方法内逻辑块之间：1 空行（可选）
- 类中成员变量与方法之间：1 空行
- 文件末尾：1 空行

#### 1.5.3 行长度

- **最大 100 列**
- 超过时换行，新行缩进 8 空格（相对方法参数起始位置）

```java
// 换行示例
mBufferManager.recycleBuffer(entry.buffer);
Log.d(TAG, "Buffer 已归还: id=" + bufferId + ", 耗时="
        + (System.currentTimeMillis() - entry.acquireTimeMs) + "ms");
```

#### 1.5.4 异常处理

- 不要捕获 `Exception` 或 `Throwable`（除非顶级线程边界）
- 捕获具体异常类型
- 不要忽略异常（空的 catch 块必须注释原因）

```java
// 正确
try {
    mCameraController.stopCamera();
} catch (IllegalStateException e) {
    Log.w(TAG, "摄像头未启动，无需停止", e);
}

// 错误（顶级线程 Runnable 除外）
try {
    // ...
} catch (Exception e) {  // 太宽泛
    // 空的 catch
}
```

### 1.6 Android 特有规范

#### 1.6.1 Log 使用

| 级别 | 方法 | 用途 |
|------|------|------|
| Error | `Log.e()` | 不可恢复的错误 |
| Warning | `Log.w()` | 可恢复的异常或意外状态 |
| Info | `Log.i()` | 生命周期事件、状态变更 |
| Debug | `Log.d()` | 调试信息（发布时会被移除） |
| Verbose | `Log.v()` | 详细日志（仅开发环境） |

- TAG 统一使用类名：`private static final String TAG = "ClassName";`

#### 1.6.2 线程安全标注

- 使用 AOSP 标准的 `@ThreadSafe` / `@MainThread` / `@WorkerThread` 注释
- 或在 Javadoc 中标注

```java
/**
 * 渲染循环 —— 线程体（Worker Thread）
 * 从处理结果队列取出已完成的任务，交给 PreviewRenderer 渲染。
 */
private void renderLoop() { ... }
```

#### 1.6.3 资源管理

- 使用 `try-finally` 确保资源释放
- UI 更新必须通过 `runOnUiThread()` 切回主线程

---

## 2. Git 提交规范

### 2.1 分支模型

```
main         ─── 生产就绪代码
  │
  ├── develop ─── 开发主线（合并功能分支）
  │
  ├── feature/xxx ─── 功能开发分支
  ├── bugfix/xxx  ─── Bug 修复分支
  └── release/x.x ─── 发布准备分支
```

#### 分支命名

| 类型 | 格式 | 示例 |
|------|------|------|
| 功能分支 | `feature/<简短描述>` | `feature/add-faceid-algorithm` |
| Bugfix | `bugfix/<issue-id>-<描述>` | `bugfix/42-fix-buffer-leak` |
| 发布 | `release/<版本号>` | `release/1.1.0` |
| 热修复 | `hotfix/<issue-id>-<描述>` | `hotfix/45-crash-on-startup` |

### 2.2 提交消息格式

遵循 **Conventional Commits 2.1** 规范：

```
<type>(<scope>): <简短描述>

<详细描述（可选）>

<尾部（可选，用于关联 issue）>
```

#### Type 类型

| 类型 | 含义 | 示例 |
|------|------|------|
| `feat` | 新功能 | `feat(camera): add DMS camera support` |
| `fix` | Bug 修复 | `fix(pipeline): fix buffer leak on timeout` |
| `docs` | 文档变更 | `docs: add coding standards document` |
| `style` | 代码风格（不影响逻辑） | `style: reformat code per Google style` |
| `refactor` | 代码重构 | `refactor(algorithm): extract face rect drawing` |
| `test` | 测试相关 | `test(pipeline): add buffer lifecycle tests` |
| `chore` | 构建/工具变更 | `chore: update gradle to 7.2.2` |
| `perf` | 性能优化 | `perf(render): reduce bitmap allocations` |

#### Scope 范围

| Scope | 模块 |
|-------|------|
| `camera` | CameraManager |
| `pipeline` | FramePipeline, BufferManager, PipelineConfig |
| `algorithm` | IFaceIDAlgorithm, FaceIDAlgorithmImpl |
| `render` | PreviewRenderer |
| `ui` | PreviewActivity, layout |
| `i18n` | string resources, translations |
| `build` | Gradle 配置 |
| `docs` | 文档 |
| `test` | 测试代码 |

#### 提交示例

```
feat(camera): add DMS camera as default input source

- Change default camera ID from CameraIds.RVC to CameraIds.DMS
- DMS camera provides driver-facing view for face detection

Closes #12
```

```
fix(pipeline): prevent buffer leak on pipeline shutdown

- Ensure all pending buffers are recycled when pipeline stops
- Add timeout-based forced recycling mechanism

Fixes #8
```

### 2.3 提交粒度

- **原子提交**：每次提交只做一件事
- **关联 issue**：在尾部使用 `Closes #N` 或 `Fixes #N`
- **避免**：`fix stuff`、`update`、`changes` 等模糊消息

---

## 3. Bugfix 规范

### 3.1 Bug 报告模板

在 GitHub Issues 中按以下模板提交：

```markdown
## Bug 描述
[清晰简洁地描述 bug]

## 复现步骤
1. 打开应用
2. 点击「开始预览」
3. 观察到 [错误行为]

## 预期行为
[描述应该发生什么]

## 实际行为
[描述实际发生了什么]

## 日志 / 截图
```
[粘贴关键 logcat 日志]
```

## 环境
- 设备: [设备型号]
- Android 版本: [例如 10 API 29]
- 应用版本: [例如 1.0.0]

## 附加信息
[可选]
```

### 3.2 Bugfix 分支与提交

```
# 1. 从 develop 创建 bugfix 分支
git checkout develop
git checkout -b bugfix/42-buffer-leak-on-stop

# 2. 修复代码

# 3. 提交修复
git add .
git commit -m "fix(pipeline): prevent buffer leak on shutdown

Ensure all pending buffers are recycled when pipeline stops
by draining the processed queue before shutting down the
timeout monitor.

Fixes #42"

# 4. 合并回 develop
git checkout develop
git merge bugfix/42-buffer-leak-on-stop
```

### 3.3 Bugfix 优先级

| 优先级 | 标签 | 响应时间 | 修复时间 |
|--------|------|----------|----------|
| P0 | `critical` | 立即 | 24 小时内 |
| P1 | `high` | 4 小时内 | 3 天内 |
| P2 | `medium` | 24 小时内 | 1 周内 |
| P3 | `low` | 1 周内 | 下个迭代 |

### 3.4 回归测试

- 每个 bugfix 必须附带**单元测试**或**集成测试**，防止回归
- 在 PR 描述中说明测试覆盖

---

## 4. 项目结构规范

```
face-id-preview/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/skyworth/faceid/
│       │   │   ├── algorithm/          # 算法抽象 + 实现
│       │   │   │   ├── IFaceIDAlgorithm.java
│       │   │   │   └── FaceIDAlgorithmImpl.java
│       │   │   ├── camera/             # 摄像头管理
│       │   │   │   └── CameraManager.java
│       │   │   ├── pipeline/           # 帧处理流水线
│       │   │   │   ├── BufferManager.java
│       │   │   │   ├── FramePipeline.java
│       │   │   │   └── PipelineConfig.java
│       │   │   ├── render/             # 预览渲染
│       │   │   │   └── PreviewRenderer.java
│       │   │   └── ui/                 # 界面
│       │   │       └── PreviewActivity.java
│       │   └── res/
│       │       ├── layout/
│       │       ├── values/
│       │       └── values-zh/
│       └── test/
│           ├── java/com/android/car/evs/  # EvsSDK Stubs
│           └── java/com/skyworth/faceid/   # 单元测试
├── docs/
│   ├── STANDARDS.md
│   └── 设计方案.md
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## 5. 命名规范速查表

| 类别 | 规范 | 示例 |
|------|------|------|
| **包** | 全小写 | `com.skyworth.faceid.pipeline` |
| **类** | PascalCase | `FramePipeline` |
| **接口** | `I` 前缀 + PascalCase | `IFaceIDAlgorithm` |
| **方法** | camelCase | `getNewFrame()` |
| **常量** | UPPER_SNAKE_CASE | `DEFAULT_MAX_PENDING_FRAMES` |
| **非静态成员** | `m` + camelCase | `mCameraManager` |
| **静态成员** | `s` + camelCase | `sDefaultPaint` |
| **局部变量** | camelCase | `frameData` |
| **方法参数** | camelCase | `maxPendingFrames` |
| **枚举** | PascalCase（值 UPPER_SNAKE） | `BufferState.QUEUED` |
| **资源 ID** | snake_case | `preview_surface` |
| **String key** | snake_case | `btn_start_preview` |
| **Log tag** | 类名 | `"FramePipeline"` |
