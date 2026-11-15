package com.example.tasksync.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.tasksync.MainActivity
import com.example.tasksync.R
import com.example.tasksync.data.local.AppDatabase
import com.example.tasksync.data.local.entity.TaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class TaskSyncWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        TaskSyncWidgetUpdater.refresh(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            refreshAll(context, manager)
        }
    }

    companion object {
        const val EXTRA_OPEN_VOICE = "com.example.tasksync.OPEN_VOICE_ASSISTANT"
        const val ACTION_REFRESH = "com.example.tasksync.widget.REFRESH"

        private fun refreshAll(context: Context, manager: AppWidgetManager) {
            val ids = manager.getAppWidgetIds(ComponentName(context, TaskSyncWidgetProvider::class.java))
            TaskSyncWidgetUpdater.refresh(context, ids)
        }
    }
}

object TaskSyncWidgetUpdater {
    fun requestRefresh(context: Context) {
        context.sendBroadcast(Intent(context, TaskSyncWidgetProvider::class.java).setAction(TaskSyncWidgetProvider.ACTION_REFRESH))
    }

    fun refresh(context: Context, widgetIds: IntArray) {
        if (widgetIds.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            val tasks = runCatching { AppDatabase.getDatabase(context).taskDao().getAllTasksOnce() }
                .getOrDefault(emptyList())
                .filter { it.status != "completed" }
                .sortedWith(compareBy<TaskEntity>({ it.dueDate.isNullOrBlank() }, { it.dueDate }, { it.dueTime }, { it.createdAt }))
            val manager = AppWidgetManager.getInstance(context)
            widgetIds.forEach { id -> manager.updateAppWidget(id, makeViews(context, tasks)) }
        }
    }

    private fun makeViews(context: Context, tasks: List<TaskEntity>): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_tasksync)
        views.setTextViewText(R.id.widget_task_1, taskLine(tasks.getOrNull(0)))
        views.setTextViewText(R.id.widget_task_2, taskLine(tasks.getOrNull(1)))
        views.setTextViewText(R.id.widget_task_3, taskLine(tasks.getOrNull(2)))
        val voiceIntent = Intent(context, MainActivity::class.java)
            .putExtra(TaskSyncWidgetProvider.EXTRA_OPEN_VOICE, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val listIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        views.setOnClickPendingIntent(R.id.widget_mic, PendingIntent.getActivity(context, 4101, voiceIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        views.setOnClickPendingIntent(R.id.widget_all_tasks, PendingIntent.getActivity(context, 4102, listIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        return views
    }

    private fun taskLine(task: TaskEntity?): String {
        if (task == null) return "· No more open tasks"
        val dateLabel = task.dueDate?.let { raw ->
            runCatching { LocalDate.parse(raw).format(DateTimeFormatter.ofPattern("MMM d")) }.getOrNull()
        }
        val timeLabel = task.dueTime?.takeIf(String::isNotBlank)?.take(5)
        val metadata = listOfNotNull(dateLabel, timeLabel).joinToString(" · ")
        return if (metadata.isBlank()) "• ${task.title}" else "• ${task.title}  ·  $metadata"
    }
}
