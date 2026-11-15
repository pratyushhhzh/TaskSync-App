package com.example.tasksync.data.repository

import com.example.tasksync.data.local.dao.TaskDao
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.data.local.entity.SubtaskEntity
import kotlinx.coroutines.flow.Flow
import com.example.tasksync.data.reminder.TaskReminderScheduler
import com.example.tasksync.data.calendar.CalendarTaskSync
import com.example.tasksync.data.calendar.GoogleDeviceCalendar
import com.example.tasksync.widget.TaskSyncWidgetUpdater
import android.content.Context
import java.time.LocalDate


class TaskRepository(
    private val taskDao: TaskDao,
    private val appContext: Context
) {
    private val calendarTaskSync = CalendarTaskSync(appContext)
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
        synchronizeCalendar(task)
        refreshTaskWidget()
        return task
    }

    suspend fun updateTask(task: TaskEntity) {
        val updatedTask = task.copy(updatedAt = System.currentTimeMillis())
        taskDao.updateTask(updatedTask)
        scheduleReminder(updatedTask)
        synchronizeCalendar(updatedTask)
        refreshTaskWidget()
    }

    suspend fun restoreTask(task: TaskEntity, subtasks: List<SubtaskEntity> = emptyList()) {
        taskDao.insertTask(task)
        for (subtask in subtasks) taskDao.insertSubtask(subtask)
        scheduleReminder(task)
        synchronizeCalendar(task)
        refreshTaskWidget()
    }

    suspend fun markTaskCompleted(taskId: String): TaskEntity? {
        val task = taskDao.getTaskById(taskId) ?: return null
        taskDao.updateTaskStatus(taskId, "completed", System.currentTimeMillis())
        TaskReminderScheduler.cancel(appContext, taskId)
        taskDao.getTaskById(taskId)?.let { synchronizeCalendar(it, task) }
        val next = createNextOccurrence(task)
        refreshTaskWidget()
        return next
    }

    suspend fun reopenTask(taskId: String, newStatus: String = "inbox") {
        taskDao.updateTaskStatus(taskId, newStatus, System.currentTimeMillis())
        taskDao.getTaskById(taskId)?.let { scheduleReminder(it); synchronizeCalendar(it) }
        refreshTaskWidget()
    }

    suspend fun setTaskStatus(taskId: String, status: String) {
        val current = taskDao.getTaskById(taskId) ?: return
        if (status == "completed" && current.status != "completed") {
            markTaskCompleted(taskId)
        } else {
            taskDao.updateTaskStatus(taskId, status, System.currentTimeMillis())
            taskDao.getTaskById(taskId)?.let {
                scheduleReminder(it)
                synchronizeCalendar(it)
            }
            refreshTaskWidget()
        }
    }

    suspend fun deleteTask(task: TaskEntity): List<SubtaskEntity> {
        val subtasks = taskDao.deleteTaskAndSubtasks(task)
        TaskReminderScheduler.cancel(appContext, task.id)
        synchronizeCalendar(null, task)
        refreshTaskWidget()
        return subtasks
    }

    fun synchronizeReminder(taskId: String, taskAfterTransaction: TaskEntity?) {
        if (taskAfterTransaction == null) TaskReminderScheduler.cancel(appContext, taskId)
        else scheduleReminder(taskAfterTransaction)
    }

    fun refreshTaskWidget() = TaskSyncWidgetUpdater.requestRefresh(appContext)

    suspend fun synchronizeCalendar(taskAfterTransaction: TaskEntity?, taskBeforeTransaction: TaskEntity? = null): String? =
        runCatching {
            if (taskAfterTransaction == null) {
                taskBeforeTransaction?.let { calendarTaskSync.deleteEvent(it) }
            } else {
                val synced = calendarTaskSync.syncTask(taskAfterTransaction)
                if (synced.googleCalendarId != taskAfterTransaction.googleCalendarId ||
                    synced.googleCalendarEventId != taskAfterTransaction.googleCalendarEventId
                ) taskDao.updateTask(synced)
            }
            null
        }.getOrElse { "Google Calendar sync failed: ${it.message ?: "calendar unavailable"}." }

    val connectedGoogleCalendarLabel: String? get() = calendarTaskSync.selectedCalendarLabel

    suspend fun availableGoogleCalendars(): List<GoogleDeviceCalendar> = calendarTaskSync.availableGoogleCalendars()

    suspend fun connectGoogleCalendar(calendar: GoogleDeviceCalendar): List<String> {
        calendarTaskSync.connect(calendar)
        val failures = mutableListOf<String>()
        taskDao.getAllTasksOnce().filter { it.status != "completed" && !it.dueDate.isNullOrBlank() }.forEach { task ->
            synchronizeCalendar(task)?.let(failures::add)
        }
        refreshTaskWidget()
        return failures
    }

    suspend fun disconnectGoogleCalendar(): List<String> {
        val failures = mutableListOf<String>()
        taskDao.getAllTasksOnce().filter { it.googleCalendarEventId != null }.forEach { task ->
            val failure = runCatching { calendarTaskSync.deleteEvent(task) }.exceptionOrNull()
            if (failure == null) taskDao.updateTask(task.copy(googleCalendarId = null, googleCalendarEventId = null))
            else failures += "Could not remove one linked Google Calendar event: ${failure.message ?: "calendar unavailable"}."
        }
        if (failures.isEmpty()) calendarTaskSync.disconnect()
        refreshTaskWidget()
        return failures
    }

    private fun scheduleReminder(task: TaskEntity) {
        TaskReminderScheduler.schedule(appContext, task.id, task.title, task.description, task.dueDate, task.dueTime, task.reminderEnabled && task.status != "completed")
    }

    fun buildNextOccurrence(task: TaskEntity): TaskEntity? {
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
        return next
    }

    private suspend fun createNextOccurrence(task: TaskEntity): TaskEntity? =
        buildNextOccurrence(task)?.also {
            taskDao.insertTask(it)
            scheduleReminder(it)
            synchronizeCalendar(it)
            refreshTaskWidget()
        }
}
