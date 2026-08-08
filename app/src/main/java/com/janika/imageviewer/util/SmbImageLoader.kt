package com.janika.imageviewer.util

import android.content.Context
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.File as SmbFile
import com.janika.imageviewer.data.local.PreferencesManager
import com.janika.imageviewer.data.repository.SmbSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 网络图片加载工具 - 将SMB远程文件缓存到本地（基于 SMBJ）
 */
object SmbImageLoader {

    private const val CACHE_DIR = "smb_cache"

    /** 拷贝缓冲大小，SMBJ 会按服务器最大读块自动拆分，不会触发 INVALID_PARAMETER。
     *  服务器 maxRead=8MB，取 2MB 兼顾内存与 HDD 寻道频率（更大连续读块 → 更少磁头寻道）。 */
    private const val BUFFER_SIZE = 2 * 1024 * 1024

    /** 超过该大小的文件使用并发分段读（小图顺序读即可，避免开销） */
    private const val SEGMENTATION_THRESHOLD = 512 * 1024

    /** 进度回调最小间隔（毫秒），避免频繁刷新 UI */
    private const val PROGRESS_INTERVAL_MS = 200L

    /** 全局并发下载上限，防止网格浏览时同时发起过多 SMB 下载 */
    private val downloadSemaphore = Semaphore(3)

    /** 同一文件路径的下载去重锁，避免重复下载写坏缓存 */
    private val downloadLocks = ConcurrentHashMap<String, Mutex>()

    /** 旧缓存迁移只需执行一次 */
    private val migrationDone = AtomicBoolean(false)

    /** 缓存迁移锁（防止并发时重复清空） */
    private val migrationLock = Any()

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

    /** 旧缓存迁移（升级到原子写入后，一次性清空可能存在的半成品缓存） */
    private fun migrateOldCache(context: Context) {
        // 进程内只执行一次
        if (!migrationDone.compareAndSet(false, true)) return
        val root = File(context.cacheDir, CACHE_DIR)
        if (!root.exists()) return
        synchronized(migrationLock) {
            val prefs = context.getSharedPreferences("smb_cache_migration", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("cleared_partial_v3", false)) {
                // 老版本可能留下读一半的半成品文件，升级后整体清空一次
                root.listFiles()?.forEach { it.deleteRecursively() }
                prefs.edit().putBoolean("cleared_partial_v3", true).apply()
            } else {
                // 后续仅清理历史遗留的扁平缓存与孤儿临时文件
                root.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
                root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                    dir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
                }
            }
        }
    }

    /**
     * 下载SMB文件到本地缓存并返回本地文件路径
     */
    suspend fun cacheSmbFile(
        context: Context,
        serverAddress: String,
        shareName: String,
        filePath: String,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            migrateOldCache(context)

            // 缓存到子目录: smb_cache/{server}_{share}/{pathHash}_{filename}
            val shareDir = getShareCacheDir(context, serverAddress, shareName)
            val remoteFileName = filePath.substringAfterLast('/').ifEmpty { filePath }
            val cacheKey = filePath.hashCode().toString(16)
            val cacheFile = File(shareDir, "${cacheKey}_${remoteFileName}")

            // 如果已缓存，直接返回
            if (cacheFile.exists()) {
                cacheFile.absolutePath
            } else {
                // 同一文件路径串行下载（去重），并受全局并发上限约束
                val lockKey = "$serverAddress/$shareName/$filePath"
                downloadLocks.computeIfAbsent(lockKey) { Mutex() }.withLock {
                    // 等待锁期间可能已被其他协程下载完成
                    if (cacheFile.exists()) {
                        cacheFile.absolutePath
                    } else {
                        downloadSemaphore.withPermit {
                            val concurrency = PreferencesManager(context).loadSegmentConcurrency()
                            val result = downloadSmbFile(serverAddress, shareName, filePath, cacheFile, onProgress, concurrency)
                            if (result == null) {
                                // 下载失败时删除半成品文件，避免下次被误判为已缓存
                                cacheFile.delete()
                            }
                            result
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 协程取消（如页面翻走）不是下载失败，原样抛出
            if (e is CancellationException) throw e
            android.util.Log.e("SmbImageLoader", "缓存SMB文件失败: $serverAddress/$shareName/$filePath", e)
            null
        }
    }

    /**
     * 通过已认证的共享上下文下载文件并写入缓存
     */
    private suspend fun downloadSmbFile(
        serverAddress: String,
        shareName: String,
        filePath: String,
        cacheFile: File,
        onProgress: ((Long, Long) -> Unit)?,
        concurrency: Int
    ): String? {
        return try {
            val share = SmbSessionManager.getDiskShare(shareName)
            share.openFile(
                filePath,
                setOf(AccessMask.GENERIC_READ),
                setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            ).use { file ->
                readAndCache(file, cacheFile, onProgress, concurrency)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            android.util.Log.w("SmbImageLoader", "下载SMB文件失败: $serverAddress/$shareName/$filePath", e)
            null
        }
    }

    /**
     * 按文件大小分流：大文件并发分段读，小文件顺序读。
     */
    private suspend fun readAndCache(
        smbFile: SmbFile,
        cacheFile: File,
        onProgress: ((Long, Long) -> Unit)?,
        concurrency: Int
    ): String {
        val total = smbFile.getFileInformation(FileStandardInformation::class.java).endOfFile
        return if (total > SEGMENTATION_THRESHOLD) {
            readAndCacheSegmented(smbFile, cacheFile, total, onProgress, concurrency)
        } else {
            readAndCacheSequential(smbFile, cacheFile, total, onProgress)
        }
    }

    /** 小文件：顺序读取 */
    private fun readAndCacheSequential(
        smbFile: SmbFile,
        cacheFile: File,
        total: Long,
        onProgress: ((Long, Long) -> Unit)?
    ): String {
        var downloaded = 0L
        // 先写临时文件，下载完整后原子重命名到最终路径，避免半成品文件被误判为已缓存
        val tmpFile = File(cacheFile.parentFile, cacheFile.name + ".tmp")
        try {
            FileOutputStream(tmpFile).use { output ->
                // SMBJ 按偏移顺序读取，按时间节流进度回调
                val buffer = ByteArray(BUFFER_SIZE)
                var lastCallbackTime = 0L
                while (true) {
                    val n = smbFile.read(buffer, downloaded)
                    if (n <= 0) break
                    output.write(buffer, 0, n)
                    downloaded += n
                    if (onProgress != null) {
                        val now = System.currentTimeMillis()
                        if (now - lastCallbackTime >= PROGRESS_INTERVAL_MS) {
                            lastCallbackTime = now
                            onProgress(downloaded, total)
                        }
                    }
                }
                if (onProgress != null) {
                    onProgress(downloaded, total)
                }
            }
            if (!tmpFile.renameTo(cacheFile)) {
                tmpFile.delete()
                throw java.io.IOException("缓存文件重命名失败")
            }
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
        return cacheFile.absolutePath
    }

    /**
     * 大文件：并发分段读。按 [concurrency] 切段，各段独立协程并发发 SMB 读请求，
     * 用 FileChannel 位置式写入本地临时文件（并发非重叠区间安全）。
     */
    private suspend fun readAndCacheSegmented(
        smbFile: SmbFile,
        cacheFile: File,
        total: Long,
        onProgress: ((Long, Long) -> Unit)?,
        concurrency: Int
    ): String {
        val tmpFile = File(cacheFile.parentFile, cacheFile.name + ".tmp")
        try {
            FileChannel.open(
                tmpFile.toPath(),
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            ).use { channel ->
                val n = concurrency.coerceIn(1, 16)
                val segmentSize = if (total <= 0) 0L else (total + n - 1) / n
                val downloaded = AtomicLong(0)
                val lastCallbackTime = AtomicLong(0)
                val startMs = System.currentTimeMillis()

                coroutineScope {
                    (0 until n).map { seg ->
                        async(Dispatchers.IO) {
                            val start = seg * segmentSize
                            val end = minOf(start + segmentSize, total)
                            var offset = start
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (offset < end) {
                                val want = minOf(BUFFER_SIZE.toLong(), end - offset).toInt()
                                val r = smbFile.read(buffer, offset, 0, want)
                                if (r <= 0) break
                                channel.write(ByteBuffer.wrap(buffer, 0, r), offset)
                                offset += r
                                val done = downloaded.addAndGet(r.toLong())
                                if (onProgress != null) {
                                    val now = System.currentTimeMillis()
                                    val prev = lastCallbackTime.get()
                                    if (now - prev >= PROGRESS_INTERVAL_MS &&
                                        lastCallbackTime.compareAndSet(prev, now)
                                    ) {
                                        onProgress(done, total)
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }

                if (downloaded.get() < total) {
                    throw java.io.IOException("下载不完整: ${downloaded.get()}/$total")
                }
                if (onProgress != null) {
                    onProgress(total, total)
                }
                // 打印实测吞吐量（字节/秒 → MB/s），便于判断是否打满带宽
                val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                val mbps = total * 1000.0 / elapsedMs / (1024.0 * 1024.0)
                android.util.Log.i(
                    "SmbImageLoader",
                    "分段下载完成: ${"%.1f".format(mbps)} MB/s (并发=$n, ${total / 1024}KB)"
                )
            }
            if (!tmpFile.renameTo(cacheFile)) {
                tmpFile.delete()
                throw java.io.IOException("缓存文件重命名失败")
            }
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
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
