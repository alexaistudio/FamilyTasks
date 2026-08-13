package ru.simple.mycalendar.v2.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TaskEntityTest {
    @Test
    fun overdueIsDerivedFromCurrentDateInsteadOfStoredFlag() {
        val today = LocalDate.of(2026, 8, 8)
        val yesterday = TaskEntity(title = "Дело", localDate = "2026-08-07")
        assertTrue(yesterday.isOverdue(today))
        assertFalse(yesterday.copy(localDate = today.toString()).isOverdue(today))
        assertFalse(yesterday.copy(completedAt = 1L).isOverdue(today))
        assertFalse(yesterday.copy(localDate = "").isOverdue(today))
    }

    @Test
    fun recurrenceKeepsOriginalDayAcrossShortMonths() {
        val january = TaskEntity(
            title = "Отчёт",
            localDate = "2026-01-31",
            repeatRule = RepeatRule.MONTHLY.value,
            repeatAnchor = "2026-01-31"
        )
        val february = january.copy(localDate = january.nextOccurrenceDate().toString())
        assertTrue(february.localDate == "2026-02-28")
        assertTrue(february.nextOccurrenceDate().toString() == "2026-03-31")

        val leapDay = january.copy(
            localDate = "2028-02-29",
            repeatRule = RepeatRule.YEARLY.value,
            repeatAnchor = "2028-02-29"
        )
        assertTrue(leapDay.nextOccurrenceDate().toString() == "2029-02-28")
    }

    @Test
    fun russianCalendarKeepsShortDaySeparateFromHoliday() {
        assertTrue(ProductionCalendar.kindOf(LocalDate.of(2026, 6, 11)) == DayKind.SHORTENED)
        assertTrue(ProductionCalendar.kindOf(LocalDate.of(2026, 6, 12)) == DayKind.HOLIDAY)
    }

    @Test
    fun notificationTargetsUseStableIdsInsteadOfNames() {
        val addressed = TaskEntity(
            title = "Семейное дело",
            localDate = "2026-08-08",
            notifyAllUsers = false,
            notifyUserIds = TaskEntity.encodeNotificationUsers(setOf("stable-b", "stable-a"))
        )
        assertTrue(addressed.shouldNotifyUser("stable-a"))
        assertFalse(addressed.shouldNotifyUser("renamed-user"))
        assertTrue(addressed.copy(notifyAllUsers = true).shouldNotifyUser("new-family-member"))
    }
}
