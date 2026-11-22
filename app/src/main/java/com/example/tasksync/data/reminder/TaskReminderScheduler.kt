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

    fun schedule(
        context: Context,
        taskId: String,
        title: String,
        description: String?,
        dueDate: String?,
        dueTime: String?,
        enabled: Boolean,
        permissionGranted: Boolean? = null,
    ): ReminderScheduleStatus {
        cancel(context, taskId)
        if (!enabled) return ReminderScheduleStatus.NOT_REQUESTED
        if (dueDate.isNullOrBlank() || dueTime.isNullOrBlank()) return ReminderScheduleStatus.MISSING_DATE_TIME
        val hasPermission = permissionGranted ?: (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        if (!hasPermission) return ReminderScheduleStatus.PERMISSION_REQUIRED
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return ReminderScheduleStatus.APP_NOTIFICATIONS_DISABLED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = context.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
            if (channel == null || channel.importance == android.app.NotificationManager.IMPORTANCE_NONE) {
                return ReminderScheduleStatus.CHANNEL_DISABLED
            }
        }
        val triggerAt = runCatching {
            LocalDateTime.of(java.time.LocalDate.parse(dueDate), LocalTime.parse(dueTime)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull() ?: return ReminderScheduleStatus.SCHEDULING_FAILED
        if (triggerAt <= System.currentTimeMillis()) return ReminderScheduleStatus.PAST_TIME

        return runCatching {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context, taskId, title, description))
            ReminderScheduleStatus.SCHEDULED
        }.getOrElse { ReminderScheduleStatus.SCHEDULING_FAILED }
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

enum class ReminderScheduleStatus {
    NOT_REQUESTED,
    MISSING_DATE_TIME,
    PERMISSION_REQUIRED,
    APP_NOTIFICATIONS_DISABLED,
    CHANNEL_DISABLED,
    PAST_TIME,
    SCHEDULED,
    SCHEDULING_FAILED,
}

class TaskReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val dao = com.example.tasksync.data.local.AppDatabase.getDatabase(context).taskDao()
                dao.getTasksWithReminders().forEach { task ->
                    val status = TaskReminderScheduler.schedule(context, task.id, task.title, task.description, task.dueDate, task.dueTime, task.reminderEnabled)
                    if (status != ReminderScheduleStatus.SCHEDULED) dao.updateTask(task.copy(reminderEnabled = false))
                }
            } finally {
                pending.finish()
            }
        }
    }
}
