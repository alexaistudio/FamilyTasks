package ru.simple.mycalendar.v2.sync

import org.json.JSONArray
import org.json.JSONObject
import ru.simple.mycalendar.v2.data.PurgeEntity
import ru.simple.mycalendar.v2.data.TaskEntity
import ru.simple.mycalendar.v2.data.UserProfileEntity

data class SyncSnapshot(
    val tasks: List<TaskEntity>,
    val purges: List<PurgeEntity>,
    val profiles: List<UserProfileEntity> = emptyList()
)

object SnapshotCodec {
    fun encode(snapshot: SyncSnapshot): ByteArray {
        val root = JSONObject().put("version", 1)
        root.put("tasks", JSONArray().apply { snapshot.tasks.forEach { put(taskToJson(it)) } })
        root.put("purges", JSONArray().apply {
            snapshot.purges.forEach { put(JSONObject().put("taskId", it.taskId).put("purgedAt", it.purgedAt)) }
        })
        root.put("profiles", JSONArray().apply { snapshot.profiles.forEach { put(profileToJson(it)) } })
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): SyncSnapshot {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("version") == 1) { "Неподдерживаемая версия снимка" }
        val tasksJson = root.getJSONArray("tasks")
        val tasks = List(tasksJson.length()) { jsonToTask(tasksJson.getJSONObject(it)) }
        val purgesJson = root.optJSONArray("purges") ?: JSONArray()
        val purges = List(purgesJson.length()) {
            val json = purgesJson.getJSONObject(it)
            PurgeEntity(json.getString("taskId"), json.getLong("purgedAt"))
        }
        val profilesJson = root.optJSONArray("profiles") ?: JSONArray()
        val profiles = List(profilesJson.length()) { jsonToProfile(profilesJson.getJSONObject(it)) }
        return SyncSnapshot(tasks, purges, profiles)
    }

    private fun taskToJson(task: TaskEntity) = JSONObject()
        .put("id", task.id)
        .put("seriesId", task.seriesId)
        .put("title", task.title)
        .put("note", task.note)
        .put("localDate", task.localDate)
        .put("timeMinutes", task.timeMinutes)
        .put("important", task.important)
        .put("color", task.color)
        .put("repeatRule", task.repeatRule)
        .put("repeatAnchor", task.repeatAnchor)
        .put("reminderMinutesBefore", task.reminderMinutesBefore)
        .put("notifyAtStart", task.notifyAtStart)
        .put("reminderSound", task.reminderSound)
        .put("notifyAllUsers", task.notifyAllUsers)
        .put("notifyUserIds", task.notifyUserIds)
        .put("revisionVector", task.revisionVector)
        .put("completedAt", task.completedAt)
        .put("orderKey", task.orderKey)
        .put("createdAt", task.createdAt)
        .put("updatedAt", task.updatedAt)
        .put("deletedAt", task.deletedAt)

    private fun jsonToTask(json: JSONObject) = TaskEntity(
        id = json.getString("id"),
        seriesId = json.nullableString("seriesId"),
        title = json.getString("title"),
        note = json.optString("note", ""),
        localDate = json.getString("localDate"),
        timeMinutes = json.nullableInt("timeMinutes"),
        important = json.optBoolean("important", false),
        color = json.nullableLong("color"),
        repeatRule = json.nullableString("repeatRule"),
        repeatAnchor = json.nullableString("repeatAnchor"),
        reminderMinutesBefore = json.nullableInt("reminderMinutesBefore"),
        notifyAtStart = json.optBoolean("notifyAtStart", true),
        reminderSound = json.optString("reminderSound", "normal"),
        notifyAllUsers = json.optBoolean("notifyAllUsers", true),
        notifyUserIds = json.optString("notifyUserIds", ""),
        revisionVector = json.optString("revisionVector", ""),
        completedAt = json.nullableLong("completedAt"),
        orderKey = json.getLong("orderKey"),
        createdAt = json.getLong("createdAt"),
        updatedAt = json.getLong("updatedAt"),
        deletedAt = json.nullableLong("deletedAt")
    )

    private fun profileToJson(profile: UserProfileEntity) = JSONObject()
        .put("id", profile.id)
        .put("displayName", profile.displayName)
        .put("revisionVector", profile.revisionVector)
        .put("createdAt", profile.createdAt)
        .put("updatedAt", profile.updatedAt)

    private fun jsonToProfile(json: JSONObject) = UserProfileEntity(
        id = json.getString("id"),
        displayName = json.optString("displayName", ""),
        revisionVector = json.optString("revisionVector", ""),
        createdAt = json.getLong("createdAt"),
        updatedAt = json.getLong("updatedAt")
    )

    private fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else getString(name)
    private fun JSONObject.nullableLong(name: String): Long? = if (isNull(name)) null else getLong(name)
    private fun JSONObject.nullableInt(name: String): Int? = if (isNull(name)) null else getInt(name)
}
