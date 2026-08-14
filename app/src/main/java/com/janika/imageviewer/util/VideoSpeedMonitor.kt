package com.janika.imageviewer.util

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * 网络视频下载速度监测 - 累积已读字节数，计算平均速度（字节/秒）。
 * 流式播放由 SmbVideoDataSource 每次 read 上报；先缓存后播放由下载进度回调上报。
 */
class VideoSpeedMonitor {
    private val totalBytes = AtomicLong(0L)
    private val startNanos = AtomicLong(SystemClock.elapsedRealtimeNanos())

    /** 上报新读取的字节数（累计制的增量） */
    fun onBytesRead(bytes: Long) {
        if (bytes > 0) totalBytes.addAndGet(bytes)
    }

    /** 开始新的传输统计（换视频/重试时调用） */
    fun reset() {
        totalBytes.set(0L)
        startNanos.set(SystemClock.elapsedRealtimeNanos())
    }

    /** 当前平均下载速度（字节/秒），无数据返回 0 */
    fun getSpeedBps(): Long {
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNanos.get()) / 1_000_000L
        return if (elapsedMs > 0) totalBytes.get() * 1000L / elapsedMs else 0L
    }
}
