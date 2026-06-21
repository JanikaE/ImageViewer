package com.janika.imageviewer.util

import android.content.Context
import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 网络图片加载工具 - 将SMB远程文件缓存到本地
 */
object SmbImageLoader {

    private const val CACHE_DIR = "smb_cache"

    /** 为服务器+共享名生成安全的文件夹名 */
    private fun cacheFolderName(server: String, share: String): String {
        val safe = server.replace(".", "_").replace(":", "_")
        return "${safe}_${share}"
    }

    /** 获取指定共享的缓存子目录 */
    private fun getShareCacheDir(context: Context, server: String, share: String): File {
        return File(File(context.cacheDir, CACHE_DIR), cacheFolderName(server, share)).also {
            if (!it.exists()) it.mkdirs()
        }
    }

    /** 迁移旧的扁平缓存到新目录结构 */
    private fun migrateOldCache(context: Context) {
        val root = File(context.cacheDir, CACHE_DIR)
        if (!root.exists()) return
        root.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }

    /**
     * 下载SMB文件到本地缓存并返回本地文件路径
     */
    suspend fun cacheSmbFile(
        context: Context,
        smbUrl: String,
        username: String? = null,
        password: String? = null,
        serverAddress: String? = null,
        shareName: String? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            migrateOldCache(context)

            // 从URL提取服务器、共享名和路径
            val urlWithoutProtocol = smbUrl.removePrefix("smb://")
            val allParts = urlWithoutProtocol.split("/")
            if (allParts.size < 2) return@withContext null

            val server = serverAddress ?: allParts[0]
            val share = shareName ?: allParts[1]
            val path = if (allParts.size > 2) allParts.drop(2).joinToString("/") else ""

            // 缓存到子目录: smb_cache/{server}_{share}/{pathHash}_{filename}
            val shareDir = getShareCacheDir(context, server, share)
            val remoteFileName = path.substringAfterLast('/').ifEmpty { path }
            val cacheKey = path.hashCode().toString(16)
            val cacheFile = File(shareDir, "${cacheKey}_${remoteFileName}")

            // 如果已缓存，直接返回
            if (cacheFile.exists()) {
                return@withContext cacheFile.absolutePath
            }

            // 优先使用 SmbRepository 已认证的共享上下文
            val sharedCtx = com.janika.imageviewer.data.repository.SmbRepository.getSharedContext()
            
            // 尝试多种认证方式
            var smbFile: SmbFile? = null
            var lastException: Exception? = null

            // 方式1: 使用已保存的共享上下文
            if (sharedCtx != null) {
                try {
                    smbFile = SmbFile(smbUrl, sharedCtx)
                    if (smbFile.exists() && !smbFile.isDirectory) {
                        return@withContext readAndCache(smbFile, cacheFile)
                    }
                } catch (e: Exception) {
                    lastException = e
                }
            }

            // 方式2: 使用传入的凭据创建新上下文
            if (!username.isNullOrEmpty()) {
                try {
                    val baseContext = SingletonContext.getInstance()
                    val authenticator = NtlmPasswordAuthenticator("", username, password ?: "")
                    val authContext = baseContext.withCredentials(authenticator)
                    smbFile = SmbFile(smbUrl, authContext)
                    if (smbFile.exists() && !smbFile.isDirectory) {
                        // 更新共享上下文以供后续使用
                        com.janika.imageviewer.data.repository.SmbRepository.updateSharedContext(authContext)
                        return@withContext readAndCache(smbFile, cacheFile)
                    }
                } catch (e: Exception) {
                    lastException = e
                }
            }

            // 方式3: 使用匿名/guest访问
            try {
                val baseContext = SingletonContext.getInstance()
                val authenticator = NtlmPasswordAuthenticator(null, "guest", "")
                val authContext = baseContext.withCredentials(authenticator)
                smbFile = SmbFile(smbUrl, authContext)
                if (smbFile.exists() && !smbFile.isDirectory) {
                    com.janika.imageviewer.data.repository.SmbRepository.updateSharedContext(authContext)
                    return@withContext readAndCache(smbFile, cacheFile)
                }
            } catch (e: Exception) {
                lastException = e
            }

            // 所有方式都失败
            android.util.Log.e("SmbImageLoader", "无法加载SMB文件: $smbUrl", lastException)
            null
        } catch (e: Exception) {
            android.util.Log.e("SmbImageLoader", "缓存SMB文件失败: $smbUrl", e)
            null
        }
    }

    private fun readAndCache(smbFile: SmbFile, cacheFile: File): String {
        SmbFileInputStream(smbFile).use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        }
        return cacheFile.absolutePath
    }

    /** 获取缓存文件路径（不触发下载），若未缓存返回 null */
    fun getCachePath(
        context: Context,
        serverAddress: String,
        shareName: String,
        filePath: String
    ): String? {
        val shareDir = getShareCacheDir(context, serverAddress, shareName)
        val fileName = filePath.substringAfterLast('/').ifEmpty { filePath }
        val cacheKey = filePath.hashCode().toString(16)
        val cacheFile = File(shareDir, "${cacheKey}_${fileName}")
        return if (cacheFile.exists()) cacheFile.absolutePath else null
    }

    /**
     * 清除所有缓存
     */
    fun clearCache(context: Context) {
        val cacheDir = File(context.cacheDir, CACHE_DIR)
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    /** 清除指定共享文件夹的缓存 */
    fun clearShareCache(context: Context, serverAddress: String, shareName: String) {
        val shareDir = File(File(context.cacheDir, CACHE_DIR), cacheFolderName(serverAddress, shareName))
        if (shareDir.exists()) shareDir.deleteRecursively()
    }

    /** 单个缓存文件信息 */
    data class FileCacheInfo(val fileName: String, val size: Long)

    /** 文件夹缓存信息 */
    data class FolderCacheInfo(
        val folderName: String,
        val serverAddress: String?,
        val shareName: String?,
        val fileCount: Int,
        val totalSize: Long,
        val files: List<FileCacheInfo>
    )

    /** 获取按共享文件夹分组的缓存信息 */
    fun getCacheInfo(context: Context): List<FolderCacheInfo> {
        val root = File(context.cacheDir, CACHE_DIR)
        if (!root.exists()) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.map { dir ->
                val files = dir.listFiles()
                    ?.map { FileCacheInfo(fileName = it.name, size = it.length()) }
                    ?.sortedByDescending { it.size }
                    ?: emptyList()
                // 尝试从文件夹名解析 server 和 share
                val parts = dir.name.split("_", limit = 2)
                val server = parts.getOrNull(0)?.replace("_", ".")
                val share = parts.getOrNull(1)
                FolderCacheInfo(
                    folderName = if (share != null) "$server / $share" else dir.name,
                    serverAddress = server,
                    shareName = share,
                    fileCount = files.size,
                    totalSize = files.sumOf { it.size },
                    files = files
                )
            }
            ?.sortedByDescending { it.totalSize }
            ?: emptyList()
    }

    /** 获取缓存总大小（字节） */
    fun getCacheTotalSize(context: Context): Long {
        return getCacheInfo(context).sumOf { it.totalSize }
    }
}
