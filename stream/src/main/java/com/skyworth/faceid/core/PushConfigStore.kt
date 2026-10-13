package com.skyworth.faceid.core

import android.content.Context

/**
 * 推流对端地址的持久化接口。
 *
 * 抽这层的目的：让 [PushTarget] 与具体存储实现（SharedPreferences）解耦，
 * 便于在非 Android 宿主或单测中替换为内存实现。
 *
 * 默认实现 [SharedPreferencesPushConfigStore] 由宿主 app 的 `Context` 构造，
 * 与原先直接使用 `context.getSharedPreferences(...)` 行为一致。
 */
interface PushConfigStore {

    /** 读取持久化的对端主机；未设置返回 null。 */
    fun loadHost(): String?

    /** 持久化对端主机。 */
    fun saveHost(host: String)

    /**
     * 默认实现：基于 `SharedPreferences`，键与存储文件沿用历史约定，
     * 保证升级后旧设备的已存地址不丢。
     */
    class SharedPreferencesPushConfigStore(context: Context) : PushConfigStore {

        private val prefs =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override fun loadHost(): String? =
            prefs.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() }

        override fun saveHost(host: String) {
            prefs.edit().putString(KEY_HOST, host).apply()
        }

        companion object {
            private const val PREFS = "faceid_prefs"
            private const val KEY_HOST = "push_host"
        }
    }

    /** 内存实现，供单测/无持久化场景使用。 */
    class InMemoryPushConfigStore(private var host: String? = null) : PushConfigStore {
        override fun loadHost(): String? = host
        override fun saveHost(host: String) {
            this.host = host
        }
    }
}
