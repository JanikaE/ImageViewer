package com.janika.imageviewer.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

class LocalBrowserViewModel : ViewModel() {
    private val repository = LocalFileRepository()

    private val _state = MutableStateFlow(LocalBrowserState())
    val state: StateFlow<LocalBrowserState> = _state.asStateFlow()

    init {
        loadRootDirectories()
    }

    fun loadRootDirectories() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, isRootLevel = true)
            val roots = repository.getRootDirectories()
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
            val files = repository.listFiles(path)
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
}
