# FACEP-007: 头姿朝向坐标系绘制

**状态：已完成** ✅

## 概述

基于算法返回的头姿角度（Pitch/Yaw/Roll），在两眼中间点绘制红(X)/绿(Y)/蓝(Z)三轴坐标系，直观展示人脸朝向。

## 动机

当前已通过 `FaceResult.headPitch/Yaw/Roll` 获取头姿数据，并以文字形式显示。为进一步提升可读性，增加可视化坐标系指示面部朝向。

## 设计

### 坐标系原点

**两眼中间点**，从 5 关键点中取：

- 左眼：`keypoints[0]`（index 0 = 左眼中心）
- 右眼：`keypoints[1]`（index 1 = 右眼中心）
- 中点：`((leftEye.x + rightEye.x) / 2, (leftEye.y + rightEye.y) / 2)`

若关键点数据不可用，则 fallback 到人脸框中心。

原点绘制为白色实心圆点（半径 5px）。

### 坐标轴

| 轴 | 颜色 | 含义 | 控制角度 |
|----|------|------|----------|
| X | 红色 | 脸部朝向 | Yaw（偏航）+ Pitch（俯仰） |
| Y | 绿色 | 面部上方（鼻梁方向） | Roll（翻滚） |
| Z | 蓝色 | 面部正前方（垂直于面部平面） | Roll（翻滚） |

- **X 轴**：由 yaw/pitch 决定方向，前置摄像头 yaw 取反以匹配镜像画面
- **Y 轴**：默认垂直向上（-90°），仅 roll 控制旋转
- **Z 轴**：默认水平向左（180°），仅 roll 控制旋转，长度为 X/Y 的 0.7 倍

### 绘制样式

| 属性 | X 轴 | Y 轴 | Z 轴 |
|------|------|------|------|
| 颜色 | 红色 | 绿色 | 蓝色 |
| 线宽 | 3px | 3px | 3px |
| 长度 | faceW × 1.2 | faceW × 1.2 | faceW × 0.84 |
| 箭头 | 有（10px 三角） | 有 | 有 |

### 其他绘制调整

- 106 密集地标：黄色，半径 1px（缩小）
- 5 关键点：紫色，半径 3px（缩小）
- 移除了黄色虚线裁剪框和绿色/红色人脸框

### 影响范围

| 文件 | 改动 |
|------|------|
| `FaceOverlayView.kt` | 新增坐标轴画笔（mAxisX/Y/ZPaint）+ `drawHeadPoseArrow()` + `drawArrowHead()` 方法；调整关键点颜色/大小；移除人脸框和裁剪框绘制 |
| 其他文件 | 无需修改 |

## 数据流

```
FaceResult.headYaw/headPitch/headRoll
  → IFaceIDAlgorithm.FaceIDResult.headposeYaw/Pitch/Roll
    → FaceOverlayView.FaceBox.pitch/yaw/roll
      → FaceOverlayView.onDraw() → drawHeadPoseArrow()
```

## 约束

1. 仅当 `keypoints` 非空且 ≥ 2 个点时计算两眼中间点，否则 fallback 人脸框中心
2. 仅当人脸被检测到（`FaceType.DETECTED`）时绘制坐标系
3. 所有坐标基于原图空间，绘制时缩放至 View 空间
4. Y/Z 轴仅由 roll 控制，不受 yaw/pitch 影响，保持稳定
