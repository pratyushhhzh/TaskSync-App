package com.example.tasksync.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.tasksync.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(TaskReminderScheduler.EXTRA_TASK_ID) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getDatabase(context.applicationContext).taskDao()
                when (intent.action) {
                    TaskReminderScheduler.ACTION_COMPLETE -> {
                        com.example.tasksync.data.repository.TaskRepository(dao, context.applicationContext).markTaskCompleted(taskId)
                        TaskReminderScheduler.cancelTaskNotifications(context, taskId)
                    }
                    TaskReminderScheduler.ACTION_SNOOZE -> {
                        val task = dao.getTaskById(taskId)
                        if (task != null && task.status != "completed") {
                            val minutes = intent.getIntExtra(TaskReminderScheduler.EXTRA_SNOOZE_MINUTES, 5)
                            val delay = if (minutes == TaskReminderScheduler.SNOOZE_UNTIL_TOMORROW) {
                                val dueTime = task.dueTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(9, 0)
                                val now = java.time.ZonedDateTime.now(ZoneId.systemDefault())
                                now.toLocalDate().plusDays(1).atTime(dueTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - System.currentTimeMillis()
                            } else minutes.coerceAtLeast(1) * 60_000L
                            TaskReminderScheduler.scheduleSnooze(context, taskId, delay)
                            TaskReminderScheduler.cancelTaskNotifications(context, taskId)
                        }
                    }
                    else -> {
                        val task = dao.getTaskById(taskId)
                        if (task == null || task.status == "completed" || !task.reminderEnabled) return@launch
                        val offset = intent.getIntExtra(TaskReminderScheduler.EXTRA_OFFSET_MINUTES, 5)
                        val expectedDeadline = intent.getLongExtra(TaskReminderScheduler.EXTRA_EXPECTED_DEADLINE, 0L)
                        if (offset == TaskReminderScheduler.CUSTOM_REMINDER_OFFSET) {
                            val customAt = ReminderPreferences.customReminderAt(context, taskId) ?: return@launch
                            if (customAt > System.currentTimeMillis()) return@launch
                            ReminderPreferences.setCustomReminderAt(context, taskId, null)
                        } else if (offset != TaskReminderScheduler.SNOOZE_OFFSET) {
                            if (task.dueDate.isNullOrBlank() || task.dueTime.isNullOrBlank()) return@launch
                            val actualDeadline = runCatching {
                                LocalDateTime.of(LocalDate.parse(task.dueDate), LocalTime.parse(task.dueTime))
                                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            }.getOrNull() ?: return@launch
                            if (expectedDeadline != 0L && actualDeadline != expectedDeadline) {
                                TaskReminderScheduler.schedule(context, task.id, task.title, task.description, task.dueDate, task.dueTime, task.reminderEnabled)
                                return@launch
                            }
                        }
                        val snooze = ReminderPreferences.snooze(context, taskId)
                        if (snooze != null && snooze > System.currentTimeMillis()) return@launch
                        ReminderPreferences.clearSnooze(context, taskId)
                        TaskReminderScheduler.showNotification(context, task, offset)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
