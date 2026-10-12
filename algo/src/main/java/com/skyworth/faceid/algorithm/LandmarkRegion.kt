/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.algorithm

/**
 * 面部语义区域枚举。
 *
 * 判定逻辑（睁闭眼 / 开合嘴）只依赖这些稳定、与具体算法无关的语义概念，
 * **不直接引用任何模型点位索引**。具体索引由 [LandmarkIndexMapping] 提供，
 * 从而在更换算法 / 点位定义时，只需改映射表，判定逻辑零改动。
 *
 * 方向说明：以下"左/右"以**观察者视角**描述（观察者看到的屏幕左/右），
 * 不代表人脸生理学上的左右。镜像不影响判定逻辑。
 *
 * 上下眼睑：`*_UPPER_LID`（眼上方）/ `*_LOWER_LID`（眼下方），
 * 其索引数组按"同一列"逐点对应，供开合度（EAR）计算使用。
 */
enum class LandmarkRegion {
    /** 左眼上眼睑点集（观察者视角）。 */
    LEFT_EYE_UPPER_LID,

    /** 左眼下眼睑点集（观察者视角）。 */
    LEFT_EYE_LOWER_LID,

    /** 左眼眼尾（外侧）。 */
    LEFT_EYE_OUTER_CANTHUS,

    /** 左眼眼角（内侧，靠近鼻侧）。 */
    LEFT_EYE_INNER_CANTHUS,

    /** 右眼上眼睑点集（观察者视角）。 */
    RIGHT_EYE_UPPER_LID,

    /** 右眼下眼睑点集（观察者视角）。 */
    RIGHT_EYE_LOWER_LID,

    /** 右眼眼尾（外侧）。 */
    RIGHT_EYE_OUTER_CANTHUS,

    /** 右眼眼角（内侧，靠近鼻侧）。 */
    RIGHT_EYE_INNER_CANTHUS,

    /** 上唇（外边缘）。 */
    MOUTH_UPPER_LIP,

    /** 下唇（外边缘）。 */
    MOUTH_LOWER_LIP,

    /** 左嘴角。 */
    MOUTH_LEFT_CORNER,

    /** 右嘴角。 */
    MOUTH_RIGHT_CORNER
}
