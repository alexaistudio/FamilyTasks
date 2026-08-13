package ru.simple.mycalendar.v2.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProductionCalendarTest {
    @Test
    fun `2025 matches government resolution 1335`() {
        assertEquals(DayKind.SHORTENED, ProductionCalendar.kindOf(LocalDate.of(2025, 11, 1)))
        assertEquals(DayKind.TRANSFERRED_OFF, ProductionCalendar.kindOf(LocalDate.of(2025, 11, 3)))
        assertEquals(DayKind.WORKDAY, ProductionCalendar.kindOf(LocalDate.of(2025, 2, 24)))
        assertEquals(DayKind.WORKDAY, ProductionCalendar.kindOf(LocalDate.of(2025, 3, 10)))
        assertYearTotals(year = 2025, workdays = 247, daysOff = 118, shortened = 4)
    }

    @Test
    fun `2026 matches government resolution 1466`() {
        assertEquals(DayKind.TRANSFERRED_OFF, ProductionCalendar.kindOf(LocalDate.of(2026, 1, 9)))
        assertEquals(DayKind.TRANSFERRED_OFF, ProductionCalendar.kindOf(LocalDate.of(2026, 3, 9)))
        assertEquals(DayKind.TRANSFERRED_OFF, ProductionCalendar.kindOf(LocalDate.of(2026, 5, 11)))
        assertEquals(DayKind.TRANSFERRED_OFF, ProductionCalendar.kindOf(LocalDate.of(2026, 12, 31)))
        assertYearTotals(year = 2026, workdays = 247, daysOff = 118, shortened = 4)
    }

    @Test
    fun `unconfirmed years keep statutory holidays but stay preliminary`() {
        assertTrue(ProductionCalendar.isPreliminary(2027))
        assertEquals(DayKind.HOLIDAY, ProductionCalendar.kindOf(LocalDate.of(2027, 1, 1)))
        assertEquals(DayKind.SHORTENED, ProductionCalendar.kindOf(LocalDate.of(2027, 2, 22)))
        assertEquals(DayKind.TRANSFERRED_OFF, ProductionCalendar.kindOf(LocalDate.of(2027, 5, 3)))
        assertTrue(ProductionCalendar.statusText(2027).contains("предварительный"))
    }

    @Test
    fun `confirmed years expose their official source`() {
        assertFalse(ProductionCalendar.isPreliminary(2025))
        assertFalse(ProductionCalendar.isPreliminary(2026))
        assertNotNull(ProductionCalendar.source(2025))
        assertNotNull(ProductionCalendar.source(2026))
    }

    private fun assertYearTotals(year: Int, workdays: Int, daysOff: Int, shortened: Int) {
        val days = generateSequence(LocalDate.of(year, 1, 1)) { current ->
            current.plusDays(1).takeIf { it.year == year }
        }.map(ProductionCalendar::kindOf).toList()
        assertEquals(workdays, days.count { it == DayKind.WORKDAY || it == DayKind.SHORTENED })
        assertEquals(daysOff, days.count { it == DayKind.WEEKEND || it == DayKind.HOLIDAY || it == DayKind.TRANSFERRED_OFF })
        assertEquals(shortened, days.count { it == DayKind.SHORTENED })
    }
}
