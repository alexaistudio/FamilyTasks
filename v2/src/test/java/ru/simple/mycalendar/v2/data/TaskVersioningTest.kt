package ru.simple.mycalendar.v2.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskVersioningTest {
    @Test
    fun anyNumberOfOfflineDeleteAndEditOperationsStayConcurrent() {
        val base = TaskEntity(
            id = "same", title = "Дело", localDate = "2026-08-08",
            revisionVector = "origin=1", updatedAt = 100
        )
        val deletedTwice = base.copy(
            deletedAt = 1_000,
            revisionVector = RevisionVector.bump(RevisionVector.bump(base.revisionVector, "phone-a"), "phone-a"),
            updatedAt = 1_000
        )
        val editedWithWrongClock = base.copy(
            title = "Исправлено",
            revisionVector = RevisionVector.bump(base.revisionVector, "phone-b"),
            updatedAt = 10
        )
        assertEquals(RevisionRelation.CONCURRENT, RevisionVector.compare(deletedTwice, editedWithWrongClock))
    }

    @Test
    fun causalRestoreDominatesDeletionRegardlessOfWallClock() {
        val deleted = TaskEntity(
            id = "same", title = "Дело", localDate = "2026-08-08", deletedAt = 1,
            revisionVector = "origin=1;phone-a=1", updatedAt = 100
        )
        val restored = deleted.copy(
            deletedAt = null,
            revisionVector = RevisionVector.bump(deleted.revisionVector, "phone-b"),
            updatedAt = 50
        )
        assertEquals(RevisionRelation.REMOTE_NEWER, RevisionVector.compare(deleted, restored))
    }

    @Test
    fun sevenOfflinePhonesProduceConcurrentChangesRegardlessOfTimestamps() {
        val base = "origin=1"
        val branches = (1..7).map { phone ->
            TaskEntity(
                id = "shared",
                title = "Правка телефона $phone",
                localDate = "2026-08-09",
                revisionVector = RevisionVector.bump(base, "phone-$phone"),
                updatedAt = if (phone % 2 == 0) 1L else Long.MAX_VALUE - phone
            )
        }

        branches.forEachIndexed { leftIndex, left ->
            branches.drop(leftIndex + 1).forEach { right ->
                assertEquals(RevisionRelation.CONCURRENT, RevisionVector.compare(left, right))
                assertEquals(RevisionRelation.CONCURRENT, RevisionVector.compare(right, left))
            }
        }
    }

    @Test
    fun joinedVectorDominatesBothParentsAndIsOrderIndependent() {
        val joined = RevisionVector.join("phone-a=3;origin=1", "phone-b=2;origin=1")
        assertEquals(joined, RevisionVector.join("phone-b=2;origin=1", "phone-a=3;origin=1"))
        val base = TaskEntity(id = "t", title = "Дело", localDate = "2026-08-10")
        val left = base.copy(revisionVector = "origin=1;phone-a=3")
        val right = base.copy(revisionVector = "origin=1;phone-b=2")
        val merged = base.copy(revisionVector = joined)
        assertEquals(RevisionRelation.REMOTE_NEWER, RevisionVector.compare(left, merged))
        assertEquals(RevisionRelation.REMOTE_NEWER, RevisionVector.compare(right, merged))
    }

    @Test
    fun concurrentCompletionToggleMergesWithoutConflictCopy() {
        val base = TaskEntity(
            id = "same", title = "Дело", localDate = "2026-08-10",
            revisionVector = "origin=1", updatedAt = 100
        )
        val doneOnA = base.copy(
            completedAt = 5_000,
            revisionVector = RevisionVector.bump(base.revisionVector, "phone-a"),
            updatedAt = 5_000
        )
        val toggledOnB = base.copy(
            completedAt = null,
            revisionVector = RevisionVector.bump(base.revisionVector, "phone-b"),
            updatedAt = 9_000
        )
        val merged = mergeCompletionOnlyConflict(doneOnA, toggledOnB)
        assertEquals(5_000L, merged?.completedAt)
        assertEquals(
            RevisionVector.join(doneOnA.revisionVector, toggledOnB.revisionVector),
            merged?.revisionVector
        )
        assertEquals(9_000L, merged?.updatedAt)
        assertEquals(merged, mergeCompletionOnlyConflict(toggledOnB, doneOnA))
    }

    @Test
    fun concurrentContentEditsAreNotTreatedAsCompletionOnly() {
        val base = TaskEntity(
            id = "same", title = "Дело", localDate = "2026-08-10",
            revisionVector = "origin=1", updatedAt = 100
        )
        val renamed = base.copy(
            title = "Переименовано",
            revisionVector = RevisionVector.bump(base.revisionVector, "phone-a")
        )
        val completed = base.copy(
            completedAt = 5_000,
            revisionVector = RevisionVector.bump(base.revisionVector, "phone-b")
        )
        assertEquals(null, mergeCompletionOnlyConflict(renamed, completed))
    }

    @Test
    fun deletedVersionIsNeverCompletionOnlyMerge() {
        val base = TaskEntity(
            id = "same", title = "Дело", localDate = "2026-08-10",
            revisionVector = "origin=1", updatedAt = 100
        )
        val deleted = base.copy(
            deletedAt = 7_000,
            revisionVector = RevisionVector.bump(base.revisionVector, "phone-a")
        )
        val completed = base.copy(
            completedAt = 5_000,
            revisionVector = RevisionVector.bump(base.revisionVector, "phone-b")
        )
        assertEquals(null, mergeCompletionOnlyConflict(deleted, completed))
    }
}
