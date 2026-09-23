package com.janika.imageviewer.util

import android.content.Context
import com.janika.imageviewer.data.model.ImageFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * SMB 缓存目录索引。
 *
 * 每个完整缓存文件旁保存一个独立元数据文件，避免批量下载取消或进程退出时
 * 破坏整份索引。离线浏览只接纳同时存在缓存文件和有效元数据的条目。
 */
object SmbCacheCatalog {
    private const val CACHE_DIR = "smb_cache"
    private const val META_SUFFIX = ".meta.json"
    private const val META_TMP_SUFFIX = ".meta.tmp"

    private val catalogLock = Any()
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    data class CachedEntry(
        val serverAddress: String,
        val shareName: String,
        val remotePath: String,
        val fileName: String,
        val size: Long,
        val lastModified: Long,
        val isVideo: Boolean,
        val localPath: String
    )

    fun record(
        cacheFile: File,
        serverAddress: String,
        shareName: String,
        remotePath: String,
        size: Long = cacheFile.length(),
        lastModified: Long = 0L
    ) {
        if (!cacheFile.isFile) return
        val existingMeta = File(cacheFile.parentFile, cacheFile.name + META_SUFFIX)
        if (existingMeta.isFile) return
        val extension = remotePath.substringAfterLast('.', "").lowercase()
        val json = JSONObject()
            .put("version", 1)
            .put("serverAddress", serverAddress)
            .put("shareName", shareName)
            .put("remotePath", normalize(remotePath))
            .put("fileName", remotePath.substringAfterLast('/'))
            .put("size", if (size > 0L) size else cacheFile.length())
            .put("lastModified", lastModified)
            .put("mediaType", if (extension in ImageFile.SUPPORTED_VIDEO_FORMATS) "video" else "image")

        synchronized(catalogLock) {
            val metaFile = existingMeta
            val tmpFile = File(cacheFile.parentFile, cacheFile.name + META_TMP_SUFFIX)
            try {
                tmpFile.writeText(json.toString(), Charsets.UTF_8)
                if (metaFile.exists()) metaFile.delete()
                if (!tmpFile.renameTo(metaFile)) {
                    tmpFile.delete()
                    return
                }
                _revision.value += 1
            } catch (e: Exception) {
                tmpFile.delete()
                android.util.Log.w("SmbCacheCatalog", "写入缓存元数据失败: $remotePath", e)
            }
        }
    }

    fun listEntries(context: Context, serverAddress: String, shareName: String): List<CachedEntry> {
        return allMetadataFiles(context).mapNotNull { readEntry(it) }
            .filter { it.serverAddress == serverAddress && it.shareName == shareName }
            .sortedBy { it.remotePath.lowercase() }
            .toList()
    }

    fun listShareNames(context: Context, serverAddress: String): List<String> {
        return allMetadataFiles(context).mapNotNull { readEntry(it) }
            .filter { it.serverAddress == serverAddress }
            .map { it.shareName }
            .distinct()
            .sortedBy { it.lowercase() }
            .toList()
    }

    fun hasCache(context: Context, serverAddress: String): Boolean =
        listShareNames(context, serverAddress).isNotEmpty()

    fun notifyCacheCleared() {
        _revision.value += 1
    }

    fun getDirectCachedFileCount(
        context: Context,
        serverAddress: String,
        shareName: String,
        folderPath: String
    ): Int = listEntries(context, serverAddress, shareName)
        .count { parentPath(it.remotePath) == normalize(folderPath) }

    /** 根据缓存元数据重建某一级目录，只暴露完整缓存的媒体文件。 */
    fun listDirectory(
        context: Context,
        serverAddress: String,
        shareName: String,
        folderPath: String
    ): List<ImageFile> {
        val normalizedFolder = normalize(folderPath)
        val prefix = if (normalizedFolder.isEmpty()) "" else "$normalizedFolder/"
        val entries = listEntries(context, serverAddress, shareName)
            .filter { normalizedFolder.isEmpty() || it.remotePath.startsWith(prefix) }

        val directFiles = entries.filter { parentPath(it.remotePath) == normalizedFolder }
        val childDirectoryNames = entries.mapNotNull { entry ->
            val relative = if (prefix.isEmpty()) entry.remotePath else entry.remotePath.removePrefix(prefix)
            relative.substringBefore('/', "").takeIf { it.isNotEmpty() }
        }.distinct()

        val directories = childDirectoryNames.map { directoryName ->
            val directoryPath = if (normalizedFolder.isEmpty()) directoryName else "$normalizedFolder/$directoryName"
            val directoryPrefix = "$directoryPath/"
            val descendants = entries.filter { it.remotePath.startsWith(directoryPrefix) }
            val childFiles = descendants.filter { parentPath(it.remotePath) == directoryPath }
            val childDirectories = descendants.mapNotNull { entry ->
                val relative = entry.remotePath.removePrefix(directoryPrefix)
                relative.substringBefore('/', "").takeIf { it.isNotEmpty() }
            }.distinct()
            val preview = childFiles
                .filter { !it.isVideo }
                .minByOrNull { it.fileName.lowercase() }
            ImageFile(
                name = directoryName,
                path = directoryPath,
                size = 0L,
                lastModified = 0L,
                isDirectory = true,
                previewPath = preview?.remotePath,
                childFileCount = childFiles.size,
                childDirectoryCount = childDirectories.size,
                cachedChildFileCount = childFiles.size,
                localCachePath = preview?.localPath
            )
        }

        val files = directFiles.map { entry ->
            ImageFile(
                name = entry.fileName,
                path = entry.remotePath,
                size = entry.size,
                lastModified = entry.lastModified,
                isDirectory = false,
                localCachePath = entry.localPath
            )
        }

        return (directories + files).sortedWith(
            compareByDescending<ImageFile> { it.isDirectory }.thenBy { it.name.lowercase() }
        )
    }

    private fun allMetadataFiles(context: Context): Sequence<File> {
        val root = File(context.cacheDir, CACHE_DIR)
        return root.listFiles().orEmpty().asSequence()
            .filter { it.isDirectory }
            .flatMap { it.listFiles().orEmpty().asSequence() }
            .filter { it.isFile && it.name.endsWith(META_SUFFIX) }
    }

    private fun readEntry(metaFile: File): CachedEntry? {
        return try {
            val json = JSONObject(metaFile.readText(Charsets.UTF_8))
            val cacheName = metaFile.name.removeSuffix(META_SUFFIX)
            val cacheFile = File(metaFile.parentFile, cacheName)
            if (!cacheFile.isFile) {
                metaFile.delete()
                return null
            }
            CachedEntry(
                serverAddress = json.getString("serverAddress"),
                shareName = json.getString("shareName"),
                remotePath = normalize(json.getString("remotePath")),
                fileName = json.optString("fileName").ifEmpty {
                    json.getString("remotePath").substringAfterLast('/')
                },
                size = json.optLong("size", cacheFile.length()),
                lastModified = json.optLong("lastModified", 0L),
                isVideo = json.optString("mediaType") == "video",
                localPath = cacheFile.absolutePath
            )
        } catch (e: Exception) {
            android.util.Log.w("SmbCacheCatalog", "读取缓存元数据失败: ${metaFile.name}", e)
            metaFile.delete()
            null
        }
    }

    private fun parentPath(path: String): String = normalize(path).substringBeforeLast('/', "")

    private fun normalize(path: String): String = path.replace('\\', '/').trim('/')
}
