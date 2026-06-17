package com.janika.imageviewer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.janika.imageviewer.data.local.PreferencesManager
import com.janika.imageviewer.data.model.ImageFile
import com.janika.imageviewer.data.repository.SmbRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NetworkBrowserState(
    val serverAddress: String = "",
    val shareName: String = "",
    val currentPath: String = "",
    val currentFolderName: String = "网络共享",
    val files: List<ImageFile> = emptyList(),
    val shares: List<String> = emptyList(),
    val servers: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val isConnected: Boolean = false,
    val error: String? = null,
    // 配置状态（从 PreferencesManager 加载）
    val configServerAddress: String = "",
    val configUsername: String = "",
    val configPassword: String = ""
)

class NetworkBrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SmbRepository()
    private val preferences = PreferencesManager(application)

    private val _state = MutableStateFlow(NetworkBrowserState())
    val state: StateFlow<NetworkBrowserState> = _state.asStateFlow()

    init {
        // 加载上次保存的配置并自动连接
        val savedConfig = preferences.loadConfig()
        if (savedConfig != null) {
            _state.value = _state.value.copy(
                configServerAddress = savedConfig.serverAddress,
                configUsername = savedConfig.username,
                configPassword = savedConfig.password
            )
            // 自动尝试连接
            autoConnect(savedConfig)
        }
    }

    private fun autoConnect(config: PreferencesManager.SmbConnectionConfig) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                // 冷启动时网络可能未就绪，重试最多 3 次
                var shares: List<String> = emptyList()
                var lastError: String? = null
                for (attempt in 1..3) {
                    val success = repository.connect(
                        serverAddress = config.serverAddress,
                        shareName = "",
                        username = config.username.ifEmpty { null },
                        password = config.password.ifEmpty { null }
                    )
                    shares = repository.listShares(config.serverAddress)
                    if (shares.isNotEmpty()) break
                    lastError = if (!success) "无法连接到服务器，请检查设置中的地址和凭据"
                        else "服务器上没有找到共享文件夹"
                    if (attempt < 3) kotlinx.coroutines.delay(1000L * attempt)
                }
                if (shares.isNotEmpty()) {
                    _state.value = _state.value.copy(
                        serverAddress = config.serverAddress,
                        shares = shares,
                        isLoading = false,
                        isConnected = true
                    )
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = lastError
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
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
                    shareName = "", // 空共享名用于测试连接
                    username = config.configUsername.ifEmpty { null },
                    password = config.configPassword.ifEmpty { null }
                )

                // 无论连接测试是否成功，都尝试列出共享
                val shares = repository.listShares(config.configServerAddress)

                if (shares.isEmpty()) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = if (!success) "无法连接到服务器，请检查地址和凭据" else "服务器上没有找到共享文件夹"
                    )
                } else {
                    _state.value = _state.value.copy(
                        serverAddress = config.configServerAddress,
                        shares = shares,
                        isLoading = false,
                        isConnected = true
                    )
                    // 连接成功后保存配置
                    preferences.saveConfig(
                        PreferencesManager.SmbConnectionConfig(
                            serverAddress = config.configServerAddress,
                            username = config.configUsername,
                            password = config.configPassword
                        )
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "连接失败: ${e.message}"
                )
            }
        }
    }

    fun openShare(shareName: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val config = _state.value
                // 确保已连接
                if (!repository.isConnected()) {
                    repository.connect(
                        serverAddress = config.serverAddress,
                        shareName = shareName,
                        username = config.configUsername.ifEmpty { null },
                        password = config.configPassword.ifEmpty { null }
                    )
                }
                val files = repository.listFiles(config.serverAddress, shareName)
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
                val files = repository.listFiles(config.serverAddress, config.shareName, folderPath)
                _state.value = _state.value.copy(
                    currentPath = folderPath,
                    currentFolderName = folderName,
                    files = files,
                    isLoading = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
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
        repository.disconnect()
        val saved = _state.value.configServerAddress
        _state.value = NetworkBrowserState(
            configServerAddress = saved,
            configUsername = _state.value.configUsername,
            configPassword = _state.value.configPassword
        )
    }
}
