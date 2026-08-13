package ru.simple.mycalendar.v2.data

enum class RevisionRelation { LOCAL_NEWER, REMOTE_NEWER, SAME, CONCURRENT }

object RevisionVector {
    fun initial(device: String): String = encode(mapOf(device to 1L))

    fun bump(vector: String, device: String): String {
        val values = parse(vector).toMutableMap()
        values[device] = (values[device] ?: 0L) + 1L
        return encode(values)
    }

    fun compare(local: TaskEntity, remote: TaskEntity): RevisionRelation {
        return compare(local.revisionVector, local.updatedAt, remote.revisionVector, remote.updatedAt)
    }

    /** Causal union of two vectors: dominates both parents, deterministic on all phones. */
    fun join(first: String, second: String): String {
        val merged = parse(first).toMutableMap()
        parse(second).forEach { (device, counter) ->
            merged[device] = maxOf(merged[device] ?: 0L, counter)
        }
        return encode(merged)
    }

    fun compare(
        localVector: String,
        localUpdatedAt: Long,
        remoteVector: String,
        remoteUpdatedAt: Long
    ): RevisionRelation {
        val left = parse(localVector)
        val right = parse(remoteVector)
        if (left.isEmpty() && right.isNotEmpty()) return RevisionRelation.REMOTE_NEWER
        if (left.isNotEmpty() && right.isEmpty()) return RevisionRelation.LOCAL_NEWER
        if (left.isEmpty() && right.isEmpty()) {
            return when {
                remoteUpdatedAt > localUpdatedAt -> RevisionRelation.REMOTE_NEWER
                remoteUpdatedAt < localUpdatedAt -> RevisionRelation.LOCAL_NEWER
                else -> RevisionRelation.SAME
            }
        }
        val keys = left.keys + right.keys
        val leftDominates = keys.all { (left[it] ?: 0L) >= (right[it] ?: 0L) }
        val rightDominates = keys.all { (right[it] ?: 0L) >= (left[it] ?: 0L) }
        return when {
            leftDominates && rightDominates -> when {
                remoteUpdatedAt > localUpdatedAt -> RevisionRelation.REMOTE_NEWER
                remoteUpdatedAt < localUpdatedAt -> RevisionRelation.LOCAL_NEWER
                else -> RevisionRelation.SAME
            }
            leftDominates -> RevisionRelation.LOCAL_NEWER
            rightDominates -> RevisionRelation.REMOTE_NEWER
            else -> RevisionRelation.CONCURRENT
        }
    }

    private fun parse(value: String): Map<String, Long> {
        if (value.isBlank()) return emptyMap()
        return value.split(';').mapNotNull { part ->
            val separator = part.lastIndexOf('=')
            if (separator <= 0) return@mapNotNull null
            val device = part.substring(0, separator)
            val counter = part.substring(separator + 1).toLongOrNull() ?: return@mapNotNull null
            if (counter < 1) null else device to counter
        }.toMap()
    }

    private fun encode(values: Map<String, Long>): String = values.entries
        .filter { it.key.isNotBlank() && it.value > 0 }
        .sortedBy { it.key }
        .joinToString(";") { "${it.key}=${it.value}" }
}

/**
 * Resolves a concurrent pair of active versions that differ ONLY by the
 * completion flag (checkbox toggled offline on both phones): completion wins,
 * the joined vector causally dominates both originals, so every phone computes
 * the same result and converges. Returns null when anything else differs —
 * such pairs remain real content conflicts handled by the caller.
 */
fun mergeCompletionOnlyConflict(first: TaskEntity, second: TaskEntity): TaskEntity? {
    if (first.deletedAt != null || second.deletedAt != null) return null
    val stripped = first.copy(completedAt = null, revisionVector = "", updatedAt = 0L)
    if (stripped != second.copy(completedAt = null, revisionVector = "", updatedAt = 0L)) return null
    val winner = if (first.revisionVector >= second.revisionVector) first else second
    return winner.copy(
        completedAt = listOfNotNull(first.completedAt, second.completedAt).maxOrNull(),
        revisionVector = RevisionVector.join(first.revisionVector, second.revisionVector),
        updatedAt = maxOf(first.updatedAt, second.updatedAt)
    )
}
