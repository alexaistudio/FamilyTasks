@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.simple.mycalendar.v2.ui

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.simple.mycalendar.v2.AppScreen
import ru.simple.mycalendar.v2.V2ViewModel
import ru.simple.mycalendar.v2.data.DayKind
import ru.simple.mycalendar.v2.data.ProductionCalendar
import ru.simple.mycalendar.v2.data.TaskEntity
import ru.simple.mycalendar.v2.data.RepeatRule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

private val monthTitle = DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru"))
private val fullDate = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("ru"))
private val listDate = DateTimeFormatter.ofPattern("d MMM, EEE", Locale("ru"))
private val actualListDate = DateTimeFormatter.ofPattern("dd.MM.yyyy, EEE.", Locale("ru"))
private val weekdays = listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС")
private const val CALENDAR_PAGES = 2400
private const val CALENDAR_CENTER_PAGE = CALENDAR_PAGES / 2
private const val YEAR_PAGES = 401
private const val YEAR_CENTER_PAGE = YEAR_PAGES / 2
private val CALENDAR_TASKS_TOP = 28.dp

// Vertical offset of the first tray row from the tray top: 4 dp surface
// padding + 7 dp column padding. Used only by the drag hit-testing below.
private val UNSCHEDULED_HEADER_HEIGHT = 11.dp
private const val DAY_SHEET_DISMISS_VELOCITY_PX = 1_200f

// Unscheduled rows match the compact line pitch used inside day cells, so the
// tray height follows the configured calendar font instead of a fixed value.
private fun unscheduledTaskHeight(taskFontSp: Float) = (taskFontSp + 3f).dp
private fun unscheduledTrayHeight(taskFontSp: Float) = unscheduledTaskHeight(taskFontSp) * 3 + 34.dp

private data class SheetTaskDrag(val task: TaskEntity, val pointInRoot: Offset)
private data class CalendarDropTarget(val date: LocalDate, val insertion: Int)

internal fun TaskEntity.actualListDateText(): String? = dateOrNull()?.let { date ->
    buildString {
        append(date.format(actualListDate))
        timeMinutes?.let { append(", %02d:%02d".format(it / 60, it % 60)) }
    }
}

internal fun shouldDismissDaySheet(
    dragOffsetPx: Float,
    velocityPxPerSecond: Float,
    thresholdPx: Float
): Boolean = dragOffsetPx >= thresholdPx || velocityPxPerSecond >= DAY_SHEET_DISMISS_VELOCITY_PX

@Composable
fun MyCalendarApp(model: V2ViewModel) {
    val active by model.activeTasks.collectAsStateWithLifecycle()
    val trash by model.trashTasks.collectAsStateWithLifecycle()
    val profiles by model.profiles.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var lastBackAt by remember { mutableLongStateOf(0L) }
    var calendarOpenRequest by rememberSaveable { mutableIntStateOf(0) }
    var sheetDrag by remember { mutableStateOf<SheetTaskDrag?>(null) }
    var sheetDropTarget by remember { mutableStateOf<CalendarDropTarget?>(null) }

    BackHandler(enabled = model.taskSheetOpen && model.editor == null && sheetDrag == null) {
        model.closeDay()
    }
    BackHandler(enabled = model.editor == null && !model.taskSheetOpen && model.screen != AppScreen.CALENDAR) {
        model.show(AppScreen.CALENDAR)
    }
    BackHandler(
        enabled = model.editor == null && !model.taskSheetOpen &&
            model.screen == AppScreen.CALENDAR && model.selectedDates.isNotEmpty()
    ) {
        model.clearDateSelection()
    }
    BackHandler(
        enabled = model.editor == null && !model.taskSheetOpen &&
            model.screen == AppScreen.CALENDAR && model.selectedDates.isEmpty()
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackAt <= 2_000L) {
            (context as? Activity)?.finish()
        } else {
            lastBackAt = now
            Toast.makeText(context, "Нажмите ещё раз для выхода", Toast.LENGTH_SHORT).show()
        }
    }

    // Draw the app background behind transparent system bars, then inset only
    // interactive content. This keeps icons readable without a white overlay.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Scaffold(
            topBar = {
                Surface(shadowElevation = 2.dp) {
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable {
                                    model.show(AppScreen.CALENDAR)
                                    model.clearDateSelection()
                                    calendarOpenRequest++
                                },
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text("FamilyTasks", fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(onClick = model::addGlobal) {
                            Text("+", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            },
            bottomBar = {
                NavigationBar {
                    NavItem("▦", "Календарь", model.screen == AppScreen.CALENDAR) { model.show(AppScreen.CALENDAR) }
                    NavItem("☷", "Все дела", model.screen == AppScreen.ALL_TASKS) { model.show(AppScreen.ALL_TASKS) }
                    NavItem("⚙", "Настройки", model.screen == AppScreen.SETTINGS) { model.show(AppScreen.SETTINGS) }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (model.screen) {
                    AppScreen.CALENDAR -> CalendarScreen(
                        model = model,
                        tasks = active,
                        openRequest = calendarOpenRequest,
                        externalDrag = sheetDrag,
                        onExternalTargetChange = { date, insertion ->
                            sheetDropTarget = date?.let { CalendarDropTarget(it, insertion) }
                        }
                    )
                    AppScreen.ALL_TASKS -> AllTasksScreen(model, active, trash)
                    AppScreen.SETTINGS -> SettingsScreen(model)
                }
            }
        }

        val sheetDate = model.daySheet
        if (sheetDate != null || model.unscheduledSheet) {
            DaySheet(
                date = sheetDate,
                tasks = active.filter { task ->
                    if (sheetDate == null) task.isUnscheduled() else task.localDate == sheetDate.toString()
                },
                dragging = sheetDrag != null,
                onDismiss = model::closeDay,
                onAdd = {
                    if (sheetDate == null) model.addUnscheduled() else model.addForDay(sheetDate)
                },
                onEdit = model::edit,
                onToggleDone = model::toggleCompleted,
                onTrashSelected = model::trashIds,
                onDragStart = { task, point ->
                    sheetDropTarget = null
                    sheetDrag = SheetTaskDrag(task, point)
                },
                onDragMove = { point ->
                    sheetDrag = sheetDrag?.copy(pointInRoot = point)
                },
                onDragEnd = {
                    val drag = sheetDrag
                    val target = sheetDropTarget
                    if (drag != null && target != null) {
                        model.moveTask(drag.task, target.date, target.insertion)
                        model.closeDay()
                    }
                    sheetDrag = null
                    sheetDropTarget = null
                }
            )
        }

        sheetDrag?.let { drag ->
            FloatingTaskCard(drag.task, drag.pointInRoot)
        }
    }
    }

    model.editor?.let { request ->
        TaskEditorDialog(
            request = request,
            profiles = profiles,
            currentUserId = model.currentUserId,
            onDismiss = model::closeEditor,
            onSave = { title, note, date, time, important, color, repeatRule, before, atStart, sound,
                notifyAll, notifyUsers ->
                model.saveEditor(
                    request, title, note, date, time, important, color, repeatRule, before,
                    atStart, sound, notifyAll, notifyUsers
                )
            },
            onDelete = request.task?.let { task -> { model.trash(task) } },
            onRepeatToday = request.task?.takeIf { it.isOverdue() }?.let { task -> { model.repeatToday(task) } }
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    symbol: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Text(symbol, style = MaterialTheme.typography.titleLarge) },
        label = { Text(label) }
    )
}

@Composable
private fun CalendarScreen(
    model: V2ViewModel,
    tasks: List<TaskEntity>,
    openRequest: Int,
    externalDrag: SheetTaskDrag?,
    onExternalTargetChange: (LocalDate?, Int) -> Unit
) {
    val anchorMonth = remember { model.month }
    val pager = rememberPagerState(initialPage = CALENDAR_CENTER_PAGE) { CALENDAR_PAGES }
    val scope = rememberCoroutineScope()
    var showYear by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showYear) { showYear = false }
    LaunchedEffect(openRequest) {
        if (openRequest > 0) showYear = false
    }
    val todayPage = remember(anchorMonth) {
        (CALENDAR_CENTER_PAGE + ChronoUnit.MONTHS.between(anchorMonth, YearMonth.now()).toInt())
            .coerceIn(0, CALENDAR_PAGES - 1)
    }

    LaunchedEffect(pager.currentPage) {
        model.displayMonth(anchorMonth.plusMonths((pager.currentPage - CALENDAR_CENTER_PAGE).toLong()))
    }

    if (showYear) {
        YearOverview(
            initialYear = model.month.year,
            tasks = tasks,
            onClose = { showYear = false },
            onMonth = { target ->
                val targetPage = (CALENDAR_CENTER_PAGE + ChronoUnit.MONTHS.between(anchorMonth, target).toInt())
                    .coerceIn(0, CALENDAR_PAGES - 1)
                scope.launch {
                    pager.scrollToPage(targetPage)
                    showYear = false
                }
            }
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        if (model.selectedDates.isNotEmpty()) {
            SelectionHeader(
                count = model.selectedDates.size,
                action = "Добавить дело",
                onCancel = model::clearDateSelection,
                onAction = model::addForSelectedDates
            )
        }
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = model.selectedDates.isEmpty(),
            beyondViewportPageCount = 1,
            key = { it }
        ) { page ->
            val pageMonth = anchorMonth.plusMonths((page - CALENDAR_CENTER_PAGE).toLong())
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val calendarHeight = (maxHeight - 82.dp).coerceAtLeast(610.dp)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (model.selectedDates.isEmpty()) {
                    MonthHeader(
                        pageMonth,
                        onTitle = { showYear = true },
                        onPrevious = { scope.launch { pager.animateScrollToPage((page - 1).coerceAtLeast(0)) } },
                        onNext = { scope.launch { pager.animateScrollToPage((page + 1).coerceAtMost(CALENDAR_PAGES - 1)) } },
                        onToday = { scope.launch { pager.animateScrollToPage(todayPage) } }
                    )
                }
                WeekdayHeader()
                MonthGrid(
                    month = pageMonth,
                    tasks = tasks,
                    selected = model.selectedDates,
                    onTap = model::openDay,
                    onLongPress = model::startDateSelection,
                    onPaint = model::paintDate,
                    onMoveTask = model::moveTask,
                    onOpenUnscheduled = model::openUnscheduled,
                    taskFontSp = model.calendarTaskFontSp,
                    externalDrag = externalDrag.takeIf { page == pager.currentPage },
                    onExternalTargetChange = { date, insertion ->
                        if (page == pager.currentPage) onExternalTargetChange(date, insertion)
                    },
                    modifier = Modifier.fillMaxWidth().height(calendarHeight)
                )
                if (ProductionCalendar.isPreliminary(pageMonth.year)) {
                    Text(
                        ProductionCalendar.statusText(pageMonth.year),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun MonthHeader(
    month: YearMonth,
    onTitle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val title = month.atDay(1).format(monthTitle).replaceFirstChar { it.titlecase(Locale("ru")) }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).clickable(onClick = onTitle).padding(vertical = 6.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (month != YearMonth.now()) {
            TextButton(onClick = onToday) { Text("Сегодня") }
        }
        TextButton(onClick = onPrevious) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
        TextButton(onClick = onNext) { Text("›", style = MaterialTheme.typography.headlineSmall) }
    }
}

@Composable
private fun YearOverview(
    initialYear: Int,
    tasks: List<TaskEntity>,
    onClose: () -> Unit,
    onMonth: (YearMonth) -> Unit
) {
    val pager = rememberPagerState(initialPage = YEAR_CENTER_PAGE) { YEAR_PAGES }
    val scope = rememberCoroutineScope()
    HorizontalPager(
        state = pager,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
        key = { it }
    ) { page ->
        val year = initialYear + page - YEAR_CENTER_PAGE
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    year.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).clickable(onClick = onClose).padding(vertical = 6.dp)
                )
                if (year != LocalDate.now().year) {
                    TextButton(onClick = {
                        scope.launch {
                            pager.animateScrollToPage(
                                (YEAR_CENTER_PAGE + LocalDate.now().year - initialYear)
                                    .coerceIn(0, YEAR_PAGES - 1)
                            )
                        }
                    }) { Text("Этот год") }
                }
                TextButton(onClick = onClose) { Text("Месяц") }
                TextButton(onClick = {
                    scope.launch { pager.animateScrollToPage((page - 1).coerceAtLeast(0)) }
                }) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
                TextButton(onClick = {
                    scope.launch { pager.animateScrollToPage((page + 1).coerceAtMost(YEAR_PAGES - 1)) }
                }) { Text("›", style = MaterialTheme.typography.headlineSmall) }
            }
            Text(
                ProductionCalendar.statusText(year),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
            YearGrid(year, tasks, onMonth, Modifier.weight(1f))
        }
    }
}

@Composable
private fun YearGrid(
    year: Int,
    tasks: List<TaskEntity>,
    onMonth: (YearMonth) -> Unit,
    modifier: Modifier = Modifier
) {
    val byDay = remember(tasks) { tasks.filter { it.deletedAt == null }.groupBy { it.localDate } }
    Column(
        modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        repeat(4) { row ->
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                repeat(3) { column ->
                    val month = YearMonth.of(year, row * 3 + column + 1)
                    MiniMonth(
                        month = month,
                        tasksByDay = byDay,
                        onClick = { onMonth(month) },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniMonth(
    month: YearMonth,
    tasksByDay: Map<String, List<TaskEntity>>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val first = month.atDay(1)
    val firstCell = first.minusDays((first.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
    val dates = remember(month) { List(42) { firstCell.plusDays(it.toLong()) } }
    val current = month == YearMonth.now()
    val dark = isSystemInDarkTheme()
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (current) {
            if (dark) Color(0xFF202A22) else Color(0xFFF0F7F1)
        } else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f),
        shape = RoundedCornerShape(12.dp),
        border = if (current) androidx.compose.foundation.BorderStroke(
            1.dp,
            if (dark) Color(0xFF6F9D78) else Color(0xFF9CC7A4)
        ) else null
    ) {
        Column(Modifier.padding(4.dp)) {
            Text(
                month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL", Locale("ru")))
                    .replaceFirstChar { it.titlecase(Locale("ru")) },
                fontSize = 10.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            repeat(6) { row ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    repeat(7) { column ->
                        val date = dates[row * 7 + column]
                        val inMonth = YearMonth.from(date) == month
                        Box(
                            Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (inMonth) {
                                val kind = ProductionCalendar.kindOf(date)
                                val shortened = kind == DayKind.SHORTENED
                                val dayOff = kind == DayKind.WEEKEND ||
                                    kind == DayKind.HOLIDAY || kind == DayKind.TRANSFERRED_OFF
                                val dayTasks = tasksByDay[date.toString()].orEmpty()
                                Text(
                                    date.dayOfMonth.toString(),
                                    modifier = Modifier.background(
                                        when {
                                            shortened -> if (dark) Color(0xFF3A3520) else Color(0xFFFFF2B8)
                                            dayOff -> if (dark) Color(0xFF3A2922) else Color(0xFFFFE3D2)
                                            else -> Color.Transparent
                                        },
                                        RoundedCornerShape(2.dp)
                                    ).padding(horizontal = 1.dp),
                                    fontSize = 7.sp,
                                    lineHeight = 8.sp,
                                    color = when {
                                        shortened -> if (dark) Color(0xFFF2E5A6) else Color(0xFF463D12)
                                        dayOff -> if (dark) Color(0xFFFFC4A3) else Color(0xFF783000)
                                        date == LocalDate.now() -> Color(0xFF387047)
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                dayTasks.firstNotNullOfOrNull { it.color }?.let { markerColor ->
                                    Box(
                                        Modifier
                                            .align(Alignment.BottomCenter)
                                            .size(3.dp)
                                            .background(Color(markerColor), CircleShape)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekdayHeader() {
    val dark = isSystemInDarkTheme()
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        weekdays.forEachIndexed { index, day ->
            Text(
                day,
                modifier = Modifier.weight(1f).padding(vertical = 5.dp),
                color = if (index >= 5) {
                    if (dark) Color(0xFFFFBE98) else Color(0xFF853300)
                } else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    tasks: List<TaskEntity>,
    selected: Set<LocalDate>,
    onTap: (LocalDate) -> Unit,
    onLongPress: (LocalDate) -> Unit,
    onPaint: (LocalDate) -> Unit,
    onMoveTask: (TaskEntity, LocalDate?, Int) -> Unit,
    onOpenUnscheduled: () -> Unit,
    taskFontSp: Float,
    externalDrag: SheetTaskDrag?,
    onExternalTargetChange: (LocalDate?, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val first = month.atDay(1)
    val firstCell = first.minusDays((first.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
    val dates = remember(month) { List(42) { firstCell.plusDays(it.toLong()) } }
    val byDay = remember(tasks) { tasks.groupBy { it.localDate } }
    var dragTask by remember { mutableStateOf<TaskEntity?>(null) }
    var dragTargetDate by remember { mutableStateOf<Int?>(null) }
    var dragTargetUnscheduled by remember { mutableStateOf(false) }
    var dragInsertion by remember { mutableIntStateOf(0) }
    var dragPoint by remember { mutableStateOf<Offset?>(null) }
    var rootBounds by remember { mutableStateOf<Rect?>(null) }
    val unscheduled = remember(tasks) { tasks.filter { it.isUnscheduled() } }
    val density = LocalDensity.current
    val trayHeightPx = with(density) { unscheduledTrayHeight(taskFontSp).toPx() }
    val tasksTopPx = with(density) { CALENDAR_TASKS_TOP.toPx() }
    val taskSlotPx = with(density) { (taskFontSp + 3f).dp.toPx() }
    val externalPoint = externalDrag?.pointInRoot
    val externalTargetIndex = rootBounds?.let { bounds ->
        externalPoint?.let { point ->
            val gridHeight = (bounds.height - trayHeightPx).coerceAtLeast(1f)
            val localX = point.x - bounds.left
            val localY = point.y - bounds.top
            if (localX !in 0f..bounds.width || localY !in 0f..gridHeight) null
            else {
                val column = floor(localX / (bounds.width / 7f)).toInt().coerceIn(0, 6)
                val row = floor(localY / (gridHeight / 6f)).toInt().coerceIn(0, 5)
                (row * 7 + column).takeIf { it in dates.indices }
            }
        }
    }
    val externalInsertion = rootBounds?.let { bounds ->
        externalTargetIndex?.let { index ->
            val gridHeight = (bounds.height - trayHeightPx).coerceAtLeast(1f)
            val localY = externalPoint!!.y - bounds.top
            val cellHeight = gridHeight / 6f
            val withinCell = localY - floor(localY / cellHeight) * cellHeight
            floor((withinCell - tasksTopPx) / taskSlotPx).toInt()
                .coerceIn(0, byDay[dates[index].toString()].orEmpty().size)
        }
    } ?: 0

    LaunchedEffect(externalDrag?.task?.id, externalTargetIndex, externalInsertion) {
        onExternalTargetChange(externalTargetIndex?.let(dates::get), externalInsertion)
    }

    Box(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { rootBounds = it.boundsInRoot() }
            .pointerInput(month, tasks, selected, taskFontSp) {
                var visited = mutableSetOf<Int>()
                val trayHeight = unscheduledTrayHeight(taskFontSp).toPx()
                val tasksTop = CALENDAR_TASKS_TOP.toPx()
                val taskSlot = (taskFontSp + 3f).dp.toPx()
                val trayHeader = UNSCHEDULED_HEADER_HEIGHT.toPx()
                val trayTaskHeight = unscheduledTaskHeight(taskFontSp).toPx()
                fun gridHeight(): Float = (size.height - trayHeight).coerceAtLeast(1f)
                fun indexAt(x: Float, y: Float): Int? {
                    if (x !in 0f..size.width.toFloat() || y !in 0f..gridHeight()) return null
                    val column = floor(x / (size.width / 7f)).toInt().coerceIn(0, 6)
                    val row = floor(y / (gridHeight() / 6f)).toInt().coerceIn(0, 5)
                    return (row * 7 + column).takeIf { it in dates.indices }
                }
                fun calendarTaskAt(pointY: Float, cellIndex: Int): TaskEntity? {
                    if (selected.isNotEmpty()) return null
                    val cellHeight = gridHeight() / 6f
                    val withinCell = pointY - floor(pointY / cellHeight) * cellHeight
                    val capacity = floor((cellHeight - tasksTop) / taskSlot).toInt().coerceIn(0, 4)
                    val taskIndex = floor((withinCell - tasksTop) / taskSlot).toInt()
                    return byDay[dates[cellIndex].toString()].orEmpty().take(capacity).getOrNull(taskIndex)
                }
                fun unscheduledTaskAt(pointY: Float): TaskEntity? {
                    if (selected.isNotEmpty() || pointY < gridHeight()) return null
                    val index = floor((pointY - gridHeight() - trayHeader) / trayTaskHeight).toInt()
                    return unscheduled.take(3).getOrNull(index)
                }
                fun insertionAt(pointY: Float, cellIndex: Int): Int {
                    val cellHeight = gridHeight() / 6f
                    val withinCell = pointY - floor(pointY / cellHeight) * cellHeight
                    val index = floor((withinCell - tasksTop) / taskSlot).toInt()
                    return index.coerceIn(0, byDay[dates[cellIndex].toString()].orEmpty().size)
                }
                fun updateDragTarget(point: Offset) {
                    val index = indexAt(point.x, point.y)
                    dragTargetDate = index
                    dragTargetUnscheduled = index == null && point.y in gridHeight()..size.height.toFloat()
                    dragInsertion = when {
                        index != null -> insertionAt(point.y, index)
                        dragTargetUnscheduled -> floor((point.y - gridHeight() - trayHeader) / trayTaskHeight)
                            .toInt().coerceIn(0, unscheduled.size)
                        else -> 0
                    }
                }
                fun clearDrag() {
                    dragTask = null
                    dragTargetDate = null
                    dragTargetUnscheduled = false
                    dragPoint = null
                }
                detectDragGesturesAfterLongPress(
                    onDragStart = { point ->
                        visited = mutableSetOf()
                        val index = indexAt(point.x, point.y)
                        dragTask = index?.let { calendarTaskAt(point.y, it) } ?: unscheduledTaskAt(point.y)
                        if (dragTask != null) {
                            dragPoint = point
                            updateDragTarget(point)
                        } else if (index != null) {
                                visited.add(index)
                                onLongPress(dates[index])
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (dragTask != null) {
                            dragPoint = change.position
                            updateDragTarget(change.position)
                        } else {
                            indexAt(change.position.x, change.position.y)?.let { index ->
                                if (visited.add(index)) onPaint(dates[index])
                            }
                        }
                    },
                    onDragEnd = {
                        val task = dragTask
                        if (task != null) {
                            when {
                                dragTargetDate != null -> onMoveTask(task, dates[dragTargetDate!!], dragInsertion)
                                dragTargetUnscheduled -> onMoveTask(task, null, dragInsertion)
                            }
                        }
                        clearDrag()
                    },
                    onDragCancel = { clearDrag() }
                )
            }
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().weight(1f)) {
                repeat(6) { row ->
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        repeat(7) { column ->
                            val date = dates[row * 7 + column]
                            DayCell(
                                date = date,
                                inMonth = YearMonth.from(date) == month,
                                tasks = byDay[date.toString()].orEmpty(),
                                selectionMode = selected.isNotEmpty(),
                                selected = date in selected,
                                dragTarget = (dragTargetDate == row * 7 + column &&
                                    byDay[date.toString()].orEmpty().none { it.id == dragTask?.id }) ||
                                    externalTargetIndex == row * 7 + column,
                                draggingTaskId = dragTask?.id ?: externalDrag?.task?.id,
                                taskFontSp = taskFontSp,
                                onTap = { onTap(date) },
                                modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 1.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            UnscheduledTray(
                tasks = unscheduled,
                dragTarget = dragTargetUnscheduled,
                draggingTaskId = dragTask?.id,
                onOpen = onOpenUnscheduled,
                taskFontSp = taskFontSp
            )
        }
        val floatingTask = dragTask
        val floatingPoint = dragPoint
        if (floatingTask != null && floatingPoint != null) {
            Surface(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (floatingPoint.x - 24.dp.toPx()).roundToInt(),
                            (floatingPoint.y - 18.dp.toPx()).roundToInt()
                        )
                    }
                    .widthIn(max = 240.dp)
                    .zIndex(10f),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 10.dp
            ) {
                Text(
                    (if (floatingTask.repeatRule != null) "↻ " else "") + floatingTask.title,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    tasks: List<TaskEntity>,
    selectionMode: Boolean,
    selected: Boolean,
    dragTarget: Boolean,
    draggingTaskId: String?,
    taskFontSp: Float,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val kind = ProductionCalendar.kindOf(date)
    val shortened = kind == DayKind.SHORTENED
    val dayOff = kind == DayKind.WEEKEND || kind == DayKind.HOLIDAY || kind == DayKind.TRANSFERRED_OFF
    val dark = isSystemInDarkTheme()
    val isToday = date == LocalDate.now()
    val base = when {
        isToday -> if (dark) Color(0xFF202A22) else Color(0xFFF0F7F1)
        shortened -> if (dark) Color(0xFF29271C) else Color(0xFFFFFAE9)
        dayOff -> if (dark) Color(0xFF2A211C) else Color(0xFFFFF4ED)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .52f)
    }
    Surface(
        modifier = modifier
            .alpha(if (inMonth) 1f else .16f)
            .clickable(onClick = onTap),
        color = base,
        shape = RoundedCornerShape(11.dp),
        border = when {
            dragTarget -> androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.tertiary)
            selected -> androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            isToday -> androidx.compose.foundation.BorderStroke(
                1.dp,
                if (dark) Color(0xFF6F9D78) else Color(0xFF9CC7A4)
            )
            else -> null
        }
    ) {
        BoxWithConstraints {
        val taskSlot = taskFontSp + 3f
        val taskCapacity = floor(((maxHeight - CALENDAR_TASKS_TOP).value / taskSlot).toDouble())
            .toInt().coerceIn(0, 4)
        Column(Modifier.padding(horizontal = 5.dp, vertical = 4.dp)) {
            Box(
                Modifier
                    .size(24.dp)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                    .then(
                        if (selectionMode && !selected) {
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    date.dayOfMonth.toString(),
                    color = when {
                        selected -> MaterialTheme.colorScheme.onPrimary
                        shortened -> if (dark) Color(0xFFF2E5A6) else Color(0xFF463D12)
                        dayOff -> if (dark) Color(0xFFFFC4A3) else Color(0xFF783000)
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = if (date == LocalDate.now()) FontWeight.ExtraBold else FontWeight.SemiBold,
                    fontSize = 12.sp
                )
            }
            tasks.take(taskCapacity).forEach { task ->
                CalendarTaskLine(task, dragging = task.id == draggingTaskId, fontSizeSp = taskFontSp)
            }
            if (tasks.size > taskCapacity && taskCapacity > 0) {
                Text("ещё ${tasks.size - taskCapacity}", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        }
    }
}

@Composable
private fun CalendarTaskLine(
    task: TaskEntity,
    modifier: Modifier = Modifier,
    dragging: Boolean = false,
    fontSizeSp: Float = 9f
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(
                if (dragging) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                RoundedCornerShape(5.dp)
            )
            .padding(top = 2.dp, start = if (dragging) 2.dp else 0.dp)
    ) {
        task.color?.let { value ->
            Box(Modifier.size(6.dp).background(Color(value), CircleShape))
            Spacer(Modifier.width(3.dp))
        }
        Text(
            (if (task.repeatRule != null) "↻ " else "") + (if (task.important) "★ " else "") + task.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp + 1f).sp,
            color = if (task.isOverdue()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (task.completedAt != null) TextDecoration.LineThrough else TextDecoration.None
        )
    }
}

@Composable
private fun UnscheduledTray(
    tasks: List<TaskEntity>,
    dragTarget: Boolean,
    draggingTaskId: String?,
    onOpen: () -> Unit,
    taskFontSp: Float
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(unscheduledTrayHeight(taskFontSp))
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .55f),
        shape = RoundedCornerShape(14.dp),
        border = if (dragTarget) {
            androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.tertiary)
        } else null
    ) {
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Без даты",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)
                )
            }
        } else {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                tasks.take(3).forEach { task ->
                    CalendarTaskLine(
                        task = task,
                        dragging = task.id == draggingTaskId,
                        fontSizeSp = taskFontSp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(unscheduledTaskHeight(taskFontSp))
                    )
                }
                if (tasks.size > 3) {
                    Text("ещё ${tasks.size - 3}", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun DaySheet(
    date: LocalDate?,
    tasks: List<TaskEntity>,
    dragging: Boolean,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (TaskEntity) -> Unit,
    onToggleDone: (TaskEntity) -> Unit,
    onTrashSelected: (Set<String>) -> Unit,
    onDragStart: (TaskEntity, Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    var sheetBounds by remember { mutableStateOf<Rect?>(null) }
    var selectedIds by remember(date) { mutableStateOf(emptySet<String>()) }
    var activeDragId by remember { mutableStateOf<String?>(null) }
    var sheetOffsetY by remember(date) { mutableFloatStateOf(0f) }
    val dismissThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    val sheetDragState = rememberDraggableState { delta ->
        sheetOffsetY = (sheetOffsetY + delta).coerceAtLeast(0f)
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(if (dragging) Color.Transparent else Color.Black.copy(alpha = .34f))
                .clickable(enabled = !dragging, onClick = onDismiss)
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 651.dp)
                .offset { IntOffset(0, sheetOffsetY.roundToInt()) }
                .onGloballyPositioned { sheetBounds = it.boundsInRoot() }
                .alpha(if (dragging) 0f else 1f)
                .clickable(enabled = !dragging) {},
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            tonalElevation = 10.dp
        ) {
            Column(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .draggable(
                            state = sheetDragState,
                            orientation = Orientation.Vertical,
                            enabled = !dragging,
                            onDragStopped = { velocity ->
                                if (shouldDismissDaySheet(sheetOffsetY, velocity, dismissThresholdPx)) {
                                    onDismiss()
                                } else {
                                    sheetOffsetY = 0f
                                }
                            }
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .height(28.dp)
                            .width(42.dp)
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .height(4.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }
                    if (selectedIds.isEmpty()) {
                        Text(
                            date?.format(fullDate)?.replaceFirstChar { it.titlecase(Locale("ru")) } ?: "Без даты",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                        )
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 18.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (selectedIds.isNotEmpty()) {
                        SelectionHeader(
                            count = selectedIds.size,
                            action = "В корзину",
                            onCancel = { selectedIds = emptySet() },
                            onAction = { onTrashSelected(selectedIds) }
                        )
                    }
                    tasks.forEach { task ->
                        CompactDayTask(
                            task = task,
                            selected = task.id in selectedIds,
                            selectionMode = selectedIds.isNotEmpty(),
                            onEdit = { onEdit(task) },
                            onToggle = { onToggleDone(task) },
                            onSelect = {
                                selectedIds = if (task.id in selectedIds) selectedIds - task.id else selectedIds + task.id
                            },
                            onLongPress = {
                                if (task.id !in selectedIds) selectedIds = selectedIds + task.id
                            },
                            onDragMove = { point ->
                                if (activeDragId == task.id) {
                                    onDragMove(point)
                                } else if (sheetBounds?.contains(point) == false) {
                                    activeDragId = task.id
                                    selectedIds = setOf(task.id)
                                    onDragStart(task, point)
                                }
                            },
                            onDragEnd = {
                                if (activeDragId == task.id) onDragEnd()
                                activeDragId = null
                            }
                        )
                    }
                    repeat((7 - tasks.size).coerceAtLeast(3)) {
                        Box(
                            Modifier.fillMaxWidth().height(50.dp)
                                .combinedClickable(onClick = onAdd, onLongClick = onAdd),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactDayTask(
    task: TaskEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .onGloballyPositioned { coordinates = it }
            .pointerInput(task.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onLongPress() },
                    onDrag = { change, _ ->
                        change.consume()
                        coordinates?.localToRoot(change.position)?.let(onDragMove)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd
                )
            }
            .clickable(onClick = if (selectionMode) onSelect else onEdit),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Box(
                Modifier.size(28.dp).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                    .clickable(onClick = onSelect),
                contentAlignment = Alignment.Center
            ) { if (selected) Text("✓", color = MaterialTheme.colorScheme.onPrimary) }
        } else {
            Checkbox(checked = task.completedAt != null, onCheckedChange = { onToggle() })
        }
        task.color?.let { Box(Modifier.size(9.dp).background(Color(it), CircleShape)) }
        Spacer(Modifier.width(8.dp))
        Text(
            buildString {
                task.timeMinutes?.let { append("%02d:%02d  ".format(it / 60, it % 60)) }
                if (task.repeatRule != null) append("↻ ")
                if (task.important) append("★ ")
                append(task.title)
            },
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textDecoration = if (task.completedAt != null) TextDecoration.LineThrough else null,
            color = if (task.isOverdue()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
        Text("✎", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.primary)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun FloatingTaskCard(task: TaskEntity, pointInRoot: Offset) {
    Surface(
        modifier = Modifier
            .offset {
                IntOffset(
                    (pointInRoot.x - 28.dp.toPx()).roundToInt(),
                    (pointInRoot.y - 22.dp.toPx()).roundToInt()
                )
            }
            .widthIn(max = 260.dp)
            .zIndex(30f),
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 12.dp
    ) {
        Text(
            (if (task.repeatRule != null) "↻ " else "") +
                (if (task.important) "★ " else "") + task.title,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private enum class AllTasksTab { ACTUAL, COMPLETED, DELETED }

@Composable
private fun AllTasksScreen(model: V2ViewModel, active: List<TaskEntity>, trash: List<TaskEntity>) {
    var tabName by rememberSaveable { mutableStateOf(AllTasksTab.ACTUAL.name) }
    val tab = AllTasksTab.entries.firstOrNull { it.name == tabName } ?: AllTasksTab.ACTUAL
    var confirmPermanent by remember { mutableStateOf(false) }
    val current = remember(active) { active.filter { it.completedAt == null } }
    val completed = remember(active) {
        active.filter { it.completedAt != null }.sortedByDescending { it.completedAt }
    }
    val inTrash = tab == AllTasksTab.DELETED
    val selectedIds = if (inTrash) model.selectedTrashIds else model.selectedTaskIds
    val shown = when (tab) {
        AllTasksTab.ACTUAL -> current
        AllTasksTab.COMPLETED -> completed
        AllTasksTab.DELETED -> trash
    }

    Column(Modifier.fillMaxSize()) {
        if (selectedIds.isNotEmpty()) {
            if (inTrash) {
                Column {
                    SelectionHeader(
                        count = selectedIds.size,
                        action = "Восстановить",
                        onCancel = { model.clearTaskSelection(true) },
                        onAction = model::restoreSelected
                    )
                    TextButton(onClick = { confirmPermanent = true }, modifier = Modifier.align(Alignment.End)) {
                        Text("Удалить навсегда", color = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                SelectionHeader(
                    count = selectedIds.size,
                    action = "В корзину",
                    onCancel = { model.clearTaskSelection(false) },
                    onAction = model::trashSelected
                )
            }
        } else {
            TabRow(selectedTabIndex = tab.ordinal) {
                AllTasksTab.entries.forEach { entry ->
                    Tab(
                        selected = tab == entry,
                        onClick = { tabName = entry.name },
                        text = {
                            Text(
                                when (entry) {
                                    AllTasksTab.ACTUAL -> "Актуальные"
                                    AllTasksTab.COMPLETED -> "Выполненные"
                                    AllTasksTab.DELETED -> "Удалённые"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
        }
        if (shown.isEmpty()) {
            EmptyState(
                when (tab) {
                    AllTasksTab.ACTUAL -> "Нет актуальных дел"
                    AllTasksTab.COMPLETED -> "Выполненные дела появятся здесь"
                    AllTasksTab.DELETED -> "Удалённые дела можно будет восстановить здесь"
                }
            )
        } else LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown, key = { it.id }) { task ->
                TaskListRow(
                    task = task,
                    selected = task.id in selectedIds,
                    selectionMode = inTrash || selectedIds.isNotEmpty(),
                    trash = inTrash,
                    onSelect = { model.toggleTaskSelection(task.id, inTrash) },
                    onClick = {
                        if (inTrash) model.toggleTaskSelection(task.id, true) else model.edit(task)
                    },
                    onToggleDone = { if (!inTrash) model.toggleCompleted(task) }
                )
            }
        }
    }

    if (confirmPermanent) {
        AlertDialog(
            onDismissRequest = { confirmPermanent = false },
            title = { Text("Удалить безвозвратно?") },
            text = { Text("Выбранные дела уже нельзя будет восстановить.") },
            dismissButton = { TextButton(onClick = { confirmPermanent = false }) { Text("Отмена") } },
            confirmButton = {
                TextButton(onClick = { confirmPermanent = false; model.deleteSelectedForever() }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
}

@Composable
private fun TaskListRow(
    task: TaskEntity,
    selected: Boolean,
    selectionMode: Boolean,
    trash: Boolean,
    onSelect: () -> Unit,
    onClick: () -> Unit,
    onToggleDone: () -> Unit
) {
    val actualClick = if (selectionMode) onSelect else onClick
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(onClick = actualClick, onLongClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Box(
                Modifier.size(28.dp).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center
            ) { if (selected) Text("✓", color = MaterialTheme.colorScheme.onPrimary) }
            Spacer(Modifier.width(10.dp))
        } else {
            Checkbox(checked = task.completedAt != null, onCheckedChange = { onToggleDone() })
        }
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                task.color?.let {
                    Box(
                        Modifier.width(5.dp).fillMaxHeight()
                            .background(Color(it), RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.width(9.dp))
                }
                Text(
                    (if (task.important) "★ " else "") + task.title,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (task.important) FontWeight.SemiBold else FontWeight.Normal,
                    textDecoration = if (task.completedAt != null) TextDecoration.LineThrough else null,
                    color = if (!trash && task.isOverdue()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
            val actual = !trash && task.completedAt == null
            val metadata = listOfNotNull(
                if (actual) task.actualListDateText() else task.dateOrNull()?.format(listDate),
                when {
                    actual && task.isOverdue() -> "Просрочено"
                    actual && task.isUnscheduled() -> "Без даты"
                    else -> null
                },
                RepeatRule.from(task.repeatRule)?.title
            ).joinToString(" · ")
            if (metadata.isNotEmpty()) {
                Text(
                    metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (actual && task.isOverdue()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (task.note.isNotBlank()) Text(task.note, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SelectionHeader(count: Int, action: String, onCancel: () -> Unit, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) { Text("✕") }
            Text("Выбрано: $count", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
