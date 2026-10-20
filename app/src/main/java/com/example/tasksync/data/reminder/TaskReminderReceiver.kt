package com.example.tasksync.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TaskReminderScheduler.showNotification(
            context,
            intent.getStringExtra(TaskReminderScheduler.EXTRA_TASK_ID) ?: return,
            intent.getStringExtra(TaskReminderScheduler.EXTRA_TITLE) ?: "Task",
            intent.getStringExtra(TaskReminderScheduler.EXTRA_DESCRIPTION)
        )
    }
}
