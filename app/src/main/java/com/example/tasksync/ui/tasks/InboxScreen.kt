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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
fun InboxScreen(viewModel: TaskListViewModel = viewModel()) {
    val inboxTasks by viewModel.inboxTasks.collectAsState()
    val projects by viewModel.projects.collectAsState()
    var selectedTask by remember { mutableStateOf<TaskEntity?>(null) }
    var showBottomSheet by remember { mutableStateOf(false) }
    val subtasks by remember(selectedTask?.id) { viewModel.subtasksForTask(selectedTask?.id.orEmpty()) }
        .collectAsState(initial = emptyList())

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { selectedTask = null; showBottomSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) { Icon(Icons.Default.Add, contentDescription = "Add inbox task") }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp)) {
                    Text("${inboxTasks.size} captured, not sorted yet", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Inbox", style = MaterialTheme.typography.headlineLarge)
                }
            }
            if (inboxTasks.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Sort in 30 seconds", style = MaterialTheme.typography.titleMedium)
                                Text("Give each item a date or a project.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(onClick = { selectedTask = inboxTasks.first(); showBottomSheet = true }) { Text("Start") }
                        }
                    }
                }
                items(inboxTasks, key = { it.id }) { task ->
                    TaskItemCard(
                        task = task,
                        onTaskClick = { selectedTask = it; showBottomSheet = true },
                        onStatusChange = viewModel::toggleTaskCompletion,
                        onPriorityChange = { item, priority -> viewModel.updateTask(item.copy(priority = priority)) }
                    )
                }
                item { Spacer(Modifier.height(18.dp)) }
            } else {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 90.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Inbox is clear", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text("Unplanned tasks will land here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (showBottomSheet) {
            TaskDetailBottomSheet(
                task = selectedTask,
                projects = projects,
                subtasks = subtasks,
                onDismissRequest = { showBottomSheet = false },
                onSaveTask = { title, desc, date, time, priority, projectId, reminderEnabled, recurrenceRule ->
                    val task = selectedTask
                    if (task == null) viewModel.createTask(title, desc, "inbox", date, time, reminderEnabled, recurrenceRule, priority, projectId)
                    else viewModel.updateTask(task.copy(title = title, description = desc, dueDate = date, dueTime = time, priority = priority, projectId = projectId, reminderEnabled = reminderEnabled, recurrenceRule = recurrenceRule))
                },
                onDeleteTask = { viewModel.deleteTask(it); showBottomSheet = false },
                onAddSubtask = { title -> selectedTask?.let { viewModel.addSubtask(it.id, title) } },
                onToggleSubtask = viewModel::toggleSubtask,
                onDeleteSubtask = viewModel::deleteSubtask
            )
        }
    }
}
