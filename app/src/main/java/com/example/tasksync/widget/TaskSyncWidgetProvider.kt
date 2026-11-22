package com.example.tasksync.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import com.example.tasksync.MainActivity
import com.example.tasksync.R
import com.example.tasksync.data.calendar.CalendarTaskSync
import com.example.tasksync.data.local.AppDatabase
import com.example.tasksync.data.reminder.TaskReminderScheduler
import java.time.LocalDate

/** Calendar-style home-screen widget. Each launcher instance keeps its own selected date. */
class TaskSyncWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        CalendarWidgetRefreshScheduler.ensurePeriodic(context)
        TaskSyncCalendarWidgetObserver.register(context)
        CalendarWidgetRefreshScheduler.enqueueImmediate(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        TaskSyncWidgetUpdater.showPlaceholder(context, appWidgetIds)
        CalendarWidgetRefreshScheduler.enqueueImmediate(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) {
        TaskSyncWidgetUpdater.showPlaceholder(context, intArrayOf(appWidgetId))
        CalendarWidgetRefreshScheduler.enqueueImmediate(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { CalendarWidgetSelectionStore.clear(context, it) }
    }

    override fun onDisabled(context: Context) {
        CalendarWidgetRefreshScheduler.cancelPeriodic(context)
        TaskSyncCalendarWidgetObserver.unregister()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> CalendarWidgetRefreshScheduler.enqueueImmediate(context)
            ACTION_SELECT_DAY -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val day = intent.getLongExtra(EXTRA_SELECTED_EPOCH_DAY, Long.MIN_VALUE)
                val selected = runCatching { LocalDate.ofEpochDay(day) }.getOrNull()
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID && selected != null) {
                    CalendarWidgetSelectionStore.set(context, id, selected)
                    CalendarWidgetRefreshScheduler.enqueueImmediate(context)
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_VOICE = "com.example.tasksync.OPEN_VOICE_ASSISTANT"
        const val EXTRA_OPEN_CALENDAR_DATE = "com.example.tasksync.OPEN_CALENDAR_DATE"
        const val ACTION_REFRESH = "com.example.tasksync.widget.REFRESH"
        const val ACTION_SELECT_DAY = "com.example.tasksync.widget.SELECT_DAY"
        const val EXTRA_SELECTED_EPOCH_DAY = "selected_epoch_day"
    }
}

object CalendarWidgetSelectionStore {
    private const val PREFS = "tasksync_calendar_widget_state"

    fun get(context: Context, widgetId: Int): LocalDate = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("selected_date_$widgetId", null)
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: LocalDate.now()

    fun set(context: Context, widgetId: Int, date: LocalDate) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("selected_date_$widgetId", date.toString())
            .apply()
    }

    fun clear(context: Context, widgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("selected_date_$widgetId")
            .apply()
    }
}

object TaskSyncWidgetUpdater {
    fun requestRefresh(context: Context) {
        context.sendBroadcast(Intent(context, TaskSyncWidgetProvider::class.java).setAction(TaskSyncWidgetProvider.ACTION_REFRESH))
    }

    /** Render a usable widget immediately while the asynchronous Room/calendar refresh runs. */
    fun showPlaceholder(context: Context, widgetIds: IntArray) {
        if (widgetIds.isEmpty()) return
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val locale = appContext.resources.configuration.locales[0] ?: java.util.Locale.getDefault()
        val zone = java.time.ZoneId.systemDefault()
        widgetIds.forEach { widgetId ->
            val selectedDate = CalendarWidgetSelectionStore.get(appContext, widgetId)
            val model = CalendarWidgetModelFactory.create(
                selectedDate = selectedDate,
                locale = locale,
                zone = zone,
                tasks = emptyList(),
                eventsByDate = emptyMap(),
                use24HourTime = DateFormat.is24HourFormat(appContext),
                maxAgendaEntries = 0,
            )
            manager.updateAppWidget(widgetId, makeViews(appContext, widgetId, selectedDate, model, maxAgendaEntries = 1))
        }
    }

    suspend fun refresh(context: Context, widgetIds: IntArray) {
        if (widgetIds.isEmpty()) return
        val tasks = runCatching {
            AppDatabase.getDatabase(context.applicationContext).taskDao().getAllTasksOnce()
        }.getOrDefault(emptyList())
        val taskSyncCalendar = CalendarTaskSync(context.applicationContext)
        val canReadGoogleCalendar = taskSyncCalendar.selectedCalendarId != null && taskSyncCalendar.hasCalendarPermissions()
        val taskEventIds = tasks.mapNotNull { it.googleCalendarEventId }.toSet()
        val manager = AppWidgetManager.getInstance(context)
        widgetIds.forEach { widgetId ->
            val selectedDate = CalendarWidgetSelectionStore.get(context, widgetId)
            val locale = context.resources.configuration.locales[0] ?: java.util.Locale.getDefault()
            val weekStart = selectedDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.temporal.WeekFields.of(locale).firstDayOfWeek))
            val events = if (canReadGoogleCalendar) {
                (0L..6L).associate { offset ->
                    val date = weekStart.plusDays(offset)
                    date to runCatching { taskSyncCalendar.eventsForDate(date, taskEventIds) }.getOrDefault(emptyList())
                }
            } else emptyMap()
            val options = manager.getAppWidgetOptions(widgetId)
            val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            val maxAgendaEntries = when {
                maxHeight >= 330 -> 3
                maxHeight >= 250 -> 2
                else -> 1
            }
            val model = CalendarWidgetModelFactory.create(
                selectedDate = selectedDate,
                locale = locale,
                zone = java.time.ZoneId.systemDefault(),
                tasks = tasks,
                eventsByDate = events,
                use24HourTime = DateFormat.is24HourFormat(context),
                maxAgendaEntries = 3,
            )
            manager.updateAppWidget(widgetId, makeViews(context, widgetId, selectedDate, model, maxAgendaEntries))
        }
    }

    private fun makeViews(
        context: Context,
        widgetId: Int,
        selectedDate: LocalDate,
        model: CalendarWidgetModel,
        maxAgendaEntries: Int,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_calendar)
        views.setTextViewText(R.id.widget_calendar_heading, model.monthTitle)
        val openCalendar = Intent(context, MainActivity::class.java)
            .putExtra(TaskSyncWidgetProvider.EXTRA_OPEN_CALENDAR_DATE, selectedDate.toString())
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        views.setOnClickPendingIntent(
            R.id.widget_calendar_heading,
            PendingIntent.getActivity(context, 20_000 + widgetId, openCalendar, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )

        model.days.forEachIndexed { index, day ->
            val weekdayId = WEEKDAY_IDS[index]
            val numberId = DAY_NUMBER_IDS[index]
            val dotId = DAY_DOT_IDS[index]
            val cellId = DAY_CELL_IDS[index]
            views.setTextViewText(weekdayId, day.weekday)
            views.setTextViewText(numberId, day.dayNumber)
            views.setContentDescription(numberId, day.accessibilityLabel)
            views.setContentDescription(cellId, "Select ${day.accessibilityLabel}")
            views.setInt(numberId, "setBackgroundResource", if (day.selected) R.drawable.widget_calendar_day_selected else R.drawable.widget_calendar_day_normal)
            views.setTextColor(numberId, if (day.selected) 0xFF101010.toInt() else 0xFFF3F3F3.toInt())
            views.setViewVisibility(dotId, if (day.hasEntries) View.VISIBLE else View.INVISIBLE)
            val selectDate = Intent(context, TaskSyncWidgetProvider::class.java)
                .setAction(TaskSyncWidgetProvider.ACTION_SELECT_DAY)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .putExtra(TaskSyncWidgetProvider.EXTRA_SELECTED_EPOCH_DAY, day.date.toEpochDay())
            views.setOnClickPendingIntent(
                cellId,
                PendingIntent.getBroadcast(context, 10_000 + widgetId * 10 + index, selectDate, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        }

        val visible = model.entries.take(maxAgendaEntries)
        views.setViewVisibility(R.id.widget_calendar_empty, if (visible.isEmpty()) View.VISIBLE else View.GONE)
        val rowIds = intArrayOf(R.id.widget_agenda_row_0, R.id.widget_agenda_row_1, R.id.widget_agenda_row_2)
        val titleIds = intArrayOf(R.id.widget_agenda_title_0, R.id.widget_agenda_title_1, R.id.widget_agenda_title_2)
        val timeIds = intArrayOf(R.id.widget_agenda_time_0, R.id.widget_agenda_time_1, R.id.widget_agenda_time_2)
        val chevronIds = intArrayOf(R.id.widget_agenda_chevron_0, R.id.widget_agenda_chevron_1, R.id.widget_agenda_chevron_2)
        val dividerIds = intArrayOf(R.id.widget_agenda_divider_0, R.id.widget_agenda_divider_1)
        rowIds.forEachIndexed { index, rowId ->
            val entry = visible.getOrNull(index)
            views.setViewVisibility(rowId, if (entry == null) View.GONE else View.VISIBLE)
            if (entry != null) {
                views.setTextViewText(titleIds[index], entry.title)
                views.setTextViewText(timeIds[index], entry.timeLabel)
                val target = entry.taskId?.let { taskId ->
                    Intent(context, MainActivity::class.java)
                        .putExtra(TaskReminderScheduler.EXTRA_OPEN_TASK_ID, taskId)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                } ?: entry.calendarEventId?.let { eventId ->
                    Intent(Intent.ACTION_VIEW, Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, eventId.toString()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .takeIf { it.resolveActivity(context.packageManager) != null }
                        ?: Intent(context, MainActivity::class.java)
                            .putExtra(TaskSyncWidgetProvider.EXTRA_OPEN_CALENDAR_DATE, selectedDate.toString())
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                if (target != null) {
                    val pending = PendingIntent.getActivity(context, 30_000 + widgetId * 10 + index, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    views.setOnClickPendingIntent(rowId, pending)
                    views.setOnClickPendingIntent(chevronIds[index], pending)
                }
            }
        }
        dividerIds.forEachIndexed { index, dividerId ->
            views.setViewVisibility(dividerId, if (index < visible.size - 1) View.VISIBLE else View.GONE)
        }
        val hiddenCount = (model.moreCount + model.entries.size - visible.size).coerceAtLeast(0)
        views.setViewVisibility(R.id.widget_calendar_more, if (hiddenCount > 0 && maxAgendaEntries >= 3) View.VISIBLE else View.GONE)
        if (hiddenCount > 0) views.setTextViewText(R.id.widget_calendar_more, "$hiddenCount more · Open Calendar")
        val openMore = PendingIntent.getActivity(context, 40_000 + widgetId, openCalendar, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_calendar_more, openMore)
        return views
    }

    private val WEEKDAY_IDS = intArrayOf(R.id.widget_weekday_0, R.id.widget_weekday_1, R.id.widget_weekday_2, R.id.widget_weekday_3, R.id.widget_weekday_4, R.id.widget_weekday_5, R.id.widget_weekday_6)
    private val DAY_CELL_IDS = intArrayOf(R.id.widget_day_cell_0, R.id.widget_day_cell_1, R.id.widget_day_cell_2, R.id.widget_day_cell_3, R.id.widget_day_cell_4, R.id.widget_day_cell_5, R.id.widget_day_cell_6)
    private val DAY_NUMBER_IDS = intArrayOf(R.id.widget_day_number_0, R.id.widget_day_number_1, R.id.widget_day_number_2, R.id.widget_day_number_3, R.id.widget_day_number_4, R.id.widget_day_number_5, R.id.widget_day_number_6)
    private val DAY_DOT_IDS = intArrayOf(R.id.widget_day_dot_0, R.id.widget_day_dot_1, R.id.widget_day_dot_2, R.id.widget_day_dot_3, R.id.widget_day_dot_4, R.id.widget_day_dot_5, R.id.widget_day_dot_6)
}

internal fun voiceCaptureWidgetIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
    .putExtra(TaskSyncWidgetProvider.EXTRA_OPEN_VOICE, true)
    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

class TaskSyncVoiceWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { manager.updateAppWidget(it, makeViews(context, it)) }
    }

    private fun makeViews(context: Context, widgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_voice)
        val launchVoice = voiceCaptureWidgetIntent(context)
        val tap = PendingIntent.getActivity(context, 50_000 + widgetId, launchVoice, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_voice_root, tap)
        views.setOnClickPendingIntent(R.id.widget_voice_microphone, tap)
        views.setOnClickPendingIntent(R.id.widget_voice_add, tap)
        return views
    }
}
