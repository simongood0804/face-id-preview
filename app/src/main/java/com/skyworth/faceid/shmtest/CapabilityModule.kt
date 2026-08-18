package com.skyworth.faceid.shmtest

/**
 * 算法能力模块（FACEP-011）。
 *
 * 把 `:algo` 算法进程的输出拆分为**可独立订阅**的能力模块，每个模块对应
 * 一个 [ShmQueue] topic id。外部 App 模块按需 `subscribe` 其中一类或多类，
 * 只接收所需模块输出（按需订阅/发布）。
 *
 * 「单模块输出依赖」：每个能力模块作为独立输出单元；模块间依赖（如
 * DISTRACTION 依赖 FACE_DETECT + VEHICLE_SPEED）由算法进程内部解析，
 * 对外透明——消费者订阅某模块时，算法进程自行保证其依赖已就绪。
 */
enum class CapabilityModule(val topic: Int, val description: String) {

    /** 人脸检测：人脸框、置信度、5 关键点。无依赖。 */
    FACE_DETECT(0x01, "人脸检测（人脸框/置信度/5关键点）"),

    /** 头姿：pitch/yaw/roll（度）。依赖 FACE_DETECT。 */
    HEADPOSE(0x02, "头姿（pitch/yaw/roll）"),

    /** 视线：gazeValid/gazeYaw/gazePitch。依赖 FACE_DETECT。 */
    GAZE(0x03, "视线（有效位/yaw/pitch）"),

    /** 分心：分心标志、触发阈值、档位。依赖 FACE_DETECT + VEHICLE_SPEED。 */
    DISTRACTION(0x04, "分心（标志/阈值/档位）"),

    /** 车速：当前车速 km/h、有效性。无依赖。 */
    VEHICLE_SPEED(0x05, "车速（km/h/有效性）");

    companion object {
        private val ALL: List<CapabilityModule> by lazy { values().toList() }

        /** 按 topic id 反查模块；未知返回 null。 */
        fun fromTopic(topic: Int): CapabilityModule? =
            ALL.firstOrNull { it.topic == topic }

        /** 校验模块集合是否合法（不含 null，topic 互不冲突）。 */
        fun valid(modules: Collection<CapabilityModule>): Boolean =
            modules.all { m -> m.topic in ALL.map { it.topic } }
    }
}
