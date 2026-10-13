package com.skyworth.faceid.core

import android.util.Log
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 在车机所在网段里自动发现摄像头切换中继（`server.py`）。
 *
 * 动机：PC 走 WiFi 的 DHCP 地址会变，写死 IP 的配置失效后推流/切换一起挂。
 * 中继与 MediaMTX 在同一台 PC 上，所以**找到中继就等于知道了对端 IP**。
 *
 * 做法：取本机非回环 IPv4 → 推出 /24 前缀 → 并行探测 `http://<ip>:<port>/state`，
 * 第一个返回 200 的即为中继。纯车机侧实现，**不需要改 PC 上任何东西**。
 *
 * 线程：阻塞操作，必须在后台线程调用。
 */
object RelayDiscovery {

    private const val TAG = "RelayDiscovery"

    /** 并行度与超时：254 个地址 / 32 并发 × 300ms ≈ 2.5s 扫完。 */
    private const val PARALLEL = 32
    private const val PROBE_TIMEOUT_MS = 300

    /**
     * 扫描本网段寻找中继。
     *
     * @param port 中继端口，默认 [PushTarget.RELAY_PORT]。
     * @return 中继主机 IP；未找到返回 null。
     */
    fun findRelay(port: Int = PushTarget.RELAY_PORT): String? {
        val local = localIpv4()
        if (local == null) {
            Log.w(TAG, "no local IPv4 found")
            return null
        }
        val prefix = local.substringBeforeLast('.', "")
        if (prefix.isEmpty()) {
            Log.w(TAG, "cannot derive /24 prefix from $local")
            return null
        }
        Log.i(TAG, "scanning $prefix.1-254:$port (local=$local)")

        val pool = Executors.newFixedThreadPool(PARALLEL)
        val found = AtomicReference<String?>(null)
        try {
            val tasks = (1..254).map { last ->
                val ip = "$prefix.$last"
                pool.submit {
                    if (found.get() != null) return@submit
                    if (probe(ip, port)) {
                        if (found.compareAndSet(null, ip)) Log.i(TAG, "relay found at $ip")
                    }
                }
            }
            for (t in tasks) {
                try {
                    t.get(PROBE_TIMEOUT_MS.toLong() + 700, TimeUnit.MILLISECONDS)
                } catch (_: Exception) {
                    // 单个地址超时不重要；整体超时由 pool.shutdownNow 兜住
                }
                if (found.get() != null) break
            }
        } finally {
            pool.shutdownNow()
        }
        return found.get()
    }

    /** GET `http://ip:port/state`，200 即认为是中继。 */
    private fun probe(ip: String, port: Int): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("http://$ip:$port/state").openConnection() as HttpURLConnection).apply {
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                requestMethod = "GET"
                useCaches = false
            }
            conn.responseCode == 200
        } catch (_: Throwable) {
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    /**
     * 本机用于扫描的 IPv4：
     *
     * **多网段时优先 `192.`**——演示跑在 192 网段的 WiFi 局域网，而车机同时还有
     * 蜂窝/内网接口（实测 `10.202.83.250` 的 rmnet_data1）；若按"第一个可用地址"取，
     * 会去扫 `10.202.83.x`，永远找不到 192 网段上的中继。
     * 没有 192 地址时退回第一个可用地址。排除回环与 169.254.x link-local。
     */
    private fun localIpv4(): String? {
        return try {
            val addrs = Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .filterNot { it.startsWith("127.") || it.startsWith("169.254.") }
            addrs.firstOrNull { it.startsWith("192.") } ?: addrs.firstOrNull()
        } catch (t: Throwable) {
            Log.w(TAG, "localIpv4 failed", t)
            null
        }
    }
}
