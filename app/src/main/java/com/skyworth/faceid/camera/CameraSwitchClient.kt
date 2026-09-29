// 本车 6 路（编号即页面按钮序号，两边必须一致）：
//   1=AVMF  2=AVMR  3=AVMB  4=AVML  5=RVC  6=DMS
//   —— 与 CameraPreference.selectableCameraIds 的顺序完全一致。
//   注意中继侧 server.py 的 CSWITCH_CAMS 默认是 5，只允许下发 1..5（RVC 为止）；
//   要让观看端也能切到 6=DMS，把 CSWITCH_CAMS 设成 6。
//
// CameraSwitchClient.kt — 车机侧：接收「切换到摄像头 N」的指令。
//
// 指令链路：观看端（浏览器）→ 控制中继 server.py → 车机本客户端。切换动作在宿主完成：
// surface 通路下就是换一个帧来源，编码器持续运行、推流不中断；media_record 库不需要任何改动。
//
// 这里用的是**零依赖轮询版**（HttpURLConnection 拉 /state），也正是 server.py 为
// "车机没有 WebSocket 客户端" 准备的方案（其注释原话：poll if the vehicle has no
// WebSocket client）。响应延迟 ≈ intervalMs（默认 300ms），LAN 场景足够。
//
// 上游示例还提供了 WebSocket 版（更实时、断线自动重连），但它依赖
// `com.squareup.okhttp3:okhttp`——本项目没有该依赖，直接搬进来会编译不过
// （Unresolved reference: okhttp3）。需要更低延迟时再引入依赖并把对应实现补回。
//
// 线程：建议在独立线程调用；onSwitch 在后台线程触发，GL/相机相关操作请切到对应线程
// （本项目在 StreamTestActivity 里已 runOnUiThread）。

package com.skyworth.faceid.camera

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class CameraSwitchClient(
    /** 中继地址，如 `http://192.168.6.233:8081`（端口与 server.py 的 CSWITCH_PORT 一致）。 */
    private val relayBase: String,
    /** 切换回调：cam = 1..N（与页面按钮序号一致）。仅在编号**发生变化**时回调。 */
    private val onSwitch: (Int) -> Unit,
    /** 状态回调（切换动作 / 中继不可达），默认仅打日志。 */
    private val onStatus: (String) -> Unit = { Log.i(TAG, it) }
) {

    companion object {
        private const val TAG = "CameraSwitch"
    }

    @Volatile
    private var running = false

    /** 已应用过的摄像头编号，避免重复切换。 */
    private val current = AtomicInteger(0)

    /**
     * 启动轮询（幂等：会先停掉上一次）。
     *
     * 注意：server.py 的初始状态是 `{"cam": 1}`，并且连上就会把当前状态下发。因此**首次
     * 轮询通常就会触发一次 onSwitch(1)**——这是中继的设计（让车机跟随观看端状态），
     * 不是异常。若希望"进页面不切、只响应主动点击"，需在宿主侧忽略第一次回调。
     */
    fun startPolling(intervalMs: Long = 300) {
        stop()
        running = true
        Thread {
            while (running) {
                try {
                    val cam = JSONObject(httpGet("$relayBase/state")).optInt("cam", 0)
                    if (cam != 0 && cam != current.get()) {
                        current.set(cam)
                        onStatus("switch -> $cam")
                        onSwitch(cam)
                    }
                } catch (t: Throwable) {
                    onStatus("relay unreachable: ${t.message}")
                    // 退避一拍，别打爆日志
                    if (!sleep(1000)) break
                }
                if (!sleep(intervalMs)) break
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    /** 停止轮询（幂等）。 */
    fun stop() {
        running = false
    }

    /** @return false 表示已被要求停止（立刻退出循环，避免 stop() 后还多睡一轮）。 */
    private fun sleep(ms: Long): Boolean {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return running
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2000
            readTimeout = 2000
            requestMethod = "GET"
        }
        try {
            return BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
