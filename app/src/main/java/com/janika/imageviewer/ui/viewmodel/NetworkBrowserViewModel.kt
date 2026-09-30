package com.janika.imageviewer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.janika.imageviewer.data.local.PreferencesManager
import com.janika.imageviewer.data.model.ImageFile
import com.janika.imageviewer.data.repository.SmbRepository
import com.janika.imageviewer.util.SmbCacheCatalog
import com.janika.imageviewer.util.SmbImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class NetworkBrowseMode { ONLINE, CACHE_ONLY }

enum class FolderCachePhase { SCANNING, DOWNLOADING, COMPLETED, CANCELLED }

data class NetworkShareTarget(
    val serverId: String,
    val serverAddress: String,
    val shareName: String
)

data class FolderCacheProgress(
    val folderName: String,
    val phase: FolderCachePhase,
    val scanningPath: String = "",
    val currentFileName: String = "",
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val skippedFiles: Int = 0,
    val failedFiles: Int = 0
)

data class NetworkBrowserState(
    val serverAddress: String = "",
    val serverId: String = "",
    val shareName: String = "",
    val currentPath: String = "",
    val currentFolderName: String = "网络共享",
    val files: List<ImageFile> = emptyList(),
    val shareTargets: List<NetworkShareTarget> = emptyList(),
    val isLoading: Boolean = false,
    val isConnected: Boolean = false,
    val browseMode: NetworkBrowseMode = NetworkBrowseMode.ONLINE,
    val cacheAvailable: Boolean = false,
    val folderCacheProgress: FolderCacheProgress? = null,
    val error: String? = null
)

class NetworkBrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val repository = SmbRepository()
    private val preferences = PreferencesManager(application)
    private var folderCacheJob: Job? = null
    private var showFolderCounts = preferences.loadShowFolderCounts()
    private val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()
    private var configs = preferences.loadServerConfigs()

    private val _state = MutableStateFlow(
        NetworkBrowserState(shareTargets = buildTargets(configs))
    )
    val state: StateFlow<NetworkBrowserState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            SmbCacheCatalog.revision.collect {
                val current = _state.value
                if (current.browseMode == NetworkBrowseMode.ONLINE &&
                    current.shareName.isNotEmpty() && current.files.isNotEmpty()
                ) {
                    _state.value = current.copy(
                        files = annotateOnlineFiles(
                            current.files, current.serverAddress, current.shareName
                        ),
                        cacheAvailable = hasTargetCache(current.serverAddress, current.shareName)
                    )
                }
            }
        }
    }

    /** 设置页返回时重载启用的服务器与共享，不主动连接离线服务器。 */
    fun refreshShares() {
        val previousConfigs = configs
        val updatedConfigs = preferences.loadServerConfigs()
        val current = _state.value
        val previousActive = previousConfigs.firstOrNull { it.id == current.serverId }
        val updatedActive = updatedConfigs.firstOrNull { it.id == current.serverId }
        configs = updatedConfigs
        if (current.shareName.isEmpty() || updatedActive == null || !updatedActive.enabled ||
            previousActive != updatedActive
        ) {
            _state.value = _state.value.copy(
                shareTargets = buildTargets(configs),
                serverId = "",
                serverAddress = "",
                shareName = "",
                currentPath = "",
                currentFolderName = "网络共享",
                files = emptyList(),
                isLoading = false,
                isConnected = false,
                browseMode = NetworkBrowseMode.ONLINE,
                cacheAvailable = false,
                error = null
            )
        }
    }

    fun openShare(target: NetworkShareTarget) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                serverId = target.serverId,
                serverAddress = target.serverAddress,
                shareName = target.shareName,
                currentPath = "",
                currentFolderName = target.shareName,
                files = emptyList(),
                browseMode = NetworkBrowseMode.ONLINE,
                isLoading = true,
                error = null,
                cacheAvailable = hasTargetCache(target.serverAddress, target.shareName)
            )
            if (!ensureConnected(target.serverId)) return@launch
            loadOnlineDirectory("", target.shareName)
        }
    }

    fun navigateToFolder(folderPath: String, folderName: String) {
        val current = _state.value
        if (current.shareName.isEmpty()) return
        viewModelScope.launch {
            _state.value = current.copy(isLoading = true, error = null)
            try {
                val files = if (current.browseMode == NetworkBrowseMode.CACHE_ONLY) {
                    withContext(Dispatchers.IO) {
                        SmbCacheCatalog.listDirectory(
                            appContext, current.serverAddress, current.shareName, folderPath
                        )
                    }
                } else {
                    annotateOnlineFiles(
                        repository.listFiles(
                            current.serverAddress,
                            current.shareName,
                            folderPath,
                            includeFolderCounts = showFolderCounts
                        ),
                        current.serverAddress,
                        current.shareName
                    )
                }
                _state.value = _state.value.copy(
                    currentPath = folderPath,
                    currentFolderName = folderName,
                    files = files,
                    isLoading = false
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(
                    isLoading = false,
                    cacheAvailable = hasTargetCache(current.serverAddress, current.shareName),
                    error = "浏览文件夹失败: ${e.message}"
                )
            }
        }
    }

    fun navigateUp() {
        val current = _state.value
        when {
            current.currentPath.isEmpty() -> {
                _state.value = NetworkBrowserState(shareTargets = buildTargets(configs))
            }
            !current.currentPath.contains('/') -> navigateToFolder("", current.shareName)
            else -> {
                val parentPath = current.currentPath.substringBeforeLast('/')
                val parentName = parentPath.substringAfterLast('/').ifEmpty { current.shareName }
                navigateToFolder(parentPath, parentName)
            }
        }
    }

    fun scrollKey(): String {
        val current = _state.value
        if (current.shareName.isEmpty()) return "network:shares"
        return "network:${current.browseMode}:${current.serverId}:${current.shareName}:${current.currentPath}"
    }

    fun loadScrollPosition(key: String): Pair<Int, Int> = scrollPositions[key] ?: (0 to 0)

    fun saveScrollPosition(key: String, index: Int, offset: Int) {
        scrollPositions[key] = index to offset
    }

    fun updateFolderCountSetting(show: Boolean) {
        if (showFolderCounts == show) return
        showFolderCounts = show
        val current = _state.value
        if (!show) {
            _state.value = current.copy(files = current.files.map { file ->
                if (file.isDirectory) file.copy(
                    childFileCount = null,
                    childDirectoryCount = null,
                    cachedChildFileCount = null
                ) else file
            })
        } else if (current.shareName.isNotEmpty()) {
            navigateToFolder(current.currentPath, current.currentFolderName)
        }
    }

    fun disconnect() {
        folderCacheJob?.cancel()
        repository.disconnectAll()
        configs = preferences.loadServerConfigs()
        _state.value = NetworkBrowserState(shareTargets = buildTargets(configs))
    }

    /** 当前共享连接失败后，只读取该服务器和共享的完整缓存。 */
    fun enterCacheOnlyMode() {
        val current = _state.value
        if (current.serverAddress.isEmpty() || current.shareName.isEmpty()) return
        viewModelScope.launch {
            _state.value = current.copy(isLoading = true, error = null)
            val files = withContext(Dispatchers.IO) {
                SmbCacheCatalog.listDirectory(
                    appContext, current.serverAddress, current.shareName, current.currentPath
                )
            }
            _state.value = current.copy(
                files = files,
                isLoading = false,
                isConnected = false,
                browseMode = NetworkBrowseMode.CACHE_ONLY,
                cacheAvailable = true,
                error = null
            )
        }
    }

    fun retryConnection() {
        val current = _state.value
        if (current.serverId.isEmpty()) return
        viewModelScope.launch {
            _state.value = current.copy(isLoading = true, error = null, browseMode = NetworkBrowseMode.ONLINE)
            repository.disconnect(current.serverAddress)
            if (!ensureConnected(current.serverId)) return@launch
            loadOnlineDirectory(current.currentPath, current.currentFolderName)
        }
    }

    private suspend fun ensureConnected(serverId: String): Boolean {
        val config = configs.firstOrNull { it.id == serverId }
        if (config == null) {
            _state.value = _state.value.copy(isLoading = false, error = "服务器配置不存在或已被禁用")
            return false
        }
        if (repository.isConnected(config.serverAddress)) {
            _state.value = _state.value.copy(isConnected = true)
            return true
        }
        var connected = false
        for (attempt in 1..3) {
            connected = repository.connect(
                config.serverAddress,
                config.username.ifEmpty { null },
                config.password.ifEmpty { null }
            )
            if (connected) break
            if (attempt < 3) kotlinx.coroutines.delay(1000L * attempt)
        }
        _state.value = _state.value.copy(
            isConnected = connected,
            isLoading = if (connected) _state.value.isLoading else false,
            cacheAvailable = hasTargetCache(config.serverAddress, _state.value.shareName),
            error = if (connected) null else "无法连接到服务器，请检查设置中的地址和凭据"
        )
        return connected
    }

    private suspend fun loadOnlineDirectory(path: String, folderName: String) {
        val current = _state.value
        try {
            val files = annotateOnlineFiles(
                repository.listFiles(
                    current.serverAddress,
                    current.shareName,
                    path,
                    includeFolderCounts = showFolderCounts
                ),
                current.serverAddress,
                current.shareName
            )
            _state.value = _state.value.copy(
                currentPath = path,
                currentFolderName = folderName,
                files = files,
                isLoading = false,
                isConnected = true,
                error = null
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.value = _state.value.copy(
                isLoading = false,
                cacheAvailable = hasTargetCache(current.serverAddress, current.shareName),
                error = "打开共享失败: ${e.message}"
            )
        }
    }

    fun cacheFolder(folder: ImageFile) {
        val current = _state.value
        if (current.browseMode != NetworkBrowseMode.ONLINE || folderCacheJob?.isActive == true) return
        val server = current.serverAddress
        val share = current.shareName
        folderCacheJob = viewModelScope.launch {
            val completed = AtomicInteger(0)
            val skipped = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val downloadedByPath = ConcurrentHashMap<String, Long>()
            try {
                _state.value = _state.value.copy(
                    folderCacheProgress = FolderCacheProgress(
                        folderName = folder.name,
                        phase = FolderCachePhase.SCANNING,
                        scanningPath = folder.path
                    )
                )
                val files = repository.listMediaFilesRecursively(
                    serverAddress = server,
                    shareName = share,
                    folderPath = folder.path,
                    onScanningDirectory = { path ->
                        _state.value.folderCacheProgress?.let { progress ->
                            _state.value = _state.value.copy(
                                folderCacheProgress = progress.copy(scanningPath = path)
                            )
                        }
                    }
                )
                val totalBytes = files.sumOf { it.size.coerceAtLeast(0L) }
                _state.value = _state.value.copy(
                    folderCacheProgress = FolderCacheProgress(
                        folderName = folder.name,
                        phase = FolderCachePhase.DOWNLOADING,
                        totalFiles = files.size,
                        totalBytes = totalBytes
                    )
                )
                val semaphore = Semaphore(3)
                coroutineScope {
                    files.map { file ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                val existing = SmbImageLoader.getCachePath(
                                    appContext, server, share, file.path
                                )
                                val result = if (existing != null) {
                                    skipped.incrementAndGet()
                                    downloadedByPath[file.path] = file.size
                                    existing
                                } else {
                                    SmbImageLoader.cacheSmbFile(
                                        context = appContext,
                                        serverAddress = server,
                                        shareName = share,
                                        filePath = file.path,
                                        onProgress = { done, _ ->
                                            downloadedByPath[file.path] = done
                                            updateFolderDownloadProgress(
                                                file.name, completed.get(), files.size,
                                                downloadedByPath.values.sum(), totalBytes,
                                                skipped.get(), failed.get()
                                            )
                                        },
                                        expectedSize = file.size,
                                        lastModified = file.lastModified
                                    )
                                }
                                if (result == null) failed.incrementAndGet()
                                completed.incrementAndGet()
                                updateFolderDownloadProgress(
                                    file.name, completed.get(), files.size,
                                    downloadedByPath.values.sum(), totalBytes,
                                    skipped.get(), failed.get()
                                )
                            }
                        }
                    }.awaitAll()
                }
                val progress = _state.value.folderCacheProgress
                _state.value = _state.value.copy(
                    cacheAvailable = hasTargetCache(server, share),
                    folderCacheProgress = progress?.copy(
                        phase = FolderCachePhase.COMPLETED,
                        currentFileName = "",
                        completedFiles = completed.get(),
                        downloadedBytes = downloadedByPath.values.sum(),
                        skippedFiles = skipped.get(),
                        failedFiles = failed.get()
                    )
                )
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    finishCancelledCache(server, share, completed, skipped, failed, downloadedByPath)
                }
            } catch (e: Exception) {
                val progress = _state.value.folderCacheProgress
                _state.value = _state.value.copy(
                    files = annotateOnlineFiles(_state.value.files, server, share),
                    cacheAvailable = hasTargetCache(server, share),
                    folderCacheProgress = progress?.copy(
                        phase = FolderCachePhase.COMPLETED,
                        currentFileName = "",
                        failedFiles = failed.get() + 1
                    ),
                    error = "缓存文件夹失败: ${e.message}"
                )
            }
        }
    }

    private suspend fun finishCancelledCache(
        server: String,
        share: String,
        completed: AtomicInteger,
        skipped: AtomicInteger,
        failed: AtomicInteger,
        downloadedByPath: ConcurrentHashMap<String, Long>
    ) {
        val progress = _state.value.folderCacheProgress
        _state.value = _state.value.copy(
            files = annotateOnlineFiles(_state.value.files, server, share),
            cacheAvailable = hasTargetCache(server, share),
            folderCacheProgress = progress?.copy(
                phase = FolderCachePhase.CANCELLED,
                currentFileName = "",
                completedFiles = completed.get(),
                downloadedBytes = downloadedByPath.values.sum(),
                skippedFiles = skipped.get(),
                failedFiles = failed.get()
            )
        )
    }

    fun cancelFolderCaching() {
        val progress = _state.value.folderCacheProgress ?: return
        if (progress.phase == FolderCachePhase.SCANNING ||
            progress.phase == FolderCachePhase.DOWNLOADING
        ) {
            _state.value = _state.value.copy(
                folderCacheProgress = progress.copy(
                    phase = FolderCachePhase.CANCELLED,
                    currentFileName = ""
                )
            )
            folderCacheJob?.cancel()
        }
    }

    fun dismissFolderCacheProgress() {
        val phase = _state.value.folderCacheProgress?.phase ?: return
        if (phase == FolderCachePhase.COMPLETED || phase == FolderCachePhase.CANCELLED) {
            _state.value = _state.value.copy(folderCacheProgress = null)
        }
    }

    private fun updateFolderDownloadProgress(
        currentFileName: String,
        completedFiles: Int,
        totalFiles: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        skippedFiles: Int,
        failedFiles: Int
    ) {
        val progress = _state.value.folderCacheProgress ?: return
        _state.value = _state.value.copy(
            folderCacheProgress = progress.copy(
                currentFileName = currentFileName,
                completedFiles = completedFiles,
                totalFiles = totalFiles,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                skippedFiles = skippedFiles,
                failedFiles = failedFiles
            )
        )
    }

    private fun buildTargets(configs: List<PreferencesManager.SmbServerConfig>): List<NetworkShareTarget> =
        configs.asSequence()
            .filter { it.enabled }
            .flatMap { config ->
                config.shareNames.asSequence().map { share ->
                    NetworkShareTarget(config.id, config.serverAddress, share)
                }
            }
            .toList()

    private suspend fun hasTargetCache(serverAddress: String, shareName: String): Boolean =
        withContext(Dispatchers.IO) {
            SmbCacheCatalog.listShareNames(appContext, serverAddress).contains(shareName)
        }

    private suspend fun annotateOnlineFiles(
        files: List<ImageFile>,
        serverAddress: String,
        shareName: String
    ): List<ImageFile> = withContext(Dispatchers.IO) {
        files.map { file ->
            if (file.isDirectory) {
                if (showFolderCounts) file.copy(
                    cachedChildFileCount = SmbCacheCatalog.getDirectCachedFileCount(
                        appContext, serverAddress, shareName, file.path
                    )
                ) else file.copy(cachedChildFileCount = null)
            } else {
                file.copy(
                    localCachePath = SmbImageLoader.getCachePath(
                        appContext, serverAddress, shareName, file.path
                    )
                )
            }
        }
    }
}
