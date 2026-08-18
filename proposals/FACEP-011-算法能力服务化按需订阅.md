# 提案：算法能力服务化——支持外部 App 模块按需订阅/发布

> 提案编号：FACEP-011  
> 创建日期：2026-08-18  
> 状态：已实现（A/B/C 三阶段全部落地）

---

## 1. 背景与动机

FACEP-010 已把 DMS 拆成**算法进程（`:algo`）**与**渲染进程（主进程）**双进程架构，两者唯一关联为共享内存（`ShmQueue`）分享算法结果。算法进程自包含：独立取帧、推理、信号整合、结果发布。

但当前算法能力的**对外接入方式存在局限**：

1. **接口固定且私有**：`AlgoEngineBridge`（`shmtest/AlgoEngineBridge.kt`）只暴露 `getSharedMemory/getState/start/stop/setDumpPath`，且 `AlgoEngineService` 为 `android:exported="false"`，**只有主进程 PreviewActivity 能绑定使用**，其他 App 模块无法接入。
2. **结果整体广播、不可按需**：算法进程把**全部**模块输出（人脸框、头姿、视线、分心、车速）打包进单一 `AlgorithmResult`（`shmtest/AlgorithmResult.kt`），经 `ShmQueue.publish` 以**单一 topic** 广播。消费端无论是否需要，都必须解析整个结构、占用全部带宽。
3. **无注册/订阅机制**：当前只有「一个绑定消费者（主进程）」，不存在「其他 App 模块注册、按需订阅某类算法输出」的标准通道。

**核心诉求**：把当前 DMS 算法能力（人脸检测、头姿、视线、分心、车速等）**服务化**，使其成为一个**可被其他 App 模块依赖的独立能力**——外部模块通过**接口文档注册服务、调用初始化**即可使用，并支持**按模块订阅/发布**（需要哪类输出就订阅哪类，不消费全部）。

**关键既有基础**：`ShmQueue.publish(topic, payload)` 已支持 **topic 机制**（`ShmMessage(topic, payload, sequence)`，见 `bus/ShmQueue.kt:227-285`），可直接作为「按模块订阅/发布」的底层载体，无需另造跨进程通道。

---

## 2. 目标与范围

### 2.1 目标
1. **能力服务化**：把 `:algo` 算法进程开放为**对外算法能力服务**（跨 App 可绑定），其他 App 模块通过接口文档注册 + 初始化即可使用。
2. **按需订阅/发布**：以 `CapabilityModule`（能力模块）为维度，算法进程按模块发布，外部模块按需订阅，只接收所需模块输出。
3. **标准接口**：提供 `IFaceCapabilityService`（服务侧）+ `FaceCapabilityClient`（SDK/客户端侧），形成对外接口文档。
4. **渲染进程同步改造（前置约束，方案 A）**：主进程 PreviewActivity 作为**内建订阅者**，完整接入新订阅机制（`register/init/subscribe/topic 过滤`）。渲染进程**职责不变**（仍为帧渲染、结果绘制、dump 处理），但**消费方式必须从「整体读 `AlgorithmResult`」改为「按模块订阅 + topic 过滤」**，否则算法进程改为按 topic 发布后，渲染进程将读不到数据，人脸框/分心/车速全部失效。**本项为强制前置依赖，不是可选优化。**

### 2.2 不在本次范围
- **不改算法推理逻辑**：`FaceIDAlgorithmImpl` 的检测/活体/头姿/视线推理不变，只调整结果发布方式。
- **不引入跨进程序列化框架**：继续用 `ShmQueue` 定长/字节序列化（`ShmMessageSerializer`）。
- **不处理多摄像头/多人脸策略**：沿用当前单 IR 相机、最多 10 人脸的既有逻辑。
- **安全鉴权**：对外开放的 Binder 服务需考虑签名/权限校验，本提案给出方案方向，具体权限模型按平台约束定。

---

## 3. 技术方案

### 3.1 能力模块（CapabilityModule）划分

把算法输出拆成**可独立订阅的模块**，每个模块对应一个 topic（复用 `ShmQueue` 的整数 topic）：

| 模块枚举 | topic id | 输出内容 | 依赖模块 |
|----------|----------|----------|----------|
| `FACE_DETECT` | 0x01 | 人脸框、置信度、5关键点 | 无 |
| `HEADPOSE` | 0x02 | 头姿 pitch/yaw/roll | FACE_DETECT |
| `GAZE` | 0x03 | 视线有效位、yaw/pitch | FACE_DETECT |
| `DISTRACTION` | 0x04 | 分心标志、触发阈值、档位 | FACE_DETECT + VEHICLE_SPEED |
| `VEHICLE_SPEED` | 0x05 | 车速 km/h、有效性 | 无 |

> 「单模块输出依赖」= 每个能力模块作为独立输出单元，外部模块按需订阅其中一个或多个；模块间依赖（如 DISTRACTION 依赖车速）由算法进程内部解析，对外透明。

### 3.2 对外服务接口（IFaceCapabilityService）

> 说明：下文 `IFaceCapabilityService` 泛指「算法能力服务接口」这一概念，**实际实现为扩展后的 `AlgoEngineBridge`**（一个 Binder 同时承载引擎控制 + 能力注册/订阅）。

把 `AlgoEngineService` 的能力开放化。**实现上在现有 `AlgoEngineBridge` 上扩展**（一个 Binder 同时承载引擎控制 + 能力注册/订阅），事务码 6-10 为新增能力接口：

```kotlin
// AlgoEngineBridge（FACEP-011 扩展，事务码 6-10）
interface AlgoEngineBridge : IInterface {
    // —— 引擎控制（渲染进程用，事务码 1-5）——
    fun getSharedMemory(): SharedMemory
    fun getState(): String
    fun start(): Boolean
    fun stop()
    fun setDumpPath(path: String)

    // —— 能力注册/订阅（对外模块用，事务码 6-10）——
    /** 注册为消费者；token 为客户端存活 token（服务端 linkToDeath 监听进程死亡） */
    fun register(packageName: String, token: IBinder?): Int
    fun unregister(clientId: Int)
    fun init(modelDir: String): Int
    /** 订阅能力模块（topic 数组，可多次累加） */
    fun subscribe(clientId: Int, moduleIds: IntArray): Int
    fun unsubscribe(clientId: Int, moduleIds: IntArray): Int
}
```

**实现要点**：
- `AlgoEngineService` 维护消费者注册表 `mClients`（clientId→包名）+ 订阅表 `mSubscriptions`（clientId→模块集合），用 `mClientLock` 保护。
- **安全**：`register` 校验调用方 uid（`Binder.getCallingUid() == Process.myUid()`），仅放行同 `android.uid.system`；`Manifest` 的 `AlgoEngineService` 改 `exported=true`。
- **错误码**：`ERR_OK=0 / ERR_CLIENT_INVALID=-1 / ERR_CLIENT_FULL=-2 / ERR_INVALID_MODULE=-3 / ERR_NOT_INITIALIZED=-4 / ERR_ACCESS_DENIED=-5`；`MAX_CLIENTS=4`。
- **死亡清理**：`register(packageName, token)` 对 token `linkToDeath`，客户端进程崩溃自动 `unregister`，防僵尸 clientId 占满槽位。

### 3.3 按模块订阅/发布数据流

```
算法进程(:algo)                        共享内存 ShmQueue            外部 App 模块 / 主进程
─────────────────                      ─────────────────         ───────────────────────
FaceIDAlgorithmImpl 推理
   │  facebox / headpose / gaze / distract / speed
   ▼
模块打包器 CapabilityPublisher
   ├─ publish(topic=0x01, 人脸框 payload)     ──▶ 槽位  |◀── 订阅 FACE_DETECT  → 只读人脸框
   ├─ publish(topic=0x02, 头姿 payload)       ──▶ 槽位  |◀── 订阅 HEADPOSE
   ├─ publish(topic=0x04, 分心 payload)       ──▶ 槽位  |◀── 订阅 DISTRACTION
   └─ ...（每模块独立 topic、独立 payload）
```

**要点**：
- **发布端**：`CapabilityPublisher` 把 `AlgorithmResult` 拆分为各模块独立 payload，按模块 topic 发布；**只发布已被至少一个消费者订阅的模块**（无订阅者则跳过，节省带宽）。
- **消费端**：订阅者 attach 共享内存，按自身订阅的 topic 过滤 `ShmMessage.topic`，只反序列化所需模块。
- **依赖透明**：外部模块订阅 `DISTRACTION` 时，算法进程内部自行保证其依赖的车速/人脸已就绪，对外不暴露依赖。

### 3.4 客户端 SDK（FaceCapabilityClient）

为外部 App 模块提供开箱即用的 SDK，封装「注册-初始化-订阅-取结果」：

```kotlin
class FaceCapabilityClient(context: Context) {
    fun connect(): Boolean            // 绑定（阻塞等待绑定完成，超时 2s）
    fun init(): Int                    // 注册 + 初始化算法 + 附着共享内存（一次性）
    fun subscribe(modules: Set<CapabilityModule>): Int
    fun unsubscribe(modules: Set<CapabilityModule>): Int
    fun obtainFaceBox(): FaceBoxData?   // 按模块取数（类型化）
    fun obtainHeadpose(): HeadposeData?
    fun obtainGaze(): GazeData?
    fun obtainDistract(): DistractData?
    fun obtainSpeed(): SpeedData?
    fun disconnect()                    // 注销消费者 + 断开服务
}
```

**实现要点**：
- **`connect()` 用 `CountDownLatch` 阻塞等待 `onServiceConnected`**（Binder 回调是异步的），避免调用方 `connect()` 后立即 `init()` 时 `mBridge` 未就绪。
- **`init()` 一次性** attach 结果共享内存 + `registerReader` 并缓存；`obtain*` 复用同一 reader，避免每次取数都 mmap + Binder IPC + 占用/释放 reader 槽位。
- **线程安全**：`mBridge/mClientId/mInitialized` 用 `@Volatile` + `mLock` 保护注册/断开/队列清理。
- **存活 token**：客户端持有 `Binder()` 作为 token 传给 `register`，供服务端死亡检测。

对应**接口文档**（`docs/FaceCapability_接入说明.md`）给出三步用法：
1. 注册：`connect()`
2. 初始化：`init()`
3. 按需取数：`subscribe([FACE_DETECT])` → `obtainFaceBox()`

### 3.5 按需订阅 vs 当前整体广播的差异

| 维度 | 当前（FACEP-010） | 本提案（FACEP-011） |
|------|-------------------|---------------------|
| 接口 | `AlgoEngineBridge`（私有、仅主进程） | `IFaceCapabilityService`（对外、可多客户端） |
| 发布 | 单一 `AlgorithmResult` 整体广播 | 按 `CapabilityModule` 独立 topic 发布 |
| 消费 | 主进程解析全部字段 | 按订阅 topic 过滤，只取所需模块 |
| 注册 | 无 | `register/unregister`，多消费者 |
| 初始化 | `startEngine`（内部） | 对外 `init(modelDir)` 显式初始化 |
| 带宽 | 始终发送全量 | 只发送被订阅模块，节省共享内存 |

### 3.6 渲染进程同步改造（方案 A，强制前置）

按需订阅/发布会**改变算法结果的数据形态**（由单一 `AlgorithmResult` 拆分为多 topic 模块），因此渲染进程**必须同步改造**，否则编译与运行都会失败。渲染进程的**职责不变**，仅**消费链路**按以下三处调整：

#### 3.6.1 绑定流程：`bind` → `register + init + subscribe`

当前 `PreviewActivity.onServiceConnected`（`ui/PreviewActivity.kt:181-203`）只做 `getSharedMemory + setDumpPath`。改为：

```
onServiceConnected(binder):
    1. service = IFaceCapabilityService.Stub.asInterface(binder)
    2. clientId  = service.register(packageName)          // 注册为本进程消费者
    3. service.init(modelDir)                             // 初始化算法（幂等）
    4. service.subscribe(clientId, [FACE_DETECT,
                                    DISTRACTION,
                                    VEHICLE_SPEED])        // 订阅渲染所需模块
    5. shm = service.getSharedMemory(); attach + registerReader
    6. service.setDumpPath(dumpDir)                        // 沿用 dump 路径下发
    7. startResultConsumer()
```

> 渲染进程**只订阅渲染需要**的模块（人脸框、分心、车速），不订阅 HEADPOSE/GAZE（当前绘制不用），从而体现按需收益。

#### 3.6.2 消费线程：整体解码 → 按 topic 过滤

`startResultConsumer`（`ui/PreviewActivity.kt:224-251`）当前读单条整体 `AlgorithmResult`：

```kotlin
val algoResult = AlgorithmResult.decode(m.payload)   // 单一 topic，整体结构
runOnUiThread { handleAlgoResult(algoResult) }
```

改为**按 topic 分模块解析**，仅对订阅的模块做反序列化：

```kotlin
val m = q.readNext(rid) ?: break
when (m.topic) {
    TOPIC_FACE_DETECT   -> faceBox  = FaceBoxData.decode(m.payload)
    TOPIC_DISTRACTION   -> distract = DistractData.decode(m.payload)
    TOPIC_VEHICLE_SPEED -> speed    = SpeedData.decode(m.payload)
    else -> continue                                  // 未订阅模块直接跳过
}
// 汇总后一次绘制（人脸框 + 分心 + 车速）
runOnUiThread { handleModules(faceBox, distract, speed) }
```

#### 3.6.3 绘制逻辑：单结构 → 多模块汇总

`handleAlgoResult`（`ui/PreviewActivity.kt:257`）当前从单一 `AlgorithmResult` 取 `faceLeft/Right`、`distracted`、`speedKmh`。改为**汇总各模块数据**后调用，绘制细节（画人脸框、分心提示、车速文本）**保持不变**：

```kotlin
private fun handleModules(faceBox: FaceBoxData?,
                          distract: DistractData?,
                          speed: SpeedData?) {
    mFaceOverlay.setDistracted(distract?.distracted ?: false)
    updateSpeedText(speed)
    // 人脸框绘制逻辑不变（faceBox.rect → setFaces）
}
```

#### 3.6.4 渲染进程改动清单

| 位置 | 现状 | 改为 |
|------|------|------|
| `PreviewActivity.onServiceConnected` | 仅 `getSharedMemory` + `setDumpPath` | `register → init → subscribe → getSharedMemory → setDumpPath` |
| `PreviewActivity.startResultConsumer` | 单 topic 整体解码 | 按 `m.topic` 分模块解析，跳过未订阅模块 |
| `PreviewActivity.handleAlgoResult` | 从单一 `AlgorithmResult` 取数 | 汇总多模块数据，绘制逻辑不变 |
| `PreviewActivity.bindAlgoEngine` | `AlgoEngineBridge` | `IFaceCapabilityService` |
| 绘制/渲染/dump | 不变 | 不变 |

#### 3.6.5 改造约束

- **必须与本提案同步实施**：算法进程发布方式切换与渲染进程订阅切换**在同一次发布中原子完成**，避免中间态（算法按 topic 发、渲染仍按整体读）导致功能失效。
- **回退策略**：若按模块改造验证失败，可临时让算法进程对「渲染进程」这一消费者继续发布兼容的整体 `AlgorithmResult`（见 4. 风险-回退），但**不推荐长期双轨**。

### 3.7 实现步骤（阶段划分）

- **阶段 A：接口扩展与 SDK**
  - 扩展 `IFaceCapabilityService`（新增 register/unregister/init/subscribe/unsubscribe 事务码）
  - `AlgoEngineService` 开放（exported + 权限校验），实现多消费者注册
  - 新增 `FaceCapabilityClient` SDK + `CapabilityModule` 枚举 + `CapabilityPublisher` 模块打包器
- **阶段 B：按 topic 发布改造 + 渲染进程同步改造**
  - `AlgoEngineService.onAlgorithmResult` 改为经 `CapabilityPublisher` 按模块 topic 发布
  - 维护「订阅表」（模块 → 订阅者集合），仅发布有订阅者的模块
  - **渲染进程同步改造（方案 A，详见 3.6）**：PreviewActivity 改为内建订阅者，完成 `register → init → subscribe → topic 过滤消费` 三处消费链路调整，绘制逻辑不变。**本阶段算法发布与渲染订阅必须原子落地，不可拆分上线。**
- **阶段 C：接口文档 + 示例**
  - 编写 `docs/FaceCapability_接入说明.md`（注册/初始化/订阅/取数）
  - 提供最小示例模块（示例 App 或单元测试）验证「别的 App 注册即用」

---

## 4. 风险与对策

| 风险 | 对策 |
|------|------|
| **对外服务安全**（任意 App 绑定消耗算法资源） | 权限/签名校验（`android:permission` + `sharedUserId` 校验），限制 `register` 的白名单 |
| **多消费者读竞争** | `ShmQueue` 已支持多读者-单写者，各消费者独立读指针；慢消费者不阻塞发布者（仅标记失效） |
| **按模块发布增加发布端开销** | 仅发布有订阅者的模块；同一模块多订阅者只发布一次 |
| **接口契约演进** | topic 常量、`ModuleData` 序列化保持定长 + MAGIC + 长度校验（沿用 `AlgorithmResult` 经验） |
| **回退** | 保留 `AlgoEngineBridge` 旧接口一段时间，主进程过渡期可双轨；验证失败可回退到整体广播 |

---

## 5. 验收标准

1. **单模块订阅**：外部模块只 `subscribe(FACE_DETECT)` 时，共享内存中仅出现人脸框 topic 数据，无头姿/分心 topic。
2. **注册即用**：按接口文档完成 `connect → init → subscribe → obtain` 四步，即可获得对应算法输出（无需了解算法内部）。
3. **多消费者**：主进程 + 至少一个外部模块同时订阅不同模块，互不干扰、各自只收到所需数据。
4. **带宽收益**：相比整体广播，单模块订阅时单帧共享内存写入字节数明显下降。
5. **渲染进程同步正确性（方案 A）**：PreviewActivity 经 `register → init → subscribe → topic 过滤消费` 改造后，人脸框/分心/车速显示**与改造前行为一致**；且只消费其订阅的 `FACE_DETECT/DISTRACTION/VEHICLE_SPEED` 三个 topic，不接收 HEADPOSE/GAZE。
6. **原子上线**：算法进程按 topic 发布与渲染进程按 topic 订阅**同一次发布切换**，不存在中间态导致渲染读不到数据。

---

## 6. 实现记录

### 阶段交付
- **阶段 A**：`CapabilityModule` 枚举、`AlgoEngineBridge` 扩展 `register/unregister/init/subscribe/unsubscribe`（事务码 6-10）、`FaceCapabilityClient` SDK、`CapabilityModuleTest`(16)。
- **阶段 B**：`ModuleData`（5 个模块 payload）、`CapabilityPublisher` 按订阅发布、`AlgoEngineService` 按模块 topic 发布、`PreviewActivity` 注册+订阅+topic 过滤消费、`ModuleDataTest`(20)。
- **阶段 C**：`docs/FaceCapability_接入说明.md` 接口文档、`FaceCapabilityClientSample` 示例模块。

### 实现落地文件
| 文件 | 职责 |
|------|------|
| `shmtest/CapabilityModule.kt` | 能力模块枚举（topic 0x01-0x05） |
| `shmtest/ModuleData.kt` | 各模块 payload 序列化（FaceBoxData/HeadposeData/GazeData/DistractData/SpeedData） |
| `shmtest/CapabilityPublisher.kt` | 按订阅表只发布有订阅者的模块 |
| `shmtest/AlgoEngineService.kt` | 多消费者注册表 + 订阅表 + 按模块发布 + linkToDeath |
| `shmtest/AlgoEngineBridge.kt` | Binder 事务码 1-10（引擎控制 + 能力接口） |
| `shmtest/FaceCapabilityClient.kt` | 对外 SDK（connect/init/subscribe/obtain*/disconnect） |
| `shmtest/FaceCapabilityClientSample.kt` | 接入示例模块 |
| `ui/PreviewActivity.kt` | 渲染进程：register→subscribe→topic 过滤消费 |
| `bus/ShmQueue.kt` | 槽位分配空洞复用 + closed 检查 |

### 隐患修复（评审后，共 11 项）
**第一轮（5 项）**
1. **`stopEngine` 清空注册表**：引擎重启后清理 `mClients/mSubscriptions/mNextClientId`，防「幽灵订阅」错误发布。
2. **`ShmQueue.publish` 检查 `closed`**：close 后拒绝写入，防并发 stopEngine 写坏映射；`closed` 改 `@Volatile`。
3. **`mPublisher/mOutQueue` 加 `@Volatile`**：消除算法线程读与 stopEngine 置 null 的竞态。
4. **`register` 加 uid 校验**：`AlgoEngineService` 仅放行同 uid（`android.uid.system`）调用方，新增 `ERR_ACCESS_DENIED(-5)`；`Manifest` 的 `AlgoEngineService` 改 `exported=true`。
5. **linkToDeath 死亡清理**：`register(packageName, token)` 接收客户端存活 token，服务端监听进程死亡自动 `unregister`，防僵尸 clientId 占满 `MAX_CLIENTS`。

**第二轮（4 项）**
6. **SDK `obtain*` 每次 mmap/registerReader（严重）**：改为 `init()` 一次性 attach + 注册固定 reader，`obtain*` 复用，避免反复 Binder IPC + mmap + reader 槽位耗尽。
7. **SDK `connect()` 异步时序**：用 `CountDownLatch` 阻塞等待 `onServiceConnected`，避免 `init()` 在 bridge 就绪前调用。
8. **SDK 线程安全**：`mBridge/mClientId/mInitialized` 加 `@Volatile` + `mLock` 保护。
9. **`PreviewActivity` 数据快照错位**：用不可变 `ModulesSnapshot` 原子替换，避免人脸框/分心/车速跨帧错位；数据未变化时跳过绘制。

**第三轮（2 项）**
10. **`PreviewActivity.onDestroy` 未注销消费者**：补 `unregister`，防多客户端场景下渲染进程 clientId 残留占槽。
11. **`ShmQueue.registerReader` 槽位分配缺陷（P2）**：`initializeHeader` 槽位改全置 0（未分配），`registerReader` 改「遍历找第一个空闲槽」复用注销产生的空洞，进程内 `readerLock` 保证原子，避免「注销后重注册落到已占用槽位」导致两个 reader 共享槽、读指针互相覆盖。

### 验证
- `assembleRelease` 编译通过，lint 全绿。
- 单元测试 126 例全部通过（含 `CapabilityModuleTest` 16、`ModuleDataTest` 28、`ShmQueueTest` 20、`AlgorithmResultTest` 14 含地标往返）。

### 数据完整性修复（评审后追加）
重构精简 `AlgorithmResult` 时曾丢失部分算法返回数据，已补回跨进程链路：
1. **`gazeCalibrated`（视线标定状态）**：补进 `AlgorithmResult` + `GazeData.calibrated`，渲染 `FaceBox.gazeCalibrated` 显示校准状态（`G:...c%d`）。
2. **5 关键点**：补进 `AlgorithmResult` + `FaceBoxData`，供视线线/头姿箭头起点（此前丢失）。
3. **`distractionScore`/`distractionHpScore`/`distractionGazeScore`**：补进 `AlgorithmResult` + `DistractData`。

> **106 密集地标（`landmarks`）不跨进程传输**：原计划新增 `LANDMARKS` 模块传输 106 点（用于绘制眼睛/嘴巴开合状态），但因需要精确的关键点索引（当前 InsightFace 模型的 106 点索引无法从公开资料可靠确认）且开销大（~848B/条），**已决定不传输**。如需绘制眼睛/嘴巴开合，需另行确认模型索引后按需扩展。
