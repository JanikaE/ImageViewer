package com.janika.imageviewer.data.repository

import com.janika.imageviewer.data.model.ImageFile
import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * SMB 网络共享仓库 - 浏览局域网共享文件夹中的图片
 */
class SmbRepository {

    companion object {
        @Volatile
        private var sharedContext: CIFSContext? = null

        fun getSharedContext(): CIFSContext? = sharedContext

        fun updateSharedContext(context: CIFSContext) {
            sharedContext = context
        }
    }

    private var authContext: CIFSContext? = null

    fun isConnected(): Boolean = authContext != null

    fun connect(
        serverAddress: String,
        shareName: String,
        username: String? = null,
        password: String? = null,
        domain: String? = null
    ): Boolean {
        return try {
            val baseContext = SingletonContext.getInstance()
            val authenticator = if (!username.isNullOrEmpty()) {
                NtlmPasswordAuthenticator(
                    domain ?: "",
                    username,
                    password ?: ""
                )
            } else {
                NtlmPasswordAuthenticator(null, "guest", "")
            }
            authContext = baseContext.withCredentials(authenticator)
            sharedContext = authContext
            // 测试连接
            val testUrl = buildSmbUrl(serverAddress, shareName)
            val testFile = SmbFile(testUrl, authContext)
            testFile.exists()
            true
        } catch (e: Exception) {
            authContext = null
            e.printStackTrace()
            false
        }
    }

    fun disconnect() {
        authContext = null
        sharedContext = null
    }

    suspend fun listShares(serverAddress: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val context = authContext ?: SingletonContext.getInstance()
            val url = "smb://$serverAddress/"
            val smbFile = SmbFile(url, context)
            smbFile.list()?.map { it.trimEnd('/') }
                ?.filter { it.isNotEmpty() && !it.endsWith("$") } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun listFiles(
        serverAddress: String,
        shareName: String,
        folderPath: String = ""
    ): List<ImageFile> = withContext(Dispatchers.IO) {
        try {
            val context = authContext ?: return@withContext emptyList()
            val url = buildSmbUrl(serverAddress, shareName, folderPath)
            val smbDir = SmbFile(url, context)

            if (!smbDir.exists() || !smbDir.isDirectory) return@withContext emptyList()

            val fileList = smbDir.listFiles()?.toList() ?: return@withContext emptyList()

            // JCIFS 在特殊字符路径下，getName() 会返回 "父文件夹名+文件名"
            val parentLastName = folderPath.substringAfterLast('/').trimEnd('/')

            // 先构建基础 ImageFile 列表
            val items = fileList.mapNotNull { smbFile ->
                val rawName = smbFile.name.trimEnd('/')
                val cleanName = cleanFileName(rawName, parentLastName)

                if (cleanName.isEmpty()) return@mapNotNull null
                if (smbFile.isDirectory && (cleanName.startsWith(".") || cleanName == ".")) return@mapNotNull null
                if (!smbFile.isDirectory) {
                    val ext = cleanName.substringAfterLast('.', "").lowercase()
                    if (ext !in ImageFile.SUPPORTED_FORMATS) return@mapNotNull null
                }

                ImageFile(
                    name = cleanName,
                    path = if (folderPath.isEmpty()) cleanName else "$folderPath/$cleanName",
                    size = smbFile.length(),
                    lastModified = smbFile.lastModified(),
                    isDirectory = smbFile.isDirectory
                )
            }

            // 为文件夹并发获取第一张图片作为预览
            val dirs = items.filter { it.isDirectory }
            if (dirs.isNotEmpty()) {
                val previewMap = coroutineScope {
                    dirs.map { dir ->
                        async {
                            try {
                                val subUrl = buildSmbUrl(serverAddress, shareName, dir.path)
                                val subDir = SmbFile(subUrl, context)
                                val firstName = subDir.listFiles()
                                    ?.firstOrNull { f ->
                                        !f.isDirectory &&
                                        f.name.substringAfterLast('.', "").lowercase() in ImageFile.SUPPORTED_FORMATS
                                    }
                                    ?.name?.trimEnd('/')
                                if (firstName != null) {
                                    val clean = cleanFileName(firstName, dir.name)
                                    dir.path to "smb://$serverAddress/$shareName/${dir.path}/$clean"
                                } else null
                            } catch (_: Exception) { null }
                        }
                    }.awaitAll().filterNotNull().toMap()
                }

                if (previewMap.isNotEmpty()) {
                    return@withContext items.map { item ->
                        if (item.isDirectory) {
                            previewMap[item.path]?.let { smbPreviewUrl ->
                                item.copy(previewPath = smbPreviewUrl)
                            } ?: item
                        } else item
                    }.sortedWith(compareByDescending<ImageFile> { it.isDirectory }
                        .thenBy { it.name.lowercase() })
                }
            }

            items.sortedWith(compareByDescending<ImageFile> { it.isDirectory }
                .thenBy { it.name.lowercase() })
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 清理 JCIFS 返回的文件名 — 当路径含特殊字符时，getName() 会在文件名前拼接父文件夹名。
     * 例如 folderPath="FolderA" 时，文件 "001.jpg" 的 getName() 返回 "FolderA001.jpg"。
     * 利用已知的父文件夹名来剥离多余前缀。
     */
    private fun cleanFileName(rawName: String, parentLastName: String): String {
        if (parentLastName.isEmpty()) return rawName

        // 情况1: rawName == parentLastName（目录自身的名字）
        if (rawName == parentLastName) return rawName

        // 情况2: rawName 以 parentLastName 开头 → 剥离前缀
        if (rawName.startsWith(parentLastName) && rawName.length > parentLastName.length) {
            val stripped = rawName.substring(parentLastName.length)
            // 仅当剥离后的结果看起来像合法文件名时才采用
            if (stripped.isNotEmpty() && stripped != rawName) {
                return stripped
            }
        }

        return rawName
    }

    fun getSmbFileUrl(serverAddress: String, shareName: String, filePath: String): String {
        return buildSmbUrl(serverAddress, shareName, filePath)
    }

    suspend fun scanNetworkServers(): List<String> = withContext(Dispatchers.IO) {
        try {
            // 简单扫描本地网络常见子网
            val localHost = InetAddress.getLocalHost()
            val hostAddress = localHost.hostAddress ?: return@withContext emptyList()
            val prefix = hostAddress.substringBeforeLast(".")
            val results = mutableListOf<String>()

            // 只扫描几个常见 IP 作为快速检测
            for (i in 1..20) {
                try {
                    val addr = InetAddress.getByName("$prefix.$i")
                    if (addr.isReachable(300)) {
                        // 尝试 SMB 端口
                        results.add("$prefix.$i")
                    }
                } catch (_: Exception) {
                    // 忽略
                }
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildSmbUrl(serverAddress: String, shareName: String, path: String = ""): String {
        val cleanPath = path.trimStart('/')
        return if (cleanPath.isEmpty()) {
            "smb://$serverAddress/$shareName/"
        } else {
            "smb://$serverAddress/$shareName/$cleanPath"
        }
    }
}
