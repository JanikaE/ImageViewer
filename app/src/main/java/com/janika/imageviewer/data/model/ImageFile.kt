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

    val isSupportedFormat: Boolean
        get() = isDirectory || extension in SUPPORTED_FORMATS

    companion object {
        val SUPPORTED_FORMATS = setOf("png", "jpg", "jpeg", "webp", "gif")
    }
}
