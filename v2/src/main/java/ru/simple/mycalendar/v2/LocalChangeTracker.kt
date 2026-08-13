package ru.simple.mycalendar.v2

import android.content.Context

/** Persistent monotonic generation of changes that still need server upload. */
class LocalChangeTracker(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun generation(): Long = prefs.getLong(KEY_GENERATION, 0L)

    @Synchronized
    fun markChanged(): Long {
        val current = prefs.getLong(KEY_GENERATION, 0L)
        val next = if (current == Long.MAX_VALUE) current else current + 1L
        check(prefs.edit().putLong(KEY_GENERATION, next).commit())
        return next
    }

    companion object {
        private const val PREFS = "local_change_generation_v1"
        private const val KEY_GENERATION = "generation"
    }
}
