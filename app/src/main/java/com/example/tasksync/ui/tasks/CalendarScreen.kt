package com.example.tasksync.ui.tasks

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tasksync.data.calendar.GoogleDeviceCalendar
import com.example.tasksync.data.local.entity.TaskEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun CalendarScreen(viewModel: TaskListViewModel = viewModel()) {
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
    val subtasks by remember(selectedTask?.id) { viewModel.subtasksForTask(selectedTask?.id.orEmpty()) }
        .collectAsState(initial = emptyList())
    val weekStart = selectedDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val week = (0..6).map { weekStart.plusDays(it.toLong()) }
    val dateTasks = allTasks.filter { it.dueDate == selectedDate.toString() }
    val untimedTasks = dateTasks.filter { it.dueTime.isNullOrBlank() }
    val timedTasks = dateTasks.filterNot { it.dueTime.isNullOrBlank() }
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { selectedTask = null; showTaskSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) { Icon(Icons.Default.Add, contentDescription = "Add task for selected day") }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 13.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(selectedDate.format(DateTimeFormatter.ofPattern("MMMM")), style = MaterialTheme.typography.headlineLarge)
                    }
                    IconButton(onClick = { selectedDate = selectedDate.minusWeeks(1) }) { Icon(Icons.Default.ChevronLeft, contentDescription = "Previous week") }
                    IconButton(onClick = { selectedDate = selectedDate.plusWeeks(1) }) { Icon(Icons.Default.ChevronRight, contentDescription = "Next week") }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = connectedCalendarLabel?.let { "Google Calendar · $it" } ?: "Google Calendar not connected",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                        if (calendarMessage.isNotBlank()) Text(calendarMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (connectedCalendarLabel == null) {
                        Button(onClick = {
                            val hasPermissions = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
                            if (hasPermissions) loadGoogleCalendars()
                            else calendarPermissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                        }) { Text("Connect") }
                    } else {
                        TextButton(onClick = { showDisconnectPrompt = true }) { Text("Disconnect") }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    week.forEach { date ->
                        val selected = date == selectedDate
                        Column(
                            modifier = Modifier.weight(1f).height(48.dp)
                                .background(if (selected) Color(0xFFF5F6FF) else Color.Transparent, RoundedCornerShape(14.dp))
                                .clickable { selectedDate = date }
                                .padding(vertical = 5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(date.format(DateTimeFormatter.ofPattern("EEE")).take(1), style = MaterialTheme.typography.labelSmall, color = if (selected) Color(0xFF22284F) else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleSmall, color = if (selected) Color(0xFF22284F) else MaterialTheme.colorScheme.onBackground)
                            if (allTasks.any { it.dueDate == date.toString() }) Box(Modifier.size(4.dp).background(if (selected) Color(0xFF22284F) else MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
                        }
                    }
                }
            }
            if (untimedTasks.isNotEmpty()) {
                item { Text("Any time", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 7.dp, bottom = 4.dp)) }
                items(untimedTasks, key = { "untimed_${it.id}" }) { task -> CalendarTaskCard(task) { selectedTask = it; showTaskSheet = true } }
            }
            items((9..20).toList(), key = { "hour_$it" }) { hour ->
                val hourTasks = timedTasks.filter { task ->
                    runCatching { LocalTime.parse(task.dueTime).hour == hour }.getOrDefault(false)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(if (hourTasks.isEmpty()) 66.dp else 78.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(String.format(Locale.ROOT, "%02d:00", hour), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp).padding(top = 2.dp))
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)))
                        hourTasks.forEach { task ->
                            CalendarTaskCard(task, time = task.dueTime?.let { runCatching { LocalTime.parse(it).format(timeFormatter) }.getOrNull() }) {
                                selectedTask = task
                                showTaskSheet = true
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }

        if (showTaskSheet) {
            TaskDetailBottomSheet(
                task = selectedTask,
                projects = projects,
                subtasks = subtasks,
                initialDueDate = if (selectedTask == null) selectedDate.toString() else null,
                onDismissRequest = { showTaskSheet = false },
                onSaveTask = { title, description, date, time, priority, projectId, reminderEnabled, recurrenceRule ->
                    val task = selectedTask
                    if (task == null) viewModel.createTask(title, description, "inbox", date, time, reminderEnabled, recurrenceRule, priority, projectId)
                    else viewModel.updateTask(task.copy(title = title, description = description, dueDate = date, dueTime = time, priority = priority, projectId = projectId, reminderEnabled = reminderEnabled, recurrenceRule = recurrenceRule))
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
                title = { Text("Disconnect Google Calendar?") },
                text = { Text("TaskSync will remove its linked events from this calendar and stop syncing dated tasks.") },
                confirmButton = {
                    TextButton(onClick = {
                        showDisconnectPrompt = false
                        scope.launch {
                            val errors = viewModel.disconnectGoogleCalendar()
                            if (errors.isEmpty()) {
                                connectedCalendarLabel = null
                                calendarMessage = "Google Calendar disconnected."
                            } else calendarMessage = "Some linked events could not be removed. ${errors.first()}"
                        }
                    }) { Text("Disconnect") }
                },
                dismissButton = { TextButton(onClick = { showDisconnectPrompt = false }) { Text("Cancel") } }
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
