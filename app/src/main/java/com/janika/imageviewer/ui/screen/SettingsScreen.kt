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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToCache: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context) }
    val scope = rememberCoroutineScope()

    // ── 翻页设置 ──
    var swipeRightToLeft by remember { mutableStateOf(prefs.loadSwipeDirection()) }
    var showFolderCounts by remember { mutableStateOf(prefs.loadShowFolderCounts()) }

    // ── SMB 网络共享设置 ──
    val savedConfig = remember { prefs.loadConfig() }
    var serverAddress by remember { mutableStateOf(savedConfig?.serverAddress ?: "") }
    var username by remember { mutableStateOf(savedConfig?.username ?: "") }
    var password by remember { mutableStateOf(savedConfig?.password ?: "") }
    var shareNames by remember { mutableStateOf(savedConfig?.shareNames ?: emptyList()) }
    var newShareName by remember { mutableStateOf("") }
    var isConnecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var connectSuccess by remember { mutableStateOf(false) }

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

            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = serverAddress,
                        onValueChange = { serverAddress = it; connectSuccess = false; connectError = null },
                        label = { Text("服务器地址") },
                        placeholder = { Text("例如: 192.168.1.100") },
                        leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isConnecting
                    )

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it; connectSuccess = false; connectError = null },
                        label = { Text("用户名（可选）") },
                        placeholder = { Text("匿名登录留空") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isConnecting
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; connectSuccess = false; connectError = null },
                        label = { Text("密码（可选）") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isConnecting
                    )

                    // ── 共享名列表（手动配置） ──
                    Text(
                        text = "共享名列表",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    shareNames.forEach { share ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = share,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                shareNames = shareNames.filter { it != share }
                                prefs.saveShareNames(shareNames)
                                connectSuccess = false
                            }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = newShareName,
                            onValueChange = { newShareName = it; connectSuccess = false },
                            label = { Text("共享名") },
                            placeholder = { Text("例如: doujinshi") },
                            leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            enabled = !isConnecting
                        )
                        OutlinedButton(
                            onClick = {
                                val name = newShareName.trim()
                                if (name.isNotEmpty() && !shareNames.contains(name)) {
                                    shareNames = shareNames + name
                                    prefs.saveShareNames(shareNames)
                                    newShareName = ""
                                    connectSuccess = false
                                }
                            },
                            enabled = !isConnecting && newShareName.isNotBlank()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Text("添加")
                        }
                    }
                    if (shareNames.isEmpty()) {
                        Text(
                            text = "还没有配置共享名，添加后可在网络页直接打开",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // 连接状态
                    if (connectSuccess) {
                        Text(
                            text = "✓ 连接成功",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    connectError?.let { err ->
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (serverAddress.isBlank()) {
                                    connectError = "请输入服务器地址"
                                    return@Button
                                }
                                isConnecting = true
                                connectError = null
                                connectSuccess = false
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        SmbSessionManager.testConnection(
                                            serverAddress = serverAddress,
                                            username = username.ifEmpty { null },
                                            password = password.ifEmpty { null },
                                            domain = null,
                                            shareName = shareNames.firstOrNull()
                                        )
                                    }
                                    isConnecting = false
                                    if (ok) {
                                        connectSuccess = true
                                        prefs.saveConfig(
                                            PreferencesManager.SmbConnectionConfig(
                                                serverAddress = serverAddress,
                                                username = username,
                                                password = password,
                                                shareNames = shareNames
                                            )
                                        )
                                    } else {
                                        connectError = "无法连接到服务器，请检查地址、凭据或共享名"
                                    }
                                }
                            },
                            enabled = !isConnecting && serverAddress.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("连接测试")
                        }

                        if (savedConfig != null) {
                            OutlinedButton(
                                onClick = {
                                    prefs.clearConfig()
                                    serverAddress = ""
                                    username = ""
                                    password = ""
                                    shareNames = emptyList()
                                    newShareName = ""
                                    connectSuccess = false
                                    connectError = null
                                }
                            ) {
                                Text("清除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
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
}
