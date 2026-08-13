package ru.simple.mycalendar.v2.sync

import android.content.Context
import ru.simple.mycalendar.v2.UiPreferences

/** Coordinates server-first operation without disabling autonomous Bluetooth sync. */
class ServerSyncHealth(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun markLocalChange() {
        if (prefs.getLong(KEY_PENDING_SINCE, 0L) == 0L) {
            prefs.edit().putLong(KEY_PENDING_SINCE, System.currentTimeMillis()).apply()
        }
    }

    @Synchronized
    fun markSuccess() {
        prefs.edit()
            .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
            .putLong(KEY_PENDING_SINCE, 0L)
            .putLong(KEY_LAST_FAILURE, 0L)
            .apply()
    }

    @Synchronized
    fun markFailure() {
        prefs.edit().putLong(KEY_LAST_FAILURE, System.currentTimeMillis()).apply()
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * A configured server gets a short head start. Bluetooth becomes active if
     * the server fails, has gone stale, or did not upload a pending change.
     */
    @Synchronized
    fun bluetoothInitiationAllowed(
        uiPreferences: UiPreferences,
        serverConfigured: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (!uiPreferences.serverBackgroundSyncEnabled() || !serverConfigured) return true

        val lastSuccess = prefs.getLong(KEY_LAST_SUCCESS, 0L)
        val lastFailure = prefs.getLong(KEY_LAST_FAILURE, 0L)
        val pendingSince = prefs.getLong(KEY_PENDING_SINCE, 0L)
        if (pendingSince > lastSuccess && safeAge(nowMillis, pendingSince) < LOCAL_CHANGE_GRACE_MS) return false
        if (lastSuccess == 0L || lastFailure > lastSuccess) return true
        if (pendingSince > lastSuccess) return true

        val staleAfter = maxOf(
            MIN_SERVER_HEALTH_MS,
            uiPreferences.syncIntervalMinutes().toLong() * 2L * 60_000L
        )
        return safeAge(nowMillis, lastSuccess) >= staleAfter
    }

    private fun safeAge(now: Long, then: Long): Long = (now - then).coerceAtLeast(0L)

    companion object {
        private const val PREFS = "server_sync_health_v1"
        private const val KEY_LAST_SUCCESS = "last_success"
        private const val KEY_LAST_FAILURE = "last_failure"
        private const val KEY_PENDING_SINCE = "pending_since"
        private const val LOCAL_CHANGE_GRACE_MS = 60_000L
        private const val MIN_SERVER_HEALTH_MS = 10 * 60_000L
    }
}
