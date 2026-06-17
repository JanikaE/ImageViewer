package com.janika.imageviewer.ui.screen

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context) }
    val scope = rememberCoroutineScope()

    // ── 翻页设置 ──
    var swipeRightToLeft by remember { mutableStateOf(prefs.loadSwipeDirection()) }

    // ── SMB 网络共享设置 ──
    val savedConfig = remember { prefs.loadConfig() }
    var serverAddress by remember { mutableStateOf(savedConfig?.serverAddress ?: "") }
    var username by remember { mutableStateOf(savedConfig?.username ?: "") }
    var password by remember { mutableStateOf(savedConfig?.password ?: "") }
    var isConnecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var connectSuccess by remember { mutableStateOf(false) }

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
                                        try {
                                            val baseCtx = SingletonContext.getInstance()
                                            val auth = if (username.isNotEmpty()) {
                                                NtlmPasswordAuthenticator("", username, password)
                                            } else {
                                                NtlmPasswordAuthenticator(null, "guest", "")
                                            }
                                            val ctx = baseCtx.withCredentials(auth)
                                            val test = SmbFile("smb://$serverAddress/", ctx)
                                            test.list().isNotEmpty()
                                        } catch (e: Exception) {
                                            false
                                        }
                                    }
                                    isConnecting = false
                                    if (ok) {
                                        connectSuccess = true
                                        prefs.saveConfig(
                                            PreferencesManager.SmbConnectionConfig(
                                                serverAddress = serverAddress,
                                                username = username,
                                                password = password
                                            )
                                        )
                                    } else {
                                        connectError = "无法连接到服务器，请检查地址和凭据"
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
        }
    }
}
