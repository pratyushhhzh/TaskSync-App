package com.example.tasksync.ui.tasks

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.ui.tasks.components.TaskItemCard
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun CalendarScreen(viewModel: TaskListViewModel = viewModel()) {
    val allTasks by viewModel.allTasks.collectAsState()
    val projects by viewModel.projects.collectAsState()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var selectedTask by remember { mutableStateOf<TaskEntity?>(null) }
    var showTaskSheet by remember { mutableStateOf(false) }
    val subtasks by remember(selectedTask?.id) {
        viewModel.subtasksForTask(selectedTask?.id.orEmpty())
    }.collectAsState(initial = emptyList())

    val dateTasks = remember(allTasks, selectedDate) {
        allTasks.filter { it.dueDate == selectedDate.toString() }
    }
    val datesWithTasks = remember(allTasks) {
        allTasks.mapNotNull { task ->
            task.dueDate?.takeIf { date -> runCatching { LocalDate.parse(date) }.isSuccess }
        }.toSet()
    }
    val weeks = remember(month) {
        val firstDay = month.atDay(1)
        val leadingEmptyDays = firstDay.dayOfWeek.value - 1
        val cells = buildList<LocalDate?> {
            repeat(leadingEmptyDays) { add(null) }
            for (day in 1..month.lengthOfMonth()) add(month.atDay(day))
            repeat((7 - size % 7) % 7) { add(null) }
        }
        cells.chunked(7)
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {
                selectedTask = null
                showTaskSheet = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add task for selected date")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = {
                        month = month.minusMonths(1)
                        selectedDate = month.atDay(minOf(selectedDate.dayOfMonth, month.lengthOfMonth()))
                    }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month")
                    }
                    Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = {
                        month = month.plusMonths(1)
                        selectedDate = month.atDay(minOf(selectedDate.dayOfMonth, month.lengthOfMonth()))
                    }) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next month")
                    }
                }
            }

            item {
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEach { day ->
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(day, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            items(weeks) { week ->
                Row(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    week.forEach { date ->
                        val isSelected = date == selectedDate
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable(enabled = date != null) { date?.let { selectedDate = it } },
                            contentAlignment = Alignment.Center
                        ) {
                            if (date != null) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text(
                                        date.dayOfMonth.toString(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (date.toString() in datesWithTasks) {
                                        Spacer(Modifier.height(2.dp))
                                        Box(
                                            Modifier.size(4.dp).background(
                                                if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                                                CircleShape
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    selectedDate.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                    style = MaterialTheme.typography.titleMedium
                )
                if (dateTasks.isEmpty()) {
                    Text(
                        "No tasks on this date",
                        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(dateTasks, key = { it.id }) { task ->
                TaskItemCard(
                    task = task,
                    onTaskClick = { selectedTask = it; showTaskSheet = true },
                    onStatusChange = viewModel::toggleTaskCompletion
                )
            }
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
                    if (task == null) {
                        viewModel.createTask(
                            title = title,
                            description = description,
                            status = "inbox",
                            dueDate = date,
                            dueTime = time,
                            reminderEnabled = reminderEnabled,
                            recurrenceRule = recurrenceRule,
                            priority = priority,
                            projectId = projectId
                        )
                    } else {
                        viewModel.updateTask(task.copy(
                            title = title,
                            description = description,
                            dueDate = date,
                            dueTime = time,
                            reminderEnabled = reminderEnabled,
                            recurrenceRule = recurrenceRule,
                            priority = priority,
                            projectId = projectId
                        ))
                    }
                },
                onDeleteTask = { viewModel.deleteTask(it); showTaskSheet = false },
                onAddSubtask = { title -> selectedTask?.let { task -> viewModel.addSubtask(task.id, title) } },
                onToggleSubtask = viewModel::toggleSubtask,
                onDeleteSubtask = viewModel::deleteSubtask
            )
        }
    }
}
