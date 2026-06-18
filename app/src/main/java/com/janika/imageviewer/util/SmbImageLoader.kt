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
            val cacheDir = File(context.cacheDir, CACHE_DIR)
            if (!cacheDir.exists()) cacheDir.mkdirs()

            // 从URL提取服务器、共享名和路径
            // 格式: smb://server/share/path/to/file
            val urlWithoutProtocol = smbUrl.removePrefix("smb://")
            val allParts = urlWithoutProtocol.split("/")
            if (allParts.size < 2) return@withContext null

            val server = serverAddress ?: allParts[0]
            val share = shareName ?: allParts[1]
            val path = if (allParts.size > 2) allParts.drop(2).joinToString("/") else ""

            // 生成本地缓存文件名
            val remoteFileName = path.substringAfterLast('/').ifEmpty { path }
            val cacheKey = "${server}_${share}_${path}".hashCode().toString(16)
            val cacheFile = File(cacheDir, "${cacheKey}_${remoteFileName}")

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
        val cacheDir = File(context.cacheDir, CACHE_DIR)
        if (!cacheDir.exists()) return null

        val fileName = filePath.substringAfterLast('/').ifEmpty { filePath }
        val cacheKey = "${serverAddress}_${shareName}_${filePath}".hashCode().toString(16)
        val cacheFile = File(cacheDir, "${cacheKey}_${fileName}")
        return if (cacheFile.exists()) cacheFile.absolutePath else null
    }

    /**
     * 清除SMB缓存
     */
    fun clearCache(context: Context) {
        val cacheDir = File(context.cacheDir, CACHE_DIR)
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }
}
