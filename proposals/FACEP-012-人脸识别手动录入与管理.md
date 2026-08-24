# 提案：人脸识别功能细化 —— 手动录入、未录入识别、人脸管理

> 提案编号：FACEP-012  
> 创建日期：2026-08-21  
> 状态：**已实施**（见 §9 实施结果）

---

## 1. 背景与动机

当前人脸识别模块（`RecognitionActivity` + `FaceEnrollmentManager`）采用**自动录入**：识别到未入库人脸时，自动采集连续稳定帧并**分配山海经异兽名称**（`SHENHAI_NAMES`，如"饕餮/混沌"），全程**无用户交互**。

该模式存在三个用户痛点，需细化为可管理的人脸识别功能：

1. **无法控制录入时机**：人脸刚进入画面即被自动录入，用户无法主动选择"录谁、何时录"；且录入后无法命名（只能得到随机异兽名）。
2. **未录入人脸无明确提示**：识别到库外的人脸时，`faceId` 落入 liveness 分支显示为 `detected`（"检测到人脸（未识别）"），无法区分"库外新面孔"与"活体检测状态"。
3. **无法管理已录入人脸**：已录入的人脸只能自动替换（名称用完替换最早），没有列表查看、删除能力。

> 诉求：**人脸识别功能细化为三步闭环——手动录入、未录入识别、人脸管理**。

---

## 2. 现状分析

### 2.1 `FaceEnrollmentManager` 现状（自动录入）

| 能力 | 现状 | 问题 |
|------|------|------|
| 录入时机 | `recognize()` 内自动触发（连续 `ENROLL_CONSECUTIVE_FRAMES=10` 帧 + `ENROLL_CONFIDENCE=0.65` + `ENROLL_COOLDOWN_FRAMES=60` 冷却） | 用户无法控制 |
| 命名 | 自动分配 `SHENHAI_NAMES` 异兽名，用完替换最早录入 | 非用户期望名称 |
| 识别 | `recognize(emb)` 与库内 `mGallery` 余弦比对（`MATCH_THRESHOLD=0.40`，`MATCH_MARGIN=0.05`），命中返回名称，未命中走自动录入 | 未区分"库外新面孔" |
| 存储 | `face_enrollments.json` 持久化（`name + emb[512]`） | — |
| 删除 | 无 | **无法管理** |

### 2.2 识别链路（`FaceIDAlgorithmImpl.processFrame`）

```kotlin
val enrollMgr = mEnrollmentManager
if (enrollMgr != null && emb != null && emb.size == 512 && faceSize >= MIN_FACE_SIZE) {
    val result = enrollMgr.recognize(emb, r.score, r.liveness)
    if (result.name != null) {
        faceId = result.name
        isNewEnroll = result.isNewEnroll
    }
} else {
    // 无录入管理器 / embedding 不足 → 走 liveness 分支
    faceId = when { liveness<0f -> "detected"; liveness>0.5f -> "detected"; else -> "spoof" }
}
```

- `recognize` 命中返回 `name`，未命中自动录入后返回新 `name`；
- 无有效 embedding 时返回 `detected`/`spoof`（无法识别是否库外）。

### 2.3 UI 现状（`RecognitionActivity`）

- 左上角 `tv_face_id`：显示 `faceId`（"无人脸 / 检测到人脸（未识别） / 疑似照片/翻拍 / 识别: xxx"）；
- 左上角 `tv_enrolled_count`：显示"已导入：N 人"（`getEnrolledCount()`）；
- **无**录入按钮、无命名弹框、无人脸管理列表。

---

## 3. 目标行为

### 3.1 手动录入流程（需求 1）

```
[识别页] 点击「开始录入」
   → 进入录入模式（overlay/文案提示"正在录入人脸，请正视镜头…"）
   → 采集人脸（复用连续稳定帧确认：≥ ENROLL_CONSECUTIVE_FRAMES 帧、conf≥ ENROLL_CONFIDENCE）
   → 录入成功 → 自动结束录入模式
   → 弹出命名对话框（输入名称，可为空则用默认名）
   → 点击「确认」 → 保存人脸 → Toast「人脸录入成功」
   → 点击「取消」 → 丢弃本次录入，回到正常识别
```

- **录入期间不再识别/自动录入**（避免与"识别未入库人脸"冲突）；
- 录入成功即结束，**不会连续录入多人**（每次录入需重新点按钮）；
- 名称校验：去除首尾空白；不允许空名与重名（与已录入冲突时提示）。

### 3.2 未录入人脸识别（需求 2）

- 识别到人脸 + 有效 embedding（faceSize ≥ `MIN_FACE_SIZE`）但**不在库内**：
  - `faceId` 返回 `unregistered`（新语义）；
  - UI 显示**「未录入人脸」**；
  - **不再自动录入**（区别于现状）。
- 无有效 embedding（活体失败/框过小）：维持 `detected`/`spoof` 语义不变。

### 3.3 人脸管理（需求 3）

- 识别页提供「管理已录入人脸」入口（按钮）；
- 进入管理列表：展示全部已录入人脸名称 + 录入数量；
- 每条可**删除**（删除前二次确认，避免误删）；
- 删除后同步持久化（`face_enrollments.json` 重写），识别立即生效（库中移除该人脸）。

---

## 4. 设计要点

### 4.1 `FaceEnrollmentManager` 改造

| 方法/状态 | 现状 | 目标 |
|-----------|------|------|
| `recognize(emb, score, liveness)` | 未命中自动录入 | **仅匹配**：命中返回 `RecognitionResult(name,false)`；未命中返回 `RecognitionResult(null,false)`（不再 auto-enroll） |
| 录入模式状态 | 无 | 新增 `@Volatile var isEnrolling`；`startManualEnrollment()` 进入，`stopManualEnrollment()`/成功自动结束 |
| 手动录入采集 | — | 新增 `onEnrollmentFrame(emb, score): Boolean`（录入模式下采集连续稳定帧，达阈值返回 `true` 表示可命名，并暂存待命名 embedding） |
| 命名保存 | `enroll(name, emb)` 直接存 | 新增 `addEnrolledFace(name, emb): Boolean`（校验重名/空名，保存并持久化，返回是否成功） |
| 删除 | 无 | 新增 `deleteFace(name): Boolean`（从库移除 + 持久化） |
| 未录入判定 | — | `recognize` 未命中时由上层据此置 `faceId="unregistered"` |

**移除/停用**：`pickName()`（异兽名自动分配）、`ENROLL_COOLDOWN_FRAMES`（冷却）、自动录入分支。

**名称管理**：
- 支持**自定义名称**（替代自动异兽名）；
- 保留 `SHENHAI_NAMES` 作为命名对话框的**默认建议/兜底**（用户可留空用默认名，或自定义）；
- `mGallery` 保持 `linkedMapOf<String, FloatArray>`，`deleteFace` 用 key 删除。

**线程安全**：`mGallery` 的写（录入/删除）在 UI 线程（弹框确认后），读（`getCount`/`recognize`）在算法线程——**改为 `CopyOnWriteArrayList`/同步集合或加锁**，避免并发读写竞争（见 FACEP-011 隐患项）。

### 4.2 `IFaceIDAlgorithm` / `FaceIDAlgorithmImpl`

- 复用现有 `getEnrolledCount()`（已实现，供列表/计数展示）；
- 新增或复用录入交互所需的透传方法（`FaceEnrollmentManager` 能力暴露）：
  - `startManualEnrollment()` / `onEnrollmentFrame(emb, score)` / `addEnrolledFace(name, emb)` / `deleteFace(name)` / `getEnrolledNames()`；
  - 可收敛为 `FaceEnrollmentManager` 直接暴露，`FaceIDAlgorithmImpl` 仅做 getter 透传（`mEnrollmentManager?.xxx()`）。
- `processFrame` 识别分支：`recognize` 未命中 → `faceId = "unregistered"`（供 UI 显示"未录入人脸"）；`isNewEnroll` 语义调整（不再由自动录入产生，改由手动录入流程处理）。

### 4.3 `RecognitionActivity` UI

| 控件 | 行为 |
|------|------|
| 「开始录入」按钮 | 调 `startManualEnrollment()`，进入录入模式，显示提示文案（"正在录入…"） |
| 录入成功弹框 | 结果回调（算法线程）→ `runOnUiThread` 弹 `AlertDialog`（`EditText` 输入名称 + 确认/取消） |
| 确认保存 | `addEnrolledFace(name, emb)` → 成功 `Toast「人脸录入成功」` + 刷新 `tv_enrolled_count`；失败（重名/空名）提示 |
| 取消 | 丢弃本次录入，恢复识别 |
| 未录入显示 | `faceId=="unregistered"` → `tv_face_id` 显示「未录入人脸」 |
| 「管理已录入人脸」 | 弹 `AlertDialog` 列表（或跳独立页），展示全部名称，每条带「删除」，删除前二次确认 |

**录入帧驱动**：录入模式激活时，`onAlgorithmResult` 里把当前帧 embedding 喂给 `onEnrollmentFrame`（替代正常识别展示），返回 `true` 即弹命名框。

### 4.4 数据流

```
[手动录入]
按钮 → startManualEnrollment()
每帧算法结果 → onEnrollmentFrame(emb) 采集稳定帧
   → 达到阈值 → 结束录入模式 + 暂存 emb + 弹命名框
确认 → addEnrolledFace(name, emb) → 持久化 → Toast + 刷新计数

[未录入识别]
recognize(emb) 未命中 → faceId="unregistered" → UI「未录入人脸」（不自动录入）

[人脸管理]
入口 → 列表面板 → deleteFace(name) → 二次确认 → 持久化 + 刷新列表/计数
```

---

## 5. 修改范围（草案）

| # | 文件 | 类型 | 内容 |
|---|------|------|------|
| 1 | `algorithm/FaceEnrollmentManager.kt` | 修改 | 停用自动录入；新增录入模式/`addEnrolledFace`/`deleteFace`/`onEnrollmentFrame`；`recognize` 仅匹配；`mGallery` 并发安全 |
| 2 | `algorithm/IFaceIDAlgorithm.kt` | 修改 | 新增录入/删除/名称列表的透传方法（含默认空实现） |
| 3 | `algorithm/FaceIDAlgorithmImpl.kt` | 修改 | 透传录入方法；`processFrame` 未命中置 `faceId="unregistered"` |
| 4 | `ui/RecognitionActivity.kt` | 修改 | 「开始录入」按钮/录入模式提示/命名弹框/Toast/未录入显示/管理列表+删除 |
| 5 | `res/layout/activity_recognition.xml` | 修改 | 新增「开始录入」「管理已录入人脸」按钮 |
| 6 | `res/values/strings.xml` | 修改 | 新增录入/管理相关文案 |
| 7 | `docs/设计方案.md` | 修改 | 补充 v3.0 后的人脸识别细化（或 v4.0） |
| 8 | `proposals/FACEP-012-…md` | 新增 | 本提案 |

---

## 6. 风险与注意事项

| 风险 | 影响 | 缓解 |
|------|------|------|
| 停用自动录入后无手动入口 → 人脸库为空 | 识别永远"未录入" | 明确「开始录入」按钮路径，保证可达 |
| 录入采集逻辑（连续稳定帧）与原自动录入重叠 | 复用 `ENROLL_CONSECUTIVE_FRAMES`/`ENROLL_CONFIDENCE` 常量，仅改变触发时机 | 提取为录入采集函数，手动/自动共用底层 |
| 命名冲突（重名/空名） | 保存失败或库数据混乱 | `addEnrolledFace` 校验重名/空名，失败返回并提示 |
| 删除误操作 | 人脸库丢失 | 删除前二次确认；`face_enrollments.json` 每次写后立即持久化 |
| `mGallery` 并发读写 | 数据竞争（录入/删除在 UI、识别在算法线程） | 同步集合/加锁（`CopyOnWriteArrayList` 或 `synchronized`） |
| 录入模式与正常识别/疲劳/分心 flag 冲突 | 录入时误判 | 录入模式下只消费识别模块结果，不入库未命名特征 |
| `faceId="unregistered"` 语义需全链路兼容 | 渲染/文案误判 | 在 `RecognitionActivity` 统一处理该语义；`FaceOverlayBridge` 按非命名脸处理（如 `detected`） |

---

## 7. 实施步骤（建议）

1. **改造 `FaceEnrollmentManager`**：停用自动录入、`recognize` 仅匹配、新增录入模式/`addEnrolledFace`/`deleteFace`、`mGallery` 并发安全；
2. **透传接口**：`IFaceIDAlgorithm`/`FaceIDAlgorithmImpl` 暴露录入/删除/名称列表方法，`processFrame` 未命中置 `unregistered`；
3. **识别 UI**：未录入显示 + 手动录入按钮 + 命名弹框 + Toast；
4. **人脸管理**：管理列表 + 删除 + 二次确认；
5. **验证**：录入→识别命名、未录入显示、删除后识别失效，设备端回归。

---

## 8. 结论

将人脸识别从"自动录入 + 随机命名"细化为**用户可控的三步闭环**：

1. **手动录入**：按钮控制录入 → 采集成功后弹框命名 → 确认保存 → Toast 反馈；
2. **未录入识别**：库外人脸明确显示「未录入人脸」，不再自动录入；
3. **人脸管理**：列表查看 + 删除已录入人脸。

核心改动集中在 `FaceEnrollmentManager`（录入/删除/仅匹配）与 `RecognitionActivity`（UI 交互），复用现有 embedding 比对与持久化能力，风险可控。

---

## 9. 实施结果（2026-08-21）

> 记录 FACEP-012 在代码库的落地情况。

### 9.1 `FaceEnrollmentManager`

| 改动 | 内容 |
|------|------|
| 停用自动录入 | `recognize()` 改为**仅匹配**（移除自动录入分支、`pickName()` 异兽自动命名、冷却计数）；未命中返回 `(null,false)` |
| 录入模式 | 新增 `isEnrolling`/`startManualEnrollment()`/`stopManualEnrollment()`/`onEnrollmentFrame(emb,score)`（复用 `ENROLL_CONFIDENCE=0.65` + `ENROLL_CONSECUTIVE_FRAMES=10` 连续稳定帧采集）、`pendingEmbedding()` |
| 命名保存 | 新增 `addEnrolledFace(name, emb): Boolean`（校验空名/重名，成功返回 true 并持久化） |
| 删除 | 新增 `deleteFace(name): Boolean`（移除 + 持久化） |
| 并发安全 | `mGallery` 所有读写加 `synchronized`（录入/删除在 UI、识别在算法线程） |
| 默认命名建议 | 新增 `defaultNameCandidates()`（未使用的异兽名，供命名框兜底） |

### 9.2 `IFaceIDAlgorithm` / `FaceIDAlgorithmImpl`

- 接口新增默认透传方法（`isEnrolling/startManualEnrollment/stopManualEnrollment/onEnrollmentFrame/pendingEmbedding/addEnrolledFace/deleteFace/getEnrolledNames/defaultNameCandidates`）；
- `FaceIDResult` 新增 `enrollmentReady: Boolean`（录入模式采集成功标记）；
- `processFrame`：录入模式下 `onEnrollmentFrame` 采集 → 成功置 `enrollmentReady`；非录入时 `recognize` 未命中 → `faceId="unregistered"`。

### 9.3 `RecognitionActivity` + UI

| 能力 | 实现 |
|------|------|
| 「开始录入/取消」按钮 | `btn_start_enroll`：进入/退出录入模式，录入中显示黄色提示"正在录入人脸…" |
| 命名弹框 | 采集成功 → `AlertDialog` + `EditText`，确认保存（空名/重名提示），取消丢弃 |
| Toast | 录入成功「人脸录入成功」；删除成功「已删除：xxx」 |
| 未录入显示 | `faceId=="unregistered"` → 「未录入人脸」 |
| 人脸管理 | `btn_manage_faces` → 名称列表（`AlertDialog`），点击条目 → 删除前二次确认 |
| 状态清理 | `stopPreview` 退出录入模式，避免下次进入残留 |

### 9.4 `FaceOverlayBridge`

- `isNamed` 排除 `unregistered`：未录入人脸**不显示名字标签**，且按**绿色 detected 框**（有效人脸）处理，而非 spoof 红色框。

### 9.5 遗留/待办

- 手动录入仅支持"录入一人后结束"，**不支持连续录入多人**（每次需重新点按钮，符合需求 1）；
- 命名框「确认」在重名/空名时保持弹框不关闭（用户可重新输入）；
- 删除人脸在算法线程/UI 线程均已加锁，但长时间运行下 `face_enrollments.json` 每次写全量，数据量大时可评估增量写。
