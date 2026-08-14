package com.janika.imageviewer.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用配置的本地持久化存储（SMB连接 + 应用设置）
 */
class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val settingsPrefs: SharedPreferences =
        context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)

    data class SmbConnectionConfig(
        val serverAddress: String,
        val username: String,
        val password: String,
        /** 已配置的共享名列表（手动维护，不做自动枚举） */
        val shareNames: List<String> = emptyList()
    )

    fun saveConfig(config: SmbConnectionConfig) {
        prefs.edit()
            .putString(KEY_SERVER_ADDRESS, config.serverAddress)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_PASSWORD, config.password)
            .putString(KEY_SHARE_NAMES, encodeShareNames(config.shareNames))
            .apply()
    }

    fun loadConfig(): SmbConnectionConfig? {
        val server = prefs.getString(KEY_SERVER_ADDRESS, null)
        if (server.isNullOrBlank()) return null
        return SmbConnectionConfig(
            serverAddress = server,
            username = prefs.getString(KEY_USERNAME, "") ?: "",
            password = prefs.getString(KEY_PASSWORD, "") ?: "",
            shareNames = loadShareNames()
        )
    }

    fun clearConfig() {
        prefs.edit()
            .remove(KEY_SERVER_ADDRESS)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .remove(KEY_SHARE_NAMES)
            .apply()
    }

    /** 加载已保存的共享名列表 */
    fun loadShareNames(): List<String> {
        return decodeShareNames(prefs.getString(KEY_SHARE_NAMES, null))
    }

    /** 保存共享名列表 */
    fun saveShareNames(shareNames: List<String>) {
        prefs.edit().putString(KEY_SHARE_NAMES, encodeShareNames(shareNames)).apply()
    }

    private fun encodeShareNames(names: List<String>): String {
        val arr = org.json.JSONArray()
        names.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun decodeShareNames(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── 应用设置 ──

    /** 翻页方向：true=从右往左划为下一页，false=从左往右划为下一页 */
    fun loadSwipeDirection(): Boolean {
        return settingsPrefs.getBoolean(KEY_SWIPE_DIRECTION, false)
    }

    fun saveSwipeDirection(rightToLeft: Boolean) {
        settingsPrefs.edit().putBoolean(KEY_SWIPE_DIRECTION, rightToLeft).apply()
    }

    /** 文件名标签字号倍率，默认 1.5 */
    fun loadLabelFontScale(): Float {
        return settingsPrefs.getFloat(KEY_LABEL_FONT_SCALE, 1.5f)
    }

    fun saveLabelFontScale(scale: Float) {
        settingsPrefs.edit().putFloat(KEY_LABEL_FONT_SCALE, scale).apply()
    }

    /** 文件名最多显示行数，默认 2 */
    fun loadLabelMaxLines(): Int {
        return settingsPrefs.getInt(KEY_LABEL_MAX_LINES, 2)
    }

    fun saveLabelMaxLines(lines: Int) {
        settingsPrefs.edit().putInt(KEY_LABEL_MAX_LINES, lines).apply()
    }

    /** 大图并发分段读取的并发度，默认 5，范围 1..16 */
    fun loadSegmentConcurrency(): Int {
        return settingsPrefs.getInt(KEY_SEGMENT_CONCURRENCY, 5).coerceIn(1, 16)
    }

    fun saveSegmentConcurrency(concurrency: Int) {
        settingsPrefs.edit().putInt(KEY_SEGMENT_CONCURRENCY, concurrency.coerceIn(1, 16)).apply()
    }

    // ── 视频播放 ──

    /** 视频播放方式：VIDEO_MODE_STREAM=流式，VIDEO_MODE_CACHE=先缓存后播放 */
    fun loadVideoPlayMode(): String {
        return settingsPrefs.getString(KEY_VIDEO_PLAY_MODE, VIDEO_MODE_STREAM) ?: VIDEO_MODE_STREAM
    }

    fun saveVideoPlayMode(mode: String) {
        settingsPrefs.edit().putString(KEY_VIDEO_PLAY_MODE, mode).apply()
    }

    /** 播放时保持屏幕常亮，默认开启 */
    fun loadKeepScreenOn(): Boolean {
        return settingsPrefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
    }

    fun saveKeepScreenOn(keepScreenOn: Boolean) {
        settingsPrefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, keepScreenOn).apply()
    }

    companion object {
        private const val PREFS_NAME = "smb_connection_prefs"
        private const val SETTINGS_PREFS_NAME = "app_settings"
        private const val KEY_SERVER_ADDRESS = "server_address"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_SHARE_NAMES = "share_names"
        private const val KEY_SWIPE_DIRECTION = "swipe_right_to_left"
        private const val KEY_LABEL_FONT_SCALE = "label_font_scale"
        private const val KEY_LABEL_MAX_LINES = "label_max_lines"
        private const val KEY_SEGMENT_CONCURRENCY = "segment_concurrency"
        private const val KEY_VIDEO_PLAY_MODE = "video_play_mode"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"

        /** 视频播放方式：流式 */
        const val VIDEO_MODE_STREAM = "stream"
        /** 视频播放方式：先缓存后播放 */
        const val VIDEO_MODE_CACHE = "cache"
    }
}
