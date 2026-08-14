package com.skyworth.faceid.shmtest

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import com.skyworth.faceid.R
import com.skyworth.faceid.bus.ShmQueue

/**
 * 阶段 B PoC：跨进程共享内存验证入口（运行在主进程）。
 *
 * 绑定 [ShmBridgeService]（`:shmtest` 进程），通过 Binder 获取 [ShmQueue]
 * 所在的 [SharedMemory]，attach 后周期性读取 `:shmtest` 进程发布的数据，
 * 验证跨进程共享内存的可见性与序号连续性。
 */
class ShmTestActivity : Activity() {

    private var bridge: ShmBridge? = null
    private var shmQueue: ShmQueue? = null
    private var readerId = -1
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private var receivedCount = 0L
    private var lastSeq = -1L
    private var gapCount = 0L

    private val readerRunnable = object : Runnable {
        override fun run() {
            val q = shmQueue ?: return
            val rid = readerId
            if (rid < 0) return
            // 批量读取新消息
            var n = 0
            while (q.hasNext(rid) && n < 64) {
                val m = q.readNext(rid) ?: break
                n++
                receivedCount++
                if (lastSeq >= 0 && m.sequence != lastSeq + 1) gapCount++
                lastSeq = m.sequence
                updateText()
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bridge = ShmBridge.Stub.asInterface(binder)
            bridge?.let { b ->
                try {
                    val shm = b.getShm()
                    shmQueue = ShmQueue.attach(shm).also { q ->
                        readerId = q.registerReader()
                    }
                    running = true
                    updateText("已连接 :shmtest，attach 成功")
                    handler.post(readerRunnable)
                } catch (e: Exception) {
                    Log.e(TAG, "attach failed", e)
                    updateText("attach 失败: ${e.message}")
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            running = false
            bridge = null
            updateText("服务断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shm_test)

        findViewById<Button>(R.id.btn_shm_connect).setOnClickListener { connect() }
        findViewById<Button>(R.id.btn_shm_stop).setOnClickListener { stop() }
        updateText("未连接")
    }

    private fun connect() {
        val intent = Intent(this, ShmBridgeService::class.java)
        bindService(intent, serviceConn, Context.BIND_AUTO_CREATE)
        updateText("正在连接 :shmtest 进程...")
    }

    private fun stop() {
        running = false
        handler.removeCallbacks(readerRunnable)
        shmQueue?.let { q ->
            if (readerId >= 0) q.unregisterReader(readerId)
            q.close()
        }
        shmQueue = null
        try { unbindService(serviceConn) } catch (_: Exception) { }
        updateText("已停止")
    }

    private fun updateText(msg: String? = null) {
        val tv = findViewById<TextView>(R.id.tv_shm_status)
        val base = msg ?: "接收=${receivedCount} 最后seq=$lastSeq 断序=$gapCount"
        runOnUiThread { tv.text = "主进程 pid=${android.os.Process.myPid()}\n$base" }
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ShmTestActivity"
        private const val POLL_MS = 50L
    }
}
