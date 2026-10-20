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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.example.tasksync.data.local.entity.ProjectEntity
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.ui.tasks.TaskDetailBottomSheet
import com.example.tasksync.ui.tasks.TaskListViewModel

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
                Icon(Icons.Default.Add, contentDescription = if (selectedProject == null) "Create project" else "Add project task")
            }
        }
    ) { padding ->
        if (selectedProject == null) {
            ProjectList(
                projects = projects,
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                onSelect = { selectedProject = it }
            )
        } else {
            ProjectBoardHeader(
                project = selectedProject!!,
                tasks = projectTasks,
                onBack = { selectedProject = null },
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                ProjectKanbanBoard(
                    tasks = projectTasks,
                    onTaskClick = { selectedTask = it; showTaskSheet = true },
                    onMoveTask = viewModel::moveTaskToStatus,
                    onAddTask = { selectedTask = null; showTaskSheet = true },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (showProjectDialog) {
            ProjectCreationDialog(
                onDismiss = { showProjectDialog = false },
                onCreate = { name, description, deadline ->
                    viewModel.createProject(name, description, deadline)
                    showProjectDialog = false
                }
            )
        }

        if (showTaskSheet) {
            TaskDetailBottomSheet(
                task = selectedTask,
                projects = projects,
                subtasks = subtasks,
                initialProjectId = selectedProject?.id,
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
                onAddSubtask = { title -> selectedTask?.let { viewModel.addSubtask(it.id, title) } },
                onToggleSubtask = viewModel::toggleSubtask,
                onDeleteSubtask = viewModel::deleteSubtask
            )
        }
    }
}

@Composable
private fun ProjectList(projects: List<ProjectEntity>, modifier: Modifier, onSelect: (ProjectEntity) -> Unit) {
    Column(modifier) {
        Text("Projects", style = MaterialTheme.typography.headlineSmall)
        Text("A focused board for every goal.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Start a project", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(5.dp))
                    Text("Add a description and target date, then organize its work on a Kanban board.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(projects, key = { it.id }) { project ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(project) },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(project.name, style = MaterialTheme.typography.titleMedium)
                            project.description?.takeIf(String::isNotBlank)?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            project.deadlineDate?.let {
                                Spacer(Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Event, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Text("  Target · $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectBoardHeader(
    project: ProjectEntity,
    tasks: List<TaskEntity>,
    onBack: () -> Unit,
    modifier: Modifier,
    board: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onBack).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All projects")
            Text("  All projects", style = MaterialTheme.typography.labelLarge)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.headlineSmall)
                project.description?.takeIf(String::isNotBlank)?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            project.deadlineDate?.let { date ->
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Event, contentDescription = null)
                        Text(date, modifier = Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("PROJECT BOARD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${tasks.count { it.status != "completed" }} active · ${tasks.count { it.status == "completed" }} done", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        board()
    }
}
