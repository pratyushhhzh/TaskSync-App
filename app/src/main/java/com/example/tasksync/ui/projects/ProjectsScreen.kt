package com.example.tasksync.ui.projects

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.example.tasksync.data.local.entity.ProjectEntity
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.ui.tasks.TaskDetailBottomSheet
import com.example.tasksync.ui.tasks.TaskListViewModel
import com.example.tasksync.ui.tasks.components.TaskItemCard

@Composable
fun ProjectsScreen(viewModel: TaskListViewModel = viewModel()) {
    val projects by viewModel.projects.collectAsState()
    var selectedProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var showProjectDialog by remember { mutableStateOf(false) }
    var showTaskSheet by remember { mutableStateOf(false) }
    var selectedTask by remember { mutableStateOf<TaskEntity?>(null) }

    val projectTasks by remember(selectedProject?.id) {
        selectedProject?.let { viewModel.tasksForProject(it.id) }
            ?: kotlinx.coroutines.flow.flowOf(emptyList<TaskEntity>())
    }.collectAsState(initial = emptyList())
    val subtasks by remember(selectedTask?.id) {
        viewModel.subtasksForTask(selectedTask?.id.orEmpty())
    }.collectAsState(initial = emptyList())

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (selectedProject == null) showProjectDialog = true
                else { selectedTask = null; showTaskSheet = true }
            }) {
                Icon(Icons.Default.Add, contentDescription = if (selectedProject == null) "Add project" else "Add task")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            selectedProject?.let { project ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { selectedProject = null }.padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All projects")
                    Text("  All projects", style = MaterialTheme.typography.labelLarge)
                }
                Text(project.name, style = MaterialTheme.typography.headlineSmall)
                project.description?.takeIf(String::isNotBlank)?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                if (projectTasks.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No tasks in this project yet. Tap + to add one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(projectTasks, key = { it.id }) { task ->
                            TaskItemCard(
                                task = task,
                                onTaskClick = { selectedTask = it; showTaskSheet = true },
                                onStatusChange = viewModel::toggleTaskCompletion
                            )
                        }
                    }
                }
            } ?: run {
                Text("Projects", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                if (projects.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Create a project to group related tasks.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(projects, key = { it.id }) { project ->
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { selectedProject = project },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(project.name, style = MaterialTheme.typography.titleMedium)
                                    project.description?.takeIf(String::isNotBlank)?.let {
                                        Spacer(Modifier.height(4.dp))
                                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showProjectDialog) {
            var name by remember { mutableStateOf("") }
            var description by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showProjectDialog = false },
                title = { Text("New project") },
                text = {
                    Column {
                        OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(description, { description = it }, label = { Text("Description (optional)") })
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.createProject(name, description)
                        showProjectDialog = false
                    }, enabled = name.isNotBlank()) { Text("Create") }
                },
                dismissButton = { TextButton(onClick = { showProjectDialog = false }) { Text("Cancel") } }
            )
        }

        if (showTaskSheet) {
            val project = selectedProject
            TaskDetailBottomSheet(
                task = selectedTask,
                projects = projects,
                subtasks = subtasks,
                initialProjectId = project?.id,
                onDismissRequest = { showTaskSheet = false },
                onSaveTask = { title, description, date, time, priority, projectIdFromEditor ->
                    if (selectedTask == null) {
                        viewModel.createTask(
                            title = title, description = description,
                            status = "inbox",
                            dueDate = date, dueTime = time, priority = priority,
                            projectId = projectIdFromEditor
                        )
                    } else {
                        viewModel.updateTask(selectedTask!!.copy(
                            title = title, description = description, dueDate = date,
                            dueTime = time, priority = priority, projectId = projectIdFromEditor
                        ))
                    }
                },
                onDeleteTask = { viewModel.deleteTask(it); showTaskSheet = false },
                onAddSubtask = { selectedTask?.let { task -> viewModel.addSubtask(task.id, it) } },
                onToggleSubtask = viewModel::toggleSubtask,
                onDeleteSubtask = viewModel::deleteSubtask
            )
        }
    }
}
