package com.janika.imageviewer.data.repository

import com.janika.imageviewer.data.model.ImageFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地文件仓库 - 浏览本地存储中的图片
 */
class LocalFileRepository {

    suspend fun getRootDirectories(
        includeFolderCounts: Boolean = true
    ): List<ImageFile> = withContext(Dispatchers.IO) {
        val list = mutableListOf<ImageFile>()

        // 外部存储根目录
        File("/storage/emulated/0").takeIf { it.exists() }?.let { root ->
            list.add(createDirectoryItem(
                root,
                "内部存储",
                includePreview = false,
                includeFolderCounts = includeFolderCounts
            ))
        }

        // 可移除存储（SD卡等）
        File("/storage").listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }?.forEach { dir ->
            list.add(createDirectoryItem(
                dir,
                dir.name,
                includePreview = false,
                includeFolderCounts = includeFolderCounts
            ))
        }

        list
    }

    suspend fun listFiles(
        directoryPath: String,
        includeFolderCounts: Boolean = true
    ): List<ImageFile> = withContext(Dispatchers.IO) {
        val dir = File(directoryPath)
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()

        dir.listFiles()
            ?.filter { file ->
                if (file.isDirectory) {
                    !file.name.startsWith(".") // 隐藏隐藏文件夹
                } else {
                    val ext = file.name.substringAfterLast('.', "").lowercase()
                    ext in ImageFile.SUPPORTED_FORMATS || ext in ImageFile.SUPPORTED_VIDEO_FORMATS
                }
            }
            ?.map { file ->
                if (file.isDirectory) {
                    createDirectoryItem(
                        file,
                        file.name,
                        includeFolderCounts = includeFolderCounts
                    )
                } else {
                    ImageFile(
                        name = file.name,
                        path = file.absolutePath,
                        size = file.length(),
                        lastModified = file.lastModified(),
                        isDirectory = false
                    )
                }
            }
            ?.sortedWith(compareByDescending<ImageFile> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()
    }

    suspend fun getParentPath(currentPath: String): String? {
        val parent = File(currentPath).parentFile
        // 不允许访问存储根目录以上的路径
        return if (parent != null && parent.absolutePath.startsWith("/storage")) {
            parent.absolutePath
        } else null
    }

    fun getFileForPath(path: String): File = File(path)

    /** 创建文件夹条目，同时统计直属内容并查找第一张预览图。 */
    private fun createDirectoryItem(
        dir: File,
        displayName: String,
        includePreview: Boolean = true,
        includeFolderCounts: Boolean = true
    ): ImageFile {
        val children = if (includePreview || includeFolderCounts) dir.listFiles() else null
        val visibleDirectoryCount = if (includeFolderCounts) {
            children?.count { it.isDirectory && !it.name.startsWith(".") }
        } else {
            null
        }
        val supportedFiles = children?.filter { file ->
            !file.isDirectory && file.name.substringAfterLast('.', "").lowercase().let { extension ->
                extension in ImageFile.SUPPORTED_FORMATS || extension in ImageFile.SUPPORTED_VIDEO_FORMATS
            }
        }
        val previewPath = if (includePreview) {
            supportedFiles
                ?.asSequence()
                ?.filter { it.name.substringAfterLast('.', "").lowercase() in ImageFile.SUPPORTED_FORMATS }
                ?.sortedBy { it.name.lowercase() }
                ?.firstOrNull()
                ?.absolutePath
        } else {
            null
        }

        return ImageFile(
            name = displayName,
            path = dir.absolutePath,
            size = 0,
            lastModified = dir.lastModified(),
            isDirectory = true,
            previewPath = previewPath,
            childFileCount = if (includeFolderCounts) supportedFiles?.size else null,
            childDirectoryCount = visibleDirectoryCount
        )
    }
}
