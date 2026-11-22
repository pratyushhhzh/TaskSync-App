package com.example.tasksync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.tasksync.data.reminder.TaskReminderScheduler
import com.example.tasksync.ui.main.MainScreen
import com.example.tasksync.ui.theme.TaskSyncTheme

class MainActivity : ComponentActivity() {
    private var openVoiceAssistantSignal by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(com.example.tasksync.widget.TaskSyncWidgetProvider.EXTRA_OPEN_VOICE, false)) openVoiceAssistantSignal++
        enableEdgeToEdge()
        createReminderNotificationChannel()
        setContent {
            TaskSyncTheme {
                MainScreen(openVoiceAssistantSignal = openVoiceAssistantSignal)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(com.example.tasksync.widget.TaskSyncWidgetProvider.EXTRA_OPEN_VOICE, false)) openVoiceAssistantSignal++
    }

    private fun createReminderNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                TaskReminderScheduler.CHANNEL_ID,
                "Task reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Notifications for tasks at their deadline time" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
