package com.example.tasksync.ui.tasks

import android.Manifest
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tasksync.data.calendar.GoogleDeviceCalendar
import com.example.tasksync.data.calendar.ExternalCalendarEvent
import com.example.tasksync.data.local.entity.TaskEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun CalendarScreen(
    autoConnectSignal: Int = 0,
    widgetSelectedDateSignal: Int = 0,
    widgetSelectedDate: String? = null,
    viewModel: TaskListViewModel = viewModel(),
) {
    val allTasks by viewModel.allTasks.collectAsState()
    val projects by viewModel.projects.collectAsState()
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var selectedTask by remember { mutableStateOf<TaskEntity?>(null) }
    var showTaskSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connectedCalendarLabel by remember(viewModel) { mutableStateOf(viewModel.connectedGoogleCalendarLabel) }
    var availableCalendars by remember { mutableStateOf<List<GoogleDeviceCalendar>>(emptyList()) }
    var showCalendarPicker by remember { mutableStateOf(false) }
    var showDisconnectPrompt by remember { mutableStateOf(false) }
    var calendarMessage by remember { mutableStateOf("") }
    var externalEvents by remember { mutableStateOf<List<ExternalCalendarEvent>>(emptyList()) }
    var defaultEventDuration by remember(viewModel) { mutableStateOf(viewModel.defaultTimedEventDurationMinutes) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val subtasks by remember(selectedTask?.id) { viewModel.subtasksForTask(selectedTask?.id.orEmpty()) }
        .collectAsState(initial = emptyList())
    val monthStart = selectedDate.withDayOfMonth(1)
    val gridStart = monthStart.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
    val monthDays = (0L until 42L).map { gridStart.plusDays(it) }
    val dateTasks = allTasks.filter { it.dueDate == selectedDate.toString() }
    val taskSyncEventIds = remember(allTasks) { allTasks.mapNotNull { it.googleCalendarEventId }.toSet() }
    val untimedTasks = dateTasks.filter { it.dueTime.isNullOrBlank() }
    val timedTasks = dateTasks.filterNot { it.dueTime.isNullOrBlank() }

    fun loadGoogleCalendars() {
        scope.launch {
            runCatching { viewModel.availableGoogleCalendars() }
                .onSuccess { calendars ->
                    availableCalendars = calendars
                    if (calendars.isEmpty()) calendarMessage = "No writable Google calendars are available on this device. Add a Google account and enable calendar sync, then try again."
                    else showCalendarPicker = true
                }
                .onFailure { calendarMessage = it.message ?: "Could not read calendars on this device." }
        }
    }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.READ_CALENDAR] == true && grants[Manifest.permission.WRITE_CALENDAR] == true) loadGoogleCalendars()
        else calendarMessage = "Calendar access is needed to add and update TaskSync events in your selected Google calendar."
    }

    fun refreshExternalEvents() {
        scope.launch {
            if (connectedCalendarLabel == null) {
                externalEvents = emptyList()
                return@launch
            }
            runCatching { viewModel.calendarEventsForDate(selectedDate, taskSyncEventIds) }
                .onSuccess { externalEvents = it }
                .onFailure {
                    externalEvents = emptyList()
                    calendarMessage = "Could not refresh Google Calendar events. TaskSync tasks remain available."
                }
        }
    }

    LaunchedEffect(autoConnectSignal) {
        // A permission/onboarding signal may be restored when returning to this tab.
        // Never reopen the calendar chooser when a destination is already saved.
        if (autoConnectSignal > 0 && connectedCalendarLabel == null) loadGoogleCalendars()
    }
    LaunchedEffect(widgetSelectedDateSignal, widgetSelectedDate) {
        if (widgetSelectedDateSignal > 0) {
            widgetSelectedDate?.let { raw -> runCatching { LocalDate.parse(raw) }.getOrNull() }?.let { selectedDate = it }
        }
    }
    LaunchedEffect(selectedDate, connectedCalendarLabel, taskSyncEventIds) { refreshExternalEvents() }
    DisposableEffect(context, connectedCalendarLabel, lifecycleOwner) {
        if (connectedCalendarLabel == null ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED
        ) return@DisposableEffect onDispose { }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { refreshExternalEvents() }
        }
        context.contentResolver.registerContentObserver(CalendarContract.Instances.CONTENT_URI, true, observer)
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshExternalEvents()
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = { }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp).pointerInput(selectedDate) {
                    var drag = 0f
                    detectHorizontalDragGestures(onHorizontalDrag = { _, amount -> drag += amount }, onDragEnd = {
                        if (drag < -60f) selectedDate = selectedDate.plusMonths(1)
                        if (drag > 60f) selectedDate = selectedDate.minusMonths(1)
                        drag = 0f
                    }, onDragCancel = { drag = 0f })
                }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(selectedDate.format(DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())), style = MaterialTheme.typography.headlineLarge)
                        connectedCalendarLabel?.let { Text("Google Calendar · $it", style = MaterialTheme.typography.labelSmall, color = Color(0xFFA9B3D6), maxLines = 1) }
                        viewModel.connectedGoogleCalendarLastSyncMillis?.let { syncedAt ->
                            Text("Synced ${java.time.Instant.ofEpochMilli(syncedAt).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a"))}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8DE4C5), maxLines = 1)
                        }
                        viewModel.connectedGoogleCalendarSyncError?.let { code ->
                            Text("Sync needs attention · $code", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1)
                        }
                    }
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = CircleShape,
                        color = if (connectedCalendarLabel == null) Color(0xFF3158D9).copy(alpha = 0.24f) else Color(0xFF1C8C73).copy(alpha = 0.22f),
                        border = BorderStroke(1.dp, (if (connectedCalendarLabel == null) Color(0xFF7399FF) else Color(0xFF6AD6B3)).copy(alpha = 0.4f)),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            IconButton(onClick = {
                                if (connectedCalendarLabel != null) {
                                    showDisconnectPrompt = true
                                } else {
                                    val hasPermissions = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
                                    if (hasPermissions) loadGoogleCalendars() else calendarPermissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                                }
                            }) {
                                Icon(
                                    Icons.Default.CalendarToday,
                                    contentDescription = if (connectedCalendarLabel == null) "Connect Google Calendar" else "Manage Google Calendar connection",
                                    tint = if (connectedCalendarLabel == null) Color(0xFF9DB7FF) else Color(0xFF8DE4C5),
                                )
                            }
                            Box(
                                Modifier.align(Alignment.BottomEnd).padding(3.dp).size(15.dp).background(
                                    if (connectedCalendarLabel == null) Color(0xFF4E7BFF) else Color(0xFF2DAC8B),
                                    CircleShape,
                                ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (connectedCalendarLabel == null) Icons.Default.Add else Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(10.dp),
                                )
                            }
                        }
                    }
                    IconButton(onClick = { selectedTask = null; showTaskSheet = true }) { Icon(Icons.Default.Add, contentDescription = "Add task for selected day", tint = MaterialTheme.colorScheme.primary) }
                }
            }
            if (calendarMessage.isNotBlank()) item { Text(calendarMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            item {
                Card(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.035f)),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            listOf("S", "M", "T", "W", "T", "F", "S").forEach { day ->
                                Text(day, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                        monthDays.chunked(7).forEach { week ->
                            Row(Modifier.fillMaxWidth()) {
                                week.forEach { date ->
                                    val selected = date == selectedDate
                                    val inMonth = date.month == selectedDate.month
                                    val hasTasks = allTasks.any { it.dueDate == date.toString() }
                                    Column(
                                        Modifier.weight(1f).height(44.dp).padding(2.dp)
                                            .clip(RoundedCornerShape(15.dp))
                                            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                            .clickable { selectedDate = date }
                                            .padding(vertical = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.labelLarge, color = if (selected) Color.White else if (inMonth) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                                        if (hasTasks) Box(Modifier.size(4.dp).background(if (selected) Color.White else Color(0xFF8AA9FF), CircleShape))
                                        else Spacer(Modifier.height(4.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                val count = dateTasks.size + externalEvents.size
                Text("${selectedDate.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())).uppercase(Locale.getDefault())}  ${selectedDate.dayOfMonth} · $count TASK${if (count == 1) "" else "S"}", style = MaterialTheme.typography.labelLarge, color = Color(0xFFA9B3D6), modifier = Modifier.padding(top = 6.dp, bottom = 5.dp), letterSpacing = 0.6.sp)
            }
            if (dateTasks.isEmpty() && externalEvents.isEmpty()) item { Text("Nothing planned for this day.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 14.dp)) }
            items(dateTasks.sortedBy { it.dueTime ?: "99:99" }, key = { "day_${it.id}" }) { task ->
                CalendarTaskCard(task, time = task.dueTime?.let { runCatching { LocalTime.parse(it).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull() }) { selectedTask = it; showTaskSheet = true }
            }
            items(externalEvents, key = { "external_${it.id}" }) { event -> ExternalEventCard(event) }
            item { Spacer(Modifier.height(20.dp)) }
        }

        if (showTaskSheet) {
            TaskDetailBottomSheet(
                task = selectedTask,
                projects = projects,
                subtasks = subtasks,
                initialDueDate = if (selectedTask == null) selectedDate.toString() else null,
                onDismissRequest = { showTaskSheet = false },
                onSaveTask = { title, description, date, time, priority, projectId, reminderEnabled, recurrenceRule, reminderOffsets, customReminderAt ->
                    val task = selectedTask
                    if (task == null) viewModel.createTask(title, description, "inbox", date, time, reminderEnabled, recurrenceRule, priority, projectId, reminderOffsets, customReminderAt)
                    else viewModel.updateTask(task.copy(title = title, description = description, dueDate = date, dueTime = time, priority = priority, projectId = projectId, reminderEnabled = reminderEnabled, recurrenceRule = recurrenceRule), reminderOffsets, customReminderAt)
                },
                onDeleteTask = { viewModel.deleteTask(it); showTaskSheet = false },
                onAddSubtask = { title -> selectedTask?.let { viewModel.addSubtask(it.id, title) } },
                onToggleSubtask = viewModel::toggleSubtask,
                onDeleteSubtask = viewModel::deleteSubtask
            )
        }

        if (showCalendarPicker) {
            AlertDialog(
                onDismissRequest = { showCalendarPicker = false },
                title = { Text("Choose a Google calendar") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Dated TaskSync tasks sync one way. Tasks with no time become all-day events; timed tasks use a 30-minute event.", style = MaterialTheme.typography.bodySmall)
                        availableCalendars.forEach { calendar ->
                            TextButton(
                                onClick = {
                                    showCalendarPicker = false
                                    scope.launch {
                                        calendarMessage = "Syncing dated tasks…"
                                        val errors = viewModel.connectGoogleCalendar(calendar)
                                        connectedCalendarLabel = "${calendar.displayName} · ${calendar.accountName}"
                                        com.example.tasksync.widget.TaskSyncCalendarWidgetObserver.register(context)
                                        com.example.tasksync.widget.CalendarWidgetRefreshScheduler.ensurePeriodic(context)
                                        com.example.tasksync.widget.CalendarWidgetRefreshScheduler.enqueueImmediate(context)
                                        calendarMessage = if (errors.isEmpty()) "Dated tasks are syncing to this calendar." else "Connected, but ${errors.size} event(s) could not sync. ${errors.first()}"
                                    }
                                }
                            ) {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                    Text(calendar.displayName)
                                    Text(calendar.accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showCalendarPicker = false }) { Text("Cancel") } }
            )
        }

        if (showDisconnectPrompt) {
            AlertDialog(
                onDismissRequest = { showDisconnectPrompt = false },
                title = { Text("Google Calendar") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("You can sync tasks again, or disconnect. If you keep events, TaskSync-owned events remain in Google Calendar and TaskSync stops managing them. Removing events deletes only events linked to TaskSync tasks.")
                        Text("Default timed event length", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(15, 30, 45, 60).forEach { minutes ->
                                androidx.compose.material3.FilterChip(
                                    selected = defaultEventDuration == minutes,
                                    onClick = { defaultEventDuration = minutes; viewModel.setDefaultTimedEventDurationMinutes(minutes) },
                                    label = { Text("${minutes}m") },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Row {
                    TextButton(onClick = {
                        scope.launch {
                            calendarMessage = "Syncing dated tasks…"
                            val errors = viewModel.resyncSelectedGoogleCalendar()
                            calendarMessage = if (errors.isEmpty()) "Google Calendar is up to date." else "Some tasks could not sync. ${errors.first()}"
                        }
                        showDisconnectPrompt = false
                    }) { Text("Sync now") }
                    TextButton(onClick = {
                        showDisconnectPrompt = false
                        loadGoogleCalendars()
                    }) { Text("Change account") }
                    TextButton(onClick = {
                        showDisconnectPrompt = false
                        scope.launch {
                            val errors = viewModel.disconnectGoogleCalendar(removeOwnedEvents = true)
                            if (errors.isEmpty()) {
                                connectedCalendarLabel = null
                                com.example.tasksync.widget.TaskSyncCalendarWidgetObserver.unregister()
                                com.example.tasksync.widget.CalendarWidgetRefreshScheduler.cancelPeriodic(context)
                                com.example.tasksync.widget.CalendarWidgetRefreshScheduler.enqueueImmediate(context)
                                calendarMessage = "Calendar disconnected and linked TaskSync events removed."
                            }
                            else calendarMessage = "Some linked events could not be removed. ${errors.first()}"
                        }
                    }) { Text("Remove & disconnect") }
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            showDisconnectPrompt = false
                            scope.launch {
                                val errors = viewModel.disconnectGoogleCalendar(removeOwnedEvents = false)
                                connectedCalendarLabel = null
                                com.example.tasksync.widget.TaskSyncCalendarWidgetObserver.unregister()
                                com.example.tasksync.widget.CalendarWidgetRefreshScheduler.cancelPeriodic(context)
                                com.example.tasksync.widget.CalendarWidgetRefreshScheduler.enqueueImmediate(context)
                                calendarMessage = if (errors.isEmpty()) "Calendar disconnected. Existing Google Calendar events were kept." else errors.first()
                            }
                        }) { Text("Keep events & disconnect") }
                        TextButton(onClick = { showDisconnectPrompt = false }) { Text("Cancel") }
                    }
                }
            )
        }
    }
}

@Composable
private fun CalendarTaskCard(task: TaskEntity, time: String? = null, onClick: (TaskEntity) -> Unit) {
    Card(
        onClick = { onClick(task) },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
            Text(task.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            val metadata = listOfNotNull(time, task.recurrenceRule?.replaceFirstChar(Char::uppercase)).joinToString(" · ")
            if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExternalEventCard(event: ExternalCalendarEvent) {
    val timeLabel = if (event.allDay) "ALL DAY" else runCatching {
        java.time.Instant.ofEpochMilli(event.startMillis).atZone(java.time.ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
    }.getOrDefault("")
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF18362F).copy(alpha = 0.75f)),
        border = BorderStroke(1.dp, Color(0xFF65C7A6).copy(alpha = 0.35f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(event.title.ifBlank { "Calendar event" }, style = MaterialTheme.typography.bodyLarge)
                Text("Google Calendar", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8DE4C5))
            }
            Text(timeLabel, style = MaterialTheme.typography.labelMedium, color = Color(0xFFB8E7D7))
        }
    }
}
