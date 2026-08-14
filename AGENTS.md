# AGENTS.md

Android image viewer (Compose, single `:app` module) that browses local storage and SMB/network shares. Package/namespace: `com.janika.imageviewer`.

## Language convention
UI strings, code comments, and git commits are in Chinese (app name "图片阅读器"). Keep new strings/comments/commits in Chinese for consistency.

## Build
- Windows-only repo. Build with the wrapper: `.\gradlew.bat :app:assembleDebug`. There is no checked-in `gradle` on PATH.
- Root helper scripts run `clean` then assemble and open the output folder: `build_debug.bat`, `build_release.bat`, `build_all.bat`.
- Release is UNSIGNED (`app-release-unsigned.apk`), `isMinifyEnabled = false`, and there is no signing config in `app/build.gradle.kts`. Don't add signing keys to the repo (`*.jks`/`*.keystore` are gitignored).
- Stack: AGP 9.0.1, Gradle 9.2.1, Kotlin 2.0.21, compileSdk 36, minSdk 24, targetSdk 36, Java 11.

## AGP 9 DSL gotchas
- `compileSdk` uses the new AGP 9 block in `app/build.gradle.kts:9`: `compileSdk { version = release(36) { minorApiLevel = 1 } }`. Don't "simplify" it back to `compileSdk = 36`.
- Do NOT add `org.jetbrains.kotlin.android` to app plugins; the `kotlin.plugin.compose` plugin pulls it in (see the comment at `app/build.gradle.kts:3`).

## Architecture
- Entry: `MainActivity.kt`. NavHost routes: `"home"`, `"local"`, `"network"`, `"settings"`, `"cache"`.
- The fullscreen viewer (`ImageViewerScreen`) is NOT a nav route. It is an overlay rendered at the top level of `ImageViewerApp` whenever `rawImageList` state is non-empty, with swipe order driven by the `swipe_right_to_left` preference (`MainActivity.kt:36-107`).
- **图片查看器不用 Dialog 窗口**：它作为主窗口内的覆盖层渲染（`NavHost` 的最后一个子元素，绘制在最上层），返回键用 `BackHandler` 处理。原因：Android 16 上 Compose `Dialog` 窗口不报告系统栏 insets，`navigationBarsPadding` 无效；改用主窗口覆盖层后与文件夹浏览共用同一套 insets 逻辑。
- **系统导航栏独立显示（不沉浸）**：`MainActivity` 不再调用 `enableEdgeToEdge()`。Android 16（API 36）强制边缘到边缘且 `android:windowOptOutEdgeToEdgeEnforcement` 已失效，因此用「内容内缩 + 背景填充」实现：`NavHost`（及图片查看器）外包一层 `Box`，`.background(colorScheme.background)` 铺满导航栏区域、`.navigationBarsPadding()` 让内容止步于导航栏上方。别把这两者改成直接 `fillMaxSize()` 裸布局，否则内容会再次延伸到导航栏后面。
- 系统栏颜色在 `Theme.kt` 的 `ImageViewerTheme` 里用 `SideEffect` + `WindowCompat` 设置（`statusBarColor`/`navigationBarColor` = `colorScheme.background`，随浅色/深色主题切换，`isAppearanceLight{Status,Navigation}Bars` 同步）。
- Local browser reads the filesystem directly with `java.io.File` on `/storage/emulated/0` (`LocalFileRepository`); folder previews are the first supported image found inside.
- Manifest sets `usesCleartextTraffic="true"` (required for SMB) and declares storage/network permissions — the manifest is the source of truth for API-level permission splits (READ_MEDIA_IMAGES 33+, READ_EXTERNAL_STORAGE ≤32).

## SMB layer (SMBJ — hard-won quirks, don't "fix")
- Uses **SMBJ 0.14.0** (`com.hierynomus:smbj`) + `slf4j-nop` (Android 无默认绑定). `SmbSessionManager` (singleton) owns `SMBClient`→`Connection`→`SMBSession`; auth happens once in `connect()` via `AuthenticationContext`, and `getDiskShare(shareName)` lazily `connectShare`s + caches the `DiskShare`. `SmbRepository` (browse/list) and `SmbImageLoader` (download) both reuse it — no per-file auth fallback chain.
- **共享名不自动枚举**：SMBJ 没有枚举服务器共享的 API。用户在设置里手动维护共享名列表（`PreferencesManager.SmbConnectionConfig.shareNames`，JSON 序列化存 SharedPreferences）；网络页直接显示这些共享名。
- **SMBJ 读取语义**：`DiskShare.openFile(path, GENERIC_READ, ...)` 返回 `File`，用 `file.read(buffer, offset)` 按偏移顺序读（-1 为 EOF）；SMBJ 内部把单次读限制为 `min(config, server.maxReadSize)`，**大缓冲不会触发 INVALID_PARAMETER**（这是选 SMBJ 换掉 jcifs-ng 的原因之一）。
- 注意：`File` **没有** `getLength()`（0.14.0），取文件大小用 `file.getFileInformation(FileStandardInformation::class.java).endOfFile`。
- 特殊字符路径：SMBJ 的 `FileIdBothDirectoryInformation.fileName` 返回服务器真实 Unicode 名，`jcifs-ng` 时代的 `cleanFileName()` 前缀剥离 workaround 已删除。
- SMB 图片下载到 `context.cacheDir/smb_cache/{server_share}/{pathHash}_{filename}`，先写 `.tmp` 再原子重命名，失败删除半成品；缓存管理 UI 读取该布局。
- 不要再看 jcifs-ng（`eu.agno3.jcifs`）；`transaction_buf_size` 调大导致 `STATUS_INVALID_PARAMETER` 的历史问题已随库更换消除。
- 大图下载用**并发分段读**：文件 > 512KB 时按设置项 `segment_concurrency`（`PreferencesManager`，默认 5，范围 1..16，设置页"下载"区块）切成 N 段并发 `File.read`，用 `FileChannel` 按偏移写本地 `.tmp`；小图走顺序读。分段下载完成会 `Log.i` 打印实测 MB/s。

## Testing
- Only Gradle-template boilerplate exists (`ExampleUnitTest`, `ExampleInstrumentedTest`). No meaningful test suite or custom lint/typecheck config. Any SMB verification requires a real LAN share; the rest can be reasoned about statically.
