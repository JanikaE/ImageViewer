package com.janika.imageviewer.data.repository

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.protocol.commons.EnumWithValue
import com.janika.imageviewer.data.model.ImageFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * SMB 网络共享仓库 - 浏览局域网共享文件夹中的图片（基于 SMBJ）
 *
 * 连接/会话生命周期由 [SmbSessionManager] 统一管理；本类只负责目录枚举与预览。
 * 共享名由用户在设置中手动配置（不做自动枚举）。
 */
class SmbRepository {

    private data class FolderDetails(
        val previewPath: String?,
        val fileCount: Int?,
        val directoryCount: Int?
    )

    fun isConnected(): Boolean = SmbSessionManager.isConnected()

    suspend fun connect(
        serverAddress: String,
        username: String? = null,
        password: String? = null,
        domain: String? = null
    ): Boolean = SmbSessionManager.connect(serverAddress, username, password, domain)

    fun disconnect() = SmbSessionManager.disconnect()

    suspend fun listFiles(
        shareName: String,
        folderPath: String = "",
        includeFolderCounts: Boolean = true
    ): List<ImageFile> = withContext(Dispatchers.IO) {
        try {
            val share = SmbSessionManager.getDiskShare(shareName)
            val fileList = share.list(folderPath) ?: return@withContext emptyList()

            val items = fileList.mapNotNull { info ->
                val rawName = info.fileName.trimEnd('/')
                if (rawName.isEmpty() || rawName == "." || rawName == "..") return@mapNotNull null
                val isDirectory = EnumWithValue.EnumUtils.isSet(info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)

                if (isDirectory) {
                    if (rawName.startsWith(".")) return@mapNotNull null
                    // 文件夹：无尺寸信息，统一按 0 处理
                    ImageFile(
                        name = rawName,
                        path = if (folderPath.isEmpty()) rawName else "$folderPath/$rawName",
                        size = 0,
                        lastModified = info.lastWriteTime?.toEpochMillis() ?: 0L,
                        isDirectory = true
                    )
                } else {
                    val ext = rawName.substringAfterLast('.', "").lowercase()
                    if (ext !in ImageFile.SUPPORTED_FORMATS && ext !in ImageFile.SUPPORTED_VIDEO_FORMATS) {
                        return@mapNotNull null
                    }
                    ImageFile(
                        name = rawName,
                        path = if (folderPath.isEmpty()) rawName else "$folderPath/$rawName",
                        size = info.endOfFile,
                        lastModified = info.lastWriteTime?.toEpochMillis() ?: 0L,
                        isDirectory = false
                    )
                }
            }

            // 为文件夹并发获取第一张图片和直属内容数量
            val dirs = items.filter { it.isDirectory }
            if (dirs.isNotEmpty()) {
                val detailsMap = coroutineScope {
                    dirs.map { dir ->
                        async {
                            try {
                                val subDir = share.list(dir.path)
                                val visibleItems = subDir.orEmpty().filter { item ->
                                    val name = item.fileName.trimEnd('/')
                                    name.isNotEmpty() && name != "." && name != ".."
                                }
                                val directoryCount = if (includeFolderCounts) {
                                    visibleItems.count { item ->
                                        EnumWithValue.EnumUtils.isSet(
                                            item.fileAttributes,
                                            FileAttributes.FILE_ATTRIBUTE_DIRECTORY
                                        ) && !item.fileName.trimEnd('/').startsWith(".")
                                    }
                                } else {
                                    null
                                }
                                val supportedFiles = visibleItems.filter { item ->
                                    !EnumWithValue.EnumUtils.isSet(
                                        item.fileAttributes,
                                        FileAttributes.FILE_ATTRIBUTE_DIRECTORY
                                    ) && item.fileName.substringAfterLast('.', "").lowercase().let { extension ->
                                        extension in ImageFile.SUPPORTED_FORMATS ||
                                            extension in ImageFile.SUPPORTED_VIDEO_FORMATS
                                    }
                                }
                                val firstImageName = supportedFiles
                                    .asSequence()
                                    .filter { item ->
                                        item.fileName.substringAfterLast('.', "").lowercase() in
                                            ImageFile.SUPPORTED_FORMATS
                                    }
                                    .sortedBy { it.fileName.lowercase() }
                                    .firstOrNull()
                                    ?.fileName
                                    ?.trimEnd('/')
                                dir.path to FolderDetails(
                                    previewPath = firstImageName?.let { "${dir.path}/$it" },
                                    fileCount = if (includeFolderCounts) supportedFiles.size else null,
                                    directoryCount = directoryCount
                                )
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }.awaitAll().filterNotNull().toMap()
                }

                if (detailsMap.isNotEmpty()) {
                    return@withContext items.map { item ->
                        if (item.isDirectory) {
                            detailsMap[item.path]?.let { details ->
                                item.copy(
                                    previewPath = details.previewPath,
                                    childFileCount = details.fileCount,
                                    childDirectoryCount = details.directoryCount
                                )
                            } ?: item
                        } else item
                    }.sortedWith(compareByDescending<ImageFile> { it.isDirectory }
                        .thenBy { it.name.lowercase() })
                }
            }

            items.sortedWith(compareByDescending<ImageFile> { it.isDirectory }
                .thenBy { it.name.lowercase() })
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        }
    }

    /**
     * 递归枚举文件夹中的全部受支持媒体文件。隐藏文件夹和不支持的文件会被忽略。
     */
    suspend fun listMediaFilesRecursively(
        shareName: String,
        folderPath: String,
        onScanningDirectory: (String) -> Unit = {}
    ): List<ImageFile> = withContext(Dispatchers.IO) {
        val share = SmbSessionManager.getDiskShare(shareName)
        val result = mutableListOf<ImageFile>()

        suspend fun scan(path: String) {
            coroutineContext.ensureActive()
            onScanningDirectory(path)
            share.list(path).orEmpty().forEach { info ->
                coroutineContext.ensureActive()
                val name = info.fileName.trimEnd('/')
                if (name.isEmpty() || name == "." || name == "..") return@forEach
                val isDirectory = EnumWithValue.EnumUtils.isSet(
                    info.fileAttributes,
                    FileAttributes.FILE_ATTRIBUTE_DIRECTORY
                )
                val itemPath = if (path.isEmpty()) name else "$path/$name"
                if (isDirectory) {
                    if (!name.startsWith(".")) scan(itemPath)
                } else {
                    val extension = name.substringAfterLast('.', "").lowercase()
                    if (extension in ImageFile.SUPPORTED_FORMATS ||
                        extension in ImageFile.SUPPORTED_VIDEO_FORMATS
                    ) {
                        result += ImageFile(
                            name = name,
                            path = itemPath,
                            size = info.endOfFile,
                            lastModified = info.lastWriteTime?.toEpochMillis() ?: 0L,
                            isDirectory = false
                        )
                    }
                }
            }
        }

        scan(folderPath)
        result
    }
}
