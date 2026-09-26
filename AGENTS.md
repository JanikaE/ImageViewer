# AGENTS.md

这是一个使用 Jetpack Compose 开发的 Android 图片/视频阅读器，只有单个 `:app` 模块，可浏览本地存储与 SMB 局域网共享。包名和命名空间均为 `com.janika.imageviewer`，应用名为“图片阅读器”。

## 语言约定

- 界面文案、代码注释、文档说明和 Git 提交信息统一使用中文。
- 类名、函数名、配置键、命令、依赖坐标和协议名称等代码标识保持原样。

## 文档同步约定

- 每次修改代码、配置或应用行为后，都必须在同一次任务中同步更新 `AGENTS.md`，确保架构、功能约束、设置项与验证方式的说明和实际实现一致。
- 更新已有功能时优先修订对应章节；新增跨模块约定时再增加章节。不能只修改实现而遗留过时的文档说明。

## 构建与工程配置

- 仓库面向 Windows，使用包装器构建：`.\gradlew.bat :app:assembleDebug`。不要假设系统 `PATH` 中存在独立安装的 `gradle`。
- `build_debug.bat` 与 `build_release.bat` 会先执行 `clean`，再构建对应 APK 并打开输出目录；`build_all.bat` 是交互式脚本，不会先执行 `clean`。
- Debug APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`。
- Release 未配置签名，输出为 `app/build/outputs/apk/release/app-release-unsigned.apk`；`isMinifyEnabled = false`。不要向仓库添加签名密钥，`*.jks` 与 `*.keystore` 已被忽略。
- 技术版本：AGP 9.0.1、Gradle 9.2.1、Kotlin 2.0.21、Java 11、`compileSdk` 36.1、`minSdk` 24、`targetSdk` 36。
- 依赖仓库集中定义在 `settings.gradle.kts`，并启用 `RepositoriesMode.FAIL_ON_PROJECT_REPOS`；不要在模块中临时添加仓库。

## AGP 9 DSL 注意事项

- `app/build.gradle.kts` 使用 AGP 9 的新写法：`compileSdk { version = release(36) { minorApiLevel = 1 } }`。不要改回 `compileSdk = 36`，否则会丢失 `minorApiLevel = 1`。
- 不要在 `app` 的插件块中添加 `org.jetbrains.kotlin.android`；`org.jetbrains.kotlin.plugin.compose` 已会引入它，源码中也保留了说明注释。

## 总体架构

- 入口为 `MainActivity.kt`。`NavHost` 路由包括 `"home"`、`"local"`、`"network"`、`"settings"`、`"cache"`。
- 本地与网络浏览分别由 `LocalBrowserViewModel`、`NetworkBrowserViewModel` 管理，并通过 `StateFlow` 向 Compose 暴露状态；文件系统和网络操作放在协程的 IO 上下文中。
- `ImageFile` 同时描述目录、图片和视频，并可携带直属缓存数量与本地缓存绝对路径；图片列表传入查看器前必须使用 `filter { it.isImage }`，不能把视频混入图片分页器。
- `ImageItem` 是覆盖层使用的统一条目：本地路径为绝对路径，网络路径为共享内相对路径，并携带 SMB 服务器、共享名和可选的本地缓存路径。存在 `localCachePath` 时，查看器和播放器必须优先使用本地文件，不能再次发起网络读取。
- 图片查看器与视频播放器都不是导航路由，而是 `ImageViewerApp` 顶层、位于 `NavHost` 之后的覆盖层。图片由 `rawImageList` 驱动，视频由 `rawVideoItem` 驱动，返回键通过 `BackHandler` 处理。
- 覆盖层状态使用普通 `remember`。视频旋转依赖 Manifest 的 `configChanges` 保持 Activity 不重建，不要简单替换为 `rememberSaveable`，否则播放器仍会重建并从头播放。

## 系统栏与窗口约定

- 图片查看器和视频播放器都不要改成 Compose `Dialog`。Android 16 上 `Dialog` 窗口不报告预期的系统栏 inset，`navigationBarsPadding` 会失效；当前实现让覆盖层与浏览页共享主窗口 inset。
- `MainActivity` 不调用 `enableEdgeToEdge()`。Android 16 强制边缘到边缘且 `android:windowOptOutEdgeToEdgeEnforcement` 已失效，因此顶层 `Box` 必须同时保留 `.background(MaterialTheme.colorScheme.background)` 与 `.navigationBarsPadding()`：前者填充导航栏区域，后者让内容停在导航栏上方。
- 系统栏颜色由 `Theme.kt` 的 `ImageViewerTheme` 在 `SideEffect` 中设置，状态栏和导航栏颜色跟随 `colorScheme.background`，浅色/深色图标也同步切换。

## 本地浏览与媒体格式

- `LocalFileRepository` 直接使用 `java.io.File` 浏览 `/storage/emulated/0`，并从 `/storage` 补充可移除存储；没有使用 Storage Access Framework。
- 本地目录列表隐藏名称以 `.` 开头的文件夹，只显示受支持的媒体文件；目录排在文件前，再按名称的小写形式排序。
- 本地与网络目录预览都只查找目录直属层级中的第一张受支持图片，不递归搜索；网络目录会并发查询直属子目录预览。
- 本地与网络文件网格在内容超出可视区域时，通过 `LazyGridScrollbar` 在右侧显示位置指示条；没有可滚动内容时不显示滚动条。
- `LocalBrowserViewModel` 与 `NetworkBrowserViewModel` 按目录键在内存中保存 `LazyGridState` 的首个可见项目索引和像素偏移。进入子文件夹后返回必须恢复离开上级目录时的位置；本地根目录、网络共享列表、不同共享目录以及 `ONLINE`/`CACHE_ONLY` 模式的位置必须相互独立。该状态只要求在当前 ViewModel 生命周期内保留，不持久化到应用重启之后。
- 支持的图片扩展名为 `png`、`jpg`、`jpeg`、`webp`、`gif`；支持的视频扩展名为 `mp4`、`mkv`、`m4v`、`webm`、`3gp`、`avi`、`mov`、`ts`、`m2ts`、`flv`、`wmv`、`ogv`。
- Manifest 是权限分版本的事实来源：`READ_MEDIA_IMAGES` 用于 API 33+，`READ_EXTERNAL_STORAGE` 限制到 API 32，同时声明网络相关权限。`usesCleartextTraffic="true"` 是 SMB 连接所需配置。

## 偏好设置与持久化

- `PreferencesManager` 使用两个 SharedPreferences 文件：`smb_connection_prefs` 保存服务器地址、用户名、密码和 JSON 编码的共享名列表；`app_settings` 保存界面、下载和视频设置。
- 当前设置项包括 `swipe_right_to_left`、`label_font_scale`、`label_max_lines`、`show_folder_counts`、`segment_concurrency`、`video_play_mode`、`keep_screen_on`。文件夹内容数量默认显示；关闭后本地与网络浏览都应跳过目录数量统计，避免无用的文件系统或 SMB 查询。大图分段并发度默认 5、范围 1..16；网络视频默认流式播放；播放时默认保持屏幕常亮。
- SMB 密码目前按普通字符串存储，并未使用加密存储；`allowBackup="true"` 且备份规则仍为模板状态。涉及凭据或备份策略的修改必须同时检查 `AndroidManifest.xml`、`backup_rules.xml`、`data_extraction_rules.xml` 与兼容迁移，不能默认现有数据已经加密或排除备份。

## SMB 层（SMBJ，谨慎修改）

- 使用 SMBJ 0.14.0（`com.hierynomus:smbj`）和 `slf4j-nop`。Android 没有默认 SLF4J 绑定，不能随意移除后者。
- `SmbSessionManager` 单例持有 `SMBClient` → `Connection` → `Session`，认证只在 `connect()` 中通过 `AuthenticationContext` 完成一次；`getDiskShare(shareName)` 按需连接并缓存 `DiskShare`。`SmbRepository`、`SmbImageLoader` 和 `SmbVideoDataSource` 必须复用该会话，不要重新引入逐文件认证回退。
- SMBJ 没有服务器共享自动枚举 API。共享名由用户在设置页手动维护，存放于 `PreferencesManager.SmbConnectionConfig.shareNames`，网络页直接展示该列表。
- `NetworkBrowserViewModel` 冷启动有保存配置时会自动连接，最多尝试 3 次，重试间隔依次为 1 秒、2 秒；不要在 UI 线程执行阻塞式 SMB 操作。
- 网络浏览有 `ONLINE` 与 `CACHE_ONLY` 两种模式。初始连接、打开共享或浏览目录失败且存在有效缓存时，用户可选择读取缓存；缓存模式只展示从缓存元数据重建的共享、目录和媒体文件，并提供显式重新连接入口。
- `SmbRepository.listFiles()` 必须向上抛出目录读取异常，不能再把异常统一转换为空列表，否则 UI 无法区分“空目录”和“连接失败”。`listMediaFilesRecursively()` 用于批量缓存，只递归非隐藏目录并收集受支持的图片和视频。
- `DiskShare.openFile(...)` 返回 SMBJ 的 `File`，通过 `file.read(buffer, offset)` 或带缓冲区区间的重载按偏移读取。SMBJ 会把单次读取限制在客户端配置和服务器 `maxReadSize` 的较小值，大缓冲不会复现 jcifs-ng 的 `STATUS_INVALID_PARAMETER` 问题。
- SMBJ 0.14.0 的 `File` 没有 `getLength()`；文件长度使用 `file.getFileInformation(FileStandardInformation::class.java).endOfFile`。
- `FileIdBothDirectoryInformation.fileName` 已提供服务器返回的真实 Unicode 文件名。不要恢复 jcifs-ng 时代用于剥离异常前缀的 `cleanFileName()` workaround。
- 不要重新引入 `eu.agno3.jcifs`。旧实现中调大 `transaction_buf_size` 导致的 `STATUS_INVALID_PARAMETER` 已随迁移到 SMBJ 消除。

## SMB 缓存与下载

- 图片和“先缓存后播放”的视频统一写入 `context.cacheDir/smb_cache/{server_share}/{pathHash}_{filename}`。服务器地址中的 `.`、`:` 会被替换为 `_`；缓存管理页按此目录结构统计和删除。
- 每个完整缓存文件旁保存一个 `{cacheFilename}.meta.json` 元数据文件，记录服务器、共享名、共享内相对路径、文件名、大小、修改时间和媒体类型。`SmbCacheCatalog` 只接纳缓存文件与有效元数据同时存在的条目，并据此重建离线目录；元数据使用独立文件，不要改成批量任务共用的单一清单。
- 下载先写同目录 `.tmp` 文件，完整后再重命名为最终文件并写入元数据；顺序读取和分段读取都必须校验最终字节数，失败或不完整时删除临时文件。升级迁移 `cleared_partial_v3` 会首次清除旧半成品缓存，之后只清理扁平旧缓存、孤儿 `.tmp` 和 `.meta.tmp` 文件；全新安装在缓存根目录不存在时应直接标记迁移完成，不能在下次启动误删首批新缓存。
- 全局同时下载上限为 3；同一 `server/share/path` 通过 `Mutex` 去重，避免重复下载并发写坏缓存。
- 文件大于 512 KiB 时，按 `segment_concurrency` 切成 N 段并发读取，使用 `FileChannel` 按偏移写入同一个临时文件；小文件顺序读取。读取缓冲区为 2 MiB，进度回调最短间隔为 200 ms。
- 分段下载完成会以 `Log.i` 记录实测 MB/s。缓存命中直接返回本地路径，不重新校验远端修改时间或文件大小。
- 旧缓存没有原始路径元数据，无法直接参与离线目录重建；在线浏览或命中已知远程路径时由 `getCachePath()` 逐步补写元数据。不要为迁移旧缓存而反解路径哈希或清空现有缓存。
- 在线网络目录长按文件夹可递归缓存其中所有受支持媒体。任务先扫描、再最多并行处理 3 个文件；进度保存在 `NetworkBrowserViewModel`，模态弹窗阻止其他页面操作，并只通过明确的“取消缓存”按钮中止。取消会清理未完成的 `.tmp`，但必须保留此前以及本次已经完成的缓存。
- 在线文件夹的“已缓存”数量只统计直属层级媒体文件，数量为 0 时不显示；缓存模式下文件夹原“文件数”直接表示直属层级已缓存文件数，不再重复显示“已缓存”。目录预览仍会自动缓存直属层级第一张图片，因此缓存数可能在浏览页面后增加。

## 图片查看器

- `ImageViewerScreen` 使用 `HorizontalPager`，翻页方向由 `swipe_right_to_left` 决定；实现方式是在入口反转列表并换算初始索引，底部页码再映射回原始顺序。
- 当前页之后预取 3 张网络图片，`beyondViewportPageCount = 2`。预取在独立协程和 `NonCancellable + Dispatchers.IO` 中执行，以免翻页取消留下半文件。
- 每页缩放范围为 0.5x..5x；放大超过 1.05x 时禁用分页滑动，由 `transformable` 处理缩放和平移。未放大时单击切换控件、双击放大到 2.5x，离开当前页会重置缩放。
- API 28+ 的 GIF 和 WebP 通过 `ImageDecoder`/`AnimatedImageDrawable` 播放动画，其余图片由 Coil 加载。网络图片必须先落入统一 SMB 缓存。
- “保存到相册”对网络图片先确保缓存完成，再由 `MediaSaver` 写入系统相册；API 29+ 使用 MediaStore 的 `Pictures/ImageViewer`，旧版本使用公共图片目录。

## 视频播放（Media3 ExoPlayer，谨慎修改）

- 播放内核是 Media3 ExoPlayer 1.5.1（`media3-exoplayer` + `media3-ui`），视图使用 `AndroidView` 包装 `PlayerView` 并复用其控制条。
- `media3-ui` 中没有 `GesturePlayerView`；它只存在于 ExoPlayer Demo 示例。应用当前没有自定义快进、快退、音量、亮度或双击手势。若以后添加手势，可参考 Demo 的 `GestureManager`，但 `PlayerView.setOnTouchListener` 会替换内部监听，必须自行维持控制条显隐。
- 进入播放器时锁定竖屏，只有全屏按钮能切换 `LANDSCAPE`/`PORTRAIT`；全程不用 `SENSOR` 或 `USER`。全屏时隐藏系统栏并允许滑动临时显示，返回键先退出全屏，再退出播放器。
- Manifest 必须保留 `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"`。否则方向切换会重建 Activity，清空普通 `remember` 中的覆盖层状态，导致播放器消失并中断播放。
- 网络视频默认流式播放：`SmbVideoDataSource` 用 SMBJ `File.read(buffer, offset)` 实现 Media3 `DataSource`，Media3 通过以新 `dataSpec.position` 重新 `open()` 完成 seek。另一模式先调用 `SmbImageLoader.cacheSmbFile` 下载完整文件，再按本地文件播放。
- 从缓存模式打开的视频必须通过 `ImageItem.localCachePath` 强制按本地文件播放，不受“流式播放”设置影响；离线图片的显示与保存到相册同样优先使用该本地路径。
- `VideoSpeedMonitor` 统计从本次准备/重试开始的平均字节速率；流式 `read` 和缓存下载进度都会上报，`STATE_BUFFERING` 期间每秒刷新“缓冲中 X MB/s”。不要把该数值误写成瞬时速度。
- 本地视频网格用 `MediaMetadataRetriever` 提取约 1 秒处的同步帧，并显示时长；网络视频网格只显示图标，避免为缩略图读取网络文件头。
- `ExoPlayer` 在 `remember` 中创建并在 `DisposableEffect` 中释放。播放错误时递增 `retryKey`，重新构建媒体源、准备并自动播放。

## 缓存管理

- 缓存页只管理 `cacheDir/smb_cache` 下的共享缓存，支持按共享目录或全部清除；清理前播放器覆盖层已退出并释放句柄。统计文件数和大小时排除 `.tmp`、`.meta.json` 与 `.meta.tmp`，但清理共享或全部缓存时必须一并删除这些辅助文件并通知 `SmbCacheCatalog` 刷新。
- 缓存统计在进入页面时通过 `remember` 计算，执行清理后当前实现直接返回上一页；不要假设它会在页面内自动刷新。

## 测试与验证

- 目前只有 Gradle 模板生成的 `ExampleUnitTest` 和 `ExampleInstrumentedTest`，没有有效业务测试，也没有自定义 lint/typecheck 配置。
- 常规改动至少运行 `.\gradlew.bat :app:assembleDebug`。涉及 Release 配置时再运行 `.\gradlew.bat :app:assembleRelease`，并牢记输出未签名。
- 每次修改后都先通过 `adb devices` 检查 USB 调试设备；如果 `adb` 不在 `PATH`，则从 `local.properties` 的 `sdk.dir` 定位 `platform-tools/adb.exe`。如果存在状态为 `device` 的已授权手机或平板，则自动执行 `.\gradlew.bat :app:installDebug`，将最新 Debug 应用构建并安装到设备；状态为 `unauthorized`、`offline` 的设备不视为可用设备，并应在结果中说明。没有可用设备时不执行安装，也不将其视为验证失败。
- SMB 浏览、认证、网络预览、递归文件夹缓存、取消后保留已完成文件、缓存数量刷新、断线进入缓存模式、分段下载与流式播放必须连接真实局域网共享验证；没有可替代它们的单元测试。
- 涉及方向切换、系统栏或覆盖层的改动应在 Android 16/API 36 设备或模拟器上手工验证竖屏、横屏、返回键和导航栏 inset。
