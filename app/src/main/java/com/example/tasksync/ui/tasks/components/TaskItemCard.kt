package com.example.tasksync.ui.tasks.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.tasksync.data.local.entity.TaskEntity

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun TaskItemCard(
    task: TaskEntity,
    onTaskClick: (TaskEntity) -> Unit,
    onStatusChange: (TaskEntity, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    projectName: String? = null,
    onTaskLongClick: (TaskEntity) -> Unit = {}
) {
    val isCompleted = task.status == "completed"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onTaskClick(task) },
                onLongClick = { onTaskLongClick(task) }
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isCompleted,
                onCheckedChange = { checked -> onStatusChange(task, checked) }
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        textDecoration = if (isCompleted) TextDecoration.LineThrough else TextDecoration.None
                    ),
                    color = if (isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )

                if (!projectName.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Project · $projectName",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (!task.description.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = task.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!task.dueDate.isNullOrEmpty() || !task.dueTime.isNullOrEmpty() || task.priority < 4 || task.recurrenceRule != null || task.reminderEnabled) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!task.dueDate.isNullOrEmpty() || !task.dueTime.isNullOrEmpty()) {
                            DueDateChip(dueDate = task.dueDate, dueTime = task.dueTime)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        if (task.reminderEnabled) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = "Reminder set", tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        if (task.recurrenceRule != null) {
                            Icon(Icons.Default.Repeat, contentDescription = "Repeats ${task.recurrenceRule}", tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        PriorityBadge(priority = task.priority)
                    }
                }
            }
        }
    }
}
