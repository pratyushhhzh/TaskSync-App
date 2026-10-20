package com.example.tasksync.data.repository

import com.example.tasksync.data.local.dao.TaskDao
import com.example.tasksync.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

class TaskRepository(
    private val taskDao: TaskDao
) {
    val allTasks: Flow<List<TaskEntity>> = taskDao.getAllTasks()
    val inboxTasks: Flow<List<TaskEntity>> = taskDao.getInboxTasks()

    suspend fun getTaskById(id: String): TaskEntity? {
        return taskDao.getTaskById(id)
    }

    fun getTasksForProject(projectId: String): Flow<List<TaskEntity>> = taskDao.getTasksForProject(projectId)

    suspend fun removeProjectFromTasks(projectId: String) {
        taskDao.removeProjectFromTasks(projectId, System.currentTimeMillis())
    }

    suspend fun createTask(
        title: String,
        description: String? = null,
        status: String = "inbox",
        dueDate: String? = null,
        dueTime: String? = null,
        priority: Int = 4,
        projectId: String? = null
    ): TaskEntity {
        val task = TaskEntity(
            id = "task_" + System.currentTimeMillis() + "_" + (1000..9999).random(),
            userId = "local_user",
            projectId = projectId,
            title = title,
            description = description,
            status = status,
            dueDate = dueDate,
            dueTime = dueTime,
            priority = priority
        )
        taskDao.insertTask(task)
        return task
    }

    suspend fun updateTask(task: TaskEntity) {
        taskDao.updateTask(task.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun markTaskCompleted(taskId: String) {
        taskDao.updateTaskStatus(taskId, "completed", System.currentTimeMillis())
    }

    suspend fun reopenTask(taskId: String, newStatus: String = "inbox") {
        taskDao.updateTaskStatus(taskId, newStatus, System.currentTimeMillis())
    }

    suspend fun deleteTask(task: TaskEntity) {
        taskDao.deleteTask(task)
    }
}
