package ru.simple.mycalendar.v2.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ru.simple.mycalendar.v2.MainActivity
import ru.simple.mycalendar.v2.R
import ru.simple.mycalendar.v2.UiPreferences
import ru.simple.mycalendar.v2.data.TaskEntity

class IncomingTaskNotifier(private val context: Context) {
    fun show(tasks: List<TaskEntity>) {
        val localUserId = UiPreferences(context).localUserId()
        val addressed = tasks.filter { it.shouldNotifyUser(localUserId) }
        if (addressed.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Новые дела после синхронизации", NotificationManager.IMPORTANCE_HIGH)
        )
        val intent = PendingIntent.getActivity(
            context,
            41_002,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (addressed.size == 1) "Появилось новое дело" else "Новых дел: ${addressed.size}"
        val body = addressed.take(3).joinToString(" · ") { it.title }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL = "new_synced_tasks_v1"
        private const val NOTIFICATION_ID = 41_002
    }
}
