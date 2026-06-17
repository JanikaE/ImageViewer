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
        val password: String
    )

    fun saveConfig(config: SmbConnectionConfig) {
        prefs.edit()
            .putString(KEY_SERVER_ADDRESS, config.serverAddress)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_PASSWORD, config.password)
            .apply()
    }

    fun loadConfig(): SmbConnectionConfig? {
        val server = prefs.getString(KEY_SERVER_ADDRESS, null)
        if (server.isNullOrBlank()) return null
        return SmbConnectionConfig(
            serverAddress = server,
            username = prefs.getString(KEY_USERNAME, "") ?: "",
            password = prefs.getString(KEY_PASSWORD, "") ?: ""
        )
    }

    fun clearConfig() {
        prefs.edit()
            .remove(KEY_SERVER_ADDRESS)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .apply()
    }

    // ── 应用设置 ──

    /** 翻页方向：true=从右往左划为下一页，false=从左往右划为下一页 */
    fun loadSwipeDirection(): Boolean {
        return settingsPrefs.getBoolean(KEY_SWIPE_DIRECTION, false)
    }

    fun saveSwipeDirection(rightToLeft: Boolean) {
        settingsPrefs.edit().putBoolean(KEY_SWIPE_DIRECTION, rightToLeft).apply()
    }

    companion object {
        private const val PREFS_NAME = "smb_connection_prefs"
        private const val SETTINGS_PREFS_NAME = "app_settings"
        private const val KEY_SERVER_ADDRESS = "server_address"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_SWIPE_DIRECTION = "swipe_right_to_left"
    }
}
