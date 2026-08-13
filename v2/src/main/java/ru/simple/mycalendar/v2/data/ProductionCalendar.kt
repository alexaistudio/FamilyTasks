package ru.simple.mycalendar.v2.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.MonthDay
import java.util.concurrent.ConcurrentHashMap

enum class DayKind { WORKDAY, WEEKEND, HOLIDAY, TRANSFERRED_OFF, SHORTENED }

data class ProductionCalendarSource(val title: String, val url: String)

/**
 * Federal Russian production calendar for a five-day Monday-Friday work week.
 *
 * Statutory holidays come from article 112 of the Labour Code. Year-specific
 * transfers are embedded only after the corresponding Government resolution
 * is officially published. The calendar itself never makes network requests.
 */
object ProductionCalendar {
    private val federalHolidays = buildList {
        (1..8).forEach { add(MonthDay.of(Month.JANUARY, it)) }
        add(MonthDay.of(Month.FEBRUARY, 23))
        add(MonthDay.of(Month.MARCH, 8))
        add(MonthDay.of(Month.MAY, 1))
        add(MonthDay.of(Month.MAY, 9))
        add(MonthDay.of(Month.JUNE, 12))
        add(MonthDay.of(Month.NOVEMBER, 4))
    }.toSet()

    private val sources = mapOf(
        2025 to ProductionCalendarSource(
            "Постановление Правительства РФ от 04.10.2024 № 1335",
            "https://government.ru/docs/all/155500/"
        ),
        2026 to ProductionCalendarSource(
            "Постановление Правительства РФ от 24.09.2025 № 1466",
            "https://publication.pravo.gov.ru/document/0001202509240023"
        )
    )

    private val special = buildMap {
        // Government resolution No. 1335 for 2025.
        putDays(
            DayKind.TRANSFERRED_OFF,
            "2025-05-02", "2025-05-08", "2025-06-13", "2025-11-03", "2025-12-31"
        )
        putDays(DayKind.SHORTENED, "2025-03-07", "2025-04-30", "2025-06-11", "2025-11-01")

        // Government resolution No. 1466 and article 112 transfers for 2026.
        putDays(DayKind.TRANSFERRED_OFF, "2026-01-09", "2026-03-09", "2026-05-11", "2026-12-31")
        putDays(DayKind.SHORTENED, "2026-04-30", "2026-05-08", "2026-06-11", "2026-11-03")
    }

    private val preliminaryTransfers = ConcurrentHashMap<Int, Set<LocalDate>>()

    fun kindOf(date: LocalDate): DayKind {
        special[date]?.let { return it }
        if (MonthDay.from(date) in federalHolidays) return DayKind.HOLIDAY
        if (isPreliminary(date.year) && date in defaultTransfers(date.year)) return DayKind.TRANSFERRED_OFF
        if (isPreliminary(date.year) && isDefaultShortened(date)) return DayKind.SHORTENED
        return when (date.dayOfWeek) {
            DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> DayKind.WEEKEND
            else -> DayKind.WORKDAY
        }
    }

    fun source(year: Int): ProductionCalendarSource? = sources[year]

    fun isPreliminary(year: Int): Boolean = source(year) == null

    fun statusText(year: Int): String = source(year)?.let {
        "РФ, пятидневка · подтверждён · постановление № ${it.title.substringAfterLast("№ ")}"
    } ?: "РФ, пятидневка · предварительный: переносы ещё не утверждены"

    private fun isDefaultShortened(date: LocalDate): Boolean =
        date.dayOfWeek != DayOfWeek.SATURDAY &&
            date.dayOfWeek != DayOfWeek.SUNDAY &&
            MonthDay.from(date.plusDays(1)) in federalHolidays

    private fun defaultTransfers(year: Int): Set<LocalDate> = preliminaryTransfers.getOrPut(year) {
        federalHolidays.asSequence()
            .filterNot { it.month == Month.JANUARY }
            .map { it.atYear(year) }
            .filter { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY }
            .map { holiday ->
                var candidate = holiday.plusDays(1)
                while (
                    candidate.dayOfWeek == DayOfWeek.SATURDAY ||
                    candidate.dayOfWeek == DayOfWeek.SUNDAY ||
                    MonthDay.from(candidate) in federalHolidays
                ) {
                    candidate = candidate.plusDays(1)
                }
                candidate
            }
            .toSet()
    }

    private fun MutableMap<LocalDate, DayKind>.putDays(kind: DayKind, vararg dates: String) {
        dates.forEach { put(LocalDate.parse(it), kind) }
    }
}
