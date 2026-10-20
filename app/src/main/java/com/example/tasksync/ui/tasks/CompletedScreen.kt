package com.example.tasksync.ui.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
fun CompletedScreen(
    viewModel: TaskListViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val completedTasks by viewModel.completedTasks.collectAsState()
    var selectedTask by remember { mutableStateOf<TaskEntity?>(null) }
    var showBottomSheet by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (completedTasks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No completed tasks yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Completed tasks will be archived here and can be reopened.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(completedTasks, key = { it.id }) { task ->
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

        if (showBottomSheet && selectedTask != null) {
            TaskDetailBottomSheet(
                task = selectedTask,
                onDismissRequest = { showBottomSheet = false },
                onSaveTask = { title, desc, date, priority ->
                    viewModel.updateTask(
                        selectedTask!!.copy(
                            title = title,
                            description = desc,
                            dueDate = date,
                            priority = priority
                        )
                    )
                },
                onDeleteTask = { task ->
                    viewModel.deleteTask(task)
                    showBottomSheet = false
                }
            )
        }
    }
}
