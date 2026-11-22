package com.example.tasksync.ui.tasks

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.tasksync.data.local.entity.ProjectEntity
import com.example.tasksync.data.local.entity.SubtaskEntity
import com.example.tasksync.data.local.entity.TaskEntity
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
    onSaveTask: (String, String?, String?, String?, Int, String?, Boolean, String?) -> Unit,
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
    var reminderEnabled by remember(task) { mutableStateOf(task?.reminderEnabled ?: false) }
    var recurrenceRule by remember(task) { mutableStateOf(task?.recurrenceRule ?: "none") }
    var priority by remember(task) { mutableIntStateOf(task?.priority ?: 4) }
    var projectId by remember(task, initialProjectId) { mutableStateOf(task?.projectId ?: initialProjectId) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var newSubtaskTitle by remember { mutableStateOf("") }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    var showNotificationSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
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

    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = if (task == null) "Create task" else "Edit task",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it; validationMessage = null },
                label = { Text("Task title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Notes (optional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            Text("Deadline", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showDatePicker = true }) {
                    Text(dueDate.ifBlank { "Choose date" })
                }
                if (dueDate.isNotBlank()) {
                    TextButton(onClick = { dueDate = ""; dueTime = "" }) { Text("Clear") }
                }
            }
            OutlinedButton(onClick = { showTimePicker = true }) {
                androidx.compose.material3.Icon(Icons.Default.AccessTime, contentDescription = "Choose time")
                Text(
                    dueTime.takeIf(String::isNotBlank)?.let {
                        runCatching { LocalTime.parse(it).format(DateTimeFormatter.ofPattern("h:mm a")) }.getOrDefault(it)
                    } ?: "Set time"
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Reminder", style = MaterialTheme.typography.labelLarge)
                    Text("Notify me around the deadline time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    enabled = dueDate.isNotBlank() && dueTime.isNotBlank()
                )
            }
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
                            reminderEnabled && (dueDate.isBlank() || dueTime.isBlank()) -> validationMessage = "Choose a deadline date and time to set a reminder."
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
                                    reminderEnabled, recurrenceRule.takeIf { it != "none" }
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
}

private fun String.isIsoDate(): Boolean = runCatching { LocalDate.parse(this) }.isSuccess

private fun String.toEpochMillisOrNull(): Long? = runCatching {
    LocalDate.parse(this).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}.getOrNull()
