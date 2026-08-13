package ru.simple.mycalendar.v2

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostics ring buffer for connectivity events (Bluetooth and server sync).
 * Keeps at most [MAX_LINES] timestamped lines in a private file that the user
 * can export from Settings. Never stores task text, profile names, keys or
 * full identifiers — call sites pass device- and digest-like values through
 * [mask] so the exported log stays anonymous.
 */
object SyncLog {
    private const val FILE_NAME = "sync-diagnostics.log"
    private const val MAX_LINES = 100
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun log(context: Context, message: String) {
        val file = file(context)
        val line = "${stamp.format(Date())}  $message"
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        val updated = (lines + line).takeLast(MAX_LINES)
        runCatching { file.writeText(updated.joinToString("\n") + "\n") }
    }

    @Synchronized
    fun read(context: Context): String =
        runCatching { file(context).readText() }.getOrDefault("").ifBlank { "Журнал пока пуст.\n" }

    /** Renders an identifier unrecognizable: "F0:18:98:AB:CD:EF" -> "F0189********". */
    fun mask(value: String?): String {
        if (value.isNullOrBlank()) return "—"
        val compact = value.filter { it.isLetterOrDigit() }
        if (compact.isEmpty()) return "—"
        return compact.take(5) + "********"
    }

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)
}
