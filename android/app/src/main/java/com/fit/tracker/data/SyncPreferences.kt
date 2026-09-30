package com.fit.tracker.data

import android.content.Context
import android.content.SharedPreferences

/**
 * SyncPreferences: Manages persistence of user settings and sync logs
 */
class SyncPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "health_connect_sync_prefs"
        private const val KEY_WEB_APP_URL = "web_app_url"
        private const val KEY_SECRET_TOKEN = "secret_token"
        private const val KEY_AUTO_SYNC_ENABLED = "auto_sync_enabled"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_LAST_SYNC_STEPS = "last_sync_steps"
        private const val KEY_LAST_SYNC_STATUS = "last_sync_status"
    }

    var webAppUrl: String
        get() = prefs.getString(KEY_WEB_APP_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WEB_APP_URL, value).apply()

    var secretToken: String
        get() = prefs.getString(KEY_SECRET_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SECRET_TOKEN, value).apply()

    var isAutoSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SYNC_ENABLED, value).apply()

    var lastSyncTime: String
        get() = prefs.getString(KEY_LAST_SYNC_TIME, "Never") ?: "Never"
        set(value) = prefs.edit().putString(KEY_LAST_SYNC_TIME, value).apply()

    var lastSyncSteps: Long
        get() = prefs.getLong(KEY_LAST_SYNC_STEPS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_STEPS, value).apply()

    var lastSyncStatus: String
        get() = prefs.getString(KEY_LAST_SYNC_STATUS, "Ready") ?: "Ready"
        set(value) = prefs.edit().putString(KEY_LAST_SYNC_STATUS, value).apply()
}
