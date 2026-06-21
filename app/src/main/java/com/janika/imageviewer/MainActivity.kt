package com.janika.imageviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.janika.imageviewer.data.local.PreferencesManager
import com.janika.imageviewer.data.model.ImageItem
import com.janika.imageviewer.ui.screen.*
import com.janika.imageviewer.ui.theme.ImageViewerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ImageViewerTheme {
                ImageViewerApp()
            }
        }
    }
}

@Composable
fun ImageViewerApp() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context) }

    // 图片查看器状态
    var rawImageList by remember { mutableStateOf<List<ImageItem>>(emptyList()) }
    var rawIndex by remember { mutableIntStateOf(0) }

    // 是否已保存网络配置
    val hasNetworkConfig = prefs.loadConfig() != null

    // 根据滑动方向配置调整列表顺序
    val swipeRightToLeft = prefs.loadSwipeDirection()
    val displayList = if (swipeRightToLeft) rawImageList.reversed() else rawImageList
    val displayIndex = if (swipeRightToLeft && rawImageList.isNotEmpty())
        rawImageList.lastIndex - rawIndex else rawIndex

    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(
                onNavigateToLocal = { navController.navigate("local") },
                onNavigateToNetwork = { navController.navigate("network") },
                onNavigateToSettings = { navController.navigate("settings") },
                hasNetworkConfig = prefs.loadConfig() != null
            )
        }

        composable("local") {
            LocalBrowserScreen(
                onImageClick = { files, index ->
                    rawImageList = ImageItem.fromLocalFiles(files)
                    rawIndex = index
                }
            )
        }

        composable("network") {
            NetworkBrowserScreen(
                onImageClick = { files, index, serverAddress, shareName, username, password ->
                    rawImageList = ImageItem.fromNetworkFiles(
                        files, serverAddress, shareName, username, password
                    )
                    rawIndex = index
                },
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToCache = { navController.navigate("cache") }
            )
        }

        composable("cache") {
            CacheManagementScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }

    // 全屏图片查看器
    if (displayList.isNotEmpty()) {
        ImageViewerScreen(
            imageList = displayList,
            initialIndex = displayIndex.coerceIn(0, displayList.lastIndex),
            swipeRightToLeft = swipeRightToLeft,
            onBack = {
                rawImageList = emptyList()
            }
        )
    }
}