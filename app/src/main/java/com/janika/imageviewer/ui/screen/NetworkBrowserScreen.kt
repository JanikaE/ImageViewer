package com.janika.imageviewer.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.janika.imageviewer.data.model.ImageFile
import com.janika.imageviewer.data.local.PreferencesManager
import com.janika.imageviewer.ui.component.FolderContentCount
import com.janika.imageviewer.ui.component.LazyGridScrollbar
import com.janika.imageviewer.ui.viewmodel.FolderCachePhase
import com.janika.imageviewer.ui.viewmodel.NetworkBrowserViewModel
import com.janika.imageviewer.ui.viewmodel.NetworkBrowseMode
import com.janika.imageviewer.util.SmbImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NetworkBrowserScreen(
    onImageClick: (List<ImageFile>, Int, String, String) -> Unit,
    onVideoClick: (ImageFile, String, String) -> Unit = { _, _, _ -> },
    onNavigateToSettings: () -> Unit = {},
    onNavigateBack: () -> Unit = {},
    viewModel: NetworkBrowserViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context) }
    val labelFontScale = prefs.loadLabelFontScale()
    val labelMaxLines = prefs.loadLabelMaxLines()
    val showFolderCounts = prefs.loadShowFolderCounts()
    var cacheConfirmFolder by remember { mutableStateOf<ImageFile?>(null) }
    val scrollKey = viewModel.scrollKey()
    val savedScrollPosition = viewModel.loadScrollPosition(scrollKey)
    val gridState = key(scrollKey) {
        rememberLazyGridState(
            initialFirstVisibleItemIndex = savedScrollPosition.first,
            initialFirstVisibleItemScrollOffset = savedScrollPosition.second
        )
    }

    LaunchedEffect(scrollKey, gridState) {
        snapshotFlow {
            gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            viewModel.saveScrollPosition(scrollKey, index, offset)
        }
    }

    fun saveCurrentScrollPosition() {
        viewModel.saveScrollPosition(
            scrollKey,
            gridState.firstVisibleItemIndex,
            gridState.firstVisibleItemScrollOffset
        )
    }

    LaunchedEffect(showFolderCounts) {
        viewModel.updateFolderCountSetting(showFolderCounts)
    }

    // 进入页面时刷新共享名列表（设置页可能已修改）
    LaunchedEffect(Unit) { viewModel.refreshShares() }

    // 拦截系统返回键
    BackHandler(enabled = state.isConnected || state.browseMode == NetworkBrowseMode.CACHE_ONLY) {
        if (state.shareName.isNotEmpty()) {
            saveCurrentScrollPosition()
            viewModel.navigateUp()
        } else {
            viewModel.disconnect()
            onNavigateBack()
        }
    }

    Column(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶部栏
        TopAppBar(
            title = {
                Text(
                    text = when {
                        state.shareName.isEmpty() -> "共享文件夹"
                        else -> state.currentFolderName
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                if (state.shareName.isNotEmpty() || state.isConnected ||
                    state.browseMode == NetworkBrowseMode.CACHE_ONLY
                ) {
                    IconButton(onClick = {
                        if (state.shareName.isNotEmpty()) {
                            saveCurrentScrollPosition()
                            viewModel.navigateUp()
                        } else {
                            viewModel.disconnect()
                            onNavigateBack()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            actions = {
                if (state.browseMode == NetworkBrowseMode.CACHE_ONLY) {
                    IconButton(onClick = { viewModel.retryConnection() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新连接")
                    }
                }
                if (state.isConnected || state.browseMode == NetworkBrowseMode.CACHE_ONLY) {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            }
        )

        if (state.browseMode == NetworkBrowseMode.CACHE_ONLY) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("正在读取本地缓存", modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.retryConnection() }) { Text("重新连接") }
                }
            }
        }

        // 错误信息
        state.error?.let { error ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onNavigateToSettings) {
                        Text("设置")
                    }
                    if (state.browseMode == NetworkBrowseMode.ONLINE) {
                        TextButton(onClick = { viewModel.retryConnection() }) {
                            Text("重试")
                        }
                    }
                }
            }
        }

        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            state.shareName.isEmpty() && state.shares.isNotEmpty() -> {
                // 显示已配置的共享文件夹列表
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        state = gridState,
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(state.shares, key = { it }) { share ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clickable {
                                        saveCurrentScrollPosition()
                                        viewModel.openShare(share)
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Dns,
                                            contentDescription = null,
                                            modifier = Modifier.size(48.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = share,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                    LazyGridScrollbar(
                        state = gridState,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(vertical = 8.dp, horizontal = 2.dp)
                            .width(4.dp)
                            .fillMaxHeight()
                    )
                }
            }
            state.files.isNotEmpty() -> {
                // 显示文件列表
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        state = gridState,
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(state.files, key = { it.path }) { file ->
                            val cachePath = file.localCachePath ?: if (!file.isDirectory) {
                                SmbImageLoader.getCachePath(
                                    context, state.serverAddress, state.shareName, file.path
                                )
                            } else null
                            NetworkFileGridItem(
                                file = file,
                                cachePath = cachePath,
                                serverAddress = state.serverAddress,
                                shareName = state.shareName,
                                cacheOnly = state.browseMode == NetworkBrowseMode.CACHE_ONLY,
                                labelFontScale = labelFontScale,
                                labelMaxLines = labelMaxLines,
                                showFolderCounts = showFolderCounts,
                                onFolderClick = {
                                    saveCurrentScrollPosition()
                                    viewModel.navigateToFolder(file.path, file.name)
                                },
                                onFolderLongClick = {
                                    if (state.browseMode == NetworkBrowseMode.ONLINE) {
                                        cacheConfirmFolder = file
                                    }
                                },
                                onVideoClick = {
                                    onVideoClick(file, state.serverAddress, state.shareName)
                                },
                                onImageClick = {
                                    // 过滤出所有图片文件并传递索引
                                    val imageFiles = state.files.filter {
                                        !it.isDirectory && it.isImage
                                    }
                                    val idx = imageFiles.indexOf(file)
                                    onImageClick(
                                        imageFiles,
                                        idx.coerceAtLeast(0),
                                        state.serverAddress,
                                        state.shareName
                                    )
                                }
                            )
                        }
                    }
                    LazyGridScrollbar(
                        state = gridState,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(vertical = 8.dp, horizontal = 2.dp)
                            .width(4.dp)
                            .fillMaxHeight()
                    )
                }
            }
            state.shareName.isEmpty() && state.shares.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (state.browseMode == NetworkBrowseMode.CACHE_ONLY) {
                            "没有可读取的缓存"
                        } else {
                            "尚未配置共享名，请到设置中添加"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "未找到共享内容",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (state.error != null && state.cacheAvailable &&
        state.browseMode == NetworkBrowseMode.ONLINE && !state.isLoading &&
        state.folderCacheProgress == null
    ) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("连接失败") },
            text = { Text("${state.error}\n\n可以重试连接，或读取已经完整缓存的文件。") },
            confirmButton = {
                TextButton(onClick = { viewModel.retryConnection() }) { Text("重试") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onNavigateToSettings) { Text("设置") }
                    TextButton(onClick = { viewModel.enterCacheOnlyMode() }) { Text("读取缓存") }
                }
            }
        )
    }

    cacheConfirmFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { cacheConfirmFolder = null },
            title = { Text("缓存文件夹") },
            text = {
                Text("将递归缓存「${folder.name}」中的所有受支持图片和视频。已缓存文件会跳过。")
            },
            confirmButton = {
                TextButton(onClick = {
                    cacheConfirmFolder = null
                    viewModel.cacheFolder(folder)
                }) { Text("开始缓存") }
            },
            dismissButton = {
                TextButton(onClick = { cacheConfirmFolder = null }) { Text("取消") }
            }
        )
    }

    state.folderCacheProgress?.let { progress ->
        val running = progress.phase == FolderCachePhase.SCANNING ||
            progress.phase == FolderCachePhase.DOWNLOADING
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    when (progress.phase) {
                        FolderCachePhase.SCANNING -> "正在扫描「${progress.folderName}」"
                        FolderCachePhase.DOWNLOADING -> "正在缓存「${progress.folderName}」"
                        FolderCachePhase.COMPLETED -> "缓存完成"
                        FolderCachePhase.CANCELLED -> "已取消缓存"
                    }
                )
            },
            text = {
                Column {
                    when (progress.phase) {
                        FolderCachePhase.SCANNING -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(12.dp))
                            Text(
                                progress.scanningPath,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        else -> {
                            val fraction = if (progress.totalBytes > 0L) {
                                progress.downloadedBytes.toFloat() / progress.totalBytes
                            } else if (progress.totalFiles > 0) {
                                progress.completedFiles.toFloat() / progress.totalFiles
                            } else 1f
                            LinearProgressIndicator(
                                progress = { fraction.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("文件 ${progress.completedFiles} / ${progress.totalFiles}")
                            Text(
                                "数据 ${formatCacheSize(progress.downloadedBytes)} / ${formatCacheSize(progress.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (progress.skippedFiles > 0) {
                                Text(
                                    "已跳过 ${progress.skippedFiles} 个现有缓存",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (progress.failedFiles > 0) {
                                Text(
                                    "失败 ${progress.failedFiles} 个",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (progress.currentFileName.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    progress.currentFileName,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (progress.isCancelling) {
                                Spacer(Modifier.height(8.dp))
                                Text("正在取消并清理未完成文件……")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (running) {
                    TextButton(
                        onClick = { viewModel.cancelFolderCaching() },
                        enabled = !progress.isCancelling
                    ) { Text("取消缓存") }
                } else {
                    TextButton(onClick = { viewModel.dismissFolderCacheProgress() }) {
                        Text("关闭")
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NetworkFileGridItem(
    file: ImageFile,
    cachePath: String?,
    serverAddress: String,
    shareName: String,
    cacheOnly: Boolean,
    labelFontScale: Float,
    labelMaxLines: Int,
    showFolderCounts: Boolean,
    onFolderClick: () -> Unit,
    onFolderLongClick: () -> Unit,
    onVideoClick: () -> Unit,
    onImageClick: () -> Unit
) {
    val context = LocalContext.current

    val nameStyle = MaterialTheme.typography.bodySmall.copy(
        fontSize = MaterialTheme.typography.bodySmall.fontSize * labelFontScale
    )
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = MaterialTheme.typography.labelSmall.fontSize * labelFontScale
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .combinedClickable(
                onClick = when {
                    file.isDirectory -> onFolderClick
                    file.isVideo -> onVideoClick
                    else -> onImageClick
                },
                onLongClick = if (file.isDirectory) onFolderLongClick else null
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (file.isDirectory) {
                // 文件夹：有预览图则缓存并显示，否则显示图标
                if (file.previewPath != null) {
                    NetworkFolderPreview(
                        serverAddress = serverAddress,
                        shareName = shareName,
                        previewPath = file.previewPath!!,
                        folderName = file.name,
                        childFileCount = file.childFileCount,
                        childDirectoryCount = file.childDirectoryCount,
                        cachedChildFileCount = if (cacheOnly) null else file.cachedChildFileCount,
                        cachedPreviewPath = file.localCachePath,
                        cacheOnly = cacheOnly,
                        labelFontScale = labelFontScale,
                        labelMaxLines = labelMaxLines,
                        showFolderCounts = showFolderCounts
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        if (showFolderCounts) {
                            FolderContentCount(
                                fileCount = file.childFileCount,
                                directoryCount = file.childDirectoryCount,
                                cachedFileCount = if (cacheOnly) null else file.cachedChildFileCount
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = file.name,
                                maxLines = labelMaxLines,
                                overflow = TextOverflow.Ellipsis,
                                style = nameStyle
                            )
                        }
                    }
                }
            } else if (file.isVideo) {
                // 网络视频：不下载缩略图，显示视频图标 + 播放图标 + 文件名
                Icon(
                    Icons.Default.Movie,
                    contentDescription = file.name,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Surface(
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = CircleShape
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "播放",
                        modifier = Modifier.padding(6.dp).size(28.dp),
                        tint = Color.White
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(4.dp)
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = file.name,
                            style = labelStyle,
                            maxLines = labelMaxLines,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            } else {
                // 图片文件：有缓存则显示缩略图，否则显示占位图标
                if (cachePath != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(File(cachePath))
                            .size(256)
                            .crossfade(true)
                            .build(),
                        contentDescription = file.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = file.name,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(4.dp)
                ) {
                    Surface(
                        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = file.name,
                            style = labelStyle,
                            maxLines = labelMaxLines,
                            overflow = TextOverflow.Ellipsis,
                            color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 网络文件夹预览图 — 缓存 SMB 图片到本地后显示
 */
@Composable
private fun NetworkFolderPreview(
    serverAddress: String,
    shareName: String,
    previewPath: String,
    folderName: String,
    childFileCount: Int?,
    childDirectoryCount: Int?,
    cachedChildFileCount: Int?,
    cachedPreviewPath: String?,
    cacheOnly: Boolean,
    labelFontScale: Float,
    labelMaxLines: Int,
    showFolderCounts: Boolean
) {
    val context = LocalContext.current
    var cachedPath by remember(cachedPreviewPath) { mutableStateOf(cachedPreviewPath) }

    LaunchedEffect(serverAddress, shareName, previewPath) {
        if (cachedPath == null && !cacheOnly) {
            cachedPath = withContext(Dispatchers.IO) {
                SmbImageLoader.cacheSmbFile(context, serverAddress, shareName, previewPath)
            }
        }
    }

    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = MaterialTheme.typography.labelSmall.fontSize * labelFontScale
    )

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (cachedPath != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(File(cachedPath!!))
                    .size(256)
                    .crossfade(true)
                    .build(),
                contentDescription = folderName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        if (showFolderCounts) {
            FolderContentCount(
                fileCount = childFileCount,
                directoryCount = childDirectoryCount,
                cachedFileCount = if (cacheOnly) null else cachedChildFileCount,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(4.dp)
            )
        }
        // 文件夹名标签
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(4.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = folderName,
                        maxLines = labelMaxLines,
                        overflow = TextOverflow.Ellipsis,
                        style = labelStyle,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

private fun formatCacheSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
}

