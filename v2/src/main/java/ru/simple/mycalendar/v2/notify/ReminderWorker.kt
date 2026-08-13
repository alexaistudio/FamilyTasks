package ru.simple.mycalendar.v2.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ru.simple.mycalendar.v2.MainActivity
import ru.simple.mycalendar.v2.R
import ru.simple.mycalendar.v2.V2App

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(TASK_ID) ?: return Result.failure()
        val task = (applicationContext as V2App).database.tasks().find(id) ?: return Result.success()
        val revision = inputData.getLong(TASK_REVISION, -1L)
        if (task.deletedAt != null || task.completedAt != null || task.dateOrNull() == null || task.timeMinutes == null) {
            return Result.success()
        }
        if (revision >= 0 && revision != task.updatedAt) return Result.success()
        val eventAt = inputData.getLong(EVENT_AT, 0L)
        if (eventAt > 0 && System.currentTimeMillis() > eventAt + MAX_LATE_MILLIS) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && applicationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return Result.success()
        }

        val loud = task.reminderSound == SOUND_LOUD
        val channelId = if (loud) CHANNEL_LOUD else CHANNEL_NORMAL
        createChannel(channelId, loud)
        val kind = inputData.getString(EVENT_KIND) ?: KIND_START
        val minutes = inputData.getInt(MINUTES_BEFORE, 0)
        val intent = PendingIntent.getActivity(
            applicationContext,
            id.hashCode(),
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (kind == KIND_START) "Пора начинать" else "Скоро: ${beforeText(minutes)}")
            .setContentText(task.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(task.title))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(if (loud) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (loud) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .build()
        val notificationId = "$id/$kind".hashCode()
        NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
        return Result.success()
    }

    private fun createChannel(id: String, loud: Boolean) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            id,
            if (loud) "Звучные напоминания" else "Обычные напоминания",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = if (loud) "Громкий сигнал и усиленная вибрация" else "Обычный системный сигнал"
            enableVibration(true)
            if (loud) vibrationPattern = longArrayOf(0, 500, 180, 500, 180, 800)
            val sound = RingtoneManager.getDefaultUri(
                if (loud) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION
            ) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val attributes = AudioAttributes.Builder()
                .setUsage(if (loud) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .build()
            setSound(sound, attributes)
        }
        manager.createNotificationChannel(channel)
    }

    private fun beforeText(minutes: Int): String = when {
        minutes >= 1440 -> "через ${minutes / 1440} дн."
        minutes >= 60 -> "через ${minutes / 60} ч."
        else -> "через $minutes мин."
    }

    companion object {
        const val TASK_ID = "task_id"
        const val EVENT_KIND = "event_kind"
        const val MINUTES_BEFORE = "minutes_before"
        const val TASK_REVISION = "task_revision"
        const val EVENT_AT = "event_at"
        const val KIND_BEFORE = "before"
        const val KIND_START = "start"
        const val SOUND_NORMAL = "normal"
        const val SOUND_LOUD = "loud"
        private const val CHANNEL_NORMAL = "task_reminders_normal_v3"
        private const val CHANNEL_LOUD = "task_reminders_loud_v3"
        private const val MAX_LATE_MILLIS = 6 * 60 * 60 * 1000L
    }
}
