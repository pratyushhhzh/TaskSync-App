package com.example.tasksync.data.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.tasksync.MainActivity
import com.example.tasksync.R
import com.example.tasksync.data.local.AppDatabase
import com.example.tasksync.data.local.entity.TaskEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object ReminderPreferences {
    private const val NAME = "task_reminder_settings"
    private const val DEFAULT_OFFSET = "default_offset_minutes"
    private const val AT_DEADLINE = "at_deadline_enabled"
    private const val DIGEST_ENABLED = "deadline_digest_enabled"
    private const val DIGEST_HOUR = "deadline_digest_hour"
    private const val DIGEST_MINUTE = "deadline_digest_minute"
    private const val LAST_NOTIFICATION_ACCESS = "last_notification_access"
    private fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun defaultOffsetMinutes(context: Context): Int = prefs(context).getInt(DEFAULT_OFFSET, 5).takeIf { it in SUPPORTED_OFFSETS } ?: 5
    fun setDefaultOffsetMinutes(context: Context, minutes: Int) { require(minutes in SUPPORTED_OFFSETS); prefs(context).edit().putInt(DEFAULT_OFFSET, minutes).apply() }
    fun atDeadlineEnabled(context: Context): Boolean = prefs(context).getBoolean(AT_DEADLINE, false)
    fun setAtDeadlineEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(AT_DEADLINE, enabled).apply() }
    fun digestEnabled(context: Context): Boolean = prefs(context).getBoolean(DIGEST_ENABLED, true)
    fun setDigestEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(DIGEST_ENABLED, enabled).apply() }
    fun digestTime(context: Context): LocalTime = LocalTime.of(prefs(context).getInt(DIGEST_HOUR, 19), prefs(context).getInt(DIGEST_MINUTE, 0))
    fun setDigestTime(context: Context, time: LocalTime) { prefs(context).edit().putInt(DIGEST_HOUR, time.hour).putInt(DIGEST_MINUTE, time.minute).apply() }
    fun lastNotificationAccess(context: Context): Boolean? = when (prefs(context).getInt(LAST_NOTIFICATION_ACCESS, -1)) {
        0 -> false
        1 -> true
        else -> null
    }
    fun setLastNotificationAccess(context: Context, allowed: Boolean) { prefs(context).edit().putInt(LAST_NOTIFICATION_ACCESS, if (allowed) 1 else 0).apply() }
    fun offsetsForTask(context: Context, taskId: String): List<Int> {
        val raw = prefs(context).getString("offsets_$taskId", null)
        return raw?.split(',')?.mapNotNull(String::toIntOrNull)?.filter { it in SUPPORTED_OFFSETS }?.distinct()?.takeIf { it.isNotEmpty() }
            ?: listOf(defaultOffsetMinutes(context))
    }
    fun setOffsetsForTask(context: Context, taskId: String, minutes: List<Int>) {
        require(minutes.isNotEmpty() && minutes.all { it in SUPPORTED_OFFSETS })
        prefs(context).edit().putString("offsets_$taskId", minutes.distinct().joinToString(",")).apply()
    }
    fun clearOffsetsForTask(context: Context, taskId: String) { prefs(context).edit().remove("offsets_$taskId").apply() }
    fun clearTask(context: Context, taskId: String) {
        prefs(context).edit().remove("offsets_$taskId").remove("custom_at_$taskId").remove("snooze_$taskId").apply()
    }
    fun customReminderAt(context: Context, taskId: String): Long? = prefs(context).getLong("custom_at_$taskId", 0L).takeIf { it > 0L }
    fun setCustomReminderAt(context: Context, taskId: String, epochMillis: Long?) {
        val editor = prefs(context).edit()
        if (epochMillis == null) editor.remove("custom_at_$taskId") else editor.putLong("custom_at_$taskId", epochMillis)
        editor.apply()
    }
    fun saveSnooze(context: Context, taskId: String, epochMillis: Long) { prefs(context).edit().putLong("snooze_$taskId", epochMillis).apply() }
    fun snooze(context: Context, taskId: String): Long? = prefs(context).getLong("snooze_$taskId", 0L).takeIf { it > 0L }
    fun clearSnooze(context: Context, taskId: String) { prefs(context).edit().remove("snooze_$taskId").apply() }

    val SUPPORTED_OFFSETS = setOf(0, 5, 10, 15, 30, 60, 1440)
}

enum class ReminderScheduleStatus {
    NOT_REQUESTED, MISSING_DATE_TIME, PERMISSION_REQUIRED, APP_NOTIFICATIONS_DISABLED,
    CHANNEL_DISABLED, PAST_TIME, SCHEDULED, SCHEDULED_INEXACT, SCHEDULING_FAILED,
}

object TaskReminderScheduler {
    const val CHANNEL_ID = "task_reminders"
    const val DIGEST_CHANNEL_ID = "deadline_digests"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_OFFSET_MINUTES = "offset_minutes"
    const val EXTRA_EXPECTED_DEADLINE = "expected_deadline"
    const val ACTION_COMPLETE = "com.example.tasksync.REMINDER_COMPLETE"
    const val ACTION_SNOOZE = "com.example.tasksync.REMINDER_SNOOZE"
    const val ACTION_DIGEST = "com.example.tasksync.DEADLINE_DIGEST"
    const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
    const val EXTRA_OPEN_TASK_ID = "open_task_id"
    private const val DIGEST_REQUEST_CODE = 0x745D31
    private const val DIGEST_NOTIFICATION_ID = 0x745D32

    fun exactAlarmAvailable(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(context: Context, taskId: String, title: String, description: String?, dueDate: String?, dueTime: String?, enabled: Boolean, permissionGranted: Boolean? = null, cancelVisibleNotifications: Boolean = true): ReminderScheduleStatus {
        val customReminderAt = ReminderPreferences.customReminderAt(context, taskId)
        val hasDeadline = !dueDate.isNullOrBlank() && !dueTime.isNullOrBlank()
        cancelAlarms(context, taskId, clearSnooze = !enabled || (!hasDeadline && customReminderAt == null))
        if (cancelVisibleNotifications) cancelTaskNotifications(context, taskId)
        if (!enabled) return ReminderScheduleStatus.NOT_REQUESTED
        if ((dueDate.isNullOrBlank() || dueTime.isNullOrBlank()) && customReminderAt == null) return ReminderScheduleStatus.MISSING_DATE_TIME
        val hasNotificationPermission = permissionGranted ?: (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        if (!hasNotificationPermission) return ReminderScheduleStatus.PERMISSION_REQUIRED
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return ReminderScheduleStatus.APP_NOTIFICATIONS_DISABLED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = context.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
            if (channel == null || channel.importance == android.app.NotificationManager.IMPORTANCE_NONE) return ReminderScheduleStatus.CHANNEL_DISABLED
        }
        val deadline = if (!dueDate.isNullOrBlank() && !dueTime.isNullOrBlank()) runCatching {
            LocalDateTime.of(LocalDate.parse(dueDate), LocalTime.parse(dueTime)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull() else null
        if (!dueDate.isNullOrBlank() && !dueTime.isNullOrBlank() && deadline == null) return ReminderScheduleStatus.SCHEDULING_FAILED
        val offsets = if (deadline != null) (ReminderPreferences.offsetsForTask(context, taskId) + if (ReminderPreferences.atDeadlineEnabled(context)) listOf(0) else emptyList()).distinct() else emptyList()
        val alarms = deadline?.let { ReminderSchedulePlanner.reminderTriggers(it, offsets, System.currentTimeMillis()) }.orEmpty().toMutableList()
        if (customReminderAt != null && customReminderAt > System.currentTimeMillis()) alarms += customReminderAt to CUSTOM_REMINDER_OFFSET
        if (alarms.isEmpty()) return ReminderScheduleStatus.PAST_TIME
        return runCatching {
            var allPrecise = true
            alarms.forEach { (triggerAt, offset) -> allPrecise = scheduleAlarm(context, taskId, offset, deadline ?: 0L, triggerAt) && allPrecise }
            scheduleSnoozeIfPending(context, taskId)
            if (allPrecise) ReminderScheduleStatus.SCHEDULED else ReminderScheduleStatus.SCHEDULED_INEXACT
        }.getOrElse { ReminderScheduleStatus.SCHEDULING_FAILED }
    }

    private fun scheduleAlarm(context: Context, taskId: String, offsetMinutes: Int, deadline: Long, triggerAt: Long): Boolean {
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            action = alarmAction(taskId, offsetMinutes)
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_OFFSET_MINUTES, offsetMinutes)
            putExtra(EXTRA_EXPECTED_DEADLINE, deadline)
        }
        val pending = PendingIntent.getBroadcast(context, alarmRequestCode(taskId, offsetMinutes), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return scheduleSystemAlarm(context, triggerAt, pending)
    }

    private fun scheduleSystemAlarm(context: Context, triggerAt: Long, pending: PendingIntent): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java)
        if (exactAlarmAvailable(context)) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                return true
            } catch (_: SecurityException) {
                // Exact-alarm access can change between the capability check and the call.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        return false
    }

    private fun scheduleSnoozeIfPending(context: Context, taskId: String) {
        val trigger = ReminderPreferences.snooze(context, taskId) ?: return
        if (trigger <= System.currentTimeMillis()) { ReminderPreferences.clearSnooze(context, taskId); return }
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            action = "com.example.tasksync.TASK_REMINDER_SNOOZE.$taskId"
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_OFFSET_MINUTES, SNOOZE_OFFSET)
        }
        val pending = PendingIntent.getBroadcast(context, snoozeRequestCode(taskId), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        scheduleSystemAlarm(context, trigger, pending)
    }

    fun scheduleSnooze(context: Context, taskId: String, delayMillis: Long) {
        val trigger = System.currentTimeMillis() + delayMillis
        ReminderPreferences.saveSnooze(context, taskId, trigger)
        scheduleSnoozeIfPending(context, taskId)
    }

    fun cancel(context: Context, taskId: String) {
        cancelAlarms(context, taskId, clearSnooze = true)
        cancelTaskNotifications(context, taskId)
    }

    private fun cancelAlarms(context: Context, taskId: String, clearSnooze: Boolean) {
        val manager = context.getSystemService(AlarmManager::class.java)
        (ReminderPreferences.offsetsForTask(context, taskId) + ReminderPreferences.defaultOffsetMinutes(context) + listOf(0, 5, 10, 15, 30, 60, 1440, CUSTOM_REMINDER_OFFSET)).distinct().forEach { offset ->
            val intent = Intent(context, TaskReminderReceiver::class.java).setAction(alarmAction(taskId, offset))
            PendingIntent.getBroadcast(context, alarmRequestCode(taskId, offset), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { manager.cancel(it); it.cancel() }
        }
        val snoozeIntent = Intent(context, TaskReminderReceiver::class.java).setAction("com.example.tasksync.TASK_REMINDER_SNOOZE.$taskId")
        PendingIntent.getBroadcast(context, snoozeRequestCode(taskId), snoozeIntent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { manager.cancel(it); it.cancel() }
        if (clearSnooze) ReminderPreferences.clearSnooze(context, taskId)
    }

    fun clearTaskConfiguration(context: Context, taskId: String) { cancel(context, taskId); ReminderPreferences.clearTask(context, taskId) }

    fun scheduleDailyDigest(context: Context) {
        cancelDailyDigest(context)
        if (!ReminderPreferences.digestEnabled(context)) return
        val whenMillis = ReminderSchedulePlanner.nextDigestMillis(System.currentTimeMillis(), ReminderPreferences.digestTime(context), ZoneId.systemDefault())
        val intent = Intent(context, DeadlineDigestReceiver::class.java).setAction(ACTION_DIGEST)
        val pending = PendingIntent.getBroadcast(context, DIGEST_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        scheduleSystemAlarm(context, whenMillis, pending)
    }

    fun cancelDailyDigest(context: Context) {
        val intent = Intent(context, DeadlineDigestReceiver::class.java).setAction(ACTION_DIGEST)
        PendingIntent.getBroadcast(context, DIGEST_REQUEST_CODE, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            context.getSystemService(AlarmManager::class.java).cancel(it); it.cancel()
        }
    }

    fun cancelTaskNotifications(context: Context, taskId: String) {
        (ReminderPreferences.offsetsForTask(context, taskId) + ReminderPreferences.defaultOffsetMinutes(context) + listOf(0, 5, 10, 15, 30, 60, 1440, SNOOZE_OFFSET, CUSTOM_REMINDER_OFFSET)).distinct()
            .forEach { NotificationManagerCompat.from(context).cancel(notificationId(context, taskId, it)) }
    }

    fun showNotification(context: Context, task: TaskEntity, offsetMinutes: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val openIntent = Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_TASK_ID, task.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(context, stableRequestCode(context, "open:${task.id}"), openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (offsetMinutes == 0) "Task due now: ${task.title}" else if (offsetMinutes == CUSTOM_REMINDER_OFFSET) "Task reminder: ${task.title}" else "Upcoming task: ${task.title}")
            .setContentText(task.dueTime?.let { "Due at ${formatTime(it)}" } ?: "Your custom reminder is due.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(task.description?.takeIf(String::isNotBlank), task.dueTime?.let { "Due at ${formatTime(it)}" }).joinToString(" · ").ifBlank { "Your custom reminder is due." }))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setGroup("tasksync_task_reminders")
            .addAction(0, "Complete", actionIntent(context, task.id, ACTION_COMPLETE, 0))
        listOf(5, 10, 30, 60).forEach { minutes -> builder.addAction(0, "${minutes}m", actionIntent(context, task.id, ACTION_SNOOZE, minutes)) }
        builder.addAction(0, "Tomorrow", actionIntent(context, task.id, ACTION_SNOOZE, SNOOZE_UNTIL_TOMORROW))
        NotificationManagerCompat.from(context).notify(notificationId(context, task.id, offsetMinutes), builder.build())
    }

    private fun actionIntent(context: Context, taskId: String, action: String, snoozeMinutes: Int): PendingIntent {
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_SNOOZE_MINUTES, snoozeMinutes)
        }
        val request = stableRequestCode(context, "action:$taskId:$action:$snoozeMinutes")
        val pending = PendingIntent.getBroadcast(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return pending
    }

    private fun alarmAction(taskId: String, offset: Int) = "com.example.tasksync.TASK_REMINDER.$taskId.$offset"
    private fun alarmRequestCode(taskId: String, offset: Int) = 31 * taskId.hashCode() + offset
    private fun snoozeRequestCode(taskId: String) = 31 * taskId.hashCode() + SNOOZE_OFFSET
    private fun notificationId(context: Context, taskId: String, offset: Int): Int = stableRequestCode(context, "notification:$taskId:$offset")

    /** Persisted IDs avoid hash collisions between task PendingIntents and notifications. */
    private fun stableRequestCode(context: Context, identity: String): Int = synchronized(this) {
        val preferences = context.getSharedPreferences("task_reminder_notification_ids", Context.MODE_PRIVATE)
        val key = "id_$identity"
        preferences.getInt(key, 0).takeIf { it != 0 } ?: run {
            val nextId = (preferences.getInt("next_id", 100_000) + 1).coerceAtLeast(100_001)
            preferences.edit().putInt(key, nextId).putInt("next_id", nextId).commit()
            nextId
        }
    }
    private fun formatTime(value: String) = runCatching { LocalTime.parse(value).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a")) }.getOrDefault(value)
    const val SNOOZE_UNTIL_TOMORROW = -1
    const val SNOOZE_OFFSET = -2
    const val CUSTOM_REMINDER_OFFSET = -3
}

class DeadlineDigestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!ReminderPreferences.digestEnabled(context)) return@launch
                val tomorrow = ReminderSchedulePlanner.tomorrowDate(System.currentTimeMillis(), ZoneId.systemDefault()).toString()
                val tasks = AppDatabase.getDatabase(context).taskDao().getAllTasksOnce()
                    .filter { it.status != "completed" && it.dueDate == tomorrow }
                    .sortedBy { it.dueTime ?: "99:99" }
                if (tasks.isNotEmpty() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
                    val lines = tasks.map { task -> task.dueTime?.let { "${task.title} · ${runCatching { LocalTime.parse(it).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a")) }.getOrDefault(it)}" } ?: task.title }
                    val inbox = NotificationCompat.InboxStyle().setSummaryText("${tasks.size} task${if (tasks.size == 1) "" else "s"} due tomorrow")
                    lines.forEach(inbox::addLine)
                    val openIntent = Intent(context, MainActivity::class.java)
                        .putExtra(TaskReminderScheduler.EXTRA_OPEN_TASK_ID, tasks.first().id)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    val open = PendingIntent.getActivity(context, DIGEST_OPEN_REQUEST, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val notification = NotificationCompat.Builder(context, TaskReminderScheduler.DIGEST_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("Tomorrow: ${tasks.size} task${if (tasks.size == 1) "" else "s"}")
                        .setContentText(lines.first())
                        .setStyle(inbox)
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .setGroup("tasksync_deadline_digest")
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .build()
                    NotificationManagerCompat.from(context).notify(DIGEST_NOTIFICATION_ID, notification)
                }
            } finally {
                TaskReminderScheduler.scheduleDailyDigest(context)
                pending.finish()
            }
        }
    }

    companion object { private const val DIGEST_NOTIFICATION_ID = 0x745D32; private const val DIGEST_OPEN_REQUEST = 0x745D33 }
}

class TaskReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getDatabase(context).taskDao()
                dao.getTasksWithReminders().forEach { task ->
                    TaskReminderScheduler.schedule(context, task.id, task.title, task.description, task.dueDate, task.dueTime, task.reminderEnabled, cancelVisibleNotifications = false)
                }
                TaskReminderScheduler.scheduleDailyDigest(context)
            } finally { pending.finish() }
        }
    }
}
