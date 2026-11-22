package com.example.tasksync.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.tasksync.data.calendar.CalendarTaskSync
import java.util.concurrent.TimeUnit

/** Keeps Room/Calendar Provider reads outside AppWidgetProvider's short broadcast window. */
class CalendarWidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = AppWidgetManager.getInstance(applicationContext)
        val ids = manager.getAppWidgetIds(ComponentName(applicationContext, TaskSyncWidgetProvider::class.java))
        if (ids.isNotEmpty()) TaskSyncWidgetUpdater.refresh(applicationContext, ids)
        return Result.success()
    }
}

object CalendarWidgetRefreshScheduler {
    private const val IMMEDIATE_WORK = "tasksync-calendar-widget-refresh"
    private const val PERIODIC_WORK = "tasksync-calendar-widget-reconciliation"

    fun enqueueImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<CalendarWidgetRefreshWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun ensurePeriodic(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context.applicationContext)
        val calendarWidgetIds = appWidgetManager.getAppWidgetIds(ComponentName(context, TaskSyncWidgetProvider::class.java))
        val calendar = CalendarTaskSync(context.applicationContext)
        if (calendarWidgetIds.isEmpty() || calendar.selectedCalendarId == null || !calendar.hasCalendarPermissions()) {
            cancelPeriodic(context)
            return
        }
        val request = PeriodicWorkRequestBuilder<CalendarWidgetRefreshWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(PERIODIC_WORK)
    }
}

/** A process-lifetime observer gives prompt widget refreshes while TaskSync is alive. */
object TaskSyncCalendarWidgetObserver {
    private var observer: ContentObserver? = null
    private var registeredResolver: android.content.ContentResolver? = null

    @Synchronized
    fun register(context: Context) {
        unregister()
        val appContext = context.applicationContext
        val calendar = CalendarTaskSync(appContext)
        val widgetIds = AppWidgetManager.getInstance(appContext).getAppWidgetIds(
            ComponentName(appContext, TaskSyncWidgetProvider::class.java),
        )
        if (widgetIds.isEmpty() || calendar.selectedCalendarId == null || !calendar.hasCalendarPermissions()) return
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = CalendarWidgetRefreshScheduler.enqueueImmediate(appContext)
        }
        appContext.contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, contentObserver)
        observer = contentObserver
        registeredResolver = appContext.contentResolver
    }

    @Synchronized
    fun unregister() {
        val current = observer ?: return
        registeredResolver?.unregisterContentObserver(current)
        observer = null
        registeredResolver = null
    }
}
