package com.example.tasksync.ui.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.ui.tasks.components.TaskItemCard

@Composable
fun InboxScreen(
    viewModel: TaskListViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val inboxTasks by viewModel.inboxTasks.collectAsState()
    var selectedTask by remember { mutableStateOf<TaskEntity?>(null) }
    var showBottomSheet by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    selectedTask = null
                    showBottomSheet = true
                },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add Task")
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            if (inboxTasks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Inbox is clear 📥",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "All personal tasks will appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(inboxTasks, key = { it.id }) { task ->
                        TaskItemCard(
                            task = task,
                            onTaskClick = {
                                selectedTask = it
                                showBottomSheet = true
                            },
                            onStatusChange = { t, checked ->
                                viewModel.toggleTaskCompletion(t, checked)
                            },
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }

        if (showBottomSheet) {
            TaskDetailBottomSheet(
                task = selectedTask,
                onDismissRequest = { showBottomSheet = false },
                onSaveTask = { title, desc, date, priority ->
                    if (selectedTask == null) {
                        viewModel.createTask(
                            title = title,
                            description = desc,
                            status = "inbox",
                            dueDate = date,
                            priority = priority
                        )
                    } else {
                        viewModel.updateTask(
                            selectedTask!!.copy(
                                title = title,
                                description = desc,
                                dueDate = date,
                                priority = priority
                            )
                        )
                    }
                },
                onDeleteTask = { task ->
                    viewModel.deleteTask(task)
                    showBottomSheet = false
                }
            )
        }
    }
}
