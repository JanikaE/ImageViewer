package com.janika.imageviewer.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.janika.imageviewer.data.local.PreferencesManager
import com.janika.imageviewer.data.model.ImageItem
import com.janika.imageviewer.util.SmbImageLoader
import com.janika.imageviewer.util.SmbVideoDataSource
import com.janika.imageviewer.util.VideoSpeedMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** 倍速菜单可选档位 */
private val SPEED_OPTIONS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * 视频播放器覆盖层 - 主窗口内渲染（不用 Dialog，遵循系统栏 insets 约定）。
 *
 * - 本地/网络视频统一入口；网络视频默认流式播放（SMBJ 随机读 DataSource），
 *   设置里可选"先缓存后播放"。
 * - 方向：进入锁定竖屏，仅全屏按钮切换横竖屏，全程不响应重力感应。
 * - 控件复用 PlayerView 自带控件；缓冲时显示实时网络下载速度。
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    videoItem: ImageItem,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val prefs = remember { PreferencesManager(context) }
    val keepScreenOn = prefs.loadKeepScreenOn()
    val playMode = prefs.loadVideoPlayMode()

    val player = remember { ExoPlayer.Builder(context).build() }

    // ── 播放器状态 ──
    var isPreparing by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isBuffering by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableLongStateOf(0L) }
    var downloadTotal by remember { mutableLongStateOf(0L) }
    var chromeVisible by remember { mutableStateOf(true) }
    var isFullscreen by remember { mutableStateOf(false) }
    var retryKey by remember { mutableIntStateOf(0) }

    // 网络下载速度监测（流式 read / 缓存下载进度共同上报）
    val speedMonitor = remember { VideoSpeedMonitor() }
    var bufferingSpeed by remember { mutableLongStateOf(0L) }

    // ── 屏幕常亮 ──
    DisposableEffect(keepScreenOn) {
        val window = activity?.window
        if (keepScreenOn && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ── 进入锁定竖屏，退出恢复原方向 ──
    DisposableEffect(Unit) {
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            if (previousOrientation != null) {
                activity?.requestedOrientation = previousOrientation
            }
        }
    }

    // ── 释放播放器 ──
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    // ── 播放器监听：缓冲/错误 ──
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("VideoPlayerScreen", "播放错误: ${error.errorCodeName}", error)
                isPreparing = false
                isBuffering = false
                errorMessage = "播放失败，请检查网络连接后重试"
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // ── 构建媒体源并播放（缓存模式会先下载） ──
    LaunchedEffect(videoItem.path, videoItem.smbServerAddress, videoItem.smbShareName, retryKey) {
        isPreparing = true
        errorMessage = null
        downloadProgress = 0L
        downloadTotal = 0L
        speedMonitor.reset()
        var lastReported = 0L
        val source = try {
            buildMediaSource(
                context = context,
                videoItem = videoItem,
                playMode = playMode,
                onProgress = { done, total ->
                    // 缓存模式下载进度：同时上报速度监测
                    speedMonitor.onBytesRead(done - lastReported)
                    lastReported = done
                    downloadProgress = done
                    downloadTotal = total
                },
                onBytesRead = { n -> speedMonitor.onBytesRead(n) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("VideoPlayerScreen", "构建媒体源失败", e)
            null
        }
        if (source == null) {
            isPreparing = false
            errorMessage = "无法获取视频文件"
            return@LaunchedEffect
        }
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
        isPreparing = false
    }

    // ── 缓冲时每秒刷新一次下载速度 ──
    LaunchedEffect(isBuffering) {
        while (isBuffering) {
            bufferingSpeed = speedMonitor.getSpeedBps()
            delay(1000)
        }
        bufferingSpeed = 0L
    }

    // ── 全屏切换：仅按钮驱动，禁用重力旋屏 ──
    fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        activity?.requestedOrientation = if (isFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val window = activity?.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (isFullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ── 返回键：全屏时先退出全屏 ──
    BackHandler {
        if (isFullscreen) toggleFullscreen() else onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 播放视图：PlayerView 自带控件
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    setFullscreenButtonClickListener { toggleFullscreen() }
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            chromeVisible = visibility == View.VISIBLE
                        }
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 顶部控制栏（与播放控件同步显隐）
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                    Text(
                        text = videoItem.name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // 倍速菜单（PlayerView 无内置按钮）
                    Box {
                        var speedMenuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { speedMenuOpen = true }) {
                            Icon(Icons.Default.Speed, contentDescription = "倍速", tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = speedMenuOpen,
                            onDismissRequest = { speedMenuOpen = false }
                        ) {
                            SPEED_OPTIONS.forEach { speed ->
                                DropdownMenuItem(
                                    text = { Text("${speed}x") },
                                    onClick = {
                                        player.setPlaybackSpeed(speed)
                                        speedMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 加载中：缓存模式下显示下载进度
        AnimatedVisibility(
            visible = isPreparing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                CircularProgressIndicator(color = Color.White)
                if (downloadTotal > 0) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "正在下载 ${formatVideoSize(downloadProgress)} / ${formatVideoSize(downloadTotal)}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (downloadTotal > 0) downloadProgress.toFloat() / downloadTotal else 0f
                        },
                        modifier = Modifier.fillMaxWidth(0.6f)
                    )
                }
            }
        }

        // 缓冲中（显示实时下载速度）
        if (!isPreparing && isBuffering && errorMessage == null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.2f)
                )
                if (bufferingSpeed > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "缓冲中 ${formatSpeed(bufferingSpeed)}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // 播放错误
        errorMessage?.let { msg ->
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = msg,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { retryKey++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("重试")
                    }
                    OutlinedButton(onClick = onBack) {
                        Text("返回")
                    }
                }
            }
        }
    }
}

/**
 * 按播放模式构建媒体源：
 * - 网络 + 缓存模式：复用 [SmbImageLoader] 整文件下载到缓存后本地播放；
 * - 网络 + 流式模式：SMBJ 随机读 DataSource 边下边播；
 * - 本地文件：直接播放。
 */
@UnstableApi
private suspend fun buildMediaSource(
    context: Context,
    videoItem: ImageItem,
    playMode: String,
    onProgress: (Long, Long) -> Unit,
    onBytesRead: (Long) -> Unit
): MediaSource? {
    val dataSourceFactory: DataSource.Factory
    val uri: Uri

    if (videoItem.localCachePath != null) {
        dataSourceFactory = DefaultDataSource.Factory(context)
        uri = Uri.fromFile(File(videoItem.localCachePath))
    } else if (videoItem.isNetworkFile && videoItem.smbServerAddress != null && videoItem.smbShareName != null) {
        val server = videoItem.smbServerAddress
        val share = videoItem.smbShareName
        val path = videoItem.path
        if (playMode == PreferencesManager.VIDEO_MODE_CACHE) {
            // 先检查是否已缓存，未缓存则下载（带进度）
            val local = withContext(Dispatchers.IO) {
                SmbImageLoader.getCachePath(context, server, share, path)
                    ?: SmbImageLoader.cacheSmbFile(context, server, share, path, onProgress = onProgress)
            }
            if (local == null) return null
            dataSourceFactory = DefaultDataSource.Factory(context)
            uri = Uri.fromFile(File(local))
        } else {
            // 流式播放：SMBJ 随机读 DataSource
            dataSourceFactory = SmbVideoDataSourceFactory(share, path, onBytesRead)
            uri = Uri.parse("smb://$server/$share/$path")
        }
    } else {
        dataSourceFactory = DefaultDataSource.Factory(context)
        uri = Uri.fromFile(File(videoItem.path))
    }

    return ProgressiveMediaSource.Factory(dataSourceFactory)
        .createMediaSource(MediaItem.fromUri(uri))
}

/** SMB 流式数据源工厂 */
@UnstableApi
private class SmbVideoDataSourceFactory(
    private val shareName: String,
    private val filePath: String,
    private val onBytesRead: (Long) -> Unit
) : DataSource.Factory {
    override fun createDataSource(): DataSource = SmbVideoDataSource(shareName, filePath, onBytesRead)
}

private fun formatSpeed(bps: Long): String = when {
    bps <= 0 -> "0 B/s"
    bps < 1024 -> "$bps B/s"
    bps < 1024 * 1024 -> "${bps / 1024} KB/s"
    else -> "${"%.1f".format(bps / (1024.0 * 1024.0))} MB/s"
}

private fun formatVideoSize(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
