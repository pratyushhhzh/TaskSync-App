package com.example.tasksync.data.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.tasksync.R
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.launch

object TaskReminderScheduler {
    const val CHANNEL_ID = "task_reminders"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_TITLE = "task_title"
    const val EXTRA_DESCRIPTION = "task_description"

    fun schedule(context: Context, taskId: String, title: String, description: String?, dueDate: String?, dueTime: String?, enabled: Boolean) {
        cancel(context, taskId)
        if (!enabled || dueDate.isNullOrBlank() || dueTime.isNullOrBlank()) return
        val triggerAt = runCatching {
            LocalDateTime.of(java.time.LocalDate.parse(dueDate), LocalTime.parse(dueTime)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull() ?: return
        if (triggerAt <= System.currentTimeMillis()) return

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context, taskId, title, description))
    }

    fun cancel(context: Context, taskId: String) {
        val intent = Intent(context, TaskReminderReceiver::class.java).setAction("com.example.tasksync.TASK_REMINDER.$taskId")
        val pending = PendingIntent.getBroadcast(context, taskId.hashCode(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pending != null) {
            context.getSystemService(AlarmManager::class.java).cancel(pending)
            pending.cancel()
        }
    }

    fun pendingIntent(context: Context, taskId: String, title: String, description: String?): PendingIntent {
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            action = "com.example.tasksync.TASK_REMINDER.$taskId"
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_DESCRIPTION, description)
        }
        return PendingIntent.getBroadcast(context, taskId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun showNotification(context: Context, taskId: String, title: String, description: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Task reminder: $title")
            .setContentText(description?.takeIf(String::isNotBlank) ?: "This task is due now.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(description?.takeIf(String::isNotBlank) ?: "This task is due now."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(taskId.hashCode(), notification)
    }
}

class TaskReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                com.example.tasksync.data.local.AppDatabase.getDatabase(context).taskDao().getTasksWithReminders().forEach { task ->
                    TaskReminderScheduler.schedule(context, task.id, task.title, task.description, task.dueDate, task.dueTime, task.reminderEnabled)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
