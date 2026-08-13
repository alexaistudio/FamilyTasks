package ru.simple.mycalendar.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.simple.mycalendar.v2.data.TaskEntity
import ru.simple.mycalendar.v2.data.TaskRepository
import ru.simple.mycalendar.v2.data.UserProfileEntity
import ru.simple.mycalendar.v2.sync.SyncClient
import ru.simple.mycalendar.v2.sync.SyncInfo
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

enum class AppScreen { CALENDAR, ALL_TASKS, SETTINGS }

data class SyncUiState(
    val info: SyncInfo,
    val working: Boolean = false,
    val message: String = ""
)

data class EditorRequest(
    val task: TaskEntity? = null,
    val dates: Set<LocalDate> = emptySet(),
    val unscheduled: Boolean = false
) {
    val key: String = task?.id ?: if (unscheduled) "unscheduled" else dates.sorted().joinToString(",")
}

class V2ViewModel(
    private val repository: TaskRepository,
    private val sync: SyncClient,
    private val uiPreferences: UiPreferences
) : ViewModel() {
    val activeTasks = repository.active.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val trashTasks = repository.trash.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val profiles = repository.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val currentUserId: String = repository.currentUserId

    var screen by mutableStateOf(AppScreen.CALENDAR)
        private set
    var month by mutableStateOf(YearMonth.now())
        private set
    var daySheet by mutableStateOf<LocalDate?>(null)
        private set
    var unscheduledSheet by mutableStateOf(false)
        private set
    val taskSheetOpen: Boolean get() = daySheet != null || unscheduledSheet
    var editor by mutableStateOf<EditorRequest?>(null)
        private set
    var selectedDates by mutableStateOf(emptySet<LocalDate>())
        private set
    var selectedTaskIds by mutableStateOf(emptySet<String>())
        private set
    var selectedTrashIds by mutableStateOf(emptySet<String>())
        private set
    var syncState by mutableStateOf(SyncUiState(sync.info()))
        private set
    var calendarTaskFontSp by mutableFloatStateOf(uiPreferences.calendarTaskFontSp())
        private set
    var syncIntervalMinutes by mutableIntStateOf(uiPreferences.syncIntervalMinutes())
        private set
    var notifyAboutNewTasks by mutableStateOf(uiPreferences.notifyAboutNewTasks())
        private set
    var serverBackgroundSyncEnabled by mutableStateOf(uiPreferences.serverBackgroundSyncEnabled())
        private set
    var bluetoothSyncEnabled by mutableStateOf(uiPreferences.bluetoothSyncEnabled())
        private set
    var automaticUpdatesEnabled by mutableStateOf(uiPreferences.automaticUpdatesEnabled())
        private set

    fun updateCalendarTaskFontSp(value: Float) {
        calendarTaskFontSp = value.coerceIn(6f, 10f)
        uiPreferences.setCalendarTaskFontSp(calendarTaskFontSp)
    }

    fun updateSyncIntervalMinutes(value: Int) {
        syncIntervalMinutes = value
        uiPreferences.setSyncIntervalMinutes(value)
    }

    fun updateNotifyAboutNewTasks(value: Boolean) {
        notifyAboutNewTasks = value
        uiPreferences.setNotifyAboutNewTasks(value)
    }

    fun updateServerBackgroundSyncEnabled(value: Boolean) {
        serverBackgroundSyncEnabled = value
        uiPreferences.setServerBackgroundSyncEnabled(value)
    }

    fun updateBluetoothSyncEnabled(value: Boolean) {
        bluetoothSyncEnabled = value
        uiPreferences.setBluetoothSyncEnabled(value)
    }

    fun updateAutomaticUpdatesEnabled(value: Boolean) {
        automaticUpdatesEnabled = value
        uiPreferences.setAutomaticUpdatesEnabled(value)
    }

    fun show(screen: AppScreen) {
        this.screen = screen
        selectedTaskIds = emptySet()
        selectedTrashIds = emptySet()
    }

    fun previousMonth() { month = month.minusMonths(1) }
    fun nextMonth() { month = month.plusMonths(1) }
    fun today() { month = YearMonth.now() }
    fun displayMonth(value: YearMonth) { month = value }

    fun openDay(date: LocalDate) {
        if (selectedDates.isNotEmpty()) {
            toggleDate(date)
        } else {
            unscheduledSheet = false
            daySheet = date
        }
    }

    fun openUnscheduled() {
        if (selectedDates.isEmpty()) {
            daySheet = null
            unscheduledSheet = true
        }
    }

    fun closeDay() {
        daySheet = null
        unscheduledSheet = false
    }

    fun startDateSelection(date: LocalDate) {
        selectedDates = selectedDates + date
    }

    fun paintDate(date: LocalDate) {
        if (date !in selectedDates) selectedDates = selectedDates + date
    }

    fun toggleDate(date: LocalDate) {
        selectedDates = if (date in selectedDates) selectedDates - date else selectedDates + date
    }

    fun clearDateSelection() { selectedDates = emptySet() }

    fun addForSelectedDates() {
        if (selectedDates.isNotEmpty()) editor = EditorRequest(dates = selectedDates)
    }

    fun addForDay(date: LocalDate) {
        closeDay()
        editor = EditorRequest(dates = setOf(date))
    }

    fun addUnscheduled() {
        closeDay()
        editor = EditorRequest(unscheduled = true)
    }

    fun addGlobal() {
        closeDay()
        editor = EditorRequest(unscheduled = true)
    }

    fun edit(task: TaskEntity) {
        closeDay()
        editor = EditorRequest(task = task)
    }

    fun closeEditor() { editor = null }

    fun saveEditor(
        request: EditorRequest,
        title: String,
        note: String,
        date: LocalDate?,
        timeMinutes: Int?,
        important: Boolean,
        color: Long?,
        repeatRule: String?,
        reminderMinutesBefore: Int?,
        notifyAtStart: Boolean,
        reminderSound: String,
        notifyAllUsers: Boolean,
        notifyUserIds: Set<String>
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val existing = request.task
            if (existing == null) {
                val dates = request.dates.ifEmpty { date?.let(::setOf) ?: emptySet() }
                if (dates.isEmpty()) {
                    repository.addUnscheduled(
                        title, note, timeMinutes, important, color,
                        reminderMinutesBefore, notifyAtStart, reminderSound,
                        notifyAllUsers, notifyUserIds
                    )
                } else {
                    repository.addToDates(
                        dates, title, note, timeMinutes, important, color, repeatRule,
                        reminderMinutesBefore, notifyAtStart, reminderSound,
                        notifyAllUsers, notifyUserIds
                    )
                }
                selectedDates = emptySet()
            } else {
                repository.save(existing.copy(
                    seriesId = when {
                        repeatRule == null -> existing.seriesId
                        existing.repeatRule == null -> UUID.randomUUID().toString()
                        else -> existing.seriesId ?: UUID.randomUUID().toString()
                    },
                    title = title.trim(), note = note.trim(), localDate = date?.toString().orEmpty(),
                    timeMinutes = timeMinutes, important = important, color = color,
                    repeatRule = repeatRule,
                    reminderMinutesBefore = reminderMinutesBefore,
                    notifyAtStart = notifyAtStart,
                    reminderSound = reminderSound,
                    notifyAllUsers = notifyAllUsers,
                    notifyUserIds = TaskEntity.encodeNotificationUsers(notifyUserIds),
                    repeatAnchor = when {
                        repeatRule == null -> null
                        existing.repeatAnchor != null -> existing.repeatAnchor
                        else -> date?.toString()
                    }
                ))
            }
        }
        editor = null
    }

    fun toggleCompleted(task: TaskEntity) = viewModelScope.launch { repository.toggleCompleted(task) }

    fun repeatToday(task: TaskEntity) {
        viewModelScope.launch { repository.repeatToday(task) }
        editor = null
        month = YearMonth.now()
    }

    fun moveTask(task: TaskEntity, date: LocalDate?, targetIndex: Int) {
        viewModelScope.launch { repository.move(task, date, targetIndex) }
    }

    fun trash(task: TaskEntity) {
        viewModelScope.launch { repository.moveToTrash(setOf(task.id)) }
        editor = null
        closeDay()
    }

    fun trashIds(ids: Set<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch { repository.moveToTrash(ids) }
        closeDay()
    }

    fun toggleTaskSelection(id: String, trash: Boolean) {
        if (trash) {
            selectedTrashIds = selectedTrashIds.toggle(id)
        } else {
            selectedTaskIds = selectedTaskIds.toggle(id)
        }
    }

    fun clearTaskSelection(trash: Boolean) {
        if (trash) selectedTrashIds = emptySet() else selectedTaskIds = emptySet()
    }

    fun trashSelected() {
        val ids = selectedTaskIds
        selectedTaskIds = emptySet()
        viewModelScope.launch { repository.moveToTrash(ids) }
    }

    fun restoreSelected() {
        val ids = selectedTrashIds
        selectedTrashIds = emptySet()
        viewModelScope.launch { repository.restore(ids) }
    }

    fun deleteSelectedForever() {
        val ids = selectedTrashIds
        selectedTrashIds = emptySet()
        viewModelScope.launch { repository.deleteForever(ids) }
    }

    fun connectSync(server: String, invite: String, certificateSha256: String = "") {
        if (server.isBlank() || invite.isBlank() || syncState.working) return
        syncState = syncState.copy(working = true, message = "Подключение…")
        viewModelScope.launch {
            syncState = try {
                val info = sync.register(server, invite, certificateSha256)
                SyncUiState(info, message = "Устройство подключено. Запустите первую синхронизацию.")
            } catch (error: Exception) {
                SyncUiState(sync.info(), message = error.message ?: "Не удалось подключиться")
            }
        }
    }

    fun syncNow() {
        if (syncState.working) return
        syncState = syncState.copy(working = true, message = "Синхронизация…")
        viewModelScope.launch {
            syncState = try {
                val seq = sync.syncNow()
                SyncUiState(sync.info().copy(lastSeq = seq), message = "Готово. Серверная позиция: $seq")
            } catch (error: Exception) {
                SyncUiState(sync.info(), message = error.message ?: "Ошибка синхронизации")
            }
        }
    }

    fun importRecoveryCode(code: String) {
        syncState = try {
            sync.importRecoveryCode(code)
            SyncUiState(
                sync.info(),
                message = "Recovery-ключ принят. Для Bluetooth включите связь на обоих телефонах; приглашение нужно только для PHP-сервера."
            )
        } catch (error: Exception) {
            SyncUiState(sync.info(), message = error.message ?: "Неверный recovery-код")
        }
    }

    fun recoveryCode(): String = sync.recoveryCode()

    fun disconnectSync() {
        sync.disconnect()
        syncState = SyncUiState(sync.info(), message = "Синхронизация отключена; локальные дела сохранены.")
    }

    fun renameCurrentUser(name: String) {
        viewModelScope.launch { repository.renameCurrentProfile(name) }
    }

    private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
}

class V2ViewModelFactory(
    private val repository: TaskRepository,
    private val sync: SyncClient,
    private val uiPreferences: UiPreferences
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = V2ViewModel(repository, sync, uiPreferences) as T
}
