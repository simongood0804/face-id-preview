package com.skyworth.faceid.zone

/**
 * 头姿射线注意点位判定。
 *
 * **坐标系**：`X = 人的右侧 / Y = 人脸朝向(前) / Z = 向上`，单位 **毫米(mm)**。
 * （以摄像头拍摄视角下的驾驶人头姿为准；需求原始数值以 **厘米(cm)** 给出，
 * 本类统一换算为 mm 并标注原值。）
 *
 * 判定分两步：
 *
 * **第一步（是否分心）**：头姿射线与 `Y = 680mm`（原 68cm，**前方 680mm 的竖直平面**）
 * 求交，取交点的**高度 Z**：
 *  - `Z > 700mm`（原 70cm）→ **不分心**（视线落点够高，视为平视前方）
 *  - 否则 → 分心，进入第二步
 *
 * **第二步（注意点位）**：射线与 `Z = 750mm`（原 75cm，**高度 750mm 的水平面**）求交，
 * 得到落点 `(x, y)`（x=横向、y=前向距离），与 5 个候选点比较**二维距离**
 * （z 恒为 750，故只比 x/y），最近者即注意点位：
 *  - 点2 `(0, 0, 750)`     原 (0, 0, 75)
 *  - 点3 `(150, 0, 750)`   原 (15, 0, 75)
 *  - 点4 `(365, 100, 750)` 原 (36.5, 10, 75)
 *  - 点5 `(365, 0, 750)`   原 (36.5, 0, 75)
 *  - 点6 `(550, 0, 750)`   原 (55, 0, 75)
 *
 * 注：点1 `(0,0,0)` 为坐标原点，按需求**不参与**判定。
 *
 * 线程安全：无状态，可任意线程调用。
 */
class HeadRayZoneDetector {

    /** 单帧判定结果。 */
    data class Prediction(
        /** 是否分心（第一步结果）。 */
        val isDistracted: Boolean,
        /** 命中的注意点位编号（2~6）；未分心或射线无效时为 [NONE]。 */
        val pointId: Int,
        /**
         * `Y=680mm` 平面交点的**高度 Z**（mm）；射线与平面平行（无交点）时为 NaN。
         * 即 UI 上显示的 "gazeZ"，用于调试/可视化。
         */
        val intersectY68Z: Float = Float.NaN,
        /**
         * `Z=750mm` 平面交点坐标 `[x, y]`（mm，x=横向、y=前向距离）；无交点时为 null。
         * 仅在分心（第二步执行）时计算。
         */
        val intersectZ75: FloatArray? = null
    ) {
        companion object {
            /** 未命中任何点位。 */
            const val NONE = -1

            /** 判定无效（无人脸/无有效头姿射线）的结果。 */
            val INVALID = Prediction(isDistracted = false, pointId = NONE)
        }
    }

    companion object {
        /** 第一步求交平面：前方 Y = 680mm（原 68cm）。 */
        const val PLANE_Y_MM = 680f

        /** 第一步不分心阈值：交点**高度** Z > 700mm（原 70cm）。 */
        const val NO_DISTRACT_Z_MM = 700f

        /** 第二步求交平面：高度 Z = 750mm（原 75cm）。 */
        const val PLANE_Z_MM = 750f

        /**
         * 候选注意点位（`[x, y]`，z 均为 [PLANE_Z_MM]）。
         * 顺序与点位编号 2~6 一一对应（下标 0 → 点2）。
         */
        val POINTS: Array<FloatArray> = arrayOf(
            floatArrayOf(0f, 0f),        // 点2：(0, 0, 750)，原 (0, 0, 75)
            floatArrayOf(150f, 0f),      // 点3：(150, 0, 750)，原 (15, 0, 75)
            floatArrayOf(365f, 100f),    // 点4：(365, 100, 750)，原 (36.5, 10, 75)
            floatArrayOf(365f, 0f),      // 点5：(365, 0, 750)，原 (36.5, 0, 75)
            floatArrayOf(550f, 0f)       // 点6：(550, 0, 750)，原 (55, 0, 75)
        )

        /** 点位编号（与 [POINTS] 下标对应，2~6）。 */
        val POINT_IDS = intArrayOf(2, 3, 4, 5, 6)

        /** 求交参数 λ 的近零下限（视为起点落在平面上，交点即起点）。 */
        private const val MIN_LAMBDA = 1e-4f
    }

    /**
     * 单帧判定。
     *
     * @param pos 头姿射线起点（通常为 `headHwT`）；null 或长度 <3 视为无效
     * @param dir 头姿射线方向（通常为 `headDir`）；null 或长度 <3 视为无效
     * @param valid 上游有效性（需 `flags&HEADFRAME` 且 `headDirValid==1`）
     * @return 判定结果；无效输入返回 [Prediction.INVALID]
     */
    fun update(pos: FloatArray?, dir: FloatArray?, valid: Boolean): Prediction {
        if (!valid || pos == null || dir == null) return Prediction.INVALID
        if (pos.size < 3 || dir.size < 3) return Prediction.INVALID

        val px = pos[0]; val py = pos[1]; val pz = pos[2]   // X=右，Y=前，Z=上
        val dx = dir[0]; val dy = dir[1]; val dz = dir[2]

        // ---- 第一步：与 Y = 680mm（前方 680mm 的竖直平面）求交 ----
        // 沿 Y 方向走到该平面：λ = (680 - py) / dy。
        // 注意：只要射线不与平面平行（dy≠0）就必然相交（平面无限），
        // λ 为负仅表示交点在射线反方向（头后方），仍照算——不视为无效。
        if (dy == 0f) {
            // 与前方平面平行：射线恒在某个 y 上，无法定义交点
            return Prediction.INVALID
        }
        val lambdaY = (PLANE_Y_MM - py) / dy
        // 交点高度（Z 分量）
        val zAtY68 = pz + lambdaY * dz

        // 高度超过阈值 → 不分心（无需计算点位）
        if (zAtY68 > NO_DISTRACT_Z_MM) {
            return Prediction(isDistracted = false, pointId = Prediction.NONE, intersectY68Z = zAtY68)
        }

        // ---- 第二步：与 Z = 750mm（高度 750mm 的水平面）求交，落点比距离 ----
        if (dz == 0f) {
            // 射线水平，与 Z=750 平面平行：无法得到落点，但仍已判为分心
            return Prediction(isDistracted = true, pointId = Prediction.NONE, intersectY68Z = zAtY68)
        }
        val lambdaZ = (PLANE_Z_MM - pz) / dz
        val xAtZ75 = px + lambdaZ * dx
        val yAtZ75 = py + lambdaZ * dy

        // 与各候选点比较二维距离（z 恒为 750，只需比 x/y），取最近
        var bestIdx = Prediction.NONE
        var bestDistSq = Float.MAX_VALUE
        for (i in POINTS.indices) {
            val ddx = xAtZ75 - POINTS[i][0]
            val ddy = yAtZ75 - POINTS[i][1]
            val distSq = ddx * ddx + ddy * ddy
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                bestIdx = i
            }
        }

        return Prediction(
            isDistracted = true,
            pointId = if (bestIdx >= 0) POINT_IDS[bestIdx] else Prediction.NONE,
            intersectY68Z = zAtY68,
            intersectZ75 = floatArrayOf(xAtZ75, yAtZ75)
        )
    }

    /** 将判定结果中的点位编号映射为可读名称（调试用）。 */
    fun pointName(pointId: Int): String = when (pointId) {
        2 -> "点2(0,0,750)"
        3 -> "点3(150,0,750)"
        4 -> "点4(365,100,750)"
        5 -> "点5(365,0,750)"
        6 -> "点6(550,0,750)"
        else -> "无"
    }
}
