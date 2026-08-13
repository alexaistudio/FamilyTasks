package ru.simple.mycalendar.v2.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ru.simple.mycalendar.v2.UiPreferences
import ru.simple.mycalendar.v2.SyncLog
import ru.simple.mycalendar.v2.V2App
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as V2App
        val sync = app.sync
        val continuePeriodicLoop = inputData.getBoolean(CONTINUE_PERIODIC_LOOP, true)
        if (!UiPreferences(applicationContext).serverBackgroundSyncEnabled() || !sync.info().configured) {
            if (continuePeriodicLoop) scheduleNext(applicationContext)
            return Result.success()
        }
        return try {
            sync.syncNow()
            app.syncHealth.markSuccess()
            SyncLog.log(applicationContext, "NET: серверный обмен выполнен")
            ru.simple.mycalendar.v2.peer.BluetoothSyncService.requestSync(applicationContext)
            if (continuePeriodicLoop) scheduleNext(applicationContext)
            Result.success()
        } catch (error: Exception) {
            app.syncHealth.markFailure()
            SyncLog.log(applicationContext, "NET: серверный обмен не удался: ${error.message}")
            ru.simple.mycalendar.v2.peer.BluetoothSyncService.requestSync(applicationContext)
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK = "mycalendar-v2-sync-flex-v4"
        private const val IMMEDIATE_WORK = "mycalendar-v2-sync-immediate-v1"
        private const val OLD_PERIODIC_WORK = "mycalendar-v2-sync"

        fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            work.cancelUniqueWork(OLD_PERIODIC_WORK)
            work.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request(context))
        }

        fun scheduleSoon(context: Context) {
            if (!UiPreferences(context).serverBackgroundSyncEnabled()) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK,
                ExistingWorkPolicy.REPLACE,
                request(context, delayMinutes = 0, continuePeriodicLoop = false)
            )
        }

        private fun scheduleNext(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request(context)
            )
        }

        private fun request(
            context: Context,
            delayMinutes: Long = UiPreferences(context).syncIntervalMinutes().toLong(),
            continuePeriodicLoop: Boolean = true
        ) = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setInputData(workDataOf(CONTINUE_PERIODIC_LOOP to continuePeriodicLoop))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

        private const val CONTINUE_PERIODIC_LOOP = "continue_periodic_loop"
    }
}
