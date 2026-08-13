package ru.simple.mycalendar.v2.peer

import android.content.Context

data class BluetoothStatus(
    val lastSuccessfulSync: Long,
    val message: String
)

object BluetoothPeerState {
    private const val PREFS = "bluetooth_peer_v2"
    private const val KEY_HAS_SYNCED = "has_successful_exchange"
    private const val KEY_LAST_SUCCESS = "last_successful_exchange"
    private const val KEY_MESSAGE = "status_message"

    fun status(context: Context): BluetoothStatus {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return BluetoothStatus(
            lastSuccessfulSync = prefs.getLong(KEY_LAST_SUCCESS, 0L),
            message = prefs.getString(KEY_MESSAGE, "Bluetooth-связь ещё не запускалась").orEmpty()
        )
    }

    fun hasSynced(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HAS_SYNCED, false)

    fun markWaiting(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MESSAGE, "Ищу рядом другое приложение FamilyTasks…").apply()
    }

    fun markFound(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MESSAGE, "Приложение рядом найдено, проверяю общий recovery-ключ…").apply()
    }

    fun markUpToDate(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MESSAGE, "Телефон семьи рядом: защищённые отпечатки совпадают, обмен не нужен").apply()
    }

    fun markDifference(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MESSAGE, "Телефон семьи рядом: найдены изменения, готовлю защищённый обмен").apply()
    }

    fun markKeyMismatch(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MESSAGE, "Приложение рядом найдено, но recovery-ключи не совпали").apply()
    }

    fun markConnectionFailed(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MESSAGE, "Приложение найдено, но соединение оборвалось; повторю автоматически").apply()
    }

    fun markUnavailable(context: Context, message: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MESSAGE, message).apply()
    }

    fun markSuccess(context: Context) {
        val now = System.currentTimeMillis()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAS_SYNCED, true)
            .putLong(KEY_LAST_SUCCESS, now)
            .putString(KEY_MESSAGE, "Телефоны связаны, защищённая синхронизация выполнена")
            .apply()
    }
}
