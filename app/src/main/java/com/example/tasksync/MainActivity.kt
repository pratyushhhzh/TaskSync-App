package com.example.tasksync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.tasksync.data.local.AppDatabase
import com.example.tasksync.data.repository.TaskRepository
import com.example.tasksync.data.reminder.ReminderPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.tasksync.data.reminder.TaskReminderScheduler
import com.example.tasksync.ui.main.MainScreen
import com.example.tasksync.ui.theme.TaskSyncTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var openVoiceAssistantSignal by mutableStateOf(0)
    private var openTaskSignal by mutableStateOf(0)
    private var openTaskId by mutableStateOf<String?>(null)
    private var openCalendarWidgetSignal by mutableStateOf(0)
    private var openCalendarWidgetDate by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(com.example.tasksync.widget.TaskSyncWidgetProvider.EXTRA_OPEN_VOICE, false)) openVoiceAssistantSignal++
        intent.getStringExtra(TaskReminderScheduler.EXTRA_OPEN_TASK_ID)?.let { taskId -> openTaskId = taskId; openTaskSignal++ }
        intent.getStringExtra(com.example.tasksync.widget.TaskSyncWidgetProvider.EXTRA_OPEN_CALENDAR_DATE)?.let { date -> openCalendarWidgetDate = date; openCalendarWidgetSignal++ }
        enableEdgeToEdge()
        createReminderNotificationChannel()
        setContent {
            TaskSyncTheme {
                MainScreen(
                    openVoiceAssistantSignal = openVoiceAssistantSignal,
                    openTaskSignal = openTaskSignal,
                    openTaskId = openTaskId,
                    openCalendarWidgetSignal = openCalendarWidgetSignal,
                    openCalendarWidgetDate = openCalendarWidgetDate,
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(com.example.tasksync.widget.TaskSyncWidgetProvider.EXTRA_OPEN_VOICE, false)) openVoiceAssistantSignal++
        intent.getStringExtra(TaskReminderScheduler.EXTRA_OPEN_TASK_ID)?.let { taskId -> openTaskId = taskId; openTaskSignal++ }
        intent.getStringExtra(com.example.tasksync.widget.TaskSyncWidgetProvider.EXTRA_OPEN_CALENDAR_DATE)?.let { date -> openCalendarWidgetDate = date; openCalendarWidgetSignal++ }
    }

    override fun onResume() {
        super.onResume()
        val notificationsAllowed = NotificationManagerCompat.from(this).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        val previousAccess = ReminderPreferences.lastNotificationAccess(this)
        ReminderPreferences.setLastNotificationAccess(this, notificationsAllowed)
        if (previousAccess != notificationsAllowed) {
            lifecycleScope.launch(Dispatchers.IO) {
                val dao = AppDatabase.getDatabase(applicationContext).taskDao()
                TaskRepository(dao, applicationContext).refreshReminderSchedules(cancelVisibleNotifications = false)
            }
        }
    }

    private fun createReminderNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                TaskReminderScheduler.CHANNEL_ID,
                "Task reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Task reminders before and at their deadlines" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            val digestChannel = NotificationChannel(
                TaskReminderScheduler.DIGEST_CHANNEL_ID,
                "Deadline summaries",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "A daily summary of unfinished tasks due tomorrow" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(digestChannel)
        }
        TaskReminderScheduler.scheduleDailyDigest(this)
    }
}
