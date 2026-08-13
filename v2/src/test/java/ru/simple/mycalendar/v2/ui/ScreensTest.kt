package ru.simple.mycalendar.v2.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.simple.mycalendar.v2.data.TaskEntity

class ScreensTest {
    @Test
    fun `actual task date includes weekday and time`() {
        val task = TaskEntity(title = "Дело", localDate = "2026-12-16", timeMinutes = 16 * 60 + 19)

        assertEquals("16.12.2026, ср., 16:19", task.actualListDateText())
    }

    @Test
    fun `actual task date keeps leading zeroes without time`() {
        val task = TaskEntity(title = "Дело", localDate = "2026-11-01")

        assertEquals("01.11.2026, вс.", task.actualListDateText())
    }
}
