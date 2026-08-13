package ru.simple.mycalendar.v2.notify

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import ru.simple.mycalendar.v2.UiPreferences
import ru.simple.mycalendar.v2.data.TaskEntity
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class ReminderScheduler(context: Context) {
    private val work = WorkManager.getInstance(context)
    private val localUserId = UiPreferences(context).localUserId()

    fun schedule(task: TaskEntity) {
        cancel(task.id)
        if (task.deletedAt != null || task.completedAt != null) return
        if (!task.shouldNotifyUser(localUserId)) return
        val date = task.dateOrNull() ?: return
        val timeMinutes = task.timeMinutes ?: return
        if (date.isBefore(LocalDate.now())) return

        val start = LocalDateTime.of(date, LocalTime.of(timeMinutes / 60, timeMinutes % 60))
            .atZone(ZoneId.systemDefault()).toInstant()
        task.reminderMinutesBefore?.takeIf { it > 0 }?.let { minutes ->
            enqueue(task, start.minusSeconds(minutes * 60L), ReminderWorker.KIND_BEFORE, minutes)
        }
        if (task.notifyAtStart) {
            enqueue(task, start, ReminderWorker.KIND_START, 0)
        }
    }

    fun cancel(id: String) {
        work.cancelUniqueWork(uniqueName(id, ReminderWorker.KIND_BEFORE))
        work.cancelUniqueWork(uniqueName(id, ReminderWorker.KIND_START))
        // Remove jobs created by versions before separate "before/start" events.
        work.cancelUniqueWork("mycalendar-v2-reminder-$id")
    }

    private fun enqueue(task: TaskEntity, eventAt: java.time.Instant, kind: String, minutesBefore: Int) {
        val delay = Duration.between(java.time.Instant.now(), eventAt).toMillis()
        if (delay <= 0) return
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            // Only opaque/technical values are stored by WorkManager. Text stays in SQLCipher.
            .setInputData(
                Data.Builder()
                    .putString(ReminderWorker.TASK_ID, task.id)
                    .putString(ReminderWorker.EVENT_KIND, kind)
                    .putInt(ReminderWorker.MINUTES_BEFORE, minutesBefore)
                    .putLong(ReminderWorker.TASK_REVISION, task.updatedAt)
                    .putLong(ReminderWorker.EVENT_AT, eventAt.toEpochMilli())
                    .build()
            )
            .addTag(WORK_TAG)
            .build()
        work.enqueueUniqueWork(uniqueName(task.id, kind), ExistingWorkPolicy.REPLACE, request)
    }

    private fun uniqueName(id: String, kind: String) = "mycalendar-v2-reminder-$kind-$id"

    companion object { private const val WORK_TAG = "mycalendar-v2-reminders" }
}
