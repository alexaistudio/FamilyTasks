package ru.simple.mycalendar.v2.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.simple.mycalendar.v2.R
import ru.simple.mycalendar.v2.UiPreferences
import java.util.concurrent.TimeUnit

class UpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val preferences = UiPreferences(applicationContext)
        if (!preferences.automaticUpdatesEnabled()) return@withContext Result.success()
        when (val update = AppUpdater(applicationContext).checkAndDownload()) {
            is UpdateResult.Ready -> {
                if (preferences.lastNotifiedUpdateVersion() != update.version) {
                    notifyReady(update.version)
                    preferences.setLastNotifiedUpdateVersion(update.version)
                }
                Result.success()
            }
            is UpdateResult.UpToDate -> Result.success()
            is UpdateResult.Error -> Result.success()
        }
    }

    private fun notifyReady(version: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Обновления FamilyTasks", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val action = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, UpdateInstallActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("FamilyTasks $version готов к установке")
                .setContentText("Нажмите и подтвердите обновление в системном окне Android.")
                .setContentIntent(action)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        )
    }

    companion object {
        private const val WORK = "familytasks_github_updates"
        private const val CHANNEL = "familytasks_updates"
        private const val NOTIFICATION_ID = 24009

        fun schedule(context: Context) {
            val work = WorkManager.getInstance(context.applicationContext)
            if (!UiPreferences(context).automaticUpdatesEnabled()) {
                work.cancelUniqueWork(WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
