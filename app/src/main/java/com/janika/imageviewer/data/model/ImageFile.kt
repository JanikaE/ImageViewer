package com.janika.imageviewer.data.model

/**
 * 图片文件数据模型
 */
data class ImageFile(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    /** 文件夹的第一张图片预览路径（仅本地文件夹，网络文件夹为 null） */
    val previewPath: String? = null
) {
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()

    val isImage: Boolean
        get() = !isDirectory && extension in SUPPORTED_FORMATS

    /** 是否为受支持的视频文件 */
    val isVideo: Boolean
        get() = !isDirectory && extension in SUPPORTED_VIDEO_FORMATS

    val isSupportedFormat: Boolean
        get() = isDirectory || extension in SUPPORTED_FORMATS || extension in SUPPORTED_VIDEO_FORMATS

    companion object {
        val SUPPORTED_FORMATS = setOf("png", "jpg", "jpeg", "webp", "gif")
        val SUPPORTED_VIDEO_FORMATS = setOf(
            "mp4", "mkv", "m4v", "webm", "3gp", "avi",
            "mov", "ts", "m2ts", "flv", "wmv", "ogv"
        )
    }
}
