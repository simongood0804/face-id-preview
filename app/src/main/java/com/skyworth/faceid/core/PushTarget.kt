package com.skyworth.faceid.core

import android.content.Context
import android.util.Log

/**
 * 推流 / 摄像头切换中继的对端（PC）地址。
 *
 * 为什么要做成运行期可配：PC 走 WiFi 时拿的是 DHCP 地址，**会变**；以前把它写死在
 * `assets` 下的 json 里（`remote_url` 字段），一变就得改代码 + `make push-system` 重刷。
 * 现在改为持久化配置，并在推流页提供「对端地址」入口（手改 + 自动发现）。
 *
 * 约定：MediaMTX 与摄像头切换中继（`server.py`）跑在**同一台 PC** 上，
 * 所以找到中继就等于知道了 MediaMTX 的主机（见 [RelayDiscovery]）。
 *
 * 读写需在主线程初始化：[init] 在 Activity `onCreate` 调用（与 `CameraPreference` 同款）。
 */
object PushTarget {

    private const val TAG = "PushTarget"
    private const val PREFS = "faceid_prefs"
    private const val KEY_HOST = "push_host"

    /** 默认对端（历史值，仅作兜底）。 */
    const val DEFAULT_HOST = "192.168.6.233"

    /** MediaMTX WHIP 端口。 */
    const val WHIP_PORT = 8889

    /** 摄像头切换中继端口（server.py 的 CSWITCH_PORT）。 */
    const val RELAY_PORT = 8081

    /** 流名（与 MediaMTX、中继的 CSWITCH_STREAM 一致）。 */
    const val STREAM = "test"

    /** 对端主机（IP 或主机名）。 */
    @Volatile
    var host: String = DEFAULT_HOST
        private set

    /** 推流地址（写入 config 的 `${PUSH_HOST}` 占位符）。 */
    val whipUrl: String get() = "http://$host:$WHIP_PORT/$STREAM/whip"

    /** 中继基地址（抓 /state 与 POST /switch）。 */
    val relayBase: String get() = "http://$host:$RELAY_PORT"

    /** 从持久化加载（Activity onCreate 调用）。 */
    fun init(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            host = prefs.getString(KEY_HOST, DEFAULT_HOST)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_HOST
            Log.i(TAG, "init: host=$host")
        } catch (e: Exception) {
            Log.w(TAG, "init failed", e)
        }
    }

    /** 保存对端地址（去掉协议前缀与端口，只留主机）。 */
    fun setHost(context: Context, value: String) {
        val cleaned = normalize(value)
        if (cleaned.isEmpty()) return
        host = cleaned
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_HOST, cleaned).apply()
            Log.i(TAG, "setHost: host=$cleaned")
        } catch (e: Exception) {
            Log.w(TAG, "setHost failed", e)
        }
    }

    /** 容错：允许用户粘贴 `http://1.2.3.4:8081/`、`1.2.3.4:8081` 这类写法。 */
    private fun normalize(raw: String): String {
        var s = raw.trim()
        s = s.removePrefix("http://").removePrefix("https://").removePrefix("ws://")
        s = s.substringBefore('/')
        // IPv6 字面量（[::1]:8081）不在这里处理，本场景用不到
        if (s.count { it == ':' } == 1) s = s.substringBefore(':')
        return s.trim()
    }
}
