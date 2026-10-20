package com.example.tasksync.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.tasksync.data.local.entity.SubtaskEntity
import com.example.tasksync.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM tasks ORDER BY CASE WHEN status = 'completed' THEN 1 ELSE 0 END, priority ASC, CASE WHEN dueDate IS NULL THEN 1 ELSE 0 END, dueDate ASC, createdAt DESC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE status != 'completed' AND dueDate IS NULL AND projectId IS NULL ORDER BY priority ASC, createdAt DESC")
    fun getInboxTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE projectId = :projectId ORDER BY CASE WHEN status = 'completed' THEN 1 ELSE 0 END, priority ASC, CASE WHEN dueDate IS NULL THEN 1 ELSE 0 END, dueDate ASC, createdAt DESC")
    fun getTasksForProject(projectId: String): Flow<List<TaskEntity>>

    @Query("UPDATE tasks SET projectId = NULL, updatedAt = :updatedAt WHERE projectId = :projectId")
    suspend fun removeProjectFromTasks(projectId: String, updatedAt: Long)

    @Query("SELECT * FROM tasks WHERE id = :taskId")
    suspend fun getTaskById(taskId: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE reminderEnabled = 1 AND status != 'completed'")
    suspend fun getTasksWithReminders(): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<TaskEntity>)

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Delete
    suspend fun deleteTask(task: TaskEntity)

    @Query("UPDATE tasks SET status = :status, updatedAt = :updatedAt WHERE id = :taskId")
    suspend fun updateTaskStatus(taskId: String, status: String, updatedAt: Long): Int

    // Subtasks
    @Query("SELECT * FROM subtasks WHERE taskId = :taskId ORDER BY sortOrder ASC")
    fun getSubtasksForTask(taskId: String): Flow<List<SubtaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubtask(subtask: SubtaskEntity)

    @Update
    suspend fun updateSubtask(subtask: SubtaskEntity)

    @Delete
    suspend fun deleteSubtask(subtask: SubtaskEntity)
}
