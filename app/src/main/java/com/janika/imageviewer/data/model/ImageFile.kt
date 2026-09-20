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
    /** 文件夹直属层级中的第一张图片预览路径 */
    val previewPath: String? = null,
    /** 文件夹直属层级中可浏览的文件数量；非文件夹或无法读取时为 null */
    val childFileCount: Int? = null,
    /** 文件夹直属层级中非隐藏文件夹的数量；非文件夹或无法读取时为 null */
    val childDirectoryCount: Int? = null
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
