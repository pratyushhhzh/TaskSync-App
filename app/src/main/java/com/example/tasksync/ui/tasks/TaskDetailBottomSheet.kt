package com.example.tasksync.ui.tasks

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
import androidx.compose.material3.Button
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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.tasksync.data.local.entity.ProjectEntity
import com.example.tasksync.data.local.entity.SubtaskEntity
import com.example.tasksync.data.local.entity.TaskEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

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
    var newSubtaskTitle by remember { mutableStateOf("") }
    var validationMessage by remember { mutableStateOf<String?>(null) }

    val pickerState = rememberDatePickerState(initialSelectedDateMillis = dueDate.toEpochMillisOrNull())

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
            OutlinedTextField(
                value = dueTime,
                onValueChange = { dueTime = it.take(5); validationMessage = null },
                label = { Text("Time (optional, HH:mm)") },
                placeholder = { Text("17:30") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

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
                    onCheckedChange = { reminderEnabled = it },
                    enabled = dueDate.isNotBlank() && dueTime.isNotBlank()
                )
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
                            recurrenceRule != "none" && dueDate.isBlank() -> validationMessage = "Choose a deadline date for a repeating task."
                            dueTime.isNotBlank() && !dueTime.matches(Regex("([01]\\d|2[0-3]):[0-5]\\d")) ->
                                validationMessage = "Enter time as HH:mm, for example 17:30."
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
}

private fun String.isIsoDate(): Boolean = runCatching { LocalDate.parse(this) }.isSuccess

private fun String.toEpochMillisOrNull(): Long? = runCatching {
    LocalDate.parse(this).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}.getOrNull()
