package com.example.tasksync.ui.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tasksync.data.local.entity.ProjectEntity
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.ui.tasks.TaskDetailBottomSheet
import com.example.tasksync.ui.tasks.TaskListViewModel

@Composable
fun ProjectsScreen(viewModel: TaskListViewModel = viewModel()) {
    val projects by viewModel.projects.collectAsState()
    val allTasks by viewModel.allTasks.collectAsState()
    var selectedProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var selectedStatus by remember { mutableStateOf("todo") }
    var showProjectDialog by remember { mutableStateOf(false) }
    var showTaskSheet by remember { mutableStateOf(false) }
    var selectedTask by remember { mutableStateOf<TaskEntity?>(null) }

    val activeProject = selectedProject ?: projects.firstOrNull()
    val projectTasks by remember(activeProject?.id) {
        activeProject?.let { viewModel.tasksForProject(it.id) }
            ?: kotlinx.coroutines.flow.flowOf(emptyList<TaskEntity>())
    }.collectAsState(initial = emptyList())
    val subtasks by remember(selectedTask?.id) {
        viewModel.subtasksForTask(selectedTask?.id.orEmpty())
    }.collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp).padding(top = 18.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Projects", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
            androidx.compose.material3.IconButton(onClick = { showProjectDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Create project", tint = Color(0xFF7399FF))
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            projects.forEach { project ->
                FilterChip(selected = project.id == activeProject?.id, onClick = { selectedProject = project }, label = { Text(project.name) })
            }
        }
        if (activeProject == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Start a project", style = MaterialTheme.typography.titleLarge)
                    Text("Create a project to organize its tasks.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            val total = projectTasks.size
            val done = projectTasks.count { it.status == "completed" }
            val inProgress = projectTasks.count { it.status == "in_progress" || it.status == "in progress" }
            val todo = total - done - inProgress
            val progress = if (total == 0) 0f else done.toFloat() / total
            Surface(Modifier.fillMaxWidth().padding(bottom = 14.dp), shape = RoundedCornerShape(24.dp), color = Color(0xFF131A48), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${activeProject.name} · $total tasks", style = MaterialTheme.typography.titleLarge)
                            Text("${(progress * 100).toInt()}% done", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA9B3D6))
                        }
                        androidx.compose.material3.IconButton(onClick = { selectedTask = null; showTaskSheet = true }) { Icon(Icons.Default.Add, contentDescription = "Add task to project", tint = Color(0xFF7399FF)) }
                    }
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f))) {
                        Box(Modifier.fillMaxWidth(progress).height(5.dp).clip(CircleShape).background(Color(0xFF4C7DFF)))
                    }
                    Text("To do $todo     In progress $inProgress     Done $done", style = MaterialTheme.typography.labelSmall, color = Color(0xFFA9B3D6))
                    activeProject.deadlineDate?.let { Text("Target · $it", style = MaterialTheme.typography.labelSmall, color = Color(0xFFBFD0FF)) }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 9.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("todo" to "To do $todo", "in_progress" to "In progress $inProgress", "completed" to "Done $done").forEach { (status, label) ->
                    FilterChip(selected = selectedStatus == status, onClick = { selectedStatus = status }, label = { Text(label) })
                }
            }
            val filtered = projectTasks.filter { task -> when (selectedStatus) {
                "todo" -> task.status != "in_progress" && task.status != "in progress" && task.status != "completed"
                "in_progress" -> task.status == "in_progress" || task.status == "in progress"
                else -> task.status == "completed"
            } }
            if (filtered.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No tasks in this section.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                items(filtered, key = { it.id }) { task -> ProjectTaskRow(task, onClick = { selectedTask = task; showTaskSheet = true }, onMove = viewModel::moveTaskToStatus) }
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
                initialProjectId = activeProject?.id,
                onDismissRequest = { showTaskSheet = false },
                onSaveTask = { title, description, date, time, priority, projectId, reminderEnabled, recurrenceRule, reminderOffsets, customReminderAt ->
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
                            projectId = projectId,
                            reminderOffsets = reminderOffsets,
                            customReminderAtMillis = customReminderAt,
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
                        ), reminderOffsets, customReminderAt)
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
private fun ProjectTaskRow(task: TaskEntity, onClick: () -> Unit, onMove: (TaskEntity, String) -> Unit) {
    var menu by remember(task.id) { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(15.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (task.status == "completed") Color(0xFF7BE8A8) else Color(0xFF4C7DFF)))
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                val meta = listOfNotNull(task.dueDate, task.dueTime, task.description?.takeIf(String::isNotBlank)).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = Color(0xFFA9B3D6), maxLines = 1)
            }
            Box {
                androidx.compose.material3.IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreHoriz, contentDescription = "Move ${task.title}", tint = Color(0xFFA9B3D6)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf("inbox" to "To do", "in_progress" to "In progress", "completed" to "Done").filter { it.first != task.status }.forEach { (status, label) ->
                        DropdownMenuItem(text = { Text("Move to $label") }, onClick = { menu = false; onMove(task, status) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectList(projects: List<ProjectEntity>, tasks: List<TaskEntity>, modifier: Modifier, onSelect: (ProjectEntity) -> Unit) {
    Column(modifier) {
        Text("YOUR WORKSPACE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.1.sp)
        Text("Projects", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 3.dp))
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
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    ) {
                        val projectTasks = tasks.filter { it.projectId == project.id }
                        val completed = projectTasks.count { it.status == "completed" }
                        val toDo = projectTasks.count { it.status == "inbox" || it.status == "todo" }
                        val inProgress = projectTasks.count { it.status == "in_progress" || it.status == "in progress" }
                        val progress = if (projectTasks.isEmpty()) 0f else completed.toFloat() / projectTasks.size
                        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(project.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                            project.description?.takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${projectTasks.size} tasks · $completed done", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))) {
                                if (progress > 0f) Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("To do $toDo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("In progress $inProgress", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Done $completed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            project.deadlineDate?.let {
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
    projects: List<ProjectEntity>,
    tasks: List<TaskEntity>,
    onBack: () -> Unit,
    onSelectProject: (ProjectEntity) -> Unit,
    modifier: Modifier,
    board: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(modifier) {
        Text("Projects", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            projects.forEach { item ->
                androidx.compose.material3.FilterChip(
                    selected = item.id == project.id,
                    onClick = { onSelectProject(item) },
                    label = { Text(item.name) }
                )
            }
            androidx.compose.material3.FilterChip(selected = false, onClick = onBack, label = { Text("All") })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
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
        board()
    }
}
