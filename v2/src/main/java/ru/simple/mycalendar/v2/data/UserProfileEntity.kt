package ru.simple.mycalendar.v2.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A family member has a stable internal id and a freely changeable display name.
 * Tasks target the id, so renaming a member never breaks notification routing.
 */
@Entity(tableName = "user_profiles")
data class UserProfileEntity(
    @PrimaryKey val id: String,
    val displayName: String = "",
    val revisionVector: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

fun UserProfileEntity.resolvedName(allProfiles: List<UserProfileEntity>): String {
    val custom = displayName.trim()
    if (custom.isNotEmpty()) return custom
    val index = allProfiles.sortedWith(compareBy<UserProfileEntity> { it.createdAt }.thenBy { it.id })
        .indexOfFirst { it.id == id }
        .coerceAtLeast(0)
    return "Пользователь ${index + 1}"
}
