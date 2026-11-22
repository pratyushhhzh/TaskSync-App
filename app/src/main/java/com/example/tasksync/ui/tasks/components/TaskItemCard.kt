package com.example.tasksync.ui.tasks.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.ui.theme.PriorityHigh
import com.example.tasksync.ui.theme.PriorityLow
import com.example.tasksync.ui.theme.PriorityMedium

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun TaskItemCard(
    task: TaskEntity,
    onTaskClick: (TaskEntity) -> Unit,
    onStatusChange: (TaskEntity, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    projectName: String? = null,
    onTaskLongClick: (TaskEntity) -> Unit = {},
    onPriorityChange: ((TaskEntity, Int) -> Unit)? = null
) {
    val isCompleted = task.status == "completed"
    var showPriorityMenu by remember(task.id) { mutableStateOf(false) }
    val priorityColor = when (task.priority) {
        1 -> PriorityHigh
        2 -> PriorityMedium
        3 -> PriorityLow
        else -> PriorityLow
    }

    Card(
        modifier = modifier.fillMaxWidth().combinedClickable(
            onClick = { onTaskClick(task) },
            onLongClick = { onTaskLongClick(task) }
        ),
        shape = RoundedCornerShape(19.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(25.dp).clip(CircleShape)
                    .then(
                        if (isCompleted) Modifier.background(Color(0xFF5AE0CE))
                        else Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f), CircleShape)
                    )
                    .clickable { onStatusChange(task, !isCompleted) },
                contentAlignment = Alignment.Center
            ) {
                if (isCompleted) Icon(Icons.Default.Check, contentDescription = "Reopen ${task.title}", tint = Color(0xFF22284F), modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 1
                )
                val metadata = buildList {
                    task.dueTime?.takeIf(String::isNotBlank)?.let { add(it.take(5)) }
                    projectName?.takeIf(String::isNotBlank)?.let { add(it) }
                    if (isCompleted) add("Done")
                    else if (isEmpty() && task.dueDate == null) add("Inbox")
                    else if (isEmpty()) task.dueDate?.let(::add)
                    task.recurrenceRule?.let { add(it.replaceFirstChar(Char::uppercase)) }
                }.joinToString(" · ")
                if (metadata.isNotBlank()) {
                    Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                } else if (task.reminderEnabled) {
                    Text("Reminder set", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (task.reminderEnabled || task.recurrenceRule != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (task.reminderEnabled) Icon(Icons.Default.NotificationsActive, contentDescription = "Reminder set", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        if (task.recurrenceRule != null) Icon(Icons.Default.Repeat, contentDescription = "Repeats ${task.recurrenceRule}", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Box {
                Box(
                    Modifier.size(18.dp).clip(CircleShape).clickable { if (onPriorityChange != null) showPriorityMenu = true },
                    contentAlignment = Alignment.Center
                ) { Box(Modifier.size(7.dp).clip(CircleShape).background(priorityColor)) }
                if (onPriorityChange != null) {
                    DropdownMenu(expanded = showPriorityMenu, onDismissRequest = { showPriorityMenu = false }) {
                        listOf(1 to "High", 2 to "Medium", 3 to "Low", 4 to "No priority").forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { showPriorityMenu = false; onPriorityChange(task, value) }
                            )
                        }
                    }
                }
            }
        }
    }
}
