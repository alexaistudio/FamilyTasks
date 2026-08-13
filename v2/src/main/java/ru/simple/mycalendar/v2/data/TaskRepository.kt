package ru.simple.mycalendar.v2.data

import kotlinx.coroutines.flow.Flow
import ru.simple.mycalendar.v2.notify.ReminderScheduler
import java.time.LocalDate
import java.nio.charset.StandardCharsets
import java.util.UUID

class TaskRepository(
    private val dao: TaskDao,
    private val reminders: ReminderScheduler,
    private val revisionDevice: String,
    val currentUserId: String,
    private val onLocalChange: () -> Unit = {}
) {
    val active: Flow<List<TaskEntity>> = dao.observeActive()
    val trash: Flow<List<TaskEntity>> = dao.observeTrash()
    val profiles: Flow<List<UserProfileEntity>> = dao.observeProfiles()

    suspend fun ensureCurrentProfile() {
        if (dao.findProfile(currentUserId) != null) return
        val now = System.currentTimeMillis()
        dao.upsertProfile(
            UserProfileEntity(
                id = currentUserId,
                revisionVector = RevisionVector.initial(revisionDevice),
                createdAt = now,
                updatedAt = now
            )
        )
        onLocalChange()
    }

    suspend fun renameCurrentProfile(name: String) {
        val clean = name.trim().take(60)
        val current = dao.findProfile(currentUserId)
        if (current == null) {
            val now = System.currentTimeMillis()
            dao.upsertProfile(
                UserProfileEntity(
                    id = currentUserId,
                    displayName = clean,
                    revisionVector = RevisionVector.initial(revisionDevice),
                    createdAt = now,
                    updatedAt = now
                )
            )
            onLocalChange()
            return
        }
        if (current.displayName == clean) return
        dao.upsertProfile(
            current.copy(
                displayName = clean,
                revisionVector = RevisionVector.bump(current.revisionVector, revisionDevice),
                updatedAt = System.currentTimeMillis()
            )
        )
        onLocalChange()
    }

    suspend fun save(task: TaskEntity) {
        val saved = task.copy(
            updatedAt = System.currentTimeMillis(),
            revisionVector = RevisionVector.bump(task.revisionVector, revisionDevice)
        )
        dao.upsert(saved)
        reminders.schedule(saved)
        onLocalChange()
    }

    suspend fun addToDates(
        dates: Set<LocalDate>,
        title: String,
        note: String,
        timeMinutes: Int?,
        important: Boolean,
        color: Long?,
        repeatRule: String?,
        reminderMinutesBefore: Int?,
        notifyAtStart: Boolean,
        reminderSound: String,
        notifyAllUsers: Boolean,
        notifyUserIds: Set<String>
    ) {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty() || dates.isEmpty()) return
        val now = System.currentTimeMillis()
        val sharedSeries = UUID.randomUUID().toString().takeIf { dates.size > 1 && repeatRule == null }
        val added = dates.sorted().mapIndexed { index, date ->
            TaskEntity(
                seriesId = if (repeatRule != null) UUID.randomUUID().toString() else sharedSeries,
                title = cleanTitle,
                note = note.trim(),
                localDate = date.toString(),
                timeMinutes = timeMinutes,
                important = important,
                color = color,
                repeatRule = repeatRule,
                repeatAnchor = date.toString().takeIf { repeatRule != null },
                reminderMinutesBefore = reminderMinutesBefore,
                notifyAtStart = notifyAtStart,
                reminderSound = reminderSound,
                notifyAllUsers = notifyAllUsers,
                notifyUserIds = TaskEntity.encodeNotificationUsers(notifyUserIds),
                revisionVector = RevisionVector.initial(revisionDevice),
                orderKey = now + index,
                createdAt = now,
                updatedAt = now
            )
        }
        dao.upsertAll(added)
        added.forEach(reminders::schedule)
        onLocalChange()
    }

    suspend fun addUnscheduled(
        title: String,
        note: String,
        timeMinutes: Int?,
        important: Boolean,
        color: Long?,
        reminderMinutesBefore: Int?,
        notifyAtStart: Boolean,
        reminderSound: String,
        notifyAllUsers: Boolean,
        notifyUserIds: Set<String>
    ) {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return
        val now = System.currentTimeMillis()
        val task = TaskEntity(
            title = cleanTitle,
            note = note.trim(),
            localDate = "",
            timeMinutes = timeMinutes,
            important = important,
            color = color,
            reminderMinutesBefore = reminderMinutesBefore,
            notifyAtStart = notifyAtStart,
            reminderSound = reminderSound,
            notifyAllUsers = notifyAllUsers,
            notifyUserIds = TaskEntity.encodeNotificationUsers(notifyUserIds),
            revisionVector = RevisionVector.initial(revisionDevice),
            orderKey = now,
            createdAt = now,
            updatedAt = now
        )
        dao.upsert(task)
        reminders.cancel(task.id)
        onLocalChange()
    }

    suspend fun moveToTrash(ids: Set<String>) {
        if (ids.isNotEmpty()) {
            dao.moveToTrash(ids, System.currentTimeMillis(), revisionDevice)
            ids.forEach(reminders::cancel)
            onLocalChange()
        }
    }

    suspend fun restore(ids: Set<String>) {
        if (ids.isNotEmpty()) {
            dao.restore(ids, System.currentTimeMillis(), revisionDevice)
            ids.mapNotNull { dao.find(it) }.forEach(reminders::schedule)
            onLocalChange()
        }
    }

    suspend fun deleteForever(ids: Set<String>) {
        if (ids.isNotEmpty()) {
            dao.deleteForever(ids, System.currentTimeMillis())
            ids.forEach(reminders::cancel)
            onLocalChange()
        }
    }

    suspend fun toggleCompleted(task: TaskEntity) {
        val now = System.currentTimeMillis()
        val completed = if (task.completedAt == null) now else null
        if (completed != null) {
            val next = buildNextOccurrence(task, now)
            val inserted = dao.completeAndAddNext(task.id, now, revisionDevice, next)
            reminders.cancel(task.id)
            inserted?.let(reminders::schedule)
        } else {
            dao.setCompleted(task.id, null, now, revisionDevice)
            dao.find(task.id)?.let(reminders::schedule)
        }
        onLocalChange()
    }

    suspend fun repeatToday(task: TaskEntity) {
        val repeated = task.copy(
            localDate = LocalDate.now().toString(),
            completedAt = null,
            deletedAt = null,
            updatedAt = System.currentTimeMillis(),
            revisionVector = RevisionVector.bump(task.revisionVector, revisionDevice)
        )
        dao.upsert(repeated)
        reminders.schedule(repeated)
        onLocalChange()
    }

    suspend fun move(task: TaskEntity, date: LocalDate?, targetIndex: Int) {
        val targetDate = date?.toString().orEmpty()
        val target = dao.snapshotTasks()
            .filter { it.deletedAt == null && it.localDate == targetDate && it.id != task.id }
            .sortedWith(
                compareBy<TaskEntity> { it.color == null }
                    .thenBy { it.timeMinutes == null }
                    .thenBy { it.timeMinutes }
                    .thenBy { it.orderKey }
            )
            .toMutableList()
        val moved = task.copy(
            localDate = targetDate,
            repeatAnchor = task.repeatAnchor ?: date?.toString()?.takeIf { task.repeatRule != null },
            completedAt = null,
            deletedAt = null
        )
        target.add(targetIndex.coerceIn(0, target.size), moved)
        val now = System.currentTimeMillis()
        val reordered = target.mapIndexed { index, value ->
            value.copy(
                orderKey = (index + 1L) * 1_000L,
                updatedAt = now + index,
                revisionVector = RevisionVector.bump(value.revisionVector, revisionDevice)
            )
        }
        dao.upsertAll(reordered)
        reordered.firstOrNull { it.id == task.id }?.let {
            if (date == null) reminders.cancel(it.id) else reminders.schedule(it)
        }
        onLocalChange()
    }

    private fun buildNextOccurrence(task: TaskEntity, now: Long): TaskEntity? {
        val nextDate = task.nextOccurrenceDate() ?: return null
        val anchor = task.repeatAnchor?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: task.dateOrNull() ?: return null
        val series = task.seriesId ?: task.id
        val nextId = UUID.nameUUIDFromBytes("mycalendar-v2/$series/$nextDate".toByteArray(StandardCharsets.UTF_8)).toString()
        return task.copy(
            id = nextId,
            seriesId = series,
            localDate = nextDate.toString(),
            repeatAnchor = anchor.toString(),
            completedAt = null,
            deletedAt = null,
            orderKey = now,
            createdAt = now,
            updatedAt = now,
            revisionVector = RevisionVector.initial(revisionDevice)
        )
    }

}
