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
- Local browser reads the filesystem directly with `java.io.File` on `/storage/emulated/0` (`LocalFileRepository`); folder previews are the first supported image found inside.
- Manifest sets `usesCleartextTraffic="true"` (required for SMB) and declares storage/network permissions — the manifest is the source of truth for API-level permission splits (READ_MEDIA_IMAGES 33+, READ_EXTERNAL_STORAGE ≤32).

## SMB layer (hard-won quirks — don't "fix")
- `SmbRepository` keeps a process-wide singleton `CIFSContext` via companion `getSharedContext()`/`updateSharedContext()`; `SmbImageLoader` reuses it as its first auth strategy (`SmbImageLoader.kt:77`), then falls back to explicit credentials, then guest.
- JCIFS bug: with special characters in a path, `SmbFile.name` returns "parentFolderName + fileName". `SmbRepository.cleanFileName()` strips the known parent prefix (`SmbRepository.kt:172`) — preserve this workaround or browsing breaks on such shares.
- SMB images are downloaded to `context.cacheDir/smb_cache/{server_share}/{pathHash}_{filename}` and cached until cleared (`SmbImageLoader`, cache-management UI reads this layout).
- `connect()` validates the share with `SmbFile(...).exists()`; `isConnected()` just checks for a non-null context.

## Testing
- Only Gradle-template boilerplate exists (`ExampleUnitTest`, `ExampleInstrumentedTest`). No meaningful test suite or custom lint/typecheck config. Any SMB verification requires a real LAN share; the rest can be reasoned about statically.
