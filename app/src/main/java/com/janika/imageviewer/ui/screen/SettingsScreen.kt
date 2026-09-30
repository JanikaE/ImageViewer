package com.janika.imageviewer.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.janika.imageviewer.data.local.PreferencesManager
import com.janika.imageviewer.data.repository.SmbSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToCache: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context) }
    // ── 翻页设置 ──
    var swipeRightToLeft by remember { mutableStateOf(prefs.loadSwipeDirection()) }
    var showFolderCounts by remember { mutableStateOf(prefs.loadShowFolderCounts()) }

    // ── SMB 网络共享设置 ──
    var serverConfigs by remember { mutableStateOf(prefs.loadServerConfigs()) }
    var editingServer by remember { mutableStateOf<PreferencesManager.SmbServerConfig?>(null) }

    // ── 视频播放设置 ──
    var videoPlayMode by remember { mutableStateOf(prefs.loadVideoPlayMode()) }
    var keepScreenOn by remember { mutableStateOf(prefs.loadKeepScreenOn()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── 翻页 ──
            Text(
                text = "翻页",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("滑动方向", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (swipeRightToLeft) "从右往左划为下一页" else "从左往右划为下一页",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = swipeRightToLeft,
                        onCheckedChange = { checked ->
                            swipeRightToLeft = checked
                            prefs.saveSwipeDirection(checked)
                        }
                    )
                }
            }

            // ── 显示 ──
            Text(
                text = "显示",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            var labelFontScale by remember { mutableFloatStateOf(prefs.loadLabelFontScale()) }
            var labelMaxLines by remember { mutableIntStateOf(prefs.loadLabelMaxLines()) }

            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("显示文件夹内容数量", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "显示直属文件、文件夹与已缓存文件数量",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = showFolderCounts,
                        onCheckedChange = { checked ->
                            showFolderCounts = checked
                            prefs.saveShowFolderCounts(checked)
                        }
                    )
                }
            }

            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("文件名标签字号", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("${"%.1f".format(labelFontScale)}x", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(36.dp))
                        Slider(
                            value = labelFontScale,
                            onValueChange = {
                                labelFontScale = it
                                prefs.saveLabelFontScale(it)
                            },
                            valueRange = 1f..2.5f,
                            steps = 5,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text("预览", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = (MaterialTheme.typography.labelSmall.fontSize * labelFontScale))
                }
            }

            Spacer(Modifier.height(8.dp))

            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("文件名最多显示行数", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("$labelMaxLines 行", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(36.dp))
                        Slider(
                            value = labelMaxLines.toFloat(),
                            onValueChange = {
                                labelMaxLines = it.toInt()
                                prefs.saveLabelMaxLines(it.toInt())
                            },
                            valueRange = 1f..4f,
                            steps = 2,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // ── 下载 ──
            Text(
                text = "下载",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            var segmentConcurrency by remember { mutableIntStateOf(prefs.loadSegmentConcurrency()) }

            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("并发分段读取", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("$segmentConcurrency", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(24.dp))
                        Slider(
                            value = segmentConcurrency.toFloat(),
                            onValueChange = {
                                segmentConcurrency = it.toInt()
                                prefs.saveSegmentConcurrency(it.toInt())
                            },
                            valueRange = 1f..16f,
                            steps = 14,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = "大图下载时并行读取的段数，数值越高速度越快、占带宽越多",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 视频 ──
            Text(
                text = "视频",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("播放方式", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                videoPlayMode = PreferencesManager.VIDEO_MODE_STREAM
                                prefs.saveVideoPlayMode(PreferencesManager.VIDEO_MODE_STREAM)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = videoPlayMode == PreferencesManager.VIDEO_MODE_STREAM,
                            onClick = {
                                videoPlayMode = PreferencesManager.VIDEO_MODE_STREAM
                                prefs.saveVideoPlayMode(PreferencesManager.VIDEO_MODE_STREAM)
                            }
                        )
                        Text("流式播放（边下边看，启动快）", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                videoPlayMode = PreferencesManager.VIDEO_MODE_CACHE
                                prefs.saveVideoPlayMode(PreferencesManager.VIDEO_MODE_CACHE)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = videoPlayMode == PreferencesManager.VIDEO_MODE_CACHE,
                            onClick = {
                                videoPlayMode = PreferencesManager.VIDEO_MODE_CACHE
                                prefs.saveVideoPlayMode(PreferencesManager.VIDEO_MODE_CACHE)
                            }
                        )
                        Text("先缓存后播放（占用存储空间）", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        text = "仅对局域网网络视频生效，本地视频始终直接播放",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("播放时保持屏幕常亮", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "防止看视频时屏幕自动熄灭",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = keepScreenOn,
                            onCheckedChange = { checked ->
                                keepScreenOn = checked
                                prefs.saveKeepScreenOn(checked)
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 网络共享 ──
            Text(
                text = "网络共享",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            serverConfigs.forEach { config ->
                Card {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Dns, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(config.serverAddress, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${config.shareNames.size} 个共享",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = config.enabled,
                                onCheckedChange = { enabled ->
                                    prefs.setServerEnabled(config.id, enabled)
                                    if (!enabled) SmbSessionManager.disconnect(config.serverAddress)
                                    serverConfigs = prefs.loadServerConfigs()
                                }
                            )
                        }
                        if (config.shareNames.isNotEmpty()) {
                            Text(
                                config.shareNames.joinToString("、"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { editingServer = config }) {
                                Icon(Icons.Default.Edit, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("编辑")
                            }
                            TextButton(onClick = {
                                SmbSessionManager.disconnect(config.serverAddress)
                                prefs.removeServerConfig(config.id)
                                serverConfigs = prefs.loadServerConfigs()
                            }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    editingServer = PreferencesManager.SmbServerConfig(
                        id = UUID.randomUUID().toString(),
                        serverAddress = "",
                        username = "",
                        password = ""
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("添加服务器")
            }
            if (serverConfigs.isEmpty()) {
                Text(
                    "还没有服务器配置，添加后可在网络页浏览已启用的共享。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── 缓存管理 ──
            Text(
                text = "缓存",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Card(
                onClick = onNavigateToCache
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("缓存管理", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "查看和清除本地图片缓存",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    editingServer?.let { original ->
        SmbServerEditorDialog(
            original = original,
            existingConfigs = serverConfigs,
            onDismiss = { editingServer = null },
            onSave = { updated ->
                SmbSessionManager.disconnect(original.serverAddress)
                prefs.upsertServerConfig(updated)
                serverConfigs = prefs.loadServerConfigs()
                editingServer = null
            }
        )
    }
}

@Composable
private fun SmbServerEditorDialog(
    original: PreferencesManager.SmbServerConfig,
    existingConfigs: List<PreferencesManager.SmbServerConfig>,
    onDismiss: () -> Unit,
    onSave: (PreferencesManager.SmbServerConfig) -> Unit
) {
    val scope = rememberCoroutineScope()
    var serverAddress by remember(original.id) { mutableStateOf(original.serverAddress) }
    var username by remember(original.id) { mutableStateOf(original.username) }
    var password by remember(original.id) { mutableStateOf(original.password) }
    var enabled by remember(original.id) { mutableStateOf(original.enabled) }
    var shareNames by remember(original.id) { mutableStateOf(original.shareNames) }
    var newShareName by remember(original.id) { mutableStateOf("") }
    var isTesting by remember(original.id) { mutableStateOf(false) }
    var resultMessage by remember(original.id) { mutableStateOf<String?>(null) }

    fun buildConfig() = original.copy(
        serverAddress = serverAddress.trim(),
        username = username,
        password = password,
        enabled = enabled,
        shareNames = shareNames
    )

    val duplicateAddress = existingConfigs.any {
        it.id != original.id && it.serverAddress.equals(serverAddress.trim(), ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = { if (!isTesting) onDismiss() },
        title = { Text(if (original.serverAddress.isBlank()) "添加服务器" else "编辑服务器") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = serverAddress,
                    onValueChange = { serverAddress = it; resultMessage = null },
                    label = { Text("服务器地址") },
                    placeholder = { Text("例如: 192.168.1.100") },
                    singleLine = true,
                    enabled = !isTesting,
                    isError = duplicateAddress,
                    modifier = Modifier.fillMaxWidth()
                )
                if (duplicateAddress) {
                    Text("此服务器地址已经存在", color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; resultMessage = null },
                    label = { Text("用户名（可选）") },
                    singleLine = true,
                    enabled = !isTesting,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; resultMessage = null },
                    label = { Text("密码（可选）") },
                    singleLine = true,
                    enabled = !isTesting,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用此服务器", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Text("共享名列表", style = MaterialTheme.typography.titleSmall)
                shareNames.forEach { share ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(share, modifier = Modifier.weight(1f))
                        IconButton(onClick = { shareNames = shareNames.filterNot { it == share } }) {
                            Icon(Icons.Default.Close, contentDescription = "删除共享")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newShareName,
                        onValueChange = { newShareName = it },
                        label = { Text("共享名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = {
                            val name = newShareName.trim()
                            if (name.isNotEmpty() && shareNames.none { it.equals(name, ignoreCase = true) }) {
                                shareNames = shareNames + name
                            }
                            newShareName = ""
                        },
                        enabled = newShareName.isNotBlank()
                    ) { Text("添加") }
                }
                resultMessage?.let {
                    Text(
                        it,
                        color = if (it.startsWith("连接成功")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedButton(
                    onClick = {
                        isTesting = true
                        resultMessage = null
                        scope.launch {
                            val failedShares = withContext(Dispatchers.IO) {
                                if (shareNames.isEmpty()) {
                                    val connected = SmbSessionManager.testConnection(
                                        serverAddress.trim(),
                                        username.ifEmpty { null },
                                        password.ifEmpty { null },
                                        null
                                    )
                                    if (connected) emptyList() else listOf("服务器连接")
                                } else {
                                    shareNames.filterNot { share ->
                                        SmbSessionManager.testConnection(
                                            serverAddress.trim(),
                                            username.ifEmpty { null },
                                            password.ifEmpty { null },
                                            null,
                                            share
                                        )
                                    }
                                }
                            }
                            isTesting = false
                            resultMessage = if (failedShares.isEmpty()) "连接成功"
                            else "无法访问共享：${failedShares.joinToString("、")}"
                        }
                    },
                    enabled = !isTesting && serverAddress.isNotBlank() && !duplicateAddress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("连接测试")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(buildConfig()) },
                enabled = serverAddress.isNotBlank() && !duplicateAddress && !isTesting
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isTesting) { Text("取消") } }
    )
}
