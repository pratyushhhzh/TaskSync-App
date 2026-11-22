package com.example.tasksync

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import com.example.tasksync.widget.CalendarWidgetRefreshScheduler
import com.example.tasksync.widget.TaskSyncWidgetProvider
import com.example.tasksync.widget.TaskSyncCalendarWidgetObserver
import com.example.tasksync.widget.TaskSyncWidgetUpdater

class TaskSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TaskSyncCalendarWidgetObserver.register(this)
        CalendarWidgetRefreshScheduler.ensurePeriodic(this)
        val calendarWidgetIds = AppWidgetManager.getInstance(this).getAppWidgetIds(
            ComponentName(this, TaskSyncWidgetProvider::class.java),
        )
        if (calendarWidgetIds.isNotEmpty()) {
            TaskSyncWidgetUpdater.showPlaceholder(this, calendarWidgetIds)
            CalendarWidgetRefreshScheduler.enqueueImmediate(this)
        }
    }
}
