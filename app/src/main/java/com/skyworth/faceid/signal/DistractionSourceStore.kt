/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.signal

import android.content.Context

/**
 * [DistractionSource] 的持久化存储（FACEP-016）。
 *
 * 用 `SharedPreferences` 保存分心数据源开关，**重启后保留**。默认 [DistractionSource.SDK]。
 */
object DistractionSourceStore {

    private const val PREFS = "face_id_distraction_prefs"
    private const val KEY_SOURCE = "distraction_source"

    /** 读取持久化的数据源；无记录或无法解析时返回默认 [DistractionSource.SDK]。 */
    fun load(context: Context): DistractionSource {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SOURCE, null) ?: return DistractionSource.SDK
        return try {
            DistractionSource.valueOf(name)
        } catch (e: IllegalArgumentException) {
            DistractionSource.SDK
        }
    }

    /** 持久化数据源，重启后保留。 */
    fun save(context: Context, source: DistractionSource) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOURCE, source.name)
            .apply()
    }
}
