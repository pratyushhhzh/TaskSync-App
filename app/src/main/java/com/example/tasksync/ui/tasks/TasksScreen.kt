package com.example.tasksync.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.ui.tasks.components.TaskItemCard
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private enum class TodayFilter(val title: String) { All("All"), High("High"), Recurring("Recurring"), Done("Done") }

@Composable
fun TasksScreen(viewModel: TaskListViewModel = viewModel()) {
    val allTasks by viewModel.allTasks.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val projectNames = remember(projects) { projects.associate { it.id to it.name } }
    var selectedFilter by remember { mutableStateOf(TodayFilter.All) }
    var selectedTask by remember { mutableStateOf<TaskEntity?>(null) }
    var showTaskSheet by remember { mutableStateOf(false) }
    var taskPendingDeletion by remember { mutableStateOf<TaskEntity?>(null) }
    val subtasks by remember(selectedTask?.id) {
        viewModel.subtasksForTask(selectedTask?.id.orEmpty())
    }.collectAsState(initial = emptyList())

    val visibleTasks = when (selectedFilter) {
        TodayFilter.All -> allTasks
        TodayFilter.High -> allTasks.filter { it.status != "completed" && it.priority <= 2 }
        TodayFilter.Recurring -> allTasks.filter { it.status != "completed" && it.recurrenceRule != null }
        TodayFilter.Done -> allTasks.filter { it.status == "completed" }
    }
    val today = LocalDate.now()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { selectedTask = null; showTaskSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) { Icon(Icons.Default.Add, contentDescription = "Add task") }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 3.dp)) {
                    Text(today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Today", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TodayFilter.entries.forEach { filter ->
                        val count = when (filter) {
                            TodayFilter.All -> allTasks.size
                            TodayFilter.High -> allTasks.count { it.status != "completed" && it.priority <= 2 }
                            TodayFilter.Recurring -> allTasks.count { it.status != "completed" && it.recurrenceRule != null }
                            TodayFilter.Done -> allTasks.count { it.status == "completed" }
                        }
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(if (filter == TodayFilter.All) "${filter.title} $count" else filter.title) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }
            if (visibleTasks.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 70.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (allTasks.isEmpty()) "A little more room to think." else "Nothing in this view.", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(5.dp))
                            Text(if (allTasks.isEmpty()) "Add a task or capture a thought." else "Choose another filter to see more tasks.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                if (selectedFilter == TodayFilter.All) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 1.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Your day", style = MaterialTheme.typography.titleLarge)
                            Text("${allTasks.count { it.status != "completed" }} open", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(visibleTasks, key = { it.id }) { task ->
                    TaskItemCard(
                        task = task,
                        onTaskClick = { selectedTask = it; showTaskSheet = true },
                        onStatusChange = viewModel::toggleTaskCompletion,
                        projectName = task.projectId?.let(projectNames::get),
                        onTaskLongClick = { taskPendingDeletion = it },
                        onPriorityChange = { item, priority -> viewModel.updateTask(item.copy(priority = priority)) }
                    )
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }

        if (showTaskSheet) {
            TaskDetailBottomSheet(
                task = selectedTask,
                projects = projects,
                subtasks = subtasks,
                onDismissRequest = { showTaskSheet = false },
                onSaveTask = { title, description, date, time, priority, projectId, reminderEnabled, recurrenceRule ->
                    val task = selectedTask
                    if (task == null) viewModel.createTask(title, description, "inbox", date, time, reminderEnabled, recurrenceRule, priority, projectId)
                    else viewModel.updateTask(task.copy(title = title, description = description, dueDate = date, dueTime = time, reminderEnabled = reminderEnabled, recurrenceRule = recurrenceRule, priority = priority, projectId = projectId))
                },
                onDeleteTask = { viewModel.deleteTask(it); showTaskSheet = false },
                onAddSubtask = { title -> selectedTask?.let { viewModel.addSubtask(it.id, title) } },
                onToggleSubtask = viewModel::toggleSubtask,
                onDeleteSubtask = viewModel::deleteSubtask
            )
        }
        taskPendingDeletion?.let { task ->
            AlertDialog(
                onDismissRequest = { taskPendingDeletion = null },
                title = { Text("Delete task?") },
                text = { Text("\"${task.title}\" will be permanently deleted.") },
                confirmButton = { TextButton(onClick = { viewModel.deleteTask(task); taskPendingDeletion = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { taskPendingDeletion = null }) { Text("Cancel") } }
            )
        }
    }
}
