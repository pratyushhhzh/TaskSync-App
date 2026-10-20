package com.example.tasksync.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey
    val id: String,
    val userId: String,
    val projectId: String? = null,
    val sectionId: String? = null,
    val title: String,
    val description: String? = null,
    val status: String = "inbox", // inbox or completed
    val dueDate: String? = null, // YYYY-MM-DD
    val dueTime: String? = null, // HH:MM:SS
    val priority: Int = 4, // 1 (High) to 4 (None)
    val assigneeId: String? = null,
    val recurrenceRule: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
