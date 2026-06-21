package com.janika.imageviewer.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.janika.imageviewer.util.SmbImageLoader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheManagementScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val cacheFolders = remember { SmbImageLoader.getCacheInfo(context) }
    val totalSize = remember { SmbImageLoader.getCacheTotalSize(context) }
    var clearTarget by remember { mutableStateOf<SmbImageLoader.FolderCacheInfo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("缓存管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 总览
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("共享文件夹数", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${cacheFolders.size}", style = MaterialTheme.typography.titleMedium)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("缓存大小", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatSize(totalSize), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            // 清除所有
            if (cacheFolders.isNotEmpty()) {
                Button(
                    onClick = { clearTarget = SmbImageLoader.FolderCacheInfo("全部","","",0,0, emptyList()) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("清除所有缓存")
                }
                Spacer(Modifier.height(8.dp))
            }

            // 按文件夹分组列表
            if (cacheFolders.isNotEmpty()) {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(cacheFolders, key = { it.folderName }) { folder ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(folder.folderName, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${folder.fileCount} 个文件 · ${formatSize(folder.totalSize)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { clearTarget = folder }) {
                                        Icon(Icons.Default.Delete, contentDescription = "清除此文件夹",
                                            tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无缓存", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    // 清除确认对话框
    clearTarget?.let { target ->
        val isAll = target.folderName == "全部"
        val title = if (isAll) "清除所有缓存" else "清除「${target.folderName}」的缓存"
        val size = if (isAll) totalSize else target.totalSize
        AlertDialog(
            onDismissRequest = { clearTarget = null },
            title = { Text(title) },
            text = { Text("确定要清除吗？\n共 ${formatSize(size)}") },
            confirmButton = {
                TextButton(onClick = {
                    if (isAll) {
                        SmbImageLoader.clearCache(context)
                    } else if (target.serverAddress != null && target.shareName != null) {
                        SmbImageLoader.clearShareCache(context, target.serverAddress!!, target.shareName!!)
                    }
                    clearTarget = null
                    onBack()
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { clearTarget = null }) { Text("取消") }
            }
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
}

