package ru.simple.mycalendar.v2.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.simple.mycalendar.v2.data.TaskEntity

class ScreensTest {

    @Test
    fun daySheetDismissesAfterCrossingDragThreshold() {
        assertEquals(true, shouldDismissDaySheet(72f, 0f, 72f))
        assertEquals(false, shouldDismissDaySheet(71f, 0f, 72f))
    }

    @Test
    fun daySheetDismissesOnFastDownwardSwipeOnly() {
        assertEquals(true, shouldDismissDaySheet(1f, 1_200f, 72f))
        assertEquals(false, shouldDismissDaySheet(1f, -1_200f, 72f))
    }

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
