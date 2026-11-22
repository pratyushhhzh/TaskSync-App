package com.example.tasksync.ui.tasks

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.data.reminder.ReminderPreferences
import com.example.tasksync.data.reminder.TaskReminderScheduler
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.layout.positionInRoot
import java.util.Locale

@Composable
fun TasksScreen(
    onOpenInbox: () -> Unit = {},
    openTaskSignal: Int = 0,
    openTaskId: String? = null,
    viewModel: TaskListViewModel = viewModel(),
) {
    val allTasks by viewModel.allTasks.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val projectNames = remember(projects) { projects.associate { it.id to it.name } }
    val context = LocalContext.current
    var selectedTask by remember { mutableStateOf<TaskEntity?>(null) }
    var showTaskSheet by remember { mutableStateOf(false) }
    var showSummary by remember { mutableStateOf(false) }
    var showReminderSettings by remember { mutableStateOf(false) }
    var homeOrder by remember { mutableStateOf(emptyList<String>()) }
    var homeOrderLoaded by remember { mutableStateOf(false) }
    var draggingTaskId by remember { mutableStateOf<String?>(null) }
    var dragPointerY by remember { mutableFloatStateOf(0f) }
    val taskBounds = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
    val today = LocalDate.now()

    LaunchedEffect(context) {
        homeOrder = HomeTaskOrderStore.load(context)
        homeOrderLoaded = true
    }

    LaunchedEffect(openTaskSignal, openTaskId, allTasks) {
        if (openTaskSignal <= 0 || openTaskId == null) return@LaunchedEffect
        allTasks.firstOrNull { it.id == openTaskId }?.let { task ->
            selectedTask = task
            showTaskSheet = true
        }
    }

    LaunchedEffect(allTasks, homeOrderLoaded) {
        if (!homeOrderLoaded || allTasks.isEmpty()) return@LaunchedEffect
        val currentIds = allTasks.mapTo(HashSet()) { it.id }
        val retained = homeOrder.filter { it in currentIds }
        val newIds = allTasks
            .filter { it.id !in retained }
            .sortedByDescending { it.createdAt }
            .map { it.id }
        val merged = newIds + retained
        if (merged != homeOrder) {
            homeOrder = merged
            HomeTaskOrderStore.save(context, merged)
        }
    }

    val pendingTasks = allTasks.filter { it.status != "completed" }
    val highPriorityCount = pendingTasks.count { it.priority == 1 }
    val overdueCount = pendingTasks.count { task ->
        task.dueDate?.let { it.isIsoDate() && LocalDate.parse(it).isBefore(today) } == true
    }
    val nextUp = pendingTasks.filter { task ->
        task.dueDate?.let { it.isIsoDate() && !LocalDate.parse(it).isBefore(today) } == true
    }.minWithOrNull(compareBy<TaskEntity>({ it.dueDate }, { it.dueTime ?: "99:99" }))

    val homeOrderIndex = remember(homeOrder) { homeOrder.withIndex().associate { it.value to it.index } }
    val homeTasks = remember(allTasks, homeOrderIndex) {
        allTasks.sortedWith(
            compareBy<TaskEntity>(
                { it.status == "completed" },
                { it.priority != 1 },
                { homeOrderIndex[it.id] ?: Int.MIN_VALUE },
                { -it.createdAt },
            ),
        )
    }

    fun moveTask(draggedId: String, targetId: String) {
        if (draggedId == targetId) return
        val reordered = homeTasks.map { it.id }.toMutableList()
        val fromIndex = reordered.indexOf(draggedId)
        val targetIndex = reordered.indexOf(targetId)
        if (fromIndex < 0 || targetIndex < 0) return
        val movedId = reordered.removeAt(fromIndex)
        reordered.add(targetIndex, movedId)
        homeOrder = reordered
        HomeTaskOrderStore.save(context, reordered)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x263D6BFF), MaterialTheme.colorScheme.background), radius = 1050f)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            var verticalDrag by remember { mutableStateOf(0f) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clickable { showSummary = !showSummary }
                    .pointerInput(showSummary) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                verticalDrag += dragAmount
                            },
                            onDragEnd = {
                                if (verticalDrag > 48f) showSummary = true
                                if (verticalDrag < -48f) showSummary = false
                                verticalDrag = 0f
                            },
                            onDragCancel = { verticalDrag = 0f },
                        )
                    },
                verticalAlignment = Alignment.Top,
            ) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(today.dayOfMonth.toString(), style = MaterialTheme.typography.displayLarge.copy(fontSize = 68.sp, lineHeight = 70.sp), color = MaterialTheme.colorScheme.onBackground)
                    Box(Modifier.padding(top = 15.dp).size(10.dp).clip(CircleShape).background(Color(0xFFFF5A4D)))
                }
                Spacer(Modifier.weight(1f))
                Column(Modifier.padding(top = 12.dp), horizontalAlignment = Alignment.End) {
                    Text(today.format(DateTimeFormatter.ofPattern("MMM’yy", Locale.getDefault())), style = MaterialTheme.typography.titleSmall, color = Color(0xFFA9B3D6))
                    Text(today.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())), style = MaterialTheme.typography.titleSmall, color = Color(0xFFA9B3D6))
                }
            }
        }
        item {
            AnimatedVisibility(
                visible = showSummary,
                enter = fadeIn() + slideInVertically { -it / 3 },
                exit = fadeOut() + slideOutVertically { -it / 3 },
            ) {
                TaskSummaryCard(
                    pendingCount = pendingTasks.size,
                    highPriorityCount = highPriorityCount,
                    overdueCount = overdueCount,
                )
            }
        }
        item { NextUpCard(task = nextUp, projectName = nextUp?.projectId?.let(projectNames::get)) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("ALL TASKS", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = Color(0xFFA9B3D6), letterSpacing = 1.2.sp)
                IconButton(onClick = { showReminderSettings = true }) {
                    Icon(Icons.Default.Notifications, contentDescription = "Reminder settings", tint = Color(0xFFA9B3D6))
                }
                IconButton(onClick = { selectedTask = null; showTaskSheet = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Create a task", tint = Color(0xFF7399FF))
                }
            }
        }
        if (homeTasks.isEmpty()) {
            item {
                EmptyTaskState(
                    title = "A little more room to think.",
                    detail = "Capture a thought with the microphone or add a task.",
                    onAddTask = { selectedTask = null; showTaskSheet = true },
                )
            }
        }
        items(homeTasks, key = { "home_${it.id}" }) { task ->
                val bounds = taskBounds[task.id]
                val isDragging = draggingTaskId == task.id
                val dragTranslation = if (isDragging && bounds != null) {
                    dragPointerY - ((bounds.first + bounds.second) / 2f)
                } else 0f
                HomeTaskRow(
                    task = task,
                    isDragging = isDragging,
                    dragTranslationY = dragTranslation,
                    onPositionChanged = { top, bottom ->
                        if (taskBounds[task.id] != (top to bottom)) taskBounds[task.id] = top to bottom
                    },
                    onClick = { selectedTask = task; showTaskSheet = true },
                    onToggle = { viewModel.toggleTaskCompletion(task, task.status != "completed") },
                    onDragStart = {
                        draggingTaskId = task.id
                        dragPointerY = bounds?.let { (it.first + it.second) / 2f } ?: 0f
                    },
                    onDrag = { dragAmount ->
                        dragPointerY += dragAmount
                        val target = homeTasks.firstOrNull { candidate ->
                            val sameCompletionGroup = (candidate.status == "completed") == (task.status == "completed")
                            val samePriorityGroup = (candidate.priority == 1) == (task.priority == 1)
                            val candidateBounds = taskBounds[candidate.id]
                            candidate.id != task.id && sameCompletionGroup && samePriorityGroup &&
                                candidateBounds?.let { dragPointerY in it.first..it.second } == true
                        }
                        target?.let { moveTask(task.id, it.id) }
                    },
                    onDragEnd = { draggingTaskId = null },
                )
        }
        item { Spacer(Modifier.height(12.dp)) }
    }

    if (showReminderSettings) {
        ReminderSettingsDialog(
            onDismiss = { showReminderSettings = false },
            onSave = { offset, atDeadline, digestEnabled, digestTime ->
                ReminderPreferences.setDefaultOffsetMinutes(context, offset)
                ReminderPreferences.setAtDeadlineEnabled(context, atDeadline)
                ReminderPreferences.setDigestEnabled(context, digestEnabled)
                ReminderPreferences.setDigestTime(context, digestTime)
                viewModel.refreshReminderSchedules()
                showReminderSettings = false
            },
        )
    }

    if (showTaskSheet) {
        val subtasks by remember(selectedTask?.id) { viewModel.subtasksForTask(selectedTask?.id.orEmpty()) }
            .collectAsState(initial = emptyList())
        TaskDetailBottomSheet(
            task = selectedTask,
            projects = projects,
            subtasks = subtasks,
            onDismissRequest = { showTaskSheet = false },
            onSaveTask = { title, description, date, time, priority, projectId, reminderEnabled, recurrenceRule, reminderOffsets, customReminderAt ->
                val task = selectedTask
                if (task == null) viewModel.createTask(title, description, "inbox", date, time, reminderEnabled, recurrenceRule, priority, projectId, reminderOffsets, customReminderAt)
                else viewModel.updateTask(task.copy(title = title, description = description, dueDate = date, dueTime = time, reminderEnabled = reminderEnabled, recurrenceRule = recurrenceRule, priority = priority, projectId = projectId), reminderOffsets, customReminderAt)
            },
            onDeleteTask = { viewModel.deleteTask(it); showTaskSheet = false },
            onAddSubtask = { title -> selectedTask?.let { viewModel.addSubtask(it.id, title) } },
            onToggleSubtask = viewModel::toggleSubtask,
            onDeleteSubtask = viewModel::deleteSubtask,
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReminderSettingsDialog(
    onDismiss: () -> Unit,
    onSave: (Int, Boolean, Boolean, LocalTime) -> Unit,
) {
    val context = LocalContext.current
    var defaultOffset by remember { mutableStateOf(ReminderPreferences.defaultOffsetMinutes(context)) }
    var atDeadline by remember { mutableStateOf(ReminderPreferences.atDeadlineEnabled(context)) }
    var digestEnabled by remember { mutableStateOf(ReminderPreferences.digestEnabled(context)) }
    var showTimePicker by remember { mutableStateOf(false) }
    val initialTime = ReminderPreferences.digestTime(context)
    val timePickerState = rememberTimePickerState(initialHour = initialTime.hour, initialMinute = initialTime.minute)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reminder settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Default reminder before a task deadline", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5 to "5m", 10 to "10m", 15 to "15m", 30 to "30m", 60 to "1h", 1440 to "1d").forEach { (minutes, label) ->
                        FilterChip(selected = defaultOffset == minutes, onClick = { defaultOffset = minutes }, label = { Text(label) })
                    }
                }
                SettingsToggleRow("At-deadline reminder", atDeadline) { atDeadline = it }
                SettingsToggleRow("Previous-day deadline digest", digestEnabled) { digestEnabled = it }
                TextButton(onClick = { showTimePicker = true }, enabled = digestEnabled) {
                    Text("Digest time · ${ReminderPreferences.digestTime(context).format(DateTimeFormatter.ofPattern("h:mm a"))}")
                }
                Text(
                    if (TaskReminderScheduler.exactAlarmAvailable(context)) "Precise alarm access is available."
                    else "Android may delay reminders because precise alarm access is unavailable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                Text(if (notificationsAllowed) "Notifications are allowed." else "Notifications are currently blocked by Android settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!notificationsAllowed) TextButton(onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    context.startActivity(intent)
                }) { Text("Open notification settings") }
                if (!TaskReminderScheduler.exactAlarmAvailable(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(android.net.Uri.parse("package:${context.packageName}"))) }
                }) { Text("Allow precise alarms") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val selectedTime = LocalTime.of(timePickerState.hour, timePickerState.minute)
                onSave(defaultOffset, atDeadline, digestEnabled, selectedTime)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Daily digest time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = { TextButton(onClick = { showTimePicker = false }) { Text("Set time") } },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NextUpCard(task: TaskEntity?, projectName: String?) {
    Box(Modifier.fillMaxWidth().height(142.dp)) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), shape = RoundedCornerShape(24.dp), color = Color.White.copy(alpha = 0.045f), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))) { Spacer(Modifier.height(112.dp)) }
        Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), shape = RoundedCornerShape(24.dp), color = Color.White.copy(alpha = 0.07f), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))) { Spacer(Modifier.height(116.dp)) }
        Column(
            Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF3D6BFF), Color(0xFF2441C9))))
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Text(if (task == null) "NEXT UP" else "NEXT UP  ·  ${task.dueTime?.let(::displayTaskTime) ?: task.dueDate?.let(::displayShortDate)?.uppercase(Locale.getDefault()) ?: "SOON"}", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.82f), letterSpacing = 0.8.sp)
            Text(task?.title ?: "No upcoming deadline", style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
            Text(
                if (task == null) "Your unscheduled tasks are still in the list below."
                else listOfNotNull(task.dueDate?.let(::displayShortDate), task.dueTime?.let(::displayTaskTime), projectName).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.82f),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun TaskSummaryCard(pendingCount: Int, highPriorityCount: Int, overdueCount: Int) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF11173A).copy(alpha = 0.95f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("You have $pendingCount ${if (pendingCount == 1) "task" else "tasks"} pending", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            Text("$highPriorityCount ${if (highPriorityCount == 1) "task" else "tasks"} with high priority", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            Text("$overdueCount overdue ${if (overdueCount == 1) "task" else "tasks"}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@Composable
private fun HomeTaskRow(
    task: TaskEntity,
    isDragging: Boolean,
    dragTranslationY: Float,
    onPositionChanged: (Float, Float) -> Unit,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val completed = task.status == "completed"
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    Row(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val top = coordinates.positionInRoot().y
                onPositionChanged(top, top + coordinates.size.height)
            }
            .graphicsLayer { translationY = dragTranslationY }
            .zIndex(if (isDragging) 1f else 0f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isDragging) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .pointerInput(task.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { currentOnDragStart() },
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        currentOnDrag(dragAmount.y)
                    },
                )
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 1.dp, vertical = 10.dp)
            .animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(7.dp))
                .then(if (completed) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier.border(1.dp, Color(0xFF66708F), RoundedCornerShape(7.dp)))
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            if (completed) Icon(Icons.Default.Check, contentDescription = "Reopen ${task.title}", tint = Color.White, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(13.dp))
        Text(task.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground, textDecoration = if (completed) TextDecoration.LineThrough else TextDecoration.None, maxLines = 1)
        task.dueTime?.takeIf(String::isNotBlank)?.let { Text(displayTaskTime(it), Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall, color = Color(0xFFA9B3D6)) }
        Text("⋮⋮", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelLarge, color = Color(0xFF7683A9))
    }
    Spacer(Modifier.fillMaxWidth().padding(horizontal = 1.dp).height(1.dp).background(Color.White.copy(alpha = 0.09f)))
}

@Composable
private fun EmptyTaskState(title: String, detail: String, onAddTask: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 42.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onAddTask), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.17f)) {
            Text("Add a task", Modifier.padding(horizontal = 17.dp, vertical = 11.dp), style = MaterialTheme.typography.labelLarge, color = Color(0xFFBFD0FF))
        }
    }
}

private fun String.isIsoDate(): Boolean = runCatching { LocalDate.parse(this) }.isSuccess
private fun displayShortDate(value: String): String = runCatching { LocalDate.parse(value).format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())) }.getOrDefault(value)
private fun displayTaskTime(value: String): String = runCatching { LocalTime.parse(value).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())) }.getOrDefault(value)
private fun timeOfDayGreeting(): String = when (java.time.LocalTime.now().hour) {
    in 5..11 -> "Good morning."
    in 12..16 -> "Good afternoon."
    else -> "Good evening."
}
