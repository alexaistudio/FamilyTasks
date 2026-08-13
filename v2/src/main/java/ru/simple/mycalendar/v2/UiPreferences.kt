package ru.simple.mycalendar.v2

import android.content.Context
import ru.simple.mycalendar.v2.sync.SyncWorker
import ru.simple.mycalendar.v2.peer.BluetoothSyncService
import ru.simple.mycalendar.v2.update.UpdateWorker
import java.util.UUID

class UiPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun calendarTaskFontSp(): Float = prefs.getFloat(KEY_TASK_FONT, DEFAULT_TASK_FONT)

    fun setCalendarTaskFontSp(value: Float) {
        prefs.edit().putFloat(KEY_TASK_FONT, value.coerceIn(6f, 10f)).apply()
    }

    fun syncIntervalMinutes(): Int = prefs.getInt(KEY_SYNC_INTERVAL, DEFAULT_SYNC_INTERVAL)

    fun setSyncIntervalMinutes(value: Int) {
        prefs.edit().putInt(KEY_SYNC_INTERVAL, value.coerceIn(5, 24 * 60)).apply()
        SyncWorker.schedule(appContext)
    }

    fun notifyAboutNewTasks(): Boolean = prefs.getBoolean(KEY_NOTIFY_NEW_TASKS, true)

    fun setNotifyAboutNewTasks(value: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_NEW_TASKS, value).apply()
    }

    fun serverBackgroundSyncEnabled(): Boolean = prefs.getBoolean(KEY_SERVER_SYNC_ENABLED, true)

    fun setServerBackgroundSyncEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_SERVER_SYNC_ENABLED, value).apply()
        SyncWorker.schedule(appContext)
    }

    fun bluetoothSyncEnabled(): Boolean = prefs.getBoolean(KEY_BLUETOOTH_SYNC_ENABLED, false)

    fun setBluetoothSyncEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_BLUETOOTH_SYNC_ENABLED, value).apply()
        BluetoothSyncService.applyEnabledState(appContext, value)
    }

    fun automaticUpdatesEnabled(): Boolean = prefs.getBoolean(KEY_AUTOMATIC_UPDATES, true)

    fun setAutomaticUpdatesEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOMATIC_UPDATES, value).apply()
        UpdateWorker.schedule(appContext)
    }

    fun lastNotifiedUpdateVersion(): String = prefs.getString(KEY_LAST_UPDATE_NOTICE, "").orEmpty()

    fun setLastNotifiedUpdateVersion(value: String) {
        prefs.edit().putString(KEY_LAST_UPDATE_NOTICE, value).apply()
    }

    fun localUserId(): String = prefs.getString(KEY_LOCAL_USER_ID, null)
        ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString(KEY_LOCAL_USER_ID, it).commit())
        }

    /** Stable only inside encrypted app storage; BLE exposes a rotating keyed tag instead. */
    fun bluetoothDeviceId(): String = prefs.getString(KEY_BLUETOOTH_DEVICE_ID, null)
        ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString(KEY_BLUETOOTH_DEVICE_ID, it).commit())
        }

    companion object {
        private const val PREFS_NAME = "ui_preferences_v2"
        private const val KEY_TASK_FONT = "calendar_task_font_sp"
        private const val KEY_SYNC_INTERVAL = "sync_interval_minutes"
        private const val KEY_NOTIFY_NEW_TASKS = "notify_new_synced_tasks"
        private const val KEY_SERVER_SYNC_ENABLED = "server_background_sync_enabled"
        private const val KEY_BLUETOOTH_SYNC_ENABLED = "bluetooth_sync_enabled"
        private const val KEY_LOCAL_USER_ID = "local_user_id"
        private const val KEY_BLUETOOTH_DEVICE_ID = "bluetooth_device_id"
        private const val KEY_AUTOMATIC_UPDATES = "automatic_github_updates"
        private const val KEY_LAST_UPDATE_NOTICE = "last_update_notice"
        private const val DEFAULT_TASK_FONT = 7f
        private const val DEFAULT_SYNC_INTERVAL = 60
    }
}
