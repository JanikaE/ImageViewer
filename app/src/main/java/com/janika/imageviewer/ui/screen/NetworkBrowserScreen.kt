package com.janika.imageviewer.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.janika.imageviewer.data.model.ImageFile
import com.janika.imageviewer.data.local.PreferencesManager
import com.janika.imageviewer.ui.viewmodel.NetworkBrowserViewModel
import com.janika.imageviewer.util.SmbImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkBrowserScreen(
    onImageClick: (List<ImageFile>, Int, String, String, String?, String?) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateBack: () -> Unit = {},
    viewModel: NetworkBrowserViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context) }
    val labelFontScale = prefs.loadLabelFontScale()
    val labelMaxLines = prefs.loadLabelMaxLines()

    // 拦截系统返回键
    BackHandler(enabled = state.isConnected) {
        if (state.shareName.isNotEmpty()) {
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
                if (state.shareName.isNotEmpty() || state.isConnected) {
                    IconButton(onClick = {
                        if (state.shareName.isNotEmpty()) {
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
                if (state.isConnected) {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            }
        )

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
                // 显示共享文件夹列表
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(state.shares, key = { it }) { share ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clickable { viewModel.openShare(share) },
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
            }
            state.files.isNotEmpty() -> {
                // 显示文件列表
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(state.files, key = { it.path }) { file ->
                        val cachePath = if (!file.isDirectory)
                            SmbImageLoader.getCachePath(context, state.serverAddress, state.shareName, file.path)
                        else null
                        NetworkFileGridItem(
                            file = file,
                            cachePath = cachePath,
                            labelFontScale = labelFontScale,
                            labelMaxLines = labelMaxLines,
                            onFolderClick = {
                                viewModel.navigateToFolder(file.path, file.name)
                            },
                            onImageClick = {
                                // 过滤出所有图片文件并传递索引
                                val imageFiles = state.files.filter { !it.isDirectory }
                                val idx = imageFiles.indexOf(file)
                                onImageClick(
                                    imageFiles,
                                    idx.coerceAtLeast(0),
                                    state.serverAddress,
                                    state.shareName,
                                    state.configUsername.ifEmpty { null },
                                    state.configPassword.ifEmpty { null }
                                )
                            }
                        )
                    }
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
}

@Composable
private fun NetworkFileGridItem(
    file: ImageFile,
    cachePath: String?,
    labelFontScale: Float,
    labelMaxLines: Int,
    onFolderClick: () -> Unit,
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
            .clickable(
                onClick = if (file.isDirectory) onFolderClick else onImageClick
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
                        smbPreviewUrl = file.previewPath!!,
                        folderName = file.name,
                        labelFontScale = labelFontScale,
                        labelMaxLines = labelMaxLines
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(8.dp)
                    ) {
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
    smbPreviewUrl: String,
    folderName: String,
    labelFontScale: Float,
    labelMaxLines: Int
) {
    val context = LocalContext.current
    var cachedPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(smbPreviewUrl) {
        cachedPath = withContext(Dispatchers.IO) {
            SmbImageLoader.cacheSmbFile(context, smbPreviewUrl)
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

