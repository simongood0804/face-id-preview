/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.fatigue

/**
 * 滑动窗口计数器（FACEP-015）。
 *
 * 记录事件时间戳，统计"当前时刻往前 [windowMs] 窗口内"的事件次数。纯 JVM。
 * 事件按时间递增记录，超窗自动过期（惰性清理）。
 */
class SlidingWindowCounter(
    /** 窗口时长（ms）。 */
    private val windowMs: Long
) {
    private val events = ArrayDeque<Long>()

    /** 记录一次事件（事件发生时刻，ms）。 */
    fun record(eventTimeMs: Long) {
        events.addLast(eventTimeMs)
    }

    /** 当前窗口内事件数（nowMs 为当前时刻，ms）。 */
    fun countInWindow(nowMs: Long): Int {
        expire(nowMs)
        return events.size
    }

    /** 惰性清理：移除 `nowMs - windowMs` 之前的过期事件。 */
    private fun expire(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (events.isNotEmpty() && events.first() < cutoff) {
            events.removeFirst()
        }
    }
}
