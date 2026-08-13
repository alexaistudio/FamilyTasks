package ru.simple.mycalendar.v2.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@Entity(
    tableName = "tasks",
    indices = [Index("localDate"), Index("deletedAt"), Index("seriesId")]
)
data class TaskEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val seriesId: String? = null,
    val title: String,
    val note: String = "",
    val localDate: String,
    val timeMinutes: Int? = null,
    val important: Boolean = false,
    val color: Long? = null,
    val repeatRule: String? = null,
    val repeatAnchor: String? = null,
    val reminderMinutesBefore: Int? = 15,
    val notifyAtStart: Boolean = true,
    val reminderSound: String = "normal",
    val notifyAllUsers: Boolean = true,
    val notifyUserIds: String = "",
    val revisionVector: String = "",
    val completedAt: Long? = null,
    val orderKey: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null
) {
    fun dateOrNull(): LocalDate? = localDate.takeIf { it.isNotBlank() }?.let(LocalDate::parse)

    fun isUnscheduled(): Boolean = localDate.isBlank()

    fun isOverdue(today: LocalDate = LocalDate.now()): Boolean =
        completedAt == null && deletedAt == null && dateOrNull()?.isBefore(today) == true

    fun notificationUsers(): Set<String> = notifyUserIds.split(';').filterTo(linkedSetOf()) { it.isNotBlank() }

    fun shouldNotifyUser(userId: String): Boolean = notifyAllUsers || userId in notificationUsers()

    companion object {
        fun encodeNotificationUsers(ids: Set<String>): String = ids.filter { it.isNotBlank() }.sorted().joinToString(";")
    }
}

enum class RepeatRule(val value: String, val title: String) {
    DAILY("daily", "Каждый день"),
    WEEKLY("weekly", "Каждую неделю"),
    MONTHLY("monthly", "Каждый месяц"),
    YEARLY("yearly", "Каждый год");

    companion object {
        fun from(value: String?): RepeatRule? = entries.firstOrNull { it.value == value }
    }
}

fun TaskEntity.nextOccurrenceDate(): LocalDate? {
    val rule = RepeatRule.from(repeatRule) ?: return null
    val current = dateOrNull() ?: return null
    val anchor = repeatAnchor?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: current
    return when (rule) {
        RepeatRule.DAILY -> current.plusDays(1)
        RepeatRule.WEEKLY -> current.plusWeeks(1)
        RepeatRule.MONTHLY -> YearMonth.from(current).plusMonths(1).let {
            it.atDay(anchor.dayOfMonth.coerceAtMost(it.lengthOfMonth()))
        }
        RepeatRule.YEARLY -> YearMonth.of(current.year + 1, anchor.month).let {
            it.atDay(anchor.dayOfMonth.coerceAtMost(it.lengthOfMonth()))
        }
    }
}
