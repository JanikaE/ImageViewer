package com.janika.imageviewer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.janika.imageviewer.data.local.PreferencesManager
import com.janika.imageviewer.data.model.ImageFile
import com.janika.imageviewer.data.repository.LocalFileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LocalBrowserState(
    val currentPath: String = "",
    val currentFolderName: String = "本地文件",
    val files: List<ImageFile> = emptyList(),
    val isLoading: Boolean = true,
    val hasParent: Boolean = false,
    val rootDirectories: List<ImageFile> = emptyList(),
    val isRootLevel: Boolean = true
)

class LocalBrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LocalFileRepository()
    private val preferences = PreferencesManager(application)
    private var showFolderCounts = preferences.loadShowFolderCounts()
    private val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()

    private val _state = MutableStateFlow(LocalBrowserState())
    val state: StateFlow<LocalBrowserState> = _state.asStateFlow()

    init {
        loadRootDirectories()
    }

    fun loadRootDirectories() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, isRootLevel = true)
            val roots = repository.getRootDirectories(showFolderCounts)
            _state.value = _state.value.copy(
                rootDirectories = roots,
                files = roots,
                isLoading = false,
                hasParent = false,
                currentFolderName = "存储设备"
            )
        }
    }

    fun navigateTo(path: String, folderName: String? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val files = repository.listFiles(path, showFolderCounts)
            val parent = repository.getParentPath(path)
            _state.value = _state.value.copy(
                currentPath = path,
                currentFolderName = folderName ?: files.firstOrNull()?.name ?: path.substringAfterLast('/'),
                files = files,
                isLoading = false,
                hasParent = parent != null,
                isRootLevel = false
            )
        }
    }

    fun navigateUp() {
        viewModelScope.launch {
            val currentPath = _state.value.currentPath
            val parent = repository.getParentPath(currentPath)
            if (parent != null) {
                val folderName = currentPath.substringAfterLast('/')
                // 检查父目录是否是根目录
                val grandParent = repository.getParentPath(parent)
                if (grandParent == null) {
                    loadRootDirectories()
                } else {
                    navigateTo(parent, "...")
                }
            } else {
                loadRootDirectories()
            }
        }
    }

    fun scrollKey(): String = if (_state.value.isRootLevel) {
        "local:root"
    } else {
        "local:${_state.value.currentPath}"
    }

    fun loadScrollPosition(key: String): Pair<Int, Int> = scrollPositions[key] ?: (0 to 0)

    fun saveScrollPosition(key: String, index: Int, offset: Int) {
        scrollPositions[key] = index to offset
    }

    /** 设置变化时更新当前列表；开启统计需要重新读取目录，关闭时可直接移除数量。 */
    fun updateFolderCountSetting(show: Boolean) {
        if (showFolderCounts == show) return
        showFolderCounts = show
        if (!show) {
            _state.value = _state.value.copy(
                files = _state.value.files.map { file ->
                    if (file.isDirectory) {
                        file.copy(childFileCount = null, childDirectoryCount = null)
                    } else {
                        file
                    }
                }
            )
        } else if (_state.value.isRootLevel) {
            loadRootDirectories()
        } else {
            navigateTo(_state.value.currentPath, _state.value.currentFolderName)
        }
    }
}
