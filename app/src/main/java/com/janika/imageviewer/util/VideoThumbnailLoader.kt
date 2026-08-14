package com.janika.imageviewer.util

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever

/**
 * 本地视频缩略图加载工具 - 用 MediaMetadataRetriever 提取视频帧与时长。
 * 网络视频不做缩略图，统一显示图标（避免下载整个文件头）。
 */
object VideoThumbnailLoader {

    /** 缩略图信息：首帧位图（可能为 null）与时长（毫秒） */
    data class VideoFrame(val bitmap: Bitmap?, val durationMs: Long)

    /** 提取视频约 1 秒处的关键帧并缩放到指定宽度，同时返回时长。失败返回空对象。 */
    fun loadFrame(path: String, targetWidth: Int = 256): VideoFrame {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val frame = retriever.getFrameAtTime(
                1_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: return VideoFrame(null, durationMs)

            // 按目标宽度等比缩放，避免网格里解码大图
            val scale = targetWidth.toFloat() / frame.width.coerceAtLeast(1)
            val height = (frame.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(frame, targetWidth, height, true)
            if (scaled !== frame) frame.recycle()
            VideoFrame(scaled, durationMs)
        } catch (e: Exception) {
            VideoFrame(null, 0L)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }
}
