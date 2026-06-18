package com.janika.imageviewer.data.repository

import com.janika.imageviewer.data.model.ImageFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地文件仓库 - 浏览本地存储中的图片
 */
class LocalFileRepository {

    suspend fun getRootDirectories(): List<ImageFile> = withContext(Dispatchers.IO) {
        val list = mutableListOf<ImageFile>()

        // 外部存储根目录
        File("/storage/emulated/0").takeIf { it.exists() }?.let { root ->
            list.add(ImageFile(
                name = "内部存储",
                path = root.absolutePath,
                size = 0,
                lastModified = root.lastModified(),
                isDirectory = true
            ))
        }

        // 可移除存储（SD卡等）
        File("/storage").listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }?.forEach { dir ->
            list.add(ImageFile(
                name = dir.name,
                path = dir.absolutePath,
                size = 0,
                lastModified = dir.lastModified(),
                isDirectory = true
            ))
        }

        list
    }

    suspend fun listFiles(directoryPath: String): List<ImageFile> = withContext(Dispatchers.IO) {
        val dir = File(directoryPath)
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()

        dir.listFiles()
            ?.filter { file ->
                if (file.isDirectory) {
                    !file.name.startsWith(".") // 隐藏隐藏文件夹
                } else {
                    val ext = file.name.substringAfterLast('.', "").lowercase()
                    ext in ImageFile.SUPPORTED_FORMATS
                }
            }
            ?.map { file ->
                val preview = if (file.isDirectory) findFirstImage(file) else null
                ImageFile(
                    name = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    lastModified = file.lastModified(),
                    isDirectory = file.isDirectory,
                    previewPath = preview
                )
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

    /** 在文件夹中查找第一张支持的图片 */
    private fun findFirstImage(dir: File): String? {
        return dir.listFiles()
            ?.sortedBy { it.name.lowercase() }
            ?.firstOrNull { file ->
                !file.isDirectory && file.name.substringAfterLast('.', "").lowercase() in ImageFile.SUPPORTED_FORMATS
            }
            ?.absolutePath
    }
}
