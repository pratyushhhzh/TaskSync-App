package com.example.tasksync.data.repository

import com.example.tasksync.data.local.dao.TaskDao
import com.example.tasksync.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow
import com.example.tasksync.data.reminder.TaskReminderScheduler
import android.content.Context
import java.time.LocalDate


class TaskRepository(
    private val taskDao: TaskDao,
    private val appContext: Context
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
        reminderEnabled: Boolean = false,
        recurrenceRule: String? = null,
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
            reminderEnabled = reminderEnabled,
            recurrenceRule = recurrenceRule,
            priority = priority
        )
        taskDao.insertTask(task)
        scheduleReminder(task)
        return task
    }

    suspend fun updateTask(task: TaskEntity) {
        val updatedTask = task.copy(updatedAt = System.currentTimeMillis())
        taskDao.updateTask(updatedTask)
        scheduleReminder(updatedTask)
    }

    suspend fun markTaskCompleted(taskId: String) {
        taskDao.getTaskById(taskId)?.let { task ->
            taskDao.updateTaskStatus(taskId, "completed", System.currentTimeMillis())
            TaskReminderScheduler.cancel(appContext, taskId)
            createNextOccurrence(task)
        }
    }

    suspend fun reopenTask(taskId: String, newStatus: String = "inbox") {
        taskDao.updateTaskStatus(taskId, newStatus, System.currentTimeMillis())
        taskDao.getTaskById(taskId)?.let(::scheduleReminder)
    }

    suspend fun deleteTask(task: TaskEntity) {
        taskDao.deleteTask(task)
        TaskReminderScheduler.cancel(appContext, task.id)
    }

    private fun scheduleReminder(task: TaskEntity) {
        TaskReminderScheduler.schedule(appContext, task.id, task.title, task.description, task.dueDate, task.dueTime, task.reminderEnabled && task.status != "completed")
    }

    private suspend fun createNextOccurrence(task: TaskEntity) {
        val intervalDays = when (task.recurrenceRule) {
            "daily" -> 1L
            "weekly" -> 7L
            "monthly" -> null
            else -> return
        }
        var nextDate = task.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        val today = LocalDate.now()
        do {
            nextDate = if (task.recurrenceRule == "monthly") nextDate.plusMonths(1) else nextDate.plusDays(intervalDays!!)
        } while (nextDate.isBefore(today))
        val next = task.copy(
            id = "task_${System.currentTimeMillis()}_${(1000..9999).random()}",
            dueDate = nextDate.toString(),
            status = "inbox",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        taskDao.insertTask(next)
        scheduleReminder(next)
    }
}
