# 提案：保留最后一帧 buffer 解决黑屏

> 提案编号：FACEP-002  
> 创建日期：2026-06-30  
> 状态：已实现

---

## 1. 动机

`EvsGL20CameraRenderer` 在 `getNewFrame()` 返回的 `HardwareBuffer` 已被关闭时，渲染器无有效数据可画，屏幕变黑。

根因：EVS 系统在 `returnBuffers()` 中关闭了仍在渲染器使用的旧 buffer。

## 2. 方案

### 2.1 总体思路

在 `returnBuffer()` 中**跳过关闭最后一帧（`mLastFrame`）的 HardwareBuffer**，使其持续保持有效。当 `getNewFrame()` 拿不到新 buffer 时，返回 `mLastFrame`（buffer 仍有效）。

```
returnBuffer() 调用时：
  buffer === mLastFrame？→ 跳过 close()，保留 buffer 存活
  buffer ≠ mLastFrame？ → 正常 close()

getNewFrame() 返回时：
  descriptor 有效 → 返回 descriptor
  descriptor 无效（closed）→ 返回 mLastFrame（buffer 存活）
```

### 2.2 修改细节

#### `FaceIDCameraController.kt`

```kotlin
/** 上一帧成功的描述符（无新帧时复用）。 */
private var mLastFrame: EvsBufferDesc? = null
```

**`getNewFrame()` — 无新帧时返回缓存：**

```kotlin
return descriptor ?: mLastFrame
```

**`returnBuffer()` — 跳过关闭最后一帧：**

```kotlin
val isLastFrame = mLastFrame != null && value.id == mLastFrame!!.id
if (!isLastFrame) {
    buffer.close()  // 非最后一帧才关闭
}
```

**`resetBuffers()` — 停止时清空：**

```kotlin
mLastFrame = null
```

### 2.3 诊断日志

```kotlin
// descriptor 失效时输出日志，区分是 descriptor 还是 mLastFrame 的问题
Log.w(TAG, "descriptor closed, fallback to mLastFrame")
if (mLastFrame also invalid) Log.w(TAG, "mLastFrame also invalid → black screen")
```

## 3. 数据流

```
正常帧：
  getNewFrame() → 出队新帧 → 更新 mLastFrame → 返回 descriptor
  
拿不到新帧时：
  getNewFrame() → descriptor 仍有效 → 返回 descriptor（上一帧）

descriptor 已关闭时：
  getNewFrame() → descriptor 无效 → 返回 mLastFrame（buffer 存活）
```

## 4. 影响分析

### 4.1 性能

无额外开销。不新增 memcpy，不新增对象分配。

### 4.2 兼容性

不依赖 `EvsBufferDesc` 构造器。不修改渲染器。

### 4.3 风险

- `doneWithFrame()` 仍然调用，EVS 系统将 buffer 标记为可用。但由于我们跳过 `close()`，Java 层 HardwareBuffer 对象持有 native 引用计数 +1，buffer 内存不会被释放。
- EVS HAL 理论上可能复用该 buffer 的内存（重叠写入），但实测 6 buffer 池 + 保留 1 帧 = 5 buffer 仍够正常工作。
- 极端场景（摄像头重连、分辨率切换）会通过 `resetBuffers()` 清空 `mLastFrame`。
