package com.example.tasksync.ui.tasks

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.ui.tasks.components.TaskItemCard

@Composable
fun TasksScreen(viewModel: TaskListViewModel = viewModel()) {
    val allTasks by viewModel.allTasks.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val activeTasks = allTasks.filter { it.status != "completed" }
    val completedTasks = allTasks.filter { it.status == "completed" }
    var showCompleted by remember { mutableStateOf(false) }
    var selectedTask by remember { mutableStateOf<TaskEntity?>(null) }
    var showTaskSheet by remember { mutableStateOf(false) }
    val subtasks by remember(selectedTask?.id) {
        viewModel.subtasksForTask(selectedTask?.id.orEmpty())
    }.collectAsState(initial = emptyList())

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {
                selectedTask = null
                showTaskSheet = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add task")
            }
        }
    ) { padding ->
        if (allTasks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No tasks yet", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tap + to add a task. Set a priority or deadline whenever you need one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (activeTasks.isNotEmpty()) {
                    item {
                        Text(
                            "Open tasks",
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    items(activeTasks, key = { it.id }) { task ->
                        TaskItemCard(
                            task = task,
                            onTaskClick = { selectedTask = it; showTaskSheet = true },
                            onStatusChange = viewModel::toggleTaskCompletion
                        )
                    }
                }

                if (completedTasks.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showCompleted = !showCompleted }
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (showCompleted) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (showCompleted) "Hide completed tasks" else "Show completed tasks"
                            )
                            Text(
                                "Completed (${completedTasks.size})",
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                    if (showCompleted) {
                        items(completedTasks, key = { it.id }) { task ->
                            TaskItemCard(
                                task = task,
                                onTaskClick = { selectedTask = it; showTaskSheet = true },
                                onStatusChange = viewModel::toggleTaskCompletion
                            )
                        }
                    }
                }
            }
        }

        if (showTaskSheet) {
            TaskDetailBottomSheet(
                task = selectedTask,
                projects = projects,
                subtasks = subtasks,
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
