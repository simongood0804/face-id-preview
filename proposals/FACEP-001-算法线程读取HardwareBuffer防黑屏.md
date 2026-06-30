# 提案：延迟回收 + 黑帧熔断 —— 解决 EVS 渲染黑屏

> 提案编号：FACEP-001  
> 创建日期：2026-06-30  
> 状态：已实现

---

## 1. 问题分析

### 1.1 黑屏表现

EVS 摄像头在运行过程中间歇性出现渲染黑屏（GLSurfaceView 全黑，其他 UI 正常）。原始 EVS 渲染（未接入 FaceID 算法时）也存在同样问题，说明是 EVS 平台层的问题，不是 FaceID 代码引入的。

### 1.2 现有兜底机制的缺陷

当前 `getNewFrame()` 的逻辑：

```
queue 有帧:
  ├── dequeue → desc (新帧)
  ├── 触发 onFrameData 回调 (提交算法线程)
  ├── EvsBufferDesc.recycle(descriptor)  ← 立即回收上一帧
  ├── descriptor = desc                   ← 直接推进到新帧
  ├── mLastFrame = desc
  └── return descriptor

queue 无帧:
  ├── 检查 descriptor 是否失效
  ├── 若失效 → return mLastFrame
  └── 未失效 → return descriptor
```

**缺陷：** 只要 EVS 能出帧（即使帧内容是全黑/buffer 已 closed），`getNewFrame()` 都会无条件推进——回收上一帧、切换到新帧、返回新帧。渲染器拿到这个无效帧后绘制出全黑画面，而上一个有效帧已经被回收了。

### 1.3 EVS HAL 是否有强制回收？

根据之前对接经验，**EVS HAL 没有强制回收机制**。问题出在应用层的帧推进逻辑——见到帧就推进，不论好坏。

## 2. 方案：延迟回收 + 帧内容有效性检测

### 2.1 核心思路

**"绘制下一帧时才归还上一帧"**——只有当新帧确认有效时，才回收上一帧并推进；新帧无效时直接丢弃，保持上一帧不变。

```
修改前：
  新帧到来 → 回收旧帧 → 切换到新帧 → renderer 绘制新帧
                                                              ← 新帧若无效，旧帧已丢，画面黑

修改后：
  新帧到来 → 检测新帧有效性
    ├── 有效 → 回收旧帧 → 切换到新帧 → renderer 绘制新帧 ✓
    └── 无效 → 丢弃新帧（回收它）→ 保持旧帧 → return 旧帧  ✓ 画面不变
```

### 2.2 帧有效性检测

有效性分两级：

| 级别 | 检测内容 | 位置 | 开销 |
|---|---|---|---|
| Level 1 | `buffer.isClosed` | `getNewFrame()` Kotlin | ~0ns |
| Level 2 | 像素内容是否全黑 | `nativeReadHardwareBuffer` JNI | ~µs 级 |

**Level 1** 是必要的——buffer 已 closed 说明 EVS 已收回缓冲区。

**Level 2** 是核心——全黑帧的 buffer 结构完好（非 null、非 closed），但像素全是 0。在 JNI 的 `nativeReadHardwareBuffer` 中，`AHardwareBuffer_lock` 已经读取了像素数据，只需在 memcpy 后采样中心区域几个 Y 值即可判断是否全黑。此判断复用已有的 CPU 读取路径，无额外开销峰值（正常帧不受影响）。

### 2.3 修改后的 getNewFrame() 流程

```
getNewFrame():
  ├── dequeue → desc (新帧)
  ├── 检查 desc.hardwareBuffer 是否 closed
  │     └── closed → EvsBufferDesc.recycle(desc), break (丢弃新帧)
  ├── 帧尺寸变化 → 回调 onFrameSizeChanged
  ├── 跳帧计数器 → 触发 onFrameData (算法线程)
  │     └── 算法线程: nativeReadHardwareBuffer
  │           ├── AHB_lock + memcpy  (已有)
  │           ├── 采样中心 3×3 区域 Y 值
  │           │     ├── 全 ≤10 → 标记为黑帧, return null
  │           │     └── 有 >10 → return byte[] (正常)
  │           ├── AHB_unlock + release
  │           └── processFrame(byte[])
  ├── 检查算法线程回调是否标记了黑帧
  │     └── 黑帧 → EvsBufferDesc.recycle(desc), break (丢弃新帧)
  ├── EvsBufferDesc.recycle(descriptor)  ← 只有到这里才回收上一帧
  ├── descriptor = desc
  ├── mLastFrame = desc
  └── return descriptor (渲染器绘制)

fallback (队列无新帧或新帧被丢弃):
  ├── descriptor 有效 → return descriptor (上一帧)
  └── descriptor 失效 → return mLastFrame (上上一帧)
```

### 2.4 returnBuffer() 的配合

当前 `returnBuffer()` 已经有了保护逻辑——只关闭非 `mLastFrame` 的 buffer：

```kotlin
val isLastFrame = mLastFrame != null && value.id == mLastFrame!!.id
if (!isLastFrame) { buffer.close() }
```

这部分无需修改，天然保护了旧帧 buffer 不被关闭。

## 3. 修改范围

| 文件 | 修改 | 说明 |
|---|---|---|
| `FaceIDCameraController.kt` | `getNewFrame()` 推进逻辑 | 核心：新帧无效时不回收旧帧，不推进 |
| `faceid_jni.cpp` | `nativeReadHardwareBuffer` 末尾 | 加黑帧检测，全黑时 return null（~10 行） |
| `FrameProcessor.kt` | 处理 null 返回值 | 已有 `if (data==null) skip`，无需修改 |
| `PreviewActivity.kt` | 无需修改 | — |

## 4. 数据流对比

```
修改前（任何帧都推进，无效帧导致黑屏）：

onFrameEvent → queue
  ↓
getNewFrame():
  ├── dequeue desc
  ├── recycle(descriptor)   ← 不管好坏先回收旧帧
  ├── descriptor = desc     ← 推进到新帧
  └── return desc (可能无效)
        ↓
EvsGL20CameraRenderer 绘制 → 若帧无效 → 黑屏


修改后（有效才推进，无效保留旧帧）：

onFrameEvent → queue
  ↓
getNewFrame():
  ├── dequeue desc
  ├── 检查 desc 有效性
  │     └── 无效 → recycle(desc), 不修改 descriptor, break
  ├── onFrameData(hw)
  │     └── nativeReadHardwareBuffer:
  │           ├── lock + memcpy
  │           ├── 检测黑帧 → 黑则 return null
  │           ├── unlock + release
  │           └── FrameProcessor: data==null → skip
  ├── 检测到黑帧 → recycle(desc), break
  ├── recycle(descriptor)   ← 此时才回收旧帧
  ├── descriptor = desc     ← 推进
  └── return desc (有效)
        ↓
EvsGL20CameraRenderer 绘制 → 正常画面
```

## 5. 风险与边界

### 5.1 长时间无效帧

如果 EVS 长时间（如数秒）持续输出无效帧，`descriptor` 一直不会更新，`mLastFrame` 也一直不变。渲染器持续绘制同一帧画面，表现为画面冻结——**冻结比黑屏对用户友好**。EVS 恢复后，下一帧有效时立即推进。

### 5.2 帧尺寸变化

如果无效帧伴随着尺寸变化（极少见），`onFrameSizeChanged` 会在有效性检测之前触发。由于随后会丢弃该帧，尺寸回调会短暂触发但视觉无影响——渲染器未切换到该帧。

### 5.3 黑帧误判

黑帧阈值（Y=10）基于 IR 摄像头的像素范围（0~255 灰度）。正常画面中心区域 Y 值远大于 10（即使光照差也在 30+）。误判风险极低。

### 5.4 性能

- 黑帧检测：在已有 memcpy 后多扫描 9 个像素（中心 3×3），开销可忽略
- 无效帧丢弃：不触发算法推理（`processFrame` 不调用），省去 ~70ms DSP 时长
- 无额外 buffer lock/unlock

## 6. 与现有方案的关系

此方案替代之前的「方案A（GL 线程读）」和「方案B（算法线程读）」。前两者试图通过移动读取位置来解决黑屏，但 EVS 黑屏是平台层的帧内容异常，不是读取竞争导致的。**只有「无效帧不推进」才能从根本上阻止坏帧进入渲染管线。**
