package com.janika.imageviewer.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 应用配置的本地持久化存储（SMB连接 + 应用设置）
 */
class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val settingsPrefs: SharedPreferences =
        context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)

    data class SmbServerConfig(
        val id: String,
        val serverAddress: String,
        val username: String,
        val password: String,
        val enabled: Boolean = true,
        /** 已配置的共享名列表（手动维护，不做自动枚举） */
        val shareNames: List<String> = emptyList()
    )

    /**
     * 加载全部服务器配置。首次读取新版数据时，会把旧版单服务器配置迁移为一条启用记录。
     */
    fun loadServerConfigs(): List<SmbServerConfig> {
        val raw = prefs.getString(KEY_SERVERS_V2, null)
        if (!raw.isNullOrBlank()) {
            try {
                val array = JSONArray(raw)
                return (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val address = item.optString("serverAddress").trim()
                    if (address.isEmpty()) return@mapNotNull null
                    SmbServerConfig(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        serverAddress = address,
                        username = item.optString("username"),
                        password = item.optString("password"),
                        enabled = item.optBoolean("enabled", true),
                        shareNames = decodeShareNames(item.optJSONArray("shareNames"))
                    )
                }
            } catch (e: Exception) {
                Log.e("PreferencesManager", "多服务器配置解析失败，尝试迁移旧配置", e)
            }
        }

        val legacyAddress = prefs.getString(KEY_SERVER_ADDRESS, null)?.trim().orEmpty()
        if (legacyAddress.isEmpty()) return emptyList()
        val migrated = listOf(
            SmbServerConfig(
                id = UUID.randomUUID().toString(),
                serverAddress = legacyAddress,
                username = prefs.getString(KEY_USERNAME, "") ?: "",
                password = prefs.getString(KEY_PASSWORD, "") ?: "",
                enabled = true,
                shareNames = decodeShareNames(prefs.getString(KEY_SHARE_NAMES, null))
            )
        )
        saveServerConfigs(migrated)
        return migrated
    }

    fun saveServerConfigs(configs: List<SmbServerConfig>) {
        val array = JSONArray()
        configs.forEach { config ->
            array.put(JSONObject().apply {
                put("id", config.id)
                put("serverAddress", config.serverAddress.trim())
                put("username", config.username)
                put("password", config.password)
                put("enabled", config.enabled)
                put("shareNames", JSONArray(config.shareNames))
            })
        }
        prefs.edit()
            .putString(KEY_SERVERS_V2, array.toString())
            .remove(KEY_SERVER_ADDRESS)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .remove(KEY_SHARE_NAMES)
            .apply()
    }

    fun upsertServerConfig(config: SmbServerConfig) {
        val normalized = config.copy(
            serverAddress = config.serverAddress.trim(),
            shareNames = config.shareNames
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
        )
        val configs = loadServerConfigs().toMutableList()
        val index = configs.indexOfFirst { it.id == normalized.id }
        if (index >= 0) configs[index] = normalized else configs += normalized
        saveServerConfigs(configs)
    }

    fun removeServerConfig(id: String) {
        saveServerConfigs(loadServerConfigs().filterNot { it.id == id })
    }

    fun setServerEnabled(id: String, enabled: Boolean) {
        saveServerConfigs(loadServerConfigs().map { config ->
            if (config.id == id) config.copy(enabled = enabled) else config
        })
    }

    private fun decodeShareNames(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            decodeShareNames(JSONArray(raw))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun decodeShareNames(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length())
            .mapNotNull { array.optString(it).trim().takeIf(String::isNotEmpty) }
            .distinctBy { it.lowercase() }
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

    /** 是否在文件夹条目中显示直属文件、文件夹与缓存数量，默认开启 */
    fun loadShowFolderCounts(): Boolean {
        return settingsPrefs.getBoolean(KEY_SHOW_FOLDER_COUNTS, true)
    }

    fun saveShowFolderCounts(show: Boolean) {
        settingsPrefs.edit().putBoolean(KEY_SHOW_FOLDER_COUNTS, show).apply()
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
        private const val KEY_SERVERS_V2 = "servers_v2"
        private const val KEY_SWIPE_DIRECTION = "swipe_right_to_left"
        private const val KEY_LABEL_FONT_SCALE = "label_font_scale"
        private const val KEY_LABEL_MAX_LINES = "label_max_lines"
        private const val KEY_SHOW_FOLDER_COUNTS = "show_folder_counts"
        private const val KEY_SEGMENT_CONCURRENCY = "segment_concurrency"
        private const val KEY_VIDEO_PLAY_MODE = "video_play_mode"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"

        /** 视频播放方式：流式 */
        const val VIDEO_MODE_STREAM = "stream"
        /** 视频播放方式：先缓存后播放 */
        const val VIDEO_MODE_CACHE = "cache"
    }
}
