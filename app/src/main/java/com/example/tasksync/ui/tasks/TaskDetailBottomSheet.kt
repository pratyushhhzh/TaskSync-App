package com.example.tasksync.ui.tasks

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.tasksync.data.local.entity.ProjectEntity
import com.example.tasksync.data.local.entity.SubtaskEntity
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.data.reminder.ReminderPreferences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailBottomSheet(
    task: TaskEntity?,
    projects: List<ProjectEntity> = emptyList(),
    subtasks: List<SubtaskEntity> = emptyList(),
    initialProjectId: String? = null,
    initialDueDate: String? = null,
    onDismissRequest: () -> Unit,
    onSaveTask: (String, String?, String?, String?, Int, String?, Boolean, String?, List<Int>, Long?) -> Unit,
    onAddSubtask: (String) -> Unit = {},
    onToggleSubtask: (SubtaskEntity, Boolean) -> Unit = { _, _ -> },
    onDeleteSubtask: (SubtaskEntity) -> Unit = {},
    onDeleteTask: ((TaskEntity) -> Unit)? = null
) {
    var title by remember(task) { mutableStateOf(task?.title ?: "") }
    var description by remember(task) { mutableStateOf(task?.description ?: "") }
    var dueDate by remember(task, initialDueDate) {
        mutableStateOf(task?.dueDate?.takeIf { it.isIsoDate() } ?: initialDueDate.orEmpty())
    }
    var dueTime by remember(task) { mutableStateOf(task?.dueTime.orEmpty()) }
    var reminderEnabled by remember(task) { mutableStateOf(task?.reminderEnabled ?: true) }
    var recurrenceRule by remember(task) { mutableStateOf(task?.recurrenceRule ?: "none") }
    var priority by remember(task) { mutableIntStateOf(task?.priority ?: 4) }
    var projectId by remember(task, initialProjectId) { mutableStateOf(task?.projectId ?: initialProjectId) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var newSubtaskTitle by remember { mutableStateOf("") }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    var showNotificationSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var reminderOffsets by remember(task) {
        mutableStateOf(
            task?.let { ReminderPreferences.offsetsForTask(context, it.id).toSet() }
                ?: setOf(ReminderPreferences.defaultOffsetMinutes(context))
        )
    }
    var customReminderAt by remember(task) { mutableStateOf(task?.let { ReminderPreferences.customReminderAt(context, it.id) }) }
    var customReminderDate by remember(task) {
        mutableStateOf(customReminderAt?.let { Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() }.orEmpty())
    }
    var showCustomReminderDatePicker by remember { mutableStateOf(false) }
    var showCustomReminderTimePicker by remember { mutableStateOf(false) }
    var exactAlarmUnavailable by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        reminderEnabled = granted
        validationMessage = if (granted) null else "Task can still be saved, but the reminder will not be scheduled without notification permission."
        showNotificationSettings = !granted
    }

    val pickerState = rememberDatePickerState(initialSelectedDateMillis = dueDate.toEpochMillisOrNull())
    val selectedTime = runCatching { LocalTime.parse(dueTime) }.getOrNull()
    val timePickerState = rememberTimePickerState(
        initialHour = selectedTime?.hour ?: 9,
        initialMinute = selectedTime?.minute ?: 0
    )
    val customDatePickerState = rememberDatePickerState(initialSelectedDateMillis = customReminderDate.toEpochMillisOrNull())
    val customTime = customReminderAt?.let { Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalTime() }
    val customTimePickerState = rememberTimePickerState(initialHour = customTime?.hour ?: 9, initialMinute = customTime?.minute ?: 0)

    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.68f)), contentAlignment = Alignment.BottomCenter) {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().clickable(onClick = onDismissRequest)
        )
        AnimatedVisibility(visible = true, enter = fadeIn(tween(350)) + slideInVertically(tween(550)) { it / 6 }, modifier = Modifier.align(Alignment.BottomCenter)) {
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxHeight(0.78f).heightIn(max = 700.dp),
            shape = RoundedCornerShape(36.dp),
            color = Color(0xFF101936),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
            shadowElevation = 22.dp,
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF1A2A6E).copy(alpha = 0.72f), Color(0xFF0D1230))))
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Surface(
                Modifier.align(Alignment.CenterHorizontally)
                    .padding(top = 10.dp, bottom = 16.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .pointerInput(onDismissRequest) {
                        var downwardDragPx = 0f
                        detectVerticalDragGestures(
                            onVerticalDrag = { _, dragAmount ->
                                if (dragAmount > 0f) downwardDragPx += dragAmount
                            },
                            onDragEnd = {
                                if (downwardDragPx >= 64.dp.toPx()) onDismissRequest()
                                downwardDragPx = 0f
                            },
                            onDragCancel = { downwardDragPx = 0f },
                        )
                    },
                color = Color.White.copy(alpha = 0.28f),
                shape = RoundedCornerShape(4.dp),
            ) {}
            Text("TASK DETAILS", style = MaterialTheme.typography.labelMedium, color = Color(0xFFBFD0FF), letterSpacing = 1.sp)
            Text(
                text = if (task == null) "Create task" else "Edit task",
                style = MaterialTheme.typography.headlineSmall,
                color = Color(0xFFF2F4FA),
            )
            Spacer(Modifier.height(16.dp))

            Text("TITLE", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9EABD0), letterSpacing = 1.sp)
            MinimalLineEditor(
                value = title,
                onValueChange = { title = it; validationMessage = null },
                placeholder = "What needs doing?",
                singleLine = true,
            )

            Spacer(Modifier.height(14.dp))
            Text("DESCRIPTION", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9EABD0), letterSpacing = 1.sp)
            MinimalLineEditor(
                value = description,
                onValueChange = { description = it },
                placeholder = "Add a note (optional)",
                singleLine = false,
            )

            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("WHEN", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Color(0xFF9EABD0), letterSpacing = 1.sp)
                if (dueDate.isNotBlank() || dueTime.isNotBlank()) {
                    TextButton(onClick = { dueDate = ""; dueTime = ""; reminderEnabled = false }) {
                        Text("Clear", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ScheduleChip(
                    modifier = Modifier.weight(1f),
                    label = "DATE",
                    value = dueDate.takeIf(String::isNotBlank)?.let(::formatTaskDate) ?: "Add date",
                    onClick = { showDatePicker = true },
                )
                ScheduleChip(
                    modifier = Modifier.weight(1f),
                    label = "TIME",
                    value = dueTime.takeIf(String::isNotBlank)?.let(::formatTaskTime) ?: "Add time",
                    onClick = { showTimePicker = true },
                )
            }

            Spacer(Modifier.height(10.dp))
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color.White.copy(alpha = 0.045f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.075f)),
            ) {
                Row(
                    Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Reminder", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFF2F4FA))
                        Text(
                            if ((dueDate.isNotBlank() && dueTime.isNotBlank()) || customReminderAt != null) "Alerts are enabled for this task"
                            else "Set a task deadline or custom alert time",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = reminderEnabled,
                        onCheckedChange = { requested ->
                            if (!requested) {
                                reminderEnabled = false
                                validationMessage = null
                                showNotificationSettings = false
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                val notifications = NotificationManagerCompat.from(context)
                                val channelDisabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                    context.getSystemService(android.app.NotificationManager::class.java)
                                        .getNotificationChannel(com.example.tasksync.data.reminder.TaskReminderScheduler.CHANNEL_ID)
                                        ?.importance == android.app.NotificationManager.IMPORTANCE_NONE
                                if (!notifications.areNotificationsEnabled() || channelDisabled) {
                                    reminderEnabled = false
                                    validationMessage = "Task can still be saved, but reminders are disabled in Android notification settings."
                                    showNotificationSettings = true
                                } else {
                                    reminderEnabled = true
                                    validationMessage = null
                                    showNotificationSettings = false
                                }
                            }
                        },
                        enabled = (dueDate.isNotBlank() && dueTime.isNotBlank()) || customReminderAt != null,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showCustomReminderDatePicker = true }) {
                    Text(customReminderAt?.let { "Custom alert · ${formatTaskDate(Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString())}" } ?: "Set custom alert time")
                }
                if (customReminderAt != null) TextButton(onClick = { customReminderAt = null; customReminderDate = "" }) { Text("Clear") }
            }
            if (customReminderDate.isNotBlank() && customReminderAt == null) {
                TextButton(onClick = { showCustomReminderTimePicker = true }) { Text("Choose alert time for ${formatTaskDate(customReminderDate)}") }
            }
            if (reminderEnabled) {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("REMIND ME", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9EABD0), letterSpacing = 0.8.sp)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5 to "5 min", 10 to "10 min", 15 to "15 min", 30 to "30 min", 60 to "1 hour", 1440 to "1 day", 0 to "At deadline").forEach { (offset, label) ->
                            FilterChip(
                                selected = offset in reminderOffsets,
                                onClick = {
                                    reminderOffsets = if (offset in reminderOffsets) reminderOffsets - offset else reminderOffsets + offset
                                    if (reminderOffsets.isEmpty()) reminderOffsets = setOf(offset)
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text("Choose one or more alerts. They remain separate from the task deadline.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !com.example.tasksync.data.reminder.TaskReminderScheduler.exactAlarmAvailable(context)) {
                        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color(0xFFB98227).copy(alpha = 0.13f), border = BorderStroke(1.dp, Color(0xFFFFCB71).copy(alpha = 0.24f))) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Text("Android may delay reminders without precise alarm access.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFD892))
                                TextButton(onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(android.net.Uri.parse("package:${context.packageName}")))
                                    }.onFailure { exactAlarmUnavailable = true }
                                }) { Text("Allow precise reminders") }
                            }
                        }
                    }
                }
            }
            if (exactAlarmUnavailable) Text("Precise alarm settings could not be opened. You can still use reminders, but Android may delay delivery.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (showNotificationSettings) {
                TextButton(onClick = {
                    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, com.example.tasksync.data.reminder.TaskReminderScheduler.CHANNEL_ID)
                    context.startActivity(intent)
                }) { Text("Open reminder notification settings") }
            }

            Spacer(Modifier.height(16.dp))
            Text("Repeat", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("none" to "Never", "daily" to "Daily", "weekdays" to "Weekdays", "weekly" to "Weekly", "monthly" to "Monthly").forEach { (value, label) ->
                    FilterChip(selected = recurrenceRule == value, onClick = { recurrenceRule = value }, label = { Text(label) })
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Priority", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(1 to "High", 2 to "Medium", 3 to "Low", 4 to "None").forEach { (value, label) ->
                    FilterChip(
                        selected = priority == value,
                        onClick = { priority = value },
                        label = { Text("P$value $label") }
                    )
                }
            }

            if (projects.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Project", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = projectId == null,
                        onClick = { projectId = null },
                        label = { Text("No project") }
                    )
                    projects.forEach { project ->
                        FilterChip(
                            selected = projectId == project.id,
                            onClick = { projectId = project.id },
                            label = { Text(project.name) }
                        )
                    }
                }
            }

            if (task != null) {
                Spacer(Modifier.height(18.dp))
                Text("Subtasks", style = MaterialTheme.typography.labelLarge)
                subtasks.forEach { subtask ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = subtask.isCompleted,
                            onCheckedChange = { onToggleSubtask(subtask, it) }
                        )
                        Text(subtask.title, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onDeleteSubtask(subtask) }) { Text("Remove") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newSubtaskTitle,
                        onValueChange = { newSubtaskTitle = it },
                        label = { Text("Add a subtask") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            onAddSubtask(newSubtaskTitle)
                            newSubtaskTitle = ""
                        },
                        enabled = newSubtaskTitle.isNotBlank()
                    ) { Text("Add") }
                }
            }

            validationMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (task != null && onDeleteTask != null) {
                    OutlinedButton(
                        onClick = { onDeleteTask(task) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Delete") }
                }
                Button(
                    onClick = {
                        when {
                            title.isBlank() -> validationMessage = "Enter a task title."
                            reminderEnabled && dueDate.isNotBlank() && dueTime.isNotBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                                validationMessage = "Allow notifications to schedule this reminder. You can still save the task without one."
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            reminderEnabled && runCatching {
                                java.time.LocalDateTime.of(LocalDate.parse(dueDate), LocalTime.parse(dueTime))
                                    .atZone(java.time.ZoneId.systemDefault()).isBefore(java.time.ZonedDateTime.now())
                            }.getOrDefault(false) -> validationMessage = "The reminder date and time have passed. Choose a future time or turn the reminder off."
                            recurrenceRule != "none" && dueDate.isBlank() -> validationMessage = "Choose a deadline date for a repeating task."
                            dueTime.isNotBlank() && runCatching { LocalTime.parse(dueTime) }.isFailure ->
                                validationMessage = "Choose a valid time."
                            else -> {
                                onSaveTask(
                                    title.trim(), description.ifBlank { null }, dueDate.ifBlank { null },
                                    dueTime.ifBlank { null }, priority, projectId,
                                    reminderEnabled && ((dueDate.isNotBlank() && dueTime.isNotBlank()) || customReminderAt != null), recurrenceRule.takeIf { it != "none" },
                                    reminderOffsets.toList(), customReminderAt,
                                )
                                onDismissRequest()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Save task") }
            }
        }
        }
        }
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dueDate = pickerState.selectedDateMillis?.let {
                        Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()
                    }.orEmpty()
                    showDatePicker = false
                }) { Text("Set date") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Choose a time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    dueTime = LocalTime.of(timePickerState.hour, timePickerState.minute)
                        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    validationMessage = null
                    showTimePicker = false
                }) { Text("Set time") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } }
        )
    }

    if (showCustomReminderDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showCustomReminderDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    customReminderDate = customDatePickerState.selectedDateMillis?.let {
                        Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()
                    }.orEmpty()
                    showCustomReminderDatePicker = false
                    if (customReminderDate.isNotBlank()) showCustomReminderTimePicker = true
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { showCustomReminderDatePicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = customDatePickerState) }
    }

    if (showCustomReminderTimePicker) {
        AlertDialog(
            onDismissRequest = { showCustomReminderTimePicker = false },
            title = { Text("Custom reminder time") },
            text = { TimePicker(state = customTimePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    customReminderAt = runCatching {
                        LocalDate.parse(customReminderDate).atTime(customTimePickerState.hour, customTimePickerState.minute)
                            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.getOrNull()
                    showCustomReminderTimePicker = false
                }) { Text("Set alert") }
            },
            dismissButton = { TextButton(onClick = { showCustomReminderTimePicker = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MinimalLineEditor(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
) {
    Column(Modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
            singleLine = singleLine,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFFF2F4FA)),
            decorationBox = { innerTextField ->
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF7783A7))
                    }
                    innerTextField()
                }
            },
        )
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.18f)))
    }
}

@Composable
private fun ScheduleChip(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.045f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF9EABD0), letterSpacing = 0.8.sp)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFF2F4FA), modifier = Modifier.padding(top = 3.dp), maxLines = 1)
        }
    }
}

private fun formatTaskDate(value: String): String = runCatching {
    LocalDate.parse(value).format(DateTimeFormatter.ofPattern("EEE, d MMM"))
}.getOrDefault(value)

private fun formatTaskTime(value: String): String = runCatching {
    LocalTime.parse(value).format(DateTimeFormatter.ofPattern("h:mm a"))
}.getOrDefault(value)

private fun String.isIsoDate(): Boolean = runCatching { LocalDate.parse(this) }.isSuccess

private fun String.toEpochMillisOrNull(): Long? = runCatching {
    LocalDate.parse(this).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}.getOrNull()
