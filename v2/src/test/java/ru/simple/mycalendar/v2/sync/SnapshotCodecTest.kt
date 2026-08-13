package ru.simple.mycalendar.v2.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.simple.mycalendar.v2.data.PurgeEntity
import ru.simple.mycalendar.v2.data.TaskEntity
import ru.simple.mycalendar.v2.data.UserProfileEntity

class SnapshotCodecTest {
    @Test
    fun allTaskAndTombstoneFieldsRoundTrip() {
        val original = SyncSnapshot(
            tasks = listOf(TaskEntity(
                id = "id", seriesId = "series", title = "Деревня", note = "Пять дней",
                localDate = "2026-08-08", timeMinutes = 845, important = true,
                color = 0xFF3F6FE5, repeatRule = "weekly", repeatAnchor = "2026-08-08",
                reminderMinutesBefore = 30, notifyAtStart = false, reminderSound = "loud",
                notifyAllUsers = false, notifyUserIds = "user-a;user-b",
                revisionVector = "phone-a=5",
                completedAt = null, orderKey = 7,
                createdAt = 8, updatedAt = 9, deletedAt = 10
            )),
            purges = listOf(PurgeEntity("gone", 11)),
            profiles = listOf(
                UserProfileEntity(
                    id = "user-a",
                    displayName = "Мама",
                    revisionVector = "phone-a=2",
                    createdAt = 12,
                    updatedAt = 13
                )
            )
        )
        val restored = SnapshotCodec.decode(SnapshotCodec.encode(original))
        assertEquals(original, restored)
    }
}
