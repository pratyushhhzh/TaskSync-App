package com.example.tasksync.data.calendar

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.BackoffPolicy
import com.example.tasksync.data.local.AppDatabase
import com.example.tasksync.data.repository.TaskRepository
import java.util.concurrent.TimeUnit

class CalendarTaskSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val eventId = inputData.getLong(KEY_DELETE_EVENT_ID, -1L)
        val calendarId = inputData.getLong(KEY_DELETE_CALENDAR_ID, -1L)
        if (eventId >= 0L && calendarId >= 0L) {
            val calendarSync = CalendarTaskSync(applicationContext)
            if (!calendarSync.hasCalendarPermissions()) {
                calendarSync.recordSyncFailure(SecurityException())
                return retryOrFail()
            }
            val pendingTaskId = inputData.getString(KEY_TASK_ID).orEmpty()
            return runCatching {
                calendarSync.deleteOwnedEvent(calendarId, eventId, pendingTaskId)
                calendarSync.clearPendingDelete(pendingTaskId, calendarId, eventId)
                val taskDao = AppDatabase.getDatabase(applicationContext).taskDao()
                taskDao.getTaskById(pendingTaskId)?.let { task ->
                    if (task.googleCalendarId == calendarId && task.googleCalendarEventId == eventId) {
                        taskDao.updateTask(
                            task.copy(googleCalendarId = null, googleCalendarEventId = null, updatedAt = System.currentTimeMillis()),
                        )
                    }
                }
                com.example.tasksync.widget.TaskSyncWidgetUpdater.requestRefresh(applicationContext)
                calendarSync.recordSyncSuccess()
                Result.success()
            }.getOrElse { calendarSync.recordSyncFailure(it); retryOrFail() }
        }
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val dao = AppDatabase.getDatabase(applicationContext).taskDao()
        val task = dao.getTaskById(taskId) ?: return Result.success()
        val repository = TaskRepository(dao, applicationContext)
        if (repository.connectedGoogleCalendarLabel == null) return Result.success()
        if (!repository.hasGoogleCalendarPermissions()) {
            CalendarTaskSync(applicationContext).recordSyncFailure(SecurityException())
            return retryOrFail()
        }
        val failure = repository.synchronizeCalendar(task)
        if (failure == null) return Result.success()
        return retryOrFail()
    }

    private fun retryOrFail() = if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

    companion object {
        private const val KEY_TASK_ID = "task_id"
        private const val KEY_DELETE_EVENT_ID = "delete_event_id"
        private const val KEY_DELETE_CALENDAR_ID = "delete_calendar_id"
        private const val MAX_ATTEMPTS = 5

        fun enqueue(context: Context, taskId: String) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = OneTimeWorkRequestBuilder<CalendarTaskSyncWorker>()
                .setInputData(Data.Builder().putString(KEY_TASK_ID, taskId).build())
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "tasksync-calendar-$taskId",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        fun enqueueDelete(context: Context, taskId: String, calendarId: Long, eventId: Long) {
            val request = OneTimeWorkRequestBuilder<CalendarTaskSyncWorker>()
                .setInputData(Data.Builder().putString(KEY_TASK_ID, taskId).putLong(KEY_DELETE_EVENT_ID, eventId).putLong(KEY_DELETE_CALENDAR_ID, calendarId).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "tasksync-calendar-$taskId",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
