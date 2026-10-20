package com.example.tasksync.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.ui.tasks.components.DueDateChip
import com.example.tasksync.ui.tasks.components.PriorityBadge

private data class BoardLane(val status: String, val title: String, val accent: Color)

@Composable
fun ProjectKanbanBoard(
    tasks: List<TaskEntity>,
    onTaskClick: (TaskEntity) -> Unit,
    onMoveTask: (TaskEntity, String) -> Unit,
    onAddTask: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lanes = listOf(
        BoardLane("inbox", "To do", MaterialTheme.colorScheme.outline),
        BoardLane("in_progress", "In progress", MaterialTheme.colorScheme.primary),
        BoardLane("completed", "Done", Color(0xFF25876A))
    )
    Row(
        modifier = modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        lanes.forEach { lane ->
            val laneTasks = tasks.filter { task ->
                when (lane.status) {
                    "inbox" -> task.status != "in_progress" && task.status != "completed"
                    else -> task.status == lane.status
                }
            }
            BoardLaneColumn(
                lane = lane,
                tasks = laneTasks,
                onTaskClick = onTaskClick,
                onMoveTask = onMoveTask,
                onAddTask = onAddTask
            )
        }
    }
}

@Composable
private fun BoardLaneColumn(
    lane: BoardLane,
    tasks: List<TaskEntity>,
    onTaskClick: (TaskEntity) -> Unit,
    onMoveTask: (TaskEntity, String) -> Unit,
    onAddTask: () -> Unit
) {
    val laneBackground = when (lane.status) {
        "in_progress" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
        "completed" -> Color(0xFF25876A).copy(alpha = 0.08f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f)
    }
    Column(
        modifier = Modifier.width(304.dp).fillMaxHeight()
            .background(laneBackground, RoundedCornerShape(22.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.size(9.dp).background(lane.accent, CircleShape))
            Text(lane.title, modifier = Modifier.weight(1f).padding(start = 9.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)) {
                Text(tasks.size.toString(), modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            }
            if (lane.status == "inbox") {
                IconButton(onClick = onAddTask, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "Add project task")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    when (lane.status) {
                        "in_progress" -> "Work in progress will show here"
                        "completed" -> "Finished tasks will show here"
                        else -> "Add the first task to this project"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    ProjectKanbanTaskCard(task, lane.status, onTaskClick, onMoveTask)
                }
            }
        }
    }
}

@Composable
private fun ProjectKanbanTaskCard(
    task: TaskEntity,
    laneStatus: String,
    onTaskClick: (TaskEntity) -> Unit,
    onMoveTask: (TaskEntity, String) -> Unit
) {
    var menuExpanded by remember(task.id) { mutableStateOf(false) }
    Card(
        onClick = { onTaskClick(task) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(task.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.MoreHoriz, contentDescription = "Move task", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        listOf("inbox" to "To do", "in_progress" to "In progress", "completed" to "Done")
                            .filter { it.first != laneStatus }
                            .forEach { (status, title) ->
                                DropdownMenuItem(
                                    text = { Text("Move to $title") },
                                    onClick = { menuExpanded = false; onMoveTask(task, status) }
                                )
                            }
                    }
                }
            }
            task.description?.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, maxLines = 3, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!task.dueDate.isNullOrBlank() || task.priority < 4 || task.reminderEnabled || task.recurrenceRule != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!task.dueDate.isNullOrBlank()) {
                        DueDateChip(task.dueDate, task.dueTime)
                        Spacer(Modifier.width(7.dp))
                    }
                    if (task.priority < 4) PriorityBadge(task.priority)
                }
            }
        }
    }
}
