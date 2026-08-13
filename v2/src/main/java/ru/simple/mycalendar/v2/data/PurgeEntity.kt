package ru.simple.mycalendar.v2.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A permanent-delete tombstone prevents an older phone from resurrecting the task. */
@Entity(tableName = "purges")
data class PurgeEntity(
    @PrimaryKey val taskId: String,
    val purgedAt: Long
)
