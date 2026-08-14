# 提案：多进程共享内存实践（ShmQueue 跨进程化）

> 提案编号：FACEP-010  
> 创建日期：2026-08-14  
> 状态：待评审

---

## 1. 背景与动机

FACEP-009 分层架构重构已把项目解耦为 `bus/signal/frame/render` 四层，并实现了**单进程内**的 `BusQueue` 消息总线（多读者-单写者环形缓冲，参照 openpilot msgq）。

但当前 `BusQueue` 仍是**进程内 JVM 内存数组**实现（`BusQueue.kt:17-18` 明确注释预留了扩展为 Android SharedMemory 的意图），存在三个演进限制：

1. **无法跨进程**：`BusQueue` 存的是**对象引用**（`BusMessage`），对象无法跨进程传递；且 `AtomicLong`/`volatile` 的可见性只在同一进程内有效。
2. **无法隔离故障**：算法/信号层仍与主进程同生死，单层崩溃（如算法 OOM）会拖垮整个预览进程。
3. **信号处理与主进程耦合**：当前 `signal` 层（车速 + 分心判定）与主进程 UI 同进程，主进程被算法/信号拖累，且不满足"算法模块自包含独立运行"的目标。

**核心诉求**：将 `BusQueue` 演化为基于 Android `SharedMemory` 的**跨进程无锁环形缓冲 `ShmQueue`**，把**全部信号处理（车速 + 算法结果整合）下沉到算法进程 `:algo`**，主进程只消费整合后的结果并绘制，从而让算法模块**不依赖主进程完整运行**，同时验证 Android 平台多进程共享内存的可行性、正确性与性能。

---

## 2. 目标与范围

### 2.1 目标
1. 新增 `bus/ShmQueue`——基于 `android.os.SharedMemory` 的**跨进程环形缓冲消息队列**，支持多读者-单写者。
2. 保持与 `BusQueue` 相同的 API 形态（`registerReader/readNext/publish` 语义），便于上层无缝替换。
3. 提供一个**跨进程最小可行验证（PoC）**：`:shmtest` 进程发布、主进程订阅，验证共享内存写入/读取/可见性/健康检查。
4. **利用已确认的 EVS 相机多进程取帧能力**（产品侧已确认支持），验证算法进程 + 主进程独立取帧（帧数据源一致、互不影响）。
5. **真实进程拆分**（阶段 E）：把 `algorithm/signal/frame(推理)` 拆分到 `:algo` 进程，主进程只留消费 + 绘制。

### 2.2 不在本次范围
- 不改动绘制层现有逻辑（`render` 保持稳定）。
- 不引入第三方序列化库（消息负载用定长/可序列化字节）。
- 阶段 E 的真实拆分**以各阶段验证结论为准**，若共享内存或 EVS 多进程取帧不可行则回退到单进程（ShmQueue 作为可选基础设施）。

---

## 3. 技术方案

### 3.1 Android 共享内存选型

| 载体 | 特点 | 选用 |
|------|------|------|
| **`android.os.SharedMemory`**（API 27+） | 原生 ashmem，可 `dup` FD、`mapReadOnly`/`mapReadWrite`，`Parcelable` 可经 Binder 传句柄 | **首选** |
| `android.os.MemoryFile` | 老 API，支持 `ParcelFileDescriptor` | 备选 |
| `mmap + anon_inode` | 低层，较少直接使用 | 不采用 |

系统应用（`sharedUserId=android.uid.system`）对 ashmem 有完整权限，直接使用 `SharedMemory`。

### 3.2 跨进程共享的难点与对策

| 难点 | 问题 | 对策 |
|------|------|------|
| **不能传对象引用** | `BusMessage` 是 JVM 对象 | `ShmQueue` 只共享**原始字节**；消息改为序列化字节（`MessageParcel`：topic + 长度 + payload 字节），或共享帧元数据 + 句柄 |
| **没有跨进程原子类型** | `AtomicLong` 是 JVM 的，不共享 | 在共享内存中**自实现无锁环形缓冲头**：写指针、每个 reader 读指针、reader 有效位，用 `mmap` 后的原始内存 + **内存屏障**（Android 上通过共享 FD 读写天然有 ashmem 语义） |
| **跨进程可见性** | JVM volatile 不跨进程 | 利用 ashmem 的 `mmap` 共享内存语义 + 写入顺序 + `FileDescriptor` 的通知（或轮询） |
| **进程崩溃写坏共享区** | 写者崩溃可能留下脏数据 | 读指针/写指针单调递增，读者可检测序号回退/越界，丢弃坏消息；健康检查兜底 |

### 3.3 `ShmQueue` 内存布局设计

参照 openpilot `msgq_header_t`，`ShmQueue` 在共享内存中布局：

```
┌────────────────────────────────────────────────┐
│  队列头（定长，struct 布局）                     │
│  - magic: u32          (校验/版本)             │
│  - capacity: u32       (槽位数)                │
│  - write_seq: u64      (发布者写指针，单调递增)  │
│  - num_readers: u32    (已注册 reader 数)       │
│  - readers_valid[]: u8 (每 reader 有效位)       │
│  - reader_pos[]: u64   (每 reader 独立读指针)    │
├────────────────────────────────────────────────┤
│  数据区（capacity 个槽位）                      │
│  - 每槽位：size:u32 + payload:bytes（最大定长）  │
└────────────────────────────────────────────────┘
```

**要点**：
- 队列头与数据区都在同一块 `SharedMemory` 中，`mapReadWrite` 后通过 ByteBuffer 读写。
- 写指针/读指针**单调递增**，取模定位槽位（与 `BusQueue` 一致）。
- 多读者-单写者：发布者写数据时，逐 reader 检查其读指针是否停留在将被覆盖的槽位，标记 `readers_valid=false`（慢读者失效，不阻塞发布者）。

### 3.4 跨进程句柄分发（Binder + FileDescriptor）

`ShmQueue` 创建后，通过 **Binder `ParcelFileDescriptor`** 把共享内存句柄传给其他进程：

```kotlin
// 提供进程侧
val shm = SharedMemory.create("shm_queue", size)
val fd: ParcelFileDescriptor = shm.fd  // 可经 Binder 传
// 消费进程侧
val shm = SharedMemory("shm_queue", fd)
val buffer = shm.mapReadWrite()
```

用 Binder 服务（`aidl` 或 `Service`）分发共享内存 FD。主进程与 `:shmtest` 进程各 `mmap` 到自己的地址空间，共享同一块物理内存。

### 3.5 消息负载策略

- **帧数据**：跨进程传递 `HardwareBuffer`（EVS 已基于 SurfaceFlinger/Binder 共享图形缓冲区）或共享内存句柄 + 元数据，**不走 ShmQueue 拷贝大图**。
- **信号/算法结果**：序列化为**字节**（定长结构体或轻量序列化），存入 ShmQueue 数据区，避免对象引用跨进程。

### 3.6 多进程项目结构关系

#### 3.6.1 当前单进程结构（现状）

```
┌─────────────────────────── 主进程（默认，:main）───────────────────────────┐
│                                                                           │
│   ┌────────────┐   ┌────────────┐   ┌────────────┐   ┌────────────┐       │
│   │  ui 层     │   │  signal 层 │   │  frame 层  │   │  render 层 │       │
│   │PreviewAct  │   │Distraction │   │FrameDistri │   │FaceOverlay │      │
│   │ ivity      │   │StateMachine│   │butor       │   │            │       │
│   └─────┬──────┘   └─────┬──────┘   └─────┬──────┘   └────────────┘       │
│         │                │                │                                │
│   ┌─────▼────────────────▼────────────────▼────────────────────────────┐  │
│   │             bus 层：BusHub/BusQueue（进程内）                         │  │
│   └────────────────────────────────────────────────────────────────────┘  │
│   ┌────────────┐   ┌────────────┐   ┌────────────┐                        │
│   │ camera 层  │   │ algorithm  │   │ pipeline   │                        │
│   │CameraMgmt  │   │FaceIDAlgo  │   │BufferMgr   │                        │
│   └────────────┘   └────────────┘   └────────────┘                        │
└───────────────────────────────────────────────────────────────────────────┘
```

#### 3.6.2 多进程拆分后结构（目标）

**原则修正**：所有信号（车速 + 算法结果）的**采集与处理都在算法进程**内完成，主进程**不做任何信号对接与处理**，只消费"整合后的算法处理结果"并绘制。

```
┌─────────────── 算法进程（:algo，自包含，全部信号处理）─────────────────┐
│  帧数据管理A（FrameDistributor + FrameSource）                          │
│      │  从相机取帧（EVS/SurfaceFlinger 共享）                           │
│      ▼                                                                  │
│  FrameProcessor（推理）+ FaceIDAlgorithmImpl                             │
│      │  产出：人脸框/坐标/分心标志/特征                                  │
│      ▼                                                                  │
│  信号处理（DistractionStateMachine + 车速 VHAL）                         │
│      │  算法结果 + 车速在此进程内整合为"算法处理结果"                    │
│      ▼                                                                  │
│  ShmQueue（发布整合后的结果信号，轻量）                                  │
└──────────────┬───────────────────────────────────────────────────────────┘
               │  只传整合后的算法处理结果（轻量信号）
               ▼
┌─────────────── 主进程（:main，只消费+绘制）──────────────────────────────┐
│  ShmQueue（消费端）── 读取算法处理结果                                    │
│      │                                                                   │
│  帧数据管理B（FrameDistributor + FrameSource）                            │
│      │  从相机取帧（数据源与算法进程一致，同一相机流）                    │
│      ▼                                                                   │
│  render 层（FaceOverlay 绘制）                                            │
└───────────────────────────────────────────────────────────────────────────┘
```

#### 3.6.3 进程边界划分原则

按"**信号处理/计算密集** vs **UI/渲染**"划分进程：

| 进程 | 承载 | 职责 |
|------|------|------|
| **算法进程 `:algo`** | `algorithm`、`frame`（推理）、`signal`（全部）、`bus`（发布端） | **自包含**：独立取帧、独立推理、独立采集车速（VHAL）、整合为算法处理结果并发布；不依赖主进程可独立完整运行 |
| **主进程 `:main`** | `ui`、`render`、`frame`（预览取帧）、`bus`（消费端） | 只做两件事：①从 ShmQueue 消费算法处理结果；②独立取帧绘制预览。**不对接车速/VHAL，不处理任何信号** |

**关键设计点**：
- **`signal` 层整体在算法进程**：`DistractionStateMachine`、`VehicleSignalSource`（VHAL 车速）都在 `:algo`。车速是分心判定的输入，必须在算法进程内即时可用。
- **`frame` 层双份**：算法进程一份（推理取帧）、主进程一份（预览取帧），两者数据源一致（同一相机流），互不依赖。
- **主进程极简**：无 `algorithm`、无 `signal`、无 VHAL，只消费结果 + 绘制。

#### 3.6.4 跨进程通信关系

| 数据 | 类型 | 方向 | 载体 |
|------|------|------|------|
| **整合后的算法处理结果**（分心标志/档位/人脸框/坐标/特征） | 轻量（KB级） | `:algo` → 主进程 | **ShmQueue**（序列化字节） |
| 相机帧 `HardwareBuffer` | 重量（MB级） | 相机 → 各进程**独立** | **EVS/SurfaceFlinger 共享**，不走 ShmQueue、不拷贝大图 |
| 车速信号（VHAL） | 轻量 | Car(HAL) → `:algo` | 算法进程内 VHAL 直连，**不跨进程** |

**要点**：
- **主进程不接触车速/任何原始信号**——只接收算法进程整合后的结果，单向下行。
- 帧图像**不经过 ShmQueue**：两个进程各自从相机取帧（帧数据源一致），避免 MB 级拷贝。

#### 3.6.5 模块依赖关系（多进程视角）

```
算法进程 (:algo)
  frame（推理） ──> algorithm（FaceIDAlgorithmImpl）
  signal ──> DistractionStateMachine ──> 算法结果（即时输入）
  signal ──> VehicleSignalSource（VHAL 车速）
  bus（ShmQueue 发布端）──> 整合后的算法处理结果

主进程 (:main)
  ui ──> render（绘制）
  frame（预览取帧）──> FrameSource（独立取帧）
  bus（ShmQueue 消费端）── 读算法处理结果

共享契约（两个进程都依赖）
  bus/ShmQueue、AlgorithmResult（整合结果的数据结构）、MessageParcel（序列化格式）
```

**要点**：
- **`IFaceIDAlgorithm` 不再跨进程**：算法实现只在 `:algo` 进程，主进程不持有算法。主进程只依赖"整合结果"的数据结构（`AlgorithmResult`），经 ShmQueue 消费。
- **`SignalDispatcher` 全部在 `:algo`**：车速 + 算法结果在算法进程内整合为 `AlgorithmResult`，发布到 ShmQueue。
- **`MessageParcel` 序列化格式**跨进程共享（新增文件），把 `AlgorithmResult` 转字节。
- **单一 Binder 服务 `ShmService`**：负责创建 `ShmQueue`、`dup` 共享内存 FD、分发给主进程，避免多个 Binder。

#### 3.6.6 多进程收益 vs 成本

**收益**：
- **算法进程完全自包含**：不依赖主进程，可独立取帧/推理/采集车速；崩溃/OOM 不影响主进程，可独立重启；
- **主进程极简**：只消费结果 + 绘制，无算法/信号/VHAL 依赖，footprint 小、崩溃面小；
- **性能隔离**：算法推理的 GC/卡顿不拖累 UI 渲染线程；
- **共享内存只传轻量信号**：整合结果 KB 级，性能好；帧走 EVS 共享不拷贝。

**成本**：
- **EVS 相机多进程取帧需验证**：两进程能否同时从同一相机流取帧（依赖 EVS 多 client 支持），是阶段 B 关键验证点；
- **序列化开销**：`AlgorithmResult` 需转字节，有少量开销；
- **复杂度**：多一个进程，崩溃恢复、版本同步、日志归属更复杂。

---

## 4. 实施步骤（分阶段）

### 阶段 A：`ShmQueue` 核心实现（单进程内先验证）
1. 新增 `bus/ShmQueue.kt`，基于 `SharedMemory` + 自实现无锁队列头（写指针/读指针/reader 有效位）。
2. 提供与 `BusQueue` 一致的 API：`registerReader/readNext/hasNext/publish/reset`。
3. 单进程内用**两个线程**验证读写正确性与环形回绕。

### 阶段 B：跨进程 PoC（`:shmtest` 进程）
1. `AndroidManifest` 新增 `:shmtest` 进程的 `Service`（或独立 Activity），模拟"算法进程"。
2. 一个进程（模拟 `:algo`）创建 `ShmQueue` 并通过 Binder `ParcelFileDescriptor` 分发句柄。
3. 另一进程（模拟主进程）订阅读取，验证：跨进程写入可见、序号正确、慢读者失效、健康检查。
4. 打印跨进程延迟/吞吐数据（对比单进程）。

### 阶段 C：封装与上层替换
1. 提供 `ShmQueue` 与 `BusQueue` 的统一抽象（如 `MessageQueue` 接口），上层 `BusHub` 按需选择进程内/跨进程实现。
2. 单元测试：单进程读写、回绕、多 reader、慢读者失效。

### 阶段 D：EVS 相机多进程取帧（已确认支持）
> 已由产品侧确认：EVS 相机支持多进程/多 client 从同一相机流取帧。

1. 在 `:algo` 进程内复用 `FrameSource` 独立打开相机取帧（帧数据源与主进程一致）。
2. 确认算法进程 + 主进程各自独立取帧（不互相阻塞、帧时序一致）。
3. 记录两进程取帧的帧率/延迟，确认互不影响。

### 阶段 E：真实进程拆分与评估
1. 按 §3.6 结构把 `algorithm/signal/frame(推理)` 拆分到 `:algo` 进程，主进程只留 `ui/render/frame(预览)/bus(消费)`。
2. 验证：算法进程自包含独立运行、主进程只消费结果 + 绘制、崩溃隔离、性能（延迟/吞吐）。
3. 以验证结论为准，不强制追求极致隔离。

---

## 5. 关键接口草案

```kotlin
/**
 * 跨进程共享内存消息队列（多读者-单写者）。
 * 基于 android.os.SharedMemory，队列头与数据区都驻留在共享内存中。
 */
class ShmQueue(
    val shm: SharedMemory,
    val capacity: Int = DEFAULT_CAPACITY,
    val maxReaders: Int = DEFAULT_MAX_READERS
) {
    fun publish(payload: ByteArray, topic: Int)
    fun readNext(readerId: Int): ShmMessage?   // 返回 topic + payload 字节
    fun hasNext(readerId: Int): Boolean
    fun registerReader(): Int
    fun unregisterReader(readerId: Int)

    companion object {
        fun create(name: String, capacity: Int, maxReaders: Int): ShmQueue
        fun attach(fd: ParcelFileDescriptor): ShmQueue  // 消费进程挂载
        const val DEFAULT_CAPACITY = 64
        const val DEFAULT_MAX_READERS = 15
    }
}

/** 跨进程消息：topic 类型 + 负载字节（非对象引用）。 */
class ShmMessage(val topic: Int, val payload: ByteArray, val seq: Long)
```

**与 `BusQueue` 的统一抽象**（阶段 C）：
```kotlin
interface MessageQueue {
    fun publish(msg: BusMessage)
    fun readNext(readerId: Int): BusMessage?
    fun hasNext(readerId: Int): Boolean
    fun registerReader(): Int
    fun unregisterReader(readerId: Int)
}
```

---

## 6. 验证方案

1. **单进程线程验证（阶段 A）**：单写者 + 多读者并发读写，断言序号连续、无消息丢失、慢读者失效正确。
2. **跨进程验证（阶段 B）**：`(模拟:algo)` 进程发布、`(模拟:main)` 进程消费，断言：
   - 发布进程 → 消费进程能读到一致数据（序号/负载）；
   - 慢读者不阻塞发布者；
   - 环形回绕（> capacity）数据正确。
3. **EVS 相机多进程取帧（阶段 D）**：已确认支持；实测算法进程 + 主进程从同一相机流独立取帧的帧率/延迟，确认互不阻塞、帧数据源一致。
4. **性能测量**：记录跨进程发布→消费延迟（对比单进程），验证共享内存相比 Binder 大对象拷贝的优势。
5. **健康检查**：写者进程退出后，读者能检测到 `alive=false` 并降级（不崩溃）。
6. **真实拆分验证（阶段 E）**：算法进程自包含独立运行、主进程只消费结果 + 绘制、崩溃隔离正确。

---

## 7. 风险与注意事项

| 风险 | 影响 | 缓解 |
|------|------|------|
| 自实现无锁环形缓冲在跨进程下的内存屏障语义复杂 | 数据竞争/可见性错误 | 严格遵循写指针发布顺序；单进程内先用线程压测；跨进程用 FD 通知或高频轮询 |
| `SharedMemory.mapReadWrite` 后跨进程并发访问 | 未同步读写 | 采用多读者-单写者模型，写者唯一；读者只读自己槽位 |
| 进程崩溃写坏共享区 | 脏数据/序号错乱 | 序号单调递增 + 越界检测；健康检查兜底降级 |
| Binder 分发 FD 的复杂度 | 句柄传递失败 | 用系统应用权限 + aidl Service 封装；失败降级为单进程 BusQueue |
| 消息对象序列化开销 | 性能 | 信号/结果用定长结构体；帧数据走句柄不拷贝 |
| EVS 相机多进程取帧的时序/帧率 | 两进程取帧可能互相影响或帧率不一致 | 已确认支持多 client 取帧；阶段 D 实测两进程独立取帧的帧率/延迟，算法结果带时间戳对齐绘制 |
| 算法进程与主进程取帧帧率/时序不一致 | 绘制与算法看到不同帧 | 两进程各自取帧（数据源一致），绘制以主进程帧为准，算法结果带时间戳对齐 |

---

## 8. 结论

本提案将 FACEP-009 的单进程 `BusQueue` 演化为基于 Android `SharedMemory` 的**跨进程 `ShmQueue`**，通过独立进程 PoC 验证 Android 平台多进程共享内存的可行性、正确性与性能。完成后：

- 上层 `BusHub` 可统一抽象 `MessageQueue`，按需选择进程内/跨进程实现；
- 为未来算法进程拆分（故障隔离、OOM 保护）奠定基础设施；
- 验证结论驱动"是否拆分真实进程"的决策，不盲目拆分。

本阶段**不改动现有业务链路**，仅新增基础设施 + 独立进程验证，风险可控。
