package com.skyworth.faceid.signal

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

/**
 * 驾驶门开关信号源（作为眼/嘴阈值校准的复位触发，见 FACEP-010 §3.7.6-D）。
 *
 * 封装 Car VHAL 的 [CarPropertyManager] 车门属性订阅（默认 [VehiclePropertyIds.DOOR_LOCK]），
 * 行为与 [VehicleSignalSource] 一致：
 * - 连接失败 / 服务断开 / 属性错误 → 门信号置为无效；
 * - 通过 [onDoorChanged] 回调输出转换后的门状态，由外部决定发布到总线与触发校准复位。
 *
 * 故障隔离：本模块仅输出"门开合状态 + 有效性"，不抛异常到外部；
 * 门信号缺省时，眼/嘴校准退化为纯运行中自适应（不影响基础判定）。
 *
 * > 注意：车门"开/合"的精确 VHAL 属性 ID（DOOR_LOCK 或平台自定义）需在实施时
 * > 按实际车载 HAL 确认；此处默认 DOOR_LOCK（锁状态），其值映射仅作合理默认。
 */
class DoorSignalSource(
    private val context: Context
) {
    private val TAG = "DoorSignalSource"

    /** 当前驾驶门是否打开。 */
    @Volatile
    var isOpen = false
        private set

    /** 门信号是否有效。 */
    @Volatile
    var isValid = false
        private set

    /** 门状态变化回调（在 Car 回调线程触发，外部自行处理线程切换）。 */
    @Volatile
    var onDoorChanged: ((SignalTypes.DoorState) -> Unit)? = null

    private var mCar: Car? = null
    private var mCarPropertyManager: CarPropertyManager? = null

    /**
     * 连接 Car 服务并订阅车门属性。
     */
    fun connect() {
        if (mCar != null) return
        try {
            mCar = Car.createCar(context, mServiceConnection)
        } catch (e: Exception) {
            Log.w(TAG, "connect: createCar failed, door invalid", e)
            isValid = false
        }
    }

    /**
     * 释放 Car 服务连接。
     */
    fun disconnect() {
        mCarPropertyManager?.unregisterCallback(mDoorCallback)
        mCarPropertyManager = null
        try {
            mCar?.disconnect()
        } catch (_: Exception) {
        }
        mCar = null
        isOpen = false
        isValid = false
    }

    /** Car 服务连接回调。 */
    private val mServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            try {
                val car = mCar ?: return
                if (!car.isConnected) return
                val pm = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return
                mCarPropertyManager = pm
                // 门开关为低频事件，用 SENSOR_RATE_NORMAL 订阅即可（事件驱动上报）
                pm.registerCallback(mDoorCallback,
                    VehiclePropertyIds.DOOR_LOCK, CarPropertyManager.SENSOR_RATE_NORMAL)
                Log.i(TAG, "onServiceConnected: subscribed DOOR_LOCK")
            } catch (e: Exception) {
                Log.w(TAG, "onServiceConnected: error, door invalid", e)
                isValid = false
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "onServiceDisconnected: car service disconnected, door invalid")
            mCarPropertyManager = null
            isValid = false
        }
    }

    /** 门状态变化回调。 */
    private val mDoorCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            // DOOR_LOCK 是 int[]（每扇门的锁状态），驾驶门一般 index 0（区域按平台定义）。
            // 值 >0（LOCKED）通常对应门锁住；此处以"非 LOCKED/有门事件"作为触发参考，
            // 具体区域与语义需按平台确认（见类注释）。
            val arr = value.value as? IntArray ?: return
            // 驾驶门锁状态：若任一门处于解锁/开合状态视为"可能换驾驶员"事件
            val open = arr.any { it == DOOR_UNLOCKED }
            isOpen = open
            isValid = true
            onDoorChanged?.invoke(SignalTypes.DoorState(open, true))
        }

        override fun onErrorEvent(propertyId: Int, zoneId: Int) {
            Log.w(TAG, "onErrorEvent: property error prop=$propertyId zone=$zoneId")
            isValid = false
            onDoorChanged?.invoke(SignalTypes.DoorState(false, false))
        }
    }

    companion object {
        /** DOOR_LOCK 值：未锁（对应门可开合，作为"可能换人"触发）。 */
        private const val DOOR_UNLOCKED = 0
    }
}
