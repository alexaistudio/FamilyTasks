package ru.simple.mycalendar.v2.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun semanticVersionsAreComparedNumerically() {
        assertTrue(AppUpdater.compareVersions("2.0.10", "2.0.9") > 0)
        assertTrue(AppUpdater.compareVersions("3.0", "2.99.99") > 0)
        assertEquals(0, AppUpdater.compareVersions("2.0.9", "2.0.9"))
    }

    @Test
    fun downloadProgressIsClampedAndRendered() {
        assertEquals(0, AppUpdater.progressPercent(-1L, 100L))
        assertEquals(50, AppUpdater.progressPercent(50L, 100L))
        assertEquals(100, AppUpdater.progressPercent(150L, 100L))
        assertEquals("[#####-----] 50%", AppUpdater.asciiProgress(50L, 100L, width = 10))
    }
}
