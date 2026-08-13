package ru.simple.mycalendar.v2.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import java.nio.charset.StandardCharsets
import java.util.UUID

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE deletedAt IS NULL ORDER BY localDate, color IS NULL, timeMinutes IS NULL, timeMinutes, orderKey, createdAt")
    fun observeActive(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<TaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tasks: List<TaskEntity>)

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun find(id: String): TaskEntity?

    @Query("SELECT * FROM tasks ORDER BY id")
    suspend fun snapshotTasks(): List<TaskEntity>

    @Query("SELECT * FROM purges ORDER BY taskId")
    suspend fun snapshotPurges(): List<PurgeEntity>

    @Query("SELECT * FROM user_profiles ORDER BY id")
    fun observeProfiles(): Flow<List<UserProfileEntity>>

    @Query("SELECT * FROM user_profiles WHERE id = :id LIMIT 1")
    suspend fun findProfile(id: String): UserProfileEntity?

    @Query("SELECT * FROM user_profiles ORDER BY id")
    suspend fun snapshotProfiles(): List<UserProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: UserProfileEntity)

    @Transaction
    suspend fun moveToTrash(ids: Collection<String>, now: Long, device: String) {
        upsertAll(ids.mapNotNull { find(it) }.map {
            it.copy(
                deletedAt = now,
                updatedAt = now,
                revisionVector = RevisionVector.bump(it.revisionVector, device)
            )
        })
    }

    @Transaction
    suspend fun restore(ids: Collection<String>, now: Long, device: String) {
        upsertAll(ids.mapNotNull { find(it) }.map {
            it.copy(
                deletedAt = null,
                updatedAt = now,
                revisionVector = RevisionVector.bump(it.revisionVector, device)
            )
        })
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPurges(purges: List<PurgeEntity>)

    @Query("DELETE FROM tasks WHERE id IN (:ids)")
    suspend fun deleteAny(ids: Collection<String>)

    @Transaction
    suspend fun deleteForever(ids: Collection<String>, now: Long) {
        upsertPurges(ids.map { PurgeEntity(it, now) })
        deleteAny(ids)
    }

    @Transaction
    suspend fun setCompleted(id: String, completedAt: Long?, now: Long, device: String) {
        val task = find(id) ?: return
        upsert(
            task.copy(
                completedAt = completedAt,
                updatedAt = now,
                revisionVector = RevisionVector.bump(task.revisionVector, device)
            )
        )
    }

    @Transaction
    suspend fun completeAndAddNext(id: String, now: Long, device: String, next: TaskEntity?): TaskEntity? {
        setCompleted(id, now, now, device)
        if (next == null || find(next.id) != null) return null
        upsert(next)
        return next
    }

    @Transaction
    suspend fun mergeSnapshot(
        tasks: List<TaskEntity>,
        purges: List<PurgeEntity>,
        profiles: List<UserProfileEntity> = emptyList()
    ) {
        val localPurges = snapshotPurges().associateBy { it.taskId }
        val remotePurges = purges.associateBy { it.taskId }
        val mergedPurges = (localPurges.keys + remotePurges.keys).map { id ->
            val local = localPurges[id]
            val remote = remotePurges[id]
            if ((local?.purgedAt ?: Long.MIN_VALUE) >= (remote?.purgedAt ?: Long.MIN_VALUE)) local!! else remote!!
        }
        if (mergedPurges.isNotEmpty()) upsertPurges(mergedPurges)

        tasks.forEach { remote ->
            val purgeAt = mergedPurges.firstOrNull { it.taskId == remote.id }?.purgedAt
            val local = find(remote.id)
            if (purgeAt != null && purgeAt >= remote.updatedAt) {
                deleteAny(listOf(remote.id))
            } else if (local == null) {
                upsert(remote)
            } else {
                mergeVersioned(local, remote)
            }
        }
        if (mergedPurges.isNotEmpty()) deleteAny(mergedPurges.map { it.taskId })

        profiles.forEach { remote ->
            val local = findProfile(remote.id)
            if (local == null) {
                upsertProfile(remote)
            } else {
                when (RevisionVector.compare(
                    local.revisionVector,
                    local.updatedAt,
                    remote.revisionVector,
                    remote.updatedAt
                )) {
                    RevisionRelation.REMOTE_NEWER -> upsertProfile(remote)
                    RevisionRelation.CONCURRENT -> {
                        if (remote.revisionVector > local.revisionVector) upsertProfile(remote)
                    }
                    RevisionRelation.LOCAL_NEWER, RevisionRelation.SAME -> Unit
                }
            }
        }
    }

    private suspend fun mergeVersioned(local: TaskEntity, remote: TaskEntity) {
        when (RevisionVector.compare(local, remote)) {
            RevisionRelation.REMOTE_NEWER -> upsert(remote)
            RevisionRelation.CONCURRENT -> mergeConcurrent(local, remote)
            RevisionRelation.LOCAL_NEWER, RevisionRelation.SAME -> Unit
        }
    }

    private suspend fun mergeConcurrent(first: TaskEntity, second: TaskEntity) {
        val firstDeleted = first.deletedAt != null
        val secondDeleted = second.deletedAt != null
        when {
            firstDeleted.xor(secondDeleted) -> {
                val deleted = if (firstDeleted) first else second
                val active = if (firstDeleted) second else first
                upsert(deleted)
                upsertConflictCopy(active)
            }
            firstDeleted && secondDeleted -> upsert(maxByVector(first, second))
            else -> {
                val completionMerged = mergeCompletionOnlyConflict(first, second)
                if (completionMerged != null) {
                    upsert(completionMerged)
                } else {
                    val winner = maxByVector(first, second)
                    val loser = if (winner === first) second else first
                    upsert(winner)
                    upsertConflictCopy(loser)
                }
            }
        }
    }

    private suspend fun upsertConflictCopy(source: TaskEntity) {
        val conflictId = UUID.nameUUIDFromBytes(
            "mycalendar-v2/conflict/${source.id}/${source.revisionVector}"
                .toByteArray(StandardCharsets.UTF_8)
        ).toString()
        if (find(conflictId) != null) return
        upsert(
            source.copy(
                id = conflictId,
                seriesId = null,
                title = source.title + " (конфликт)",
                repeatRule = null,
                repeatAnchor = null,
                orderKey = source.orderKey + 1,
                deletedAt = null
            )
        )
    }

    private fun maxByVector(first: TaskEntity, second: TaskEntity): TaskEntity =
        if (first.revisionVector >= second.revisionVector) first else second
}
