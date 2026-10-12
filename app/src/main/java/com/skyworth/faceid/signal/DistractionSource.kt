/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.signal

/**
 * 分心数据源（FACEP-016）。
 *
 * 决定 `DistractionStateMachine`（分心监测模块）的分心输入用**算法返回结果**还是
 * **业务层自研判断结果**。作用域：仅分心监测模块（疲劳/识别等不受影响）。
 *
 * 默认 [SDK]（用算法返回的 `gazeDistracted`）；切换状态需持久化（见 [DistractionSourceStore]）。
 */
enum class DistractionSource {
    /** 用算法返回的 `result.gazeDistracted`（现状，黑盒）。默认值。 */
    SDK,

    /** 用自研 [com.skyworth.faceid.zone.GazeFallpointDetector] 算出的落点区域分心。 */
    SELF
}
