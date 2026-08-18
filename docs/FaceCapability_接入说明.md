# FaceCapability 算法能力服务接入说明

> 适用版本：FACEP-011（算法能力服务化）  
> 用途：供**其他 App 模块**按需订阅/使用 `:algo` 算法进程的 DMS 算法能力。

---

## 1. 概述

`face-id-preview` 的 `:algo` 进程将算法能力（人脸检测、头姿、视线、分心、车速）做成**对外能力服务**。外部 App 模块通过注册 + 初始化即可使用，并支持**按模块订阅/发布**：需要哪类算法输出就订阅哪类，不消费全部。

**架构**：

```
外部 App 模块 / 渲染进程                算法进程(:algo)                     共享内存 ShmQueue
─────────────────────                ─────────────────                  ─────────────────
FaceCapabilityClient  ◄──Binder────  AlgoEngineService  ──publish──▶  按模块 topic 独立发布
   register / init                   · 推理整合                        · FACE_DETECT
   subscribe / obtain                · 按订阅表发布                    · DISTRACTION
                                                                       · VEHICLE_SPEED …
```

**核心概念**：
- **能力模块（`CapabilityModule`）**：算法输出的可独立订阅单元。
- **订阅（subscribe）**：消费者声明需要哪些模块。
- **按需发布**：算法进程只发布**至少有一个消费者订阅**的模块，节省共享内存带宽。

---

## 2. 快速接入（三步）

依赖 SDK：`com.skyworth.faceid.shmtest.FaceCapabilityClient`。

```kotlin
import com.skyworth.faceid.shmtest.CapabilityModule
import com.skyworth.faceid.shmtest.FaceCapabilityClient

class MyModule(context: Context) {
    private val client = FaceCapabilityClient(context)

    fun start() {
        // 1. 连接算法能力服务
        if (!client.connect()) {
            // 处理连接失败
            return
        }
        // 2. 注册 + 初始化算法
        if (client.init() != 0) {
            // 处理初始化失败
            return
        }
        // 3. 订阅所需能力模块（可多次累加）
        client.subscribe(setOf(
            CapabilityModule.FACE_DETECT,
            CapabilityModule.VEHICLE_SPEED
        ))
    }

    // 在回调/定时线程中轮询取数
    fun poll() {
        val face = client.obtainFaceBox()      // 最新人脸框（未订阅返回 null）
        val speed = client.obtainSpeed()       // 最新车速（未订阅返回 null）
        // …使用 face / speed
    }

    fun stop() {
        client.disconnect()  // 注销消费者 + 断开服务
    }
}
```

> **注意**：`obtain*` 需在**非主线程**调用（内部会 attach 共享内存并短暂读取）。

---

## 3. 能力模块清单

| 模块 | topic id | 订阅后取数方法 | 输出字段 | 内部依赖 |
|------|----------|----------------|----------|----------|
| `FACE_DETECT` | 0x01 | `obtainFaceBox()` | 帧尺寸、人脸框 left/top/right/bottom、置信度、zoneId、5 关键点 | 无 |
| `HEADPOSE` | 0x02 | `obtainHeadpose()` | pitch / yaw / roll | FACE_DETECT |
| `GAZE` | 0x03 | `obtainGaze()` | valid / yaw / pitch / calibrated | FACE_DETECT |
| `DISTRACTION` | 0x04 | `obtainDistract()` | distracted、band(fast/slow)、thresholdMs、score/hpScore/gazeScore | FACE_DETECT + VEHICLE_SPEED |
| `VEHICLE_SPEED` | 0x05 | `obtainSpeed()` | speedKmh（负数=无数据） | 无 |

> 模块间依赖（如 `DISTRACTION` 依赖车速）由算法进程内部解析，**对外透明**——订阅 `DISTRACTION` 时无需关心其依赖。

---

## 4. 接口定义

底层为手写 Binder（`AlgoEngineBridge`），`FaceCapabilityClient` 已封装。若需直接调用：

| 方法 | 说明 | 返回 |
|------|------|------|
| `register(packageName, token)` | 注册为消费者；`token` 为客户端存活 token（服务端 linkToDeath 监听进程死亡自动注销） | clientId（≥0），失败为负错误码 |
| `unregister(clientId)` | 注销消费者 | - |
| `init(modelDir)` | 初始化算法（幂等） | 0 成功；`ERR_NOT_INITIALIZED=-4` |
| `subscribe(clientId, moduleIds)` | 订阅模块（topic 数组） | 0 成功；`ERR_CLIENT_INVALID=-1`、`ERR_INVALID_MODULE=-3` |
| `unsubscribe(clientId, moduleIds)` | 退订模块 | 0 成功 |
| `getSharedMemory()` | 获取结果共享内存 | `SharedMemory` |
| `setDumpPath(path)` | 下发算法 dump 路径 | - |

**错误码**：
```
ERR_OK               =  0   成功
ERR_CLIENT_INVALID   = -1   消费者 id 无效
ERR_CLIENT_FULL      = -2   消费者数已达上限(4)
ERR_INVALID_MODULE   = -3   包含非法模块 topic
ERR_NOT_INITIALIZED  = -4   算法未初始化
ERR_ACCESS_DENIED    = -5   调用方 uid 无权限（非同 uid）
```

---

## 5. 数据格式

各模块 payload 为**定长字节流**（`ByteBuffer` + MAGIC 头，校验失败返回 null）。格式：

```
[4B MAGIC][4B 长度][模块数据]
```

- `FACE_DETECT`：`frameW(4) frameH(4) hasFace(1) faceLeft/Top/Right/Bottom(16) confidence(4) zoneId(4)`
- `HEADPOSE`：`pitch(4) yaw(4) roll(4)`
- `GAZE`：`valid(4) yaw(4) pitch(4)`
- `DISTRACTION`：`distracted(1) band(1=slow/0=fast) thresholdMs(8)`
- `VEHICLE_SPEED`：`speedKmh(4)`

> 字段字节序为**本机字节序**（`ByteOrder.nativeOrder()`）。跨进程共享内存字节序一致，无需额外处理。

---

## 6. 接入约束与最佳实践

1. **权限**：`:algo` 服务已 `exported=true`，但**仅允许同 uid（`android.uid.system`）**的调用方注册——服务端 `register` 内部校验调用方 uid，非本 uid 返回 `ERR_ACCESS_DENIED`。外部模块需与算法服务同系统签名/同 sharedUserId。
2. **订阅数**：最大并发消费者 **4**（共享内存 reader 槽位上限）。超出返回 `ERR_CLIENT_FULL`。
3. **按需订阅**：只订阅实际使用的模块，减小共享内存写入量。
4. **取数线程**：`obtain*` 在非主线程调用，避免阻塞 UI。
5. **生命周期**：模块销毁时务必 `disconnect()` 注销消费者，释放 reader 槽位。
6. **缓存**：`obtain*` 返回**最近一帧**该模块数据；帧未更新时返回上一帧（不阻塞等待）。
7. **依赖透明**：订阅 `DISTRACTION` 时无需手动订阅 `VEHICLE_SPEED`/`FACE_DETECT`，算法进程保证其内部就绪。

---

## 7. 与整体 DMS 的关系

- 主进程 `PreviewActivity` 作为**内建消费者**，已订阅 `FACE_DETECT + DISTRACTION + VEHICLE_SPEED` 用于渲染绘制。
- 外部 App 模块可与主进程**并存订阅**不同模块，互不干扰（`ShmQueue` 多读者-单写者）。
- 若外部模块未订阅任何模块，算法进程**不发布**任何数据（整帧跳过）。
