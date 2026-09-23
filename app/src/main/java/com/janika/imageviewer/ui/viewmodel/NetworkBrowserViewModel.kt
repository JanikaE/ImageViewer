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
    val failedFiles: Int = 0,
    val isCancelling: Boolean = false
)

data class NetworkBrowserState(
    val serverAddress: String = "",
    val shareName: String = "",
    val currentPath: String = "",
    val currentFolderName: String = "网络共享",
    val files: List<ImageFile> = emptyList(),
    val shares: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val isConnected: Boolean = false,
    val browseMode: NetworkBrowseMode = NetworkBrowseMode.ONLINE,
    val cacheAvailable: Boolean = false,
    val folderCacheProgress: FolderCacheProgress? = null,
    val error: String? = null,
    // 配置状态（从 PreferencesManager 加载）
    val configServerAddress: String = "",
    val configUsername: String = "",
    val configPassword: String = ""
)

class NetworkBrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val repository = SmbRepository()
    private val preferences = PreferencesManager(application)
    private var folderCacheJob: Job? = null

    private val _state = MutableStateFlow(NetworkBrowserState())
    val state: StateFlow<NetworkBrowserState> = _state.asStateFlow()

    init {
        // 加载上次保存的配置并自动连接
        val savedConfig = preferences.loadConfig()
        if (savedConfig != null) {
            _state.value = _state.value.copy(
                configServerAddress = savedConfig.serverAddress,
                configUsername = savedConfig.username,
                configPassword = savedConfig.password,
                serverAddress = savedConfig.serverAddress,
                shares = savedConfig.shareNames,
                cacheAvailable = SmbCacheCatalog.hasCache(appContext, savedConfig.serverAddress)
            )
            // 自动尝试连接
            autoConnect(savedConfig)
        }

        // 预览图或查看器新增缓存后，及时刷新当前目录的缓存标记与数量。
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
                        cacheAvailable = SmbCacheCatalog.hasCache(
                            appContext, current.serverAddress
                        )
                    )
                }
            }
        }
    }

    private fun autoConnect(config: PreferencesManager.SmbConnectionConfig) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                // 冷启动时网络可能未就绪，重试最多 3 次
                var connected = false
                var lastError: String? = null
                for (attempt in 1..3) {
                    connected = repository.connect(
                        serverAddress = config.serverAddress,
                        username = config.username.ifEmpty { null },
                        password = config.password.ifEmpty { null }
                    )
                    if (connected) break
                    lastError = "无法连接到服务器，请检查设置中的地址和凭据"
                    if (attempt < 3) kotlinx.coroutines.delay(1000L * attempt)
                }
                if (connected) {
                    val previous = _state.value
                    _state.value = _state.value.copy(
                        serverAddress = config.serverAddress,
                        shares = config.shareNames,
                        isLoading = false,
                        isConnected = true,
                        browseMode = NetworkBrowseMode.ONLINE,
                        error = null
                    )
                    if (previous.browseMode == NetworkBrowseMode.CACHE_ONLY &&
                        previous.shareName.isNotEmpty()
                    ) {
                        navigateToFolder(previous.currentPath, previous.currentFolderName)
                    }
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isConnected = false,
                        cacheAvailable = SmbCacheCatalog.hasCache(appContext, config.serverAddress),
                        error = lastError
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    isConnected = false,
                    cacheAvailable = SmbCacheCatalog.hasCache(appContext, config.serverAddress),
                    error = "连接失败: ${e.message}"
                )
            }
        }
    }

    fun updateConfigServerAddress(address: String) {
        _state.value = _state.value.copy(configServerAddress = address)
    }

    fun updateConfigUsername(username: String) {
        _state.value = _state.value.copy(configUsername = username)
    }

    fun updateConfigPassword(password: String) {
        _state.value = _state.value.copy(configPassword = password)
    }

    fun connect() {
        val config = _state.value
        if (config.configServerAddress.isBlank()) {
            _state.value = _state.value.copy(error = "请输入服务器地址")
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                // 先建立连接
                val success = repository.connect(
                    serverAddress = config.configServerAddress,
                    username = config.configUsername.ifEmpty { null },
                    password = config.configPassword.ifEmpty { null }
                )

                if (!success) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        cacheAvailable = SmbCacheCatalog.hasCache(
                            appContext, config.configServerAddress
                        ),
                        error = "无法连接到服务器，请检查地址和凭据"
                    )
                } else {
                    val shareNames = preferences.loadShareNames()
                    _state.value = _state.value.copy(
                        serverAddress = config.configServerAddress,
                        shares = shareNames,
                        isLoading = false,
                        isConnected = true,
                        browseMode = NetworkBrowseMode.ONLINE,
                        error = null
                    )
                    // 连接成功后保存配置
                    preferences.saveConfig(
                        PreferencesManager.SmbConnectionConfig(
                            serverAddress = config.configServerAddress,
                            username = config.configUsername,
                            password = config.configPassword,
                            shareNames = shareNames
                        )
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    cacheAvailable = SmbCacheCatalog.hasCache(
                        appContext, config.configServerAddress
                    ),
                    error = "连接失败: ${e.message}"
                )
            }
        }
    }

    /** 重新从配置加载共享名列表（设置页修改后返回时调用） */
    fun refreshShares() {
        if (_state.value.shareName.isEmpty() &&
            _state.value.browseMode == NetworkBrowseMode.ONLINE
        ) {
            _state.value = _state.value.copy(shares = preferences.loadShareNames())
        }
    }

    fun openShare(shareName: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val config = _state.value
                if (config.browseMode == NetworkBrowseMode.CACHE_ONLY) {
                    val files = withContext(Dispatchers.IO) {
                        SmbCacheCatalog.listDirectory(
                            appContext, config.serverAddress, shareName, ""
                        )
                    }
                    _state.value = config.copy(
                        shareName = shareName,
                        currentPath = "",
                        currentFolderName = shareName,
                        files = files,
                        isLoading = false,
                        error = null
                    )
                    return@launch
                }
                // 确保已连接
                if (!repository.isConnected()) {
                    val ok = repository.connect(
                        serverAddress = config.serverAddress,
                        username = config.configUsername.ifEmpty { null },
                        password = config.configPassword.ifEmpty { null }
                    )
                    if (!ok) {
                        _state.value = _state.value.copy(
                            isLoading = false,
                            error = "无法连接到服务器，请检查设置中的地址和凭据"
                        )
                        return@launch
                    }
                }
                val files = annotateOnlineFiles(
                    repository.listFiles(shareName), config.serverAddress, shareName
                )
                _state.value = _state.value.copy(
                    shareName = shareName,
                    currentPath = "",
                    currentFolderName = shareName,
                    files = files,
                    isLoading = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    cacheAvailable = SmbCacheCatalog.hasCache(
                        appContext, _state.value.serverAddress
                    ),
                    error = "打开共享失败: ${e.message}"
                )
            }
        }
    }

    fun navigateToFolder(folderPath: String, folderName: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                val config = _state.value
                val files = if (config.browseMode == NetworkBrowseMode.CACHE_ONLY) {
                    withContext(Dispatchers.IO) {
                        SmbCacheCatalog.listDirectory(
                            appContext, config.serverAddress, config.shareName, folderPath
                        )
                    }
                } else {
                    annotateOnlineFiles(
                        repository.listFiles(config.shareName, folderPath),
                        config.serverAddress,
                        config.shareName
                    )
                }
                _state.value = _state.value.copy(
                    currentPath = folderPath,
                    currentFolderName = folderName,
                    files = files,
                    isLoading = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    cacheAvailable = SmbCacheCatalog.hasCache(
                        appContext, _state.value.serverAddress
                    ),
                    error = "浏览文件夹失败: ${e.message}"
                )
            }
        }
    }

    fun navigateUp() {
        val currentPath = _state.value.currentPath
        when {
            currentPath.isEmpty() -> {
                // 已在共享根目录 → 返回共享列表
                _state.value = _state.value.copy(
                    shareName = "",
                    currentPath = "",
                    currentFolderName = "共享文件夹",
                    files = emptyList(),
                    shares = _state.value.shares
                )
            }
            !currentPath.contains('/') -> {
                // 单级子目录 → 返回共享根目录
                navigateToFolder("", _state.value.shareName)
            }
            else -> {
                // 多级子目录 → 返回上一级
                val parentPath = currentPath.substringBeforeLast('/')
                val parentName = parentPath.substringAfterLast('/').ifEmpty { _state.value.shareName }
                navigateToFolder(parentPath, parentName)
            }
        }
    }

    fun disconnect() {
        folderCacheJob?.cancel()
        repository.disconnect()
        val saved = _state.value.configServerAddress
        _state.value = NetworkBrowserState(
            configServerAddress = saved,
            configUsername = _state.value.configUsername,
            configPassword = _state.value.configPassword
        )
    }

    /** 连接失败后切换到只读缓存模式。 */
    fun enterCacheOnlyMode() {
        val current = _state.value
        val server = current.configServerAddress.ifEmpty { current.serverAddress }
        viewModelScope.launch {
            _state.value = current.copy(isLoading = true, error = null)
            val cachedShares = withContext(Dispatchers.IO) {
                SmbCacheCatalog.listShareNames(appContext, server)
            }
            _state.value = current.copy(
                serverAddress = server,
                shareName = "",
                currentPath = "",
                currentFolderName = "缓存共享",
                files = emptyList(),
                shares = cachedShares,
                isLoading = false,
                isConnected = false,
                browseMode = NetworkBrowseMode.CACHE_ONLY,
                cacheAvailable = cachedShares.isNotEmpty(),
                error = null
            )
        }
    }

    fun retryConnection() {
        preferences.loadConfig()?.let { autoConnect(it) }
    }

    /** 缓存指定文件夹中的全部受支持媒体文件。 */
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
                    cacheAvailable = SmbCacheCatalog.hasCache(appContext, server),
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
                val progress = _state.value.folderCacheProgress
                _state.value = _state.value.copy(
                    files = annotateOnlineFiles(_state.value.files, server, share),
                    cacheAvailable = SmbCacheCatalog.hasCache(appContext, server),
                    folderCacheProgress = progress?.copy(
                        phase = FolderCachePhase.CANCELLED,
                        currentFileName = "",
                        completedFiles = completed.get(),
                        downloadedBytes = downloadedByPath.values.sum(),
                        skippedFiles = skipped.get(),
                        failedFiles = failed.get(),
                        isCancelling = false
                    )
                )
            } catch (e: Exception) {
                val progress = _state.value.folderCacheProgress
                _state.value = _state.value.copy(
                    files = annotateOnlineFiles(_state.value.files, server, share),
                    cacheAvailable = SmbCacheCatalog.hasCache(appContext, server),
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

    fun cancelFolderCaching() {
        val progress = _state.value.folderCacheProgress ?: return
        if (progress.phase == FolderCachePhase.SCANNING ||
            progress.phase == FolderCachePhase.DOWNLOADING
        ) {
            _state.value = _state.value.copy(
                folderCacheProgress = progress.copy(isCancelling = true)
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

    private suspend fun annotateOnlineFiles(
        files: List<ImageFile>,
        serverAddress: String,
        shareName: String
    ): List<ImageFile> = withContext(Dispatchers.IO) {
        files.map { file ->
            if (file.isDirectory) {
                file.copy(
                    cachedChildFileCount = SmbCacheCatalog.getDirectCachedFileCount(
                        appContext, serverAddress, shareName, file.path
                    )
                )
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
