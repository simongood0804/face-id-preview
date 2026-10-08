# 视线估计模型演进与问题排查全记录

> 时间跨度：2026-08 下旬 ~ 2026-09-10
> 范围：设备端（SNPE/Android）与 deploy（Python/ptgaze）两侧视线估计链路
> 目标：两侧视线数值对齐（同口径残差 ≤0.5°）、模型几何可部署、可视化口径统一

## 0. 最终形态（先看结论）

当前生效的视线链路：

```
人脸检测(×1.3扩框) → pipnet68 landmark → 68点 solvePnP (R_hc, t_hc)
    → 融合眼中心3D (XY=landmark反投影射线, Z=PnP深度)
    → MPIIGaze 归一化 (M = K·S·R_n·K⁻¹, R_n = norm_rot·R_hc)
    → ptgaze_mpiigaze_fp32.dlc (多输入: image + head_pose)
    → 反归一化 gaze_cam = gaze_norm @ R_n → 相机系/世界系视线
```

关键定案：
- **头姿源**：68 点 solvePnP 的 R_hc，hopenet 已彻底弃用于视线归一化（仅保留分神检测/显示）
- **gaze 模型**：fp32 dlc（manifest: `ptgaze_mpiigaze_fp32.dlc`）
- **标准模型**：MEANSHAPE_68（MP468 全 68 点 + 严格左右对称）+ 三轴偏移表 `kMeanShape68FineTuneX/Y/Z`，生效表鼻尖 30 = 原点
- **标定内参**：`configs/dms_calibration.json`（fx=1710.7017 / fy=1708.3478 / cx=799.5 / cy=649.5），deploy 侧同款硬编码
- **角度口径**（端侧口径，两侧一致）：`yaw = atan2(-gx, -gz)`（向画面左看为正）、`pitch = asin(gy)`（向下看为正）
- **视线绘制**：设备端已改为 deploy 同款 3D 透视投影

---

## 阶段一：端侧工程链路打通（"塌缩"假象三部曲）

### 1.1 SNPE 多输入模型调用方式

**现象**：ptgaze_mpiigaze（image + head_pose 双输入）运行报 `Input count mismatch: expected 2, got 1`。

**根因**：单 tensor 的 `Run(tensor, ...)` 只喂 `input_pipelines[0]`。

**解决**：必须用 vector 重载 `ModelHandle::Run({tensor, tensor}, &outputs, user)`。注意 `utils::Tensor` 拷贝构造已删除，需构建非持有别名（`owns_data=false`）再 move 进 vector。

### 1.2 manifest 输入顺序错配

**现象**：镜像/腐蚀 patch 实验全部"无效"，模型输出对 patch 内容不敏感。

**根因**：SNPE 后端按**位置**编号输入（DLC 内部声明序通常为字母序，head_pose 在前），manifest inputs[i] 按位置绑定到后端 i。顺序错反时 image patch 被写进 head_pose 的 8 字节 UserBuffer。

**解决**：phase2_gaze manifest 改为 head_pose/image 排列（2026-08-27）。

**排查手段**：SDK 侧 `model.GetInputTensor(i)` 读回 buffer shape/首元素，一锤定音。

### 1.3 "塌缩"真根因：输入 shape/layout 错配（多次误诊后定案）

**现象**：端侧双眼输出几乎相同 ≈(-11°, +10°)，且系统性伪会聚（端侧 +25.1° vs deploy +4.5°）。

**误诊过程**（结论全部作废，保留作教训）：
1. 初诊"int8 模型在 DSP 上塌缩"→ 归因预处理节点 float 位模式 vs DLC TF8 编码不匹配 → warp 节点新增 float→TF8 字节量化输出（该修复本身有效但非根因）
2. 又发现 int8 head_pose 输入编码范围负值饱和（[-0.2754, 0]，正 yaw 被截断）

**真根因**（2026-09-03 定案）：manifest 中 ptgaze image 输入声明为 **NCHW [1,1,36,60]**，而 DLC 网络输入实际是 **NHWC 1x36x60x1**。SNPE 对 UserBuffer shape/layout 与网络不一致时**不报错**，静默按错误维度消费 2160 字节 → patch 数据被打乱 → 模型对 patch 内容不敏感。

**解决**：manifest image 输入改 `shape=[1,36,60,1], layout=NHWC`；删除 `dtype: float32` 覆盖。改对后 cpu/gpu/dsp runtime 立即正常。

**教训**：
- SNPE 静默容忍 shape 错配，"模型输出与输入无关"类症状优先查 shape/layout/顺序
- 一次只改一个变量做二分，避免多个"修复"叠加掩盖真根因

---

## 阶段二：头姿链路定案（hopenet → 68 点 PnP）

### 2.1 头姿输出约定

- hopenet：pitch 需取反对齐 SDK/PnP"低头为正"约定；"roll 取反（绕 Y 转 180° 假设）"的修正被证伪删除
- PnP 角度定义：`R_hc = Rz(roll)·Ry(yaw)·Rx(pitch)`（外旋 X→Y→Z，绕固定相机轴）；F 系 X=被摄者左、Y=下、Z=后脑；C 系 X=画面右、Y=画面下、Z=出镜头向前

### 2.2 归一化头姿源切换（2026-08-27 定案）

**决策**：视线归一化统一使用 68 点 solvePnP 的 R_hc；hopenet 彻底弃用（R_head 分支/GAZE_HEADPOSE_SRC 开关删除，headpose_6d 仅留分神检测）。

**依据**：修复后的 68 点 PnP R_hc 恰符合 MPIIGaze 归一化的人头坐标系约定（正脸左侧为 x 正向、后脑为 z 正向），可直接作为归一化 head_x 来源；hopenet 与 PnP 口径不一致且引入额外误差源。

**连带**：gaze 模型换 fp32 dlc。

---

## 阶段三：眼部归一化与 patch 链路对齐

### 3.1 透视变换方向反了

**现象**：patch 越界全 0 → 直方图均衡拉到 255 全白。

**根因**：deploy 将正向矩阵 `M = Knorm·scale·norm_rot·cam_inv` 传给 `cv2.warpPerspective`（OpenCV 内部用 M⁻¹ 做 dst→src 采样）；自写 `WarpPerspectiveBilinearGray` 是直接 dst→src 采样，必须传 **M 的逆 Minv**。

### 3.2 眼中心 3D 定位改为融合方式（2026-08-27）

**原问题**：纯模型驱动的眼中心投影与 2D landmark 眼中心存在 2~13px 固有差。

**解决**：XY = 68 点 landmark 眼角中点经 `PixelToCameraRay` 反投影射线（内含去畸变迭代），Z = solvePnP 深度。实现为 `MpiiGazeUserData.eye2d`（face_context 填充，warp 节点消费）；landmark 无效自动回退纯模型驱动。

**验证**：proj 与 lm_ctr 亚像素对齐（≤1px），视线数值仅微调（≤0.3°）。

### 3.3 眼中心口径统一

- 3D 眼中心：6 个眼周点三维均值（右 36..41 / 左 42..47），经 R_hc·m + t_hc 变换到相机系
- 2D 眼中心：Y 取 6 点 y 的 (max+min)/2，X 保持眼角中点

### 3.4 patch 区域与灰度统一

- patch 面板：36×60 原尺寸直方图均衡副本，不镜像不放大（右眼镜像仅作用于喂模型的缓冲）；`FaceResult.eye_patch` 从 [2][300×180] 改 [2][36×60]
- 灰度：BT.601 加权（0.299R + 0.587G + 0.114B），与 deploy `cv2.COLOR_BGR2GRAY` 语义一致
- warp 节点新增 `equalize` 参数（manifest params 可关）

### 3.5 归一化实现等价性验证（2026-09-07）

**方法**：同一组 R_hc + 融合眼中心 + 同图，分别喂 deploy `cv2.warpPerspective` 路径与设备端 warp 节点逐位复刻（float32、伴随矩阵求逆、dst→src 双线性、均衡 LUT、右眼镜像）。

**结论**：左眼 patch MAE=0.101 灰度级、右眼 0.025，Rn 元素差 ~1e-8，喂同一 fp32 ONNX 视线差 ≤0.0002° —— **两条实现等价，patch 差异全部来自输入（landmark/PnP/眼中心）**。

**单位陷阱**：deploy 眼中心米制 `S=0.6/dist`、设备毫米制 `S=600/dist`，喂错单位 patch 全饱和。

---

## 阶段四：MEANSHAPE_68 标准模型与偏移标定

### 4.1 标准模型重标定（2026-08-31）

- "MP468 全 68 点 + 严格左右对称"版本：iBUG68↔MP468 全 68 点语义映射（下颌 0-16 取自 FACE_OVAL 侧点），镜像配对平均 + 中线点 x=0（对称残差 <1e-12），质心居中，外眼距(36-45)=0.689175；鼻尖 30→MP 4
- 工具：`tools/pfld68_convert/gen_mean_shape68.py --write-c`
- ⚠️ 该表变化直接改变 PnP 头姿与视线归一化数值，部署后需重新对拍

**偏移存储约定**：三轴各一维数组 `kMeanShape68FineTuneX/Y/Z[68]`，程序加载时静态初始化合成 `kMShape68.v`，消费点直接读合成表。

### 4.2 Z 标定（2026-09-03 定案 v2）

**问题**：单人同姿态数据对 z 不可辨识——差分 z 与公共位姿存在简并谷；固定位姿调 z 与逐轮位姿重解全部发散；联合 LM 优化能收敛但沿谷过冲 ~3× 且单帧 PnP（设备协议）无法复现其位姿盆地。

**有效方案**："**joint 求方向 + 可部署指标线搜索定幅值**"：joint_optimize 给出谷方向后，在 eval_round（单帧多起点 PnP 重解，与设备协议一致）指标上扫幅值 α。18 帧跨场景结果：α=3.5mm 处 RMS 5.95→5.18px（↓13%），写入 kMeanShape68FineTuneZ（鼻梁压平、鼻翼前凸、眉后移、嘴前移，眼区/轮廓/内眼角冻结）。

**经验**：z 标定评估必须用可部署指标，joint 内部 cost 会骗人；世界视线剔除会把位姿多样性帧剔掉，z 标定时应放开。

### 4.3 标定工具规范化（2026-09-04）

`tools/pfld68_convert/finetune_offsets.py`：adjust_x/y/z 三函数 + apply_to_cpp 幂等写回 + 三模式 `--mode`（1 基础表评估 / 2 当前偏移评估 / 3 单人在线渐近标定）。方法文档：`docs/mean_shape_finetune.md`。

模式 3 核心机制：
- 姿态绝对值门控（|pitch|/|yaw|>30° 帧不参与）
- X：鼻梁居中帧计算，不限次微调
- Y：增量步进（分区锚点体系：眼=内眼角 39/42、鼻梁=27、嘴=外嘴角 48/54；分区间以眼为锚算"鼻梁-眼""嘴-眼"相对量；单步限幅 0.3mm）；Y 残差计算前先把模型绕 x 轴转 -pitch 归零去除俯仰耦合
- Z：头姿变化≥5° 触发，单步限幅 0.3mm
- 尺度：独立步进，以人头中心车辆系横向坐标 t_hw.x 目标 365mm（t_hw=R_wc^T·t_hc 纯旋转随尺度精确线性），单步限幅 1mm
- RMS 口径：重投影仅取非轮廓点 17-67；对比关键点 CMP_IDX=[27,28,29,39,42,48,54]（与设备端 kLmk3dPairs 一致）
- 保存判据 RMS<5px 且近 5 帧 std<0.3px；判据未达也落盘（saved=False 标记，不写回 cpp）

**实施陷阱记录**：
- `gn_z_step` 返回 mm，累加进模型单位 dz 前必须除以 mm_per_unit（曾出现 154mm 假偏移）
- `--round-robin` 是类别目录 glob 不是文件 glob，传错静默得 0 帧
- 用户手工改 cpp 会留注释掉的旧表拷贝 → 读路径先剥整行 // 注释再匹配；apply_to_cpp 写回曾命中注释块（`src.index` 命中 "// static const float ..."）→ 已加 `_find_uncommented`
- 人脸检测多人只保留面积最大框（已编入 libfacevision.so）

### 4.4 收敛性问题与逐步修复（2026-09-04 ~ 09-07）

**问题主线**：模式 3 长姿态流上 Y/Z 步进持续爬升不收敛（两轮实验 L2 差 87.45mm，复评 RMS 反而变差）。

演进路线：
1. 减小单步上限至 0.5mm：放缓但治标不治本
2. 7 点 RMS 口径 10 轮实验：轮间偏移块 L2 单调收窄 6.16→0.93mm，但 Z 仍 ~0.3mm/轮缓爬
3. **正视图固定姿位** `--frontal-fixed-pose`：正视图启动帧位姿钉死仅拟合 t，形状误差进偏移块而非伪 pitch；Y/Z 步进加 frontal_guard（不得使正视图固定位姿 RMS 恶化超 0.05px）→ Y 爬升显著缓解、Z 减半，可部署指标反而更优（10.23px < 基线 10.52px < cpp 10.70px）
4. `--frontal-y-uncapped`：正视图首次 Y 步进不限幅（把 pitch=0 先验误差一次性烘进 dy）
5. `--save-pose-check` 位姿一致性判据：保存前用 solve_pose7（复现设备 7 点解算）验证 pitch/yaw/roll 与先验差 ≤3°
6. **收敛冻结机制**（最终方案，用户定案"正视图投影误差+俯仰角都足够小→停止更新"）：固定位姿 RMS ≤5px 且 |pitch7−先验|≤3° 同时成立 → 尺度/X/Y/Z/P 全冻结

**Z 眉区爬升根因定案**：眉 17-26 在 Y 阶段恒冻结、P 无眉旋钮，Z 是眉唯一垂直自由度且只在倾斜帧触发；实测眉 ∂v/∂dz 随俯仰变号（仰头 +0.14 / 正视 −0.39 / 低头 −0.64 px/mm），俯仰无关的眉垂直残差被 Z 映射成随俯仰反号的 dz，事件分布不对称 → 净爬升 +2.9→+9.0mm/10 轮。

**冻结 10 轮验证**（mode3_10rounds_frz）：冻结于 R3 首帧触发，此后 1447 帧零步进；眉 dz 稳态 ±2.7mm vs 蠕变 +9.0mm；分区复评全面更优，正视图 7 点自由解 RMS 1.09px（现役 2.92px）。**frz 块已写回 cpp（kRealEyeOuterMm=101.51mm）**。

### 4.5 伪 pitch 机理（重要认知）

- pitch 是模型形状误差的吸收方向：pitch⟷tz⟷y偏移近简并，"复解 pitch"不可当真实头姿用于门控
- 17° 伪 pitch 之谜：纯基础模型 + 该脸 = 伪 pitch 本身（-17.08°/rms2.20px），旧手调块只是把它"凑"到 -4.18°；沿简并谷平移的偏移块不改伪 pitch
- 残差驱动的 Y/Z 步进沿谷平移，不直接优化伪 pitch → 位姿判据需显式约束
- 鼻尖 30 不是可动点：偏移块 [30] 冻结为 0（30 号=原点是部署不变量，且不在设备 7 点解算集）

### 4.6 原点不变量

生效表 kMShape68.v 的鼻尖 30 必须精确位于原点（与 deploy face_model_468.npz 口径一致）。基础表整体平移重锚（2026-09-07，v=(0,−0.047758,−0.513521)）；apply_to_cpp 写新偏移时自动平移 −Δ30 保持不变量。runtime `mm_per_unit = kRealEyeOuterMm/|v45−v36|` 取生效表外眼距（当前 101.51mm → 89.8mm/单位）。

---

## 阶段五：deploy vs 设备视线差异定位（2026-09-07 定案）

### 5.1 口径混比——"大差距"的主因

三套口径长期混用导致对拍结论反复摆动：

| 口径 | 定义 | 出现位置 |
|---|---|---|
| RAW 角 | 归一化系模型输出 (pitch, yaw) | deploy GazeResult.pitch/yaw |
| 相机系 | GC 向量反解 | 设备 face_context 打印 |
| 世界系 | R_wc 变换后 | 设备 W->dir 行 |

**端侧口径**（deploy 结果图/run_infer 摘要/设备 gaze_yaw、gaze_pitch 三者一致）：`yaw=atan2(-gx,-gz)`、`pitch=asin(gy)`。此前对拍表用的 `yaw=atan2(gx,-gz)`、`pitch=asin(-gy)` 整体差一个负号，数值大小与残差完全相同，直接对比误以为"对不上"。deploy RAW 角与端侧口径本身就差 1.4/3.5°（同图左眼），纯属口径差。

另：deploy Head 行 `as_euler("xyz")` 与设备 F->C 的 Rz·Ry·Rx 分解口径不同，头姿数字不可直接对比。

### 5.2 差异来源定量分解（2×2 输入分解 + patch 注入）

同图对拍（设备三连跑 bit-exact，ATLAS_DUMP 探针）：

| 因素 | 贡献（RAW 系） | 验证方法 |
|---|---|---|
| **patch 内容** | L 1.6~2.6° / R 0.3~1.7°（主导） | 真实 deploy patch 注入设备，RAW 角移动量与分解吻合 |
| int8 量化运行时 | ≤0.55° | fp32 ONNX vs 设备 int8 同输入 |
| Rn 几何 | ≤0.2° | RHC_OVERRIDE 扫描 |
| **hp 输入** | ≤0.09° | hp0 ±10° 扫描斜率 ~0.015°/°（归一化已把头姿从 patch 去除，hp 近似冗余 → **该解释仅对绕相机射线的 roll 分量成立，大角度下失效，见阶段七**） |

patch 内容差异根源：deploy 眼中心**深度** 814mm vs 设备 918mm（差 104mm，方向仅差 0.25°）→ S=0.6/dist 尺度差 12.7% → patch MAE 31~44 灰度级。

**patch 注入结果**：注入后设备 vs deploy(ONNX) 残差 cam 系全部 ≤0.52°。**结论：patch 内容对齐后两侧已实质对齐**；进一步收敛应修 deploy 眼中心深度口径。

### 5.3 模型空间对比（68 表 vs 468 canonical）

- 原点定义不同（468=鼻尖，68=质心），被各自 PnP 平移吸收，不影响眼中心 3D
- 尺度约定差 14%（468 自带 metric 外眼距 88.92mm vs 68 表标定 kRealEyeOuterMm）
- Procrustes 对齐后眼睛相对位置几乎一致（眼中点差 1.5mm）；单眼中心差 ±3mm 主要来自生效表 X/Y 偏移标定
- 眼中心定义口径差（deploy 16 点环极值中点 vs 设备 6 点均值）模型空间 <0.5mm——2D 眼心 ±3~5.5px 差来自检测 landmark 本身而非定义

### 5.4 证伪记录（避免重蹈）

- "deploy head_pose.estimate() 返回的不是眼球中心（≈鼻尖）"——**错的**：PipelineResult.reye_center 就是同一变量，投影实测在眼睛上；直接用 estimate() 返回值对拍完全没问题
- "run-to-run 两态摆动（pitch −17.8°/−20.8°）"——单图单进程 PnP 确定性，三连跑 bit-exact，未复现
- "deploy 与设备 patch 注入结果一样"——两次注入的本来就是同一份真实眼睛 patch，伪命题

### 5.5 实验基础设施

设备端实验开关（face_mpiigaze_warp_node，env 控制，缺省关闭零影响）：
- `ATLAS_GAZE_RHC_OVERRIDE="r00,...,r22"`：patch 归一化 R_n 与 head_pose_2d 头姿改用外部值
- `ATLAS_GAZE_PATCH_OVERRIDE=<file>`：patch 改用外部归一化结果（未镜像 36×60 灰度左+右 2×2160 字节）

dump 口径注意：face_context RAW 行打印 ga 原值，右眼 y 为镜像还原**前**符号，与 deploy 比较需先取反。

---

## 阶段六：视线绘制机制统一（2026-09-07 ~ 09-08）

### 6.1 两侧绘制机制对比

| | 设备端（改造前） | deploy |
|---|---|---|
| 投影 | 2D 正交：屏幕方向取 (gx,gy)，固定 140px | 3D 透视：[眼心, 眼心+0.15m·g] 经 cv2.projectPoints |
| 头姿参与 | 不参与 | 不参与（仅门控 + 独立显示） |

**结论**：两侧绘制都不读头姿角度；头姿在估计阶段已"烘进"视线向量（gaze_cam = gaze_norm @ R_n），绘制时零参与。

### 6.2 透视径向伪影（为什么 deploy 的图"看着不对"）

cv2.projectPoints 透视投影下，深度分量大的线段（视线 gz≈−0.99 朝相机）屏幕方向被"起点相对主点的方位"主导（径向外扩/内缩），真实俯仰/偏航分量被淹没甚至抵消：

- 头姿 Z 轴（实为脸前向 R_hc·(0,0,−1)）：文本 pitch=−7° 完全一致地画进了 3D 方向，但投影后"俯仰向上 14.8px + 径向向下 12.6px ≈ 抵消"，屏幕上看着俯仰≈0
- 视线箭头：打印 pitch 仅 1.6/4.1°，箭头却"明显下倾"——两眼都在主点下方，径向外扩≈向右下，把箭头拽向下方（R 眼真实向左 −25px 被径向向右 +20px 抵消，近乎竖直向下）

**判读规则：deploy 结果图上箭头方向 ≠ 视线方向，只有文本数值可信。**

### 6.3 设备端改为 deploy 同款透视投影（2026-09-08 完成）

- `main.cc draw_gaze_ray`：[眼中心3D, 眼中心+150mm·g] 针孔投影（无畸变，等价 cv2.projectPoints 零外参），起点白圈+实心圆点；无内参时回退旧正交画法并打印提示
- 新增 API `face_vision_get_camera_intrinsic`（face_api.h/.cpp + version_script.lds 导出）：内参可能来自 zone-config（main.cc 局部变量拿不到），从 handle 读实际生效值，覆盖 --cam-intrinsic 与 zone-config 两种来源
- 设备验证：投影落位与数学逐位一致
- ⚠️ 纯展示层改动，视线数值/头姿输出完全不受影响；继承了 deploy 的径向伪影（用户知情选择，对拍以数值为准）

---

## 阶段七：大角度视线输出坍缩（模型对头姿不敏感，2026-09-10 定案）

**现象**（fenxin_result 18 组数据；地面真值由用户确认：受试者注视目标点，目标位于头姿方向附近）：

- 文件夹 5/6/7 头姿越来越偏离相机（hp_yaw 中位 +15.7 → +26.3 → +35.1°），视线输出却始终贴在相机轴附近（out_yaw 中位 +4.4 → +7.0 → +4.5°，近常数）
- 视线误差 ≈ 头姿偏角本身（输出−真值 P90 达 44.3°）；输出 pitch 逐帧恒为 ≈−12.5°（浮点级相同）

**排查结论：非坐标转换错误，是模型推理输出坍缩**。三层证据：

1. **输入侧无 bug**：逐帧复算 hp_2d（R_n 由融合眼中心 + R_hc 第 0 列按 warp 节点同构公式构造），物理口径正确跟踪头姿（5/6/7 = +15.7/+26.3/+35.1°，与 F->C yaw 一致）——模型"看到了"正确头姿；
2. **转换链数学正确**：norm↔denorm 往返误差 5.6e-4；world = 纯旋转·cam 残差 1e-3；5.2 节 patch 注入对拍（同 patch 设备 vs deploy ≤0.52°）；
3. **输出不跟随输入**：out_yaw ~ hp_yaw 斜率 **−0.05**（完美模型应 =1，相关 −0.09 ≈ 0）；out_yaw 全域压缩在 [−10, +14]° 而需求覆盖 ±40°；out_pitch 对 hp_pitch（真值 −10~+14°）纹丝不动。

**关键恒等式**（真值校验的简化依据）：本链路口径下 hp_2d(pitch,yaw) ≡ spherical(后脑方向)，与 spherical(头前向) 在输出约定下逐位相同——即"目标沿头前向"⟺ 完美模型输出 = hp_2d（物理口径）。真值校验退化为 out ≈ hp。

**机理**：

- 模型对 hp_2d 输入近乎不敏感（5.2 节历史扫掠：±10° → 输出 0.015°/°）。5.2 节"归一化已把头姿从 patch 去除，hp 近似冗余"的解释**仅对绕相机射线的 roll 分量成立**——R_n 只消除滚转，头相对射线的 yaw/pitch 仍留在 patch 外观与 hp_2d 中；大角度下模型必须利用 hp 或从眼角几何推断头姿，两者均未做到；
- ptgaze/MPIIGaze 模型在桌面 RGB 数据上训练，训练标签（屏幕随机点）集中在相机轴 ±15° 附近 → 车载 IR patch 域差下输出回归训练先验均值 ≈ (+2.5°, −12.5°)——即"视线贴相机轴且微微向下"的表象。

**论证修正**：此前"辐辏角 L−R +5.4° ≈ 注视相机几何预期 4.8°"的验证在"模型常数先验 + 右眼镜像取反"下同样复现（常数 c → L−R = 2c），不具判别力，从证据链中剔除；往返数学与世界系纯旋转两项仍然无条件成立。

**功能影响**：分神判定会把"头转开 + 眼随头"（看侧窗/侧方目标）误判为看前路——漏检。

**改进方向**：

- 车载域数据微调/重训（覆盖大头姿 + 已知目标点标签）；
- 评估对头姿敏感的大角度视线模型（Gaze360/GazeTR 类）；
- 短期兜底：|hp_2d| 超阈值时对视线输出降权/标记低置信。

**复现方法**：从 dump 行（F->C rot + `-eye cam: center/vec`）构造 R_n（z=眼中心方向，y=z×R_hc[:,0]，x=y×z），复算 hp_2d 与隐含输出 spherical(R_n·gaze_cam)，对 |hp_2d| 分桶/回归即得坍缩斜率；眼-相机方向夹角与 |头姿yaw| 相关 ≈0 亦可佐证非系统性转换错误。

---

## 附：工程经验速查

| 类别 | 要点 |
|---|---|
| 构建 | libfacevision.so 需 ANDROID_NDK_HOME + SNPE_SDK_PATH 两个环境变量，缺 SNPE 路径会**静默**产出 stub 后端（"Failed to initialize FaceID"） |
| 部署 | 必须用 tools/make.sh 构建动态链接 faceid_example（bazel 静态链接在设备上 std::bad_cast）；faceid_example 静态链接核心，**改模型表必须重编并推送 faceid_example 本身**，只推 .so 无效；设备掉线重连后需重推 .so 和可执行文件 |
| SNPE | 多输入用 vector Run；manifest inputs 顺序=DLC 字母序；shape/layout 错配静默不报错 |
| 对拍 | 先统一口径（RAW/cam/world、端侧符号、euler 约定）；单图多次运行取同批；设备 dump 右眼 RAW y 需先取反；单位（米 vs 毫米）与通道序（RGB）陷阱 |
| 标定 | 评估必须用可部署指标（单帧 PnP 重解）；joint cost 会沿简并谷骗人；伪 pitch 是形状误差吸收方向，不可当真值门控；鼻尖 30 冻结=原点不变量 |
| 标定文件 | 内参唯一来源 configs/dms_calibration.json：设备端 push 后自动加载进 handle；deploy 侧硬编码需手动同步 |
| 视线 | 大角度（|hp_2d|>15°）下 ptgaze 模型输出坍缩贴相机轴（对 hp 输入斜率 0.015°/° + 车载域差）；"归一化已把头姿从 patch 去除"仅对 roll 分量成立；辐辏角类验证可被"常数先验+右眼镜像"复现，判别要用 out~hp 斜率 |
