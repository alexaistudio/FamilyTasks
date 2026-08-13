@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ru.simple.mycalendar.v2.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.simple.mycalendar.v2.EditorRequest
import ru.simple.mycalendar.v2.data.RepeatRule
import ru.simple.mycalendar.v2.data.TaskEntity
import ru.simple.mycalendar.v2.data.UserProfileEntity
import ru.simple.mycalendar.v2.data.resolvedName
import ru.simple.mycalendar.v2.notify.ReminderWorker
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val editorDate = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru"))
private val palette = listOf<Long?>(
    null,
    0xFF3F6FE5,
    0xFF009E9A,
    0xFF3E9B55,
    0xFFE78128,
    0xFFDA4C4C,
    0xFF8A5AD7
)

@Composable
fun TaskEditorDialog(
    request: EditorRequest,
    profiles: List<UserProfileEntity>,
    currentUserId: String,
    onDismiss: () -> Unit,
    onSave: (
        String, String, LocalDate?, Int?, Boolean, Long?, String?, Int?, Boolean, String, Boolean, Set<String>
    ) -> Unit,
    onDelete: (() -> Unit)?,
    onRepeatToday: (() -> Unit)?
) {
    val existing = request.task
    val context = LocalContext.current
    val initialDate = existing?.dateOrNull() ?: request.dates.minOrNull() ?: LocalDate.now()
    val initiallyScheduled = existing?.isUnscheduled()?.not() ?: !request.unscheduled
    val fixedNewDate = existing == null && request.dates.size == 1
    val canChooseDate = existing != null || request.dates.isEmpty()
    var title by rememberSaveable(request.key) { mutableStateOf(existing?.title.orEmpty()) }
    var note by rememberSaveable(request.key) { mutableStateOf(existing?.note.orEmpty()) }
    var dateIso by rememberSaveable(request.key) { mutableStateOf(initialDate.toString()) }
    var timeMinutes by rememberSaveable(request.key) { mutableStateOf(existing?.timeMinutes) }
    var important by rememberSaveable(request.key) { mutableStateOf(existing?.important ?: false) }
    var color by rememberSaveable(request.key) { mutableStateOf(existing?.color) }
    var hasDate by rememberSaveable(request.key) { mutableStateOf(initiallyScheduled) }
    var repeatRule by rememberSaveable(request.key) { mutableStateOf(existing?.repeatRule) }
    var reminderMinutesBefore by rememberSaveable(request.key) {
        mutableStateOf(existing?.reminderMinutesBefore)
    }
    var notifyAtStart by rememberSaveable(request.key) { mutableStateOf(existing?.notifyAtStart ?: true) }
    var reminderSound by rememberSaveable(request.key) {
        mutableStateOf(existing?.reminderSound ?: ReminderWorker.SOUND_NORMAL)
    }
    var deleteConfirm by rememberSaveable(request.key) { mutableStateOf(false) }
    var showAdvanceOptions by rememberSaveable(request.key) {
        mutableStateOf(existing?.reminderMinutesBefore != null)
    }
    var notifyAllUsers by rememberSaveable(request.key) { mutableStateOf(existing?.notifyAllUsers ?: true) }
    var notifyUserIdsValue by rememberSaveable(request.key) {
        mutableStateOf(
            TaskEntity.encodeNotificationUsers(
                existing?.notificationUsers()?.ifEmpty { setOf(currentUserId) } ?: setOf(currentUserId)
            )
        )
    }
    val notifyUserIds = notifyUserIdsValue.split(';').filterTo(linkedSetOf()) { it.isNotBlank() }
    val shownProfiles = (profiles + UserProfileEntity(currentUserId)).distinctBy { it.id }.sortedBy { it.id }
    val date = LocalDate.parse(dateIso)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)
    ) {
        Box(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).heightIn(max = 760.dp),
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 8.dp
            ) {
                Column(
                    Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        when {
                            existing != null -> "Изменить дело"
                            fixedNewDate -> "Новое дело · ${date.format(editorDate)}"
                            request.dates.size > 1 -> "Новое дело · ${request.dates.size} дней"
                            else -> "Новое дело"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                    OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                    OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Подробнее") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                    if (canChooseDate) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = hasDate,
                                onClick = { hasDate = true },
                                label = { Text("С датой") }
                            )
                            FilterChip(
                                selected = !hasDate,
                                onClick = { hasDate = false; repeatRule = null },
                                label = { Text("Без даты") }
                            )
                        }
                        if (hasDate) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(onClick = { dateIso = date.minusDays(1).toString() }) { Text("−1") }
                                Text(date.format(editorDate), style = MaterialTheme.typography.bodyMedium)
                                OutlinedButton(onClick = { dateIso = date.plusDays(1).toString() }) { Text("+1") }
                            }
                            TextButton(
                                onClick = { dateIso = LocalDate.now().toString() },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) { Text("На сегодня") }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                val seed = timeMinutes ?: 9 * 60
                                TimePickerDialog(
                                    context,
                                    { _, hour, minute -> timeMinutes = hour * 60 + minute },
                                    seed / 60,
                                    seed % 60,
                                    android.text.format.DateFormat.is24HourFormat(context)
                                ).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                timeMinutes?.let { "Время: %02d:%02d".format(it / 60, it % 60) }
                                    ?: "Выбрать время"
                            )
                        }
                        if (timeMinutes != null) {
                            TextButton(onClick = { timeMinutes = null }) { Text("Убрать") }
                        }
                    }

                    FilterChip(
                        selected = important,
                        onClick = { important = !important },
                        label = { Text(if (important) "★ Важное" else "☆ Сделать важным") }
                    )

                    if (hasDate && timeMinutes != null) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Уведомления", style = MaterialTheme.typography.labelMedium)
                            FilterChip(
                                selected = notifyAtStart,
                                onClick = { notifyAtStart = !notifyAtStart },
                                label = { Text("В начале") }
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            FilterChip(
                                selected = showAdvanceOptions,
                                onClick = {
                                    showAdvanceOptions = !showAdvanceOptions
                                    reminderMinutesBefore = if (showAdvanceOptions) 15 else null
                                },
                                label = { Text("Заранее") }
                            )
                            if (showAdvanceOptions) {
                                listOf(15 to "15 мин", 30 to "30 мин", 60 to "1 час", 120 to "2 часа", 1440 to "1 день")
                                    .forEach { (minutes, label) ->
                                        FilterChip(
                                            selected = reminderMinutesBefore == minutes,
                                            onClick = { reminderMinutesBefore = minutes },
                                            label = { Text(label) }
                                        )
                                    }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Звук", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            FilterChip(
                                selected = reminderSound == ReminderWorker.SOUND_NORMAL,
                                onClick = { reminderSound = ReminderWorker.SOUND_NORMAL },
                                label = { Text("Обычный") }
                            )
                            FilterChip(
                                selected = reminderSound == ReminderWorker.SOUND_LOUD,
                                onClick = { reminderSound = ReminderWorker.SOUND_LOUD },
                                label = { Text("Звучный") }
                            )
                        }
                    }

                    Text("Уведомить", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        FilterChip(
                            selected = notifyAllUsers,
                            onClick = { notifyAllUsers = true },
                            label = { Text("Всех") }
                        )
                        shownProfiles.forEach { profile ->
                            FilterChip(
                                selected = !notifyAllUsers && profile.id in notifyUserIds,
                                onClick = {
                                    notifyAllUsers = false
                                    val next = if (profile.id in notifyUserIds) {
                                        notifyUserIds - profile.id
                                    } else {
                                        notifyUserIds + profile.id
                                    }
                                    notifyUserIdsValue = TaskEntity.encodeNotificationUsers(next)
                                },
                                label = { Text(profile.resolvedName(shownProfiles)) }
                            )
                        }
                    }

                    if (hasDate) {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                repeatRule = if (repeatRule == null) RepeatRule.DAILY.value else null
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = repeatRule != null, onCheckedChange = null)
                            Text("Повторять", style = MaterialTheme.typography.labelMedium)
                        }
                        if (repeatRule != null) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                RepeatRule.entries.forEach { rule ->
                                    FilterChip(
                                        selected = repeatRule == rule.value,
                                        onClick = { repeatRule = rule.value },
                                        label = { Text(rule.title) }
                                    )
                                }
                            }
                        }
                    }

                    Text("Цветовая метка", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        palette.forEach { value ->
                            val fill = value?.let(::Color) ?: MaterialTheme.colorScheme.surface
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .background(fill, CircleShape)
                                    .border(
                                        if (color == value) 2.dp else 1.dp,
                                        if (color == value) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.outline,
                                        CircleShape
                                    )
                                    .clickable { color = value },
                                contentAlignment = Alignment.Center
                            ) {
                                if (value == null) Text("×", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    if (onRepeatToday != null) {
                    OutlinedButton(onClick = onRepeatToday, modifier = Modifier.fillMaxWidth()) {
                        Text("Повторить сегодня и снять просрочку")
                    }
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    Button(
                        onClick = {
                            onSave(
                                title, note, date.takeIf { hasDate }, timeMinutes, important, color,
                                repeatRule,
                                reminderMinutesBefore.takeIf { timeMinutes != null },
                                notifyAtStart && timeMinutes != null,
                                reminderSound,
                                notifyAllUsers,
                                notifyUserIds
                            )
                        },
                        enabled = title.isNotBlank()
                    ) { Text("Сохранить") }
                    }

                    if (onDelete != null) {
                    if (!deleteConfirm) {
                        TextButton(onClick = { deleteConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Удалить", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Удалить это дело?",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { deleteConfirm = false }) { Text("Нет") }
                            TextButton(onClick = onDelete) { Text("Да") }
                        }
                    }
                    }
                }
            }
        }
    }
}
