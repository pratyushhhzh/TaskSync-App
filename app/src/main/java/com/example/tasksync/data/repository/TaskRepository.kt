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

    suspend fun restoreTask(task: TaskEntity) {
        taskDao.insertTask(task)
        scheduleReminder(task)
    }

    suspend fun markTaskCompleted(taskId: String): TaskEntity? {
        val task = taskDao.getTaskById(taskId) ?: return null
        taskDao.updateTaskStatus(taskId, "completed", System.currentTimeMillis())
        TaskReminderScheduler.cancel(appContext, taskId)
        return createNextOccurrence(task)
    }

    suspend fun reopenTask(taskId: String, newStatus: String = "inbox") {
        taskDao.updateTaskStatus(taskId, newStatus, System.currentTimeMillis())
        taskDao.getTaskById(taskId)?.let(::scheduleReminder)
    }

    suspend fun setTaskStatus(taskId: String, status: String) {
        val current = taskDao.getTaskById(taskId) ?: return
        if (status == "completed" && current.status != "completed") {
            markTaskCompleted(taskId)
        } else {
            taskDao.updateTaskStatus(taskId, status, System.currentTimeMillis())
            taskDao.getTaskById(taskId)?.let(::scheduleReminder)
        }
    }

    suspend fun deleteTask(task: TaskEntity) {
        taskDao.deleteTask(task)
        TaskReminderScheduler.cancel(appContext, task.id)
    }

    private fun scheduleReminder(task: TaskEntity) {
        TaskReminderScheduler.schedule(appContext, task.id, task.title, task.description, task.dueDate, task.dueTime, task.reminderEnabled && task.status != "completed")
    }

    private suspend fun createNextOccurrence(task: TaskEntity): TaskEntity? {
        val intervalDays = when (task.recurrenceRule) {
            "daily" -> 1L
            "weekly" -> 7L
            "weekdays" -> 1L
            "monthly" -> null
            else -> return null
        }
        var nextDate = task.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val today = LocalDate.now()
        do {
            nextDate = if (task.recurrenceRule == "monthly") nextDate.plusMonths(1) else nextDate.plusDays(intervalDays!!)
            if (task.recurrenceRule == "weekdays" && nextDate.dayOfWeek.value >= 6) {
                nextDate = nextDate.plusDays((8 - nextDate.dayOfWeek.value).toLong())
            }
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
        return next
    }
}
