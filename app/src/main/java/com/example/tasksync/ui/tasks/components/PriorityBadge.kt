package com.example.tasksync.ui.tasks.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.tasksync.ui.theme.PriorityHigh
import com.example.tasksync.ui.theme.PriorityLow
import com.example.tasksync.ui.theme.PriorityMedium
import com.example.tasksync.ui.theme.PriorityNone

@Composable
fun PriorityBadge(priority: Int, modifier: Modifier = Modifier) {
    val (label, color) = when (priority) {
        1 -> "P1" to PriorityHigh
        2 -> "P2" to PriorityMedium
        3 -> "P3" to PriorityLow
        else -> "P4" to PriorityNone
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}
