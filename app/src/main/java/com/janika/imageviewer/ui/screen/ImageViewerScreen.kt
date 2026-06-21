package com.janika.imageviewer.ui.screen

import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.ImageDecoder
import android.os.Build
import android.widget.ImageView
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.janika.imageviewer.data.model.ImageItem
import com.janika.imageviewer.util.SmbImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    imageList: List<ImageItem>,
    initialIndex: Int = 0,
    swipeRightToLeft: Boolean = false,
    onBack: () -> Unit
) {
    if (imageList.isEmpty()) {
        onBack()
        return
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, imageList.lastIndex),
        pageCount = { imageList.size }
    )
    val currentPage = pagerState.currentPage
    val currentItem = imageList.getOrNull(currentPage) ?: return

    // 每页独立缩放比例（父级管理，供工具栏按钮修改）
    val pageScales = remember { mutableStateMapOf<Int, Float>() }
    val currentScale = pageScales[currentPage] ?: 1f

    // 控件自动隐藏
    var showControls by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(currentPage) {
        showControls = true
        delay(3000)
        showControls = false
    }

    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 图片翻页（放大时禁止滑动，让 transformable 处理手势）
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                userScrollEnabled = currentScale <= 1.05f
            ) { page ->
                val item = imageList[page]
                ImagePage(
                    item = item,
                    isCurrentPage = page == currentPage,
                    parentScale = pageScales[page] ?: 1f,
                    onScaleChange = { newScale -> pageScales[page] = newScale },
                    onToggleControls = { showControls = !showControls },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 顶部工具栏
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                TopAppBar(
                    title = {
                        Text(text = currentItem.name, maxLines = 1)
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            pageScales[currentPage] = (currentScale * 1.5f).coerceAtMost(5f)
                        }) {
                            Icon(Icons.Default.ZoomIn, contentDescription = "放大")
                        }
                        IconButton(onClick = {
                            pageScales[currentPage] = (currentScale / 1.5f).coerceAtLeast(0.5f)
                        }) {
                            Icon(Icons.Default.ZoomOut, contentDescription = "缩小")
                        }
                        TextButton(onClick = {
                            pageScales[currentPage] = 1f
                        }) {
                            Text("1:1")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f)
                    )
                )
            }

            // 底部页码与滚动条
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
            ) {
                BottomPageBar(
                    currentPage = currentPage,
                    totalPages = imageList.size,
                    swipeRightToLeft = swipeRightToLeft,
                    onPageChange = { page ->
                        scope.launch { pagerState.animateScrollToPage(page) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

/**
 * 单张图片页面 — 处理加载、缩放、平移、点击切换控件
 */
@Composable
private fun ImagePage(
    item: ImageItem,
    isCurrentPage: Boolean,
    parentScale: Float,
    onScaleChange: (Float) -> Unit,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(parentScale) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val context = LocalContext.current

    // 网络文件：下载到本地缓存
    var localPath by remember { mutableStateOf(if (item.isNetworkFile) null else item.path) }
    var isLoading by remember { mutableStateOf(item.isNetworkFile) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 同步父级传来的 scale（工具栏按钮触发）
    LaunchedEffect(parentScale) {
        if (kotlin.math.abs(parentScale - scale) > 0.01f) {
            scale = parentScale
            offset = Offset.Zero
        }
    }

    // scale 变化时通知父级（手势触发）
    LaunchedEffect(scale) { onScaleChange(scale) }

    // 翻页时重置缩放
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    LaunchedEffect(item.smbUrl, item.smbUsername, item.smbPassword, item.smbServerAddress, item.smbShareName) {
        if (item.isNetworkFile && item.smbUrl != null) {
            isLoading = true
            errorMessage = null
            val cached = withContext(Dispatchers.IO) {
                SmbImageLoader.cacheSmbFile(
                    context = context,
                    smbUrl = item.smbUrl,
                    username = item.smbUsername,
                    password = item.smbPassword,
                    serverAddress = item.smbServerAddress,
                    shareName = item.smbShareName
                )
            }
            if (cached != null) {
                localPath = cached
                isLoading = false
            } else {
                errorMessage = "无法加载网络图片"
                isLoading = false
            }
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("正在加载...")
                }
            }
            errorMessage != null -> {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            localPath != null -> {
                val isZoomed = scale > 1.05f

                // 放大模式：transformable 处理平移+捏合缩放
                val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
                    scale = (scale * zoomChange).coerceIn(0.5f, 5f)
                    offset = Offset(
                        x = offset.x + panChange.x,
                        y = offset.y + panChange.y
                    )
                }

                // 手势修饰符：根据缩放状态切换
                val gestureModifier = if (isZoomed) {
                    // 放大：transformable 处理所有手势
                    Modifier.transformable(state = transformableState)
                } else {
                    // 未放大：仅检测点击/双击，滑动穿透给 HorizontalPager
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onToggleControls() },
                            onDoubleTap = {
                                scale = 2.5f
                                offset = Offset.Zero
                            }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(gestureModifier)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val filePath = localPath!!
                    val isAnimated = filePath.lowercase().let {
                        it.endsWith(".webp") || it.endsWith(".gif")
                    }

                    if (isAnimated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        WebpImageViewer(filePath = filePath)
                    } else {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(File(filePath))
                                .crossfade(true)
                                .build(),
                            contentDescription = "图片浏览",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }
    }
}

/**
 * 底部页码栏：页码指示 + 拖动条快速跳转
 */
@Composable
private fun BottomPageBar(
    currentPage: Int,
    totalPages: Int,
    swipeRightToLeft: Boolean = false,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // RTL 模式：页码按原始顺序显示，滑块右侧=第一张
    val displayPage = if (swipeRightToLeft) totalPages - currentPage else currentPage + 1
    val sliderValue = currentPage.toFloat()
    val onSliderChange: (Float) -> Unit = { value ->
        onPageChange(value.toInt().coerceIn(0, totalPages - 1))
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$displayPage / $totalPages",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(56.dp)
            )

            Slider(
                value = sliderValue,
                onValueChange = onSliderChange,
                valueRange = 0f..(totalPages - 1).coerceAtLeast(0).toFloat(),
                steps = (totalPages - 2).coerceAtLeast(0),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 动画 WebP 图片查看器
 */
@Composable
private fun WebpImageViewer(
    filePath: String,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        },
        update = { imageView ->
            try {
                val file = File(filePath)
                if (!file.exists()) return@AndroidView

                val source = ImageDecoder.createSource(file)
                val drawable = ImageDecoder.decodeDrawable(source)

                imageView.setImageDrawable(drawable)

                when (drawable) {
                    is AnimatedImageDrawable -> drawable.start()
                    is Animatable -> drawable.start()
                }
            } catch (e: Exception) {
                android.util.Log.e("WebpImageViewer", "解码WebP失败: $filePath", e)
            }
        },
        modifier = modifier.fillMaxSize()
    )
}
