package com.example.tasksync.ui.tasks.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DueDateChip(
    dueDate: String?,
    dueTime: String? = null,
    modifier: Modifier = Modifier
) {
    if (dueDate.isNullOrEmpty() && dueTime.isNullOrEmpty()) return

    val displayString = when {
        !dueDate.isNullOrEmpty() && !dueTime.isNullOrEmpty() -> "$dueDate $dueTime"
        !dueDate.isNullOrEmpty() -> dueDate
        else -> dueTime.orEmpty()
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Schedule,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = displayString,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
