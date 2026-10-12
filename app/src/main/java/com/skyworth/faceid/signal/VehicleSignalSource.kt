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
 * 车机车速信号源。
 *
 * 封装 Car VHAL 的 [CarPropertyManager] 车速订阅（PERF_VEHICLE_SPEED），
 * 行为与 [PreviewActivity] 现有逻辑保持一致：
 * - 连接失败 / 服务断开 / 属性错误 → 车速置为 -1（无效），按最严格档处理；
 * - 通过 [onSpeedChanged] 回调将转换后的 km/h 车速输出，由外部决定发布到总线。
 *
 * 故障隔离：本模块仅输出"车速值 + 有效性"，不抛异常到外部；
 * 连接异常被捕获并转为无效车速信号，不影响其他信号。
 */
class VehicleSignalSource(
    private val context: Context
) {
    private val TAG = "VehicleSignalSource"

    /** 当前车速（km/h）。-1 表示无车速数据。 */
    @Volatile
    var speedKmh = -1f
        private set

    /** 车速数据是否有效。 */
    @Volatile
    var isValid = false
        private set

    /** 车速变化回调（在 Car 回调线程触发，外部自行处理线程切换）。 */
    @Volatile
    var onSpeedChanged: ((SignalTypes.VehicleSpeed) -> Unit)? = null

    private var mCar: Car? = null
    private var mCarPropertyManager: CarPropertyManager? = null

    /**
     * 连接 Car 服务并订阅车速。
     */
    fun connect() {
        if (mCar != null) return
        try {
            mCar = Car.createCar(context, mServiceConnection)
        } catch (e: Exception) {
            Log.w(TAG, "connect: createCar failed, speed invalid", e)
            speedKmh = -1f
            isValid = false
        }
    }

    /**
     * 释放 Car 服务连接。
     */
    fun disconnect() {
        mCarPropertyManager?.unregisterCallback(mSpeedCallback)
        mCarPropertyManager = null
        try {
            mCar?.disconnect()
        } catch (_: Exception) {
        }
        mCar = null
        speedKmh = -1f
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
                // 使用 SENSOR_RATE_FAST(10Hz) 与 VEHICLE_SPEED topic 的 10Hz 基准频率匹配，
                // 保证即使车速稳定不变也周期性上报，避免总线健康检查（超时=3s/10Hz=300ms）
                // 因 1Hz 上报间隔(1000ms) 大于超时而被误判为失活、持续误报 SIGNAL_ERROR。
                pm.registerCallback(mSpeedCallback,
                    VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FAST)
                Log.i(TAG, "onServiceConnected: subscribed PERF_VEHICLE_SPEED @10Hz")
            } catch (e: Exception) {
                Log.w(TAG, "onServiceConnected: error, speed invalid", e)
                speedKmh = -1f
                isValid = false
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "onServiceDisconnected: car service disconnected, speed invalid")
            mCarPropertyManager = null
            speedKmh = -1f
            isValid = false
        }
    }

    /** 车速变化回调：PERF_VEHICLE_SPEED 为 Float，单位 m/s，转成 km/h。 */
    private val mSpeedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val speedMs = value.value as? Float ?: return
            speedKmh = speedMs * 3.6f  // m/s -> km/h
            isValid = true
            onSpeedChanged?.invoke(SignalTypes.VehicleSpeed(speedKmh, true))
        }

        override fun onErrorEvent(propertyId: Int, zoneId: Int) {
            Log.w(TAG, "onErrorEvent: property error prop=$propertyId zone=$zoneId")
            speedKmh = -1f
            isValid = false
            onSpeedChanged?.invoke(SignalTypes.VehicleSpeed(-1f, false))
        }
    }
}
