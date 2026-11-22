package com.example.tasksync.data.repository

import com.example.tasksync.data.local.dao.TaskDao
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.data.local.entity.SubtaskEntity
import kotlinx.coroutines.flow.Flow
import com.example.tasksync.data.reminder.TaskReminderScheduler
import com.example.tasksync.data.calendar.CalendarTaskSync
import com.example.tasksync.data.calendar.GoogleDeviceCalendar
import com.example.tasksync.data.calendar.ExternalCalendarEvent
import com.example.tasksync.data.calendar.CalendarTaskSyncWorker
import com.example.tasksync.widget.TaskSyncWidgetUpdater
import android.content.Context
import java.time.LocalDate
import com.example.tasksync.data.reminder.ReminderScheduleStatus
import com.example.tasksync.data.reminder.ReminderPreferences


class TaskRepository(
    private val taskDao: TaskDao,
    private val appContext: Context
) {
    data class TaskCreationOutcome(val task: TaskEntity, val reminderStatus: ReminderScheduleStatus)
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
        projectId: String? = null,
        reminderOffsets: List<Int>? = null,
        customReminderAtMillis: Long? = null,
    ): TaskEntity {
        val shouldScheduleDefaultReminder = reminderEnabled || (!dueDate.isNullOrBlank() && !dueTime.isNullOrBlank())
        val task = TaskEntity(
            id = "task_" + System.currentTimeMillis() + "_" + (1000..9999).random(),
            userId = "local_user",
            projectId = projectId,
            title = title,
            description = description,
            status = status,
            dueDate = dueDate,
            dueTime = dueTime,
            // Mark a reminder active only after AlarmManager accepts it.
            reminderEnabled = false,
            recurrenceRule = recurrenceRule,
            priority = priority
        )
        reminderOffsets?.let { offsets ->
            if (offsets.toSet() == setOf(ReminderPreferences.defaultOffsetMinutes(appContext))) ReminderPreferences.clearOffsetsForTask(appContext, task.id)
            else if (offsets.isNotEmpty()) ReminderPreferences.setOffsetsForTask(appContext, task.id, offsets)
        }
        if (reminderOffsets != null) ReminderPreferences.setCustomReminderAt(appContext, task.id, customReminderAtMillis)
        taskDao.insertTask(task)
        val savedTask = scheduleReminder(task.copy(reminderEnabled = shouldScheduleDefaultReminder))
        if (calendarTaskSync.selectedCalendarId != null) CalendarTaskSyncWorker.enqueue(appContext, savedTask.id)
        refreshTaskWidget()
        return savedTask
    }

    /** Voice proposal path reports reminder delivery readiness separately from task persistence. */
    suspend fun createTaskWithReminderOutcome(
        title: String,
        description: String?,
        dueDate: String?,
        dueTime: String?,
        reminderRequested: Boolean,
        notificationPermissionGranted: Boolean,
        priority: Int,
    ): TaskCreationOutcome {
        val task = createTask(title, description, dueDate = dueDate, dueTime = dueTime, priority = priority)
        if (!reminderRequested) {
            if (!task.reminderEnabled) return TaskCreationOutcome(task, ReminderScheduleStatus.NOT_REQUESTED)
            val defaultStatus = TaskReminderScheduler.schedule(
                appContext, task.id, task.title, task.description, task.dueDate, task.dueTime,
                enabled = true, permissionGranted = notificationPermissionGranted,
            )
            return TaskCreationOutcome(task, defaultStatus)
        }
        if (dueDate.isNullOrBlank() || dueTime.isNullOrBlank()) return TaskCreationOutcome(task, ReminderScheduleStatus.MISSING_DATE_TIME)
        if (!notificationPermissionGranted) return TaskCreationOutcome(task, ReminderScheduleStatus.PERMISSION_REQUIRED)

        val result = TaskReminderScheduler.schedule(
            appContext, task.id, task.title, task.description, dueDate, dueTime,
            enabled = true, permissionGranted = true,
        )
        if (result != ReminderScheduleStatus.SCHEDULED && result != ReminderScheduleStatus.SCHEDULED_INEXACT) return TaskCreationOutcome(task, result)
        return try {
            val currentTask = taskDao.getTaskById(task.id) ?: task
            val reminderEnabledTask = currentTask.copy(reminderEnabled = true)
            taskDao.updateTask(reminderEnabledTask)
            refreshTaskWidget()
            TaskCreationOutcome(reminderEnabledTask, result)
        } catch (failure: Exception) {
            TaskReminderScheduler.cancel(appContext, task.id)
            TaskCreationOutcome(task, ReminderScheduleStatus.SCHEDULING_FAILED)
        }
    }

    suspend fun updateTask(task: TaskEntity, reminderOffsets: List<Int>? = null, customReminderAtMillis: Long? = null) {
        reminderOffsets?.let { offsets ->
            if (offsets.toSet() == setOf(ReminderPreferences.defaultOffsetMinutes(appContext))) ReminderPreferences.clearOffsetsForTask(appContext, task.id)
            else if (offsets.isNotEmpty()) ReminderPreferences.setOffsetsForTask(appContext, task.id, offsets)
        }
        if (reminderOffsets != null) ReminderPreferences.setCustomReminderAt(appContext, task.id, customReminderAtMillis)
        val updatedTask = task.copy(updatedAt = System.currentTimeMillis())
        taskDao.updateTask(updatedTask)
        val savedTask = scheduleReminder(updatedTask.copy(reminderEnabled = task.reminderEnabled))
        if (calendarTaskSync.selectedCalendarId != null) CalendarTaskSyncWorker.enqueue(appContext, savedTask.id)
        refreshTaskWidget()
    }

    suspend fun restoreTask(task: TaskEntity, subtasks: List<SubtaskEntity> = emptyList()) {
        val restoredTask = task.copy(reminderEnabled = false)
        taskDao.insertTask(restoredTask)
        for (subtask in subtasks) taskDao.insertSubtask(subtask)
        val savedTask = scheduleReminder(restoredTask.copy(reminderEnabled = task.reminderEnabled))
        if (calendarTaskSync.selectedCalendarId != null) CalendarTaskSyncWorker.enqueue(appContext, savedTask.id)
        refreshTaskWidget()
    }

    suspend fun markTaskCompleted(taskId: String): TaskEntity? {
        val task = taskDao.getTaskById(taskId) ?: return null
        taskDao.updateTaskStatus(taskId, "completed", System.currentTimeMillis())
        TaskReminderScheduler.cancel(appContext, taskId)
        taskDao.getTaskById(taskId)?.let {
            if (calendarTaskSync.selectedCalendarId != null) CalendarTaskSyncWorker.enqueue(appContext, it.id)
        }
        val next = createNextOccurrence(task)
        refreshTaskWidget()
        return next
    }

    suspend fun reopenTask(taskId: String, newStatus: String = "inbox") {
        taskDao.updateTaskStatus(taskId, newStatus, System.currentTimeMillis())
        taskDao.getTaskById(taskId)?.let { val saved = scheduleReminder(it); if (calendarTaskSync.selectedCalendarId != null) CalendarTaskSyncWorker.enqueue(appContext, saved.id) }
        refreshTaskWidget()
    }

    suspend fun setTaskStatus(taskId: String, status: String) {
        val current = taskDao.getTaskById(taskId) ?: return
        if (status == "completed" && current.status != "completed") {
            markTaskCompleted(taskId)
        } else {
            taskDao.updateTaskStatus(taskId, status, System.currentTimeMillis())
            taskDao.getTaskById(taskId)?.let {
                val saved = scheduleReminder(it)
                if (calendarTaskSync.selectedCalendarId != null) CalendarTaskSyncWorker.enqueue(appContext, saved.id)
            }
            refreshTaskWidget()
        }
    }

    suspend fun deleteTask(task: TaskEntity): List<SubtaskEntity> {
        val subtasks = taskDao.deleteTaskAndSubtasks(task)
        TaskReminderScheduler.clearTaskConfiguration(appContext, task.id)
        if (task.googleCalendarEventId != null && task.googleCalendarId != null) {
            runCatching { calendarTaskSync.deleteEvent(task) }
                .onSuccess { calendarTaskSync.recordSyncSuccess() }
                .onFailure {
                    calendarTaskSync.recordSyncFailure(it)
                    calendarTaskSync.recordPendingDelete(task.id, task.googleCalendarId, task.googleCalendarEventId)
                    CalendarTaskSyncWorker.enqueueDelete(appContext, task.id, task.googleCalendarId, task.googleCalendarEventId)
                }
        }
        refreshTaskWidget()
        return subtasks
    }

    suspend fun synchronizeReminder(taskId: String, taskAfterTransaction: TaskEntity?) {
        if (taskAfterTransaction == null) TaskReminderScheduler.cancel(appContext, taskId)
        else scheduleReminder(taskAfterTransaction)
    }

    fun refreshTaskWidget() = TaskSyncWidgetUpdater.requestRefresh(appContext)

    suspend fun refreshReminderSchedules(cancelVisibleNotifications: Boolean = true) {
        taskDao.getAllTasksOnce().forEach { scheduleReminder(it, cancelVisibleNotifications) }
        TaskReminderScheduler.scheduleDailyDigest(appContext)
    }

    suspend fun synchronizeCalendar(taskAfterTransaction: TaskEntity?, taskBeforeTransaction: TaskEntity? = null): String? {
        var staleEventDeletionPending = false
        return runCatching {
            if (taskAfterTransaction == null) {
                taskBeforeTransaction?.let { calendarTaskSync.deleteEvent(it) }
            } else {
                val synced = calendarTaskSync.syncTask(taskAfterTransaction)
                val latest = taskDao.getTaskById(taskAfterTransaction.id)
                if (latest == null) {
                    // A task can be deleted while the provider write is in flight. Remove the
                    // event created by that stale write, or persist a safe retry if removal fails.
                    runCatching { calendarTaskSync.deleteEvent(synced) }.onFailure {
                        val calendarId = synced.googleCalendarId
                        val eventId = synced.googleCalendarEventId
                        if (calendarId != null && eventId != null) {
                            staleEventDeletionPending = true
                            calendarTaskSync.recordPendingDelete(synced.id, calendarId, eventId)
                            CalendarTaskSyncWorker.enqueueDelete(appContext, synced.id, calendarId, eventId)
                        }
                    }
                } else if (
                    latest.updatedAt != taskAfterTransaction.updatedAt ||
                    latest.title != taskAfterTransaction.title ||
                    latest.description != taskAfterTransaction.description ||
                    latest.dueDate != taskAfterTransaction.dueDate ||
                    latest.dueTime != taskAfterTransaction.dueTime ||
                    latest.status != taskAfterTransaction.status
                ) {
                    // Do not overwrite a newer local edit with a stale provider response.
                    CalendarTaskSyncWorker.enqueue(appContext, latest.id)
                } else if (synced.googleCalendarId != latest.googleCalendarId ||
                    synced.googleCalendarEventId != latest.googleCalendarEventId
                ) taskDao.updateTask(latest.copy(googleCalendarId = synced.googleCalendarId, googleCalendarEventId = synced.googleCalendarEventId))
            }
            if (staleEventDeletionPending) calendarTaskSync.recordSyncFailure(IllegalStateException("STALE_EVENT_DELETE_PENDING"))
            else calendarTaskSync.recordSyncSuccess()
            refreshTaskWidget()
            null
        }.getOrElse {
            calendarTaskSync.recordSyncFailure(it)
            if (taskAfterTransaction != null && taskAfterTransaction.dueDate.isNullOrBlank()) {
                val calendarId = taskAfterTransaction.googleCalendarId
                val eventId = taskAfterTransaction.googleCalendarEventId
                if (calendarId != null && eventId != null) {
                    calendarTaskSync.recordPendingDelete(taskAfterTransaction.id, calendarId, eventId)
                }
            }
            if (it is SecurityException) "Google Calendar permission is unavailable. Reconnect it to resume synchronization."
            else "Google Calendar could not synchronize. You can retry from the Calendar screen."
        }
    }

    val connectedGoogleCalendarLabel: String? get() = calendarTaskSync.selectedCalendarLabel
    val connectedGoogleCalendarLastSyncMillis: Long? get() = calendarTaskSync.lastSuccessfulSyncMillis
    val connectedGoogleCalendarSyncError: String? get() = calendarTaskSync.lastSyncErrorCode
    val defaultTimedEventDurationMinutes: Int get() = calendarTaskSync.defaultTimedEventDurationMinutes

    fun setDefaultTimedEventDurationMinutes(minutes: Int) = calendarTaskSync.setDefaultTimedEventDurationMinutes(minutes)

    suspend fun availableGoogleCalendars(): List<GoogleDeviceCalendar> = calendarTaskSync.availableGoogleCalendars()

    suspend fun calendarEventsForDate(date: LocalDate, taskSyncEventIds: Set<Long>): List<ExternalCalendarEvent> =
        calendarTaskSync.eventsForDate(date, taskSyncEventIds)

    suspend fun connectGoogleCalendar(calendar: GoogleDeviceCalendar): List<String> {
        calendarTaskSync.connect(calendar)
        val failures = mutableListOf<String>()
        taskDao.getAllTasksOnce().filter { it.status != "completed" && !it.dueDate.isNullOrBlank() }.forEach { task ->
            synchronizeCalendar(task)?.let(failures::add)
        }
        if (failures.isEmpty()) calendarTaskSync.recordSyncSuccess()
        refreshTaskWidget()
        return failures
    }

    suspend fun disconnectGoogleCalendar(removeOwnedEvents: Boolean = true): List<String> {
        val failures = mutableListOf<String>()
        calendarTaskSync.pendingDeletes().forEach { pending ->
            if (removeOwnedEvents) {
                val failure = runCatching { calendarTaskSync.deleteOwnedEvent(pending.calendarId, pending.eventId, pending.taskId) }.exceptionOrNull()
                if (failure == null) calendarTaskSync.clearPendingDelete(pending.taskId, pending.calendarId, pending.eventId)
                else {
                    CalendarTaskSyncWorker.enqueueDelete(appContext, pending.taskId, pending.calendarId, pending.eventId)
                    failures += "Some previously linked calendar events still need removal."
                }
            } else {
                androidx.work.WorkManager.getInstance(appContext).cancelUniqueWork("tasksync-calendar-${pending.taskId}")
                calendarTaskSync.clearPendingDelete(pending.taskId, pending.calendarId, pending.eventId)
            }
        }
        taskDao.getAllTasksOnce().filter { it.googleCalendarEventId != null }.forEach { task ->
            if (!removeOwnedEvents) {
                taskDao.updateTask(task.copy(googleCalendarId = null, googleCalendarEventId = null))
            } else {
                val failure = runCatching { calendarTaskSync.deleteEvent(task) }.exceptionOrNull()
                if (failure == null) taskDao.updateTask(task.copy(googleCalendarId = null, googleCalendarEventId = null))
                else {
                    val calendarId = task.googleCalendarId
                    val eventId = task.googleCalendarEventId
                    if (calendarId != null && eventId != null) {
                        calendarTaskSync.recordPendingDelete(task.id, calendarId, eventId)
                        CalendarTaskSyncWorker.enqueueDelete(appContext, task.id, calendarId, eventId)
                    }
                    failures += "A linked Google Calendar event could not be removed yet. TaskSync will retry; you can also try again from Calendar."
                }
            }
        }
        if (failures.isEmpty()) calendarTaskSync.disconnect()
        refreshTaskWidget()
        return failures
    }

    suspend fun resyncSelectedGoogleCalendar(): List<String> {
        val failures = mutableListOf<String>()
        calendarTaskSync.pendingDeletes().forEach { pending ->
            val failure = runCatching { calendarTaskSync.deleteOwnedEvent(pending.calendarId, pending.eventId, pending.taskId) }.exceptionOrNull()
            if (failure == null) {
                calendarTaskSync.clearPendingDelete(pending.taskId, pending.calendarId, pending.eventId)
                taskDao.getTaskById(pending.taskId)?.let { task ->
                    if (task.googleCalendarId == pending.calendarId && task.googleCalendarEventId == pending.eventId) {
                        taskDao.updateTask(task.copy(googleCalendarId = null, googleCalendarEventId = null, updatedAt = System.currentTimeMillis()))
                    }
                }
            }
            else {
                CalendarTaskSyncWorker.enqueueDelete(appContext, pending.taskId, pending.calendarId, pending.eventId)
                failures += "A previously linked calendar event still needs removal."
            }
        }
        taskDao.getAllTasksOnce().filter {
            it.googleCalendarEventId != null || (it.status != "completed" && !it.dueDate.isNullOrBlank())
        }
            .forEach { task ->
                synchronizeCalendar(task)?.let {
                    CalendarTaskSyncWorker.enqueue(appContext, task.id)
                    failures += it
                }
            }
        return failures
    }

    fun hasGoogleCalendarPermissions(): Boolean = calendarTaskSync.hasCalendarPermissions()

    private suspend fun scheduleReminder(task: TaskEntity, cancelVisibleNotifications: Boolean = true): TaskEntity {
        TaskReminderScheduler.schedule(
            appContext,
            task.id,
            task.title,
            task.description,
            task.dueDate,
            task.dueTime,
            task.reminderEnabled && task.status != "completed",
            cancelVisibleNotifications = cancelVisibleNotifications,
        )
        val stored = task.copy(reminderEnabled = task.reminderEnabled)
        if (stored != task) taskDao.updateTask(stored)
        return stored
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
            reminderEnabled = false,
            googleCalendarId = null,
            googleCalendarEventId = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        return next
    }

    private suspend fun createNextOccurrence(task: TaskEntity): TaskEntity? =
        buildNextOccurrence(task)?.also {
            taskDao.insertTask(it)
            val savedTask = scheduleReminder(it.copy(reminderEnabled = task.reminderEnabled))
            if (calendarTaskSync.selectedCalendarId != null) CalendarTaskSyncWorker.enqueue(appContext, savedTask.id)
            refreshTaskWidget()
        }
}
