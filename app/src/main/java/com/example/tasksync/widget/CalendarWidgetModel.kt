package com.example.tasksync.widget

import com.example.tasksync.data.calendar.ExternalCalendarEvent
import com.example.tasksync.data.local.entity.TaskEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

internal data class CalendarWidgetDay(
    val date: LocalDate,
    val weekday: String,
    val dayNumber: String,
    val selected: Boolean,
    val hasEntries: Boolean,
    val accessibilityLabel: String,
)

internal data class CalendarWidgetAgendaEntry(
    val title: String,
    val timeLabel: String,
    val sortAtMillis: Long,
    val taskId: String? = null,
    val calendarEventId: Long? = null,
)

internal data class CalendarWidgetModel(
    val monthTitle: String,
    val days: List<CalendarWidgetDay>,
    val entries: List<CalendarWidgetAgendaEntry>,
    val moreCount: Int,
)

/** Pure, deterministic calendar presentation model shared by every calendar-widget instance. */
internal object CalendarWidgetModelFactory {
    fun create(
        selectedDate: LocalDate,
        locale: Locale,
        zone: ZoneId,
        tasks: List<TaskEntity>,
        eventsByDate: Map<LocalDate, List<ExternalCalendarEvent>>,
        use24HourTime: Boolean,
        maxAgendaEntries: Int = 2,
    ): CalendarWidgetModel {
        val weekStart = selectedDate.with(TemporalAdjusters.previousOrSame(WeekFields.of(locale).firstDayOfWeek))
        val dates = (0L..6L).map(weekStart::plusDays)
        val taskDates = tasks.mapNotNull { task -> task.dueDate?.let { raw -> runCatching { LocalDate.parse(raw) }.getOrNull() } }.toSet()
        val weekdayFormatter = DateTimeFormatter.ofPattern("EE", locale)
        val accessibilityFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM uuuu", locale)
        val days = dates.map { date ->
            val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                .codePoints().limit(2).toArray().let { cps -> String(cps, 0, cps.size) }
            CalendarWidgetDay(
                date = date,
                weekday = weekday.ifBlank { weekdayFormatter.format(date).take(2) },
                dayNumber = date.dayOfMonth.toString(),
                selected = date == selectedDate,
                hasEntries = date in taskDates || !eventsByDate[date].isNullOrEmpty(),
                accessibilityLabel = accessibilityFormatter.format(date),
            )
        }

        val timeFormatter = DateTimeFormatter.ofPattern(if (use24HourTime) "HH:mm" else "h:mm a", locale)
        val tasksForSelectedDate = tasks.mapNotNull { task ->
            val date = task.dueDate?.let { raw -> runCatching { LocalDate.parse(raw) }.getOrNull() } ?: return@mapNotNull null
            if (date != selectedDate) return@mapNotNull null
            val time = task.dueTime?.takeIf(String::isNotBlank)?.let { raw -> runCatching { LocalTime.parse(raw) }.getOrNull() }
            val at = (time?.let(date::atTime) ?: date.atStartOfDay()).atZone(zone).toInstant().toEpochMilli()
            CalendarWidgetAgendaEntry(
                title = task.title,
                timeLabel = time?.format(timeFormatter) ?: "All day",
                sortAtMillis = at,
                taskId = task.id,
            )
        }
        val dateStart = selectedDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val dateEnd = selectedDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val externalForSelectedDate = eventsByDate[selectedDate].orEmpty().distinctBy { it.id }.map { event ->
            val eventZone = event.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
            val start = Instant.ofEpochMilli(event.startMillis).atZone(eventZone)
            val end = Instant.ofEpochMilli(event.endMillis).atZone(eventZone)
            val time = if (event.allDay) "All day" else "${timeFormatter.format(start)} – ${timeFormatter.format(end)}"
            CalendarWidgetAgendaEntry(
                title = event.title,
                timeLabel = time,
                sortAtMillis = maxOf(event.startMillis, dateStart).coerceAtMost(dateEnd - 1),
                calendarEventId = event.id,
            )
        }
        val allEntries = (tasksForSelectedDate + externalForSelectedDate).sortedWith(
            compareBy<CalendarWidgetAgendaEntry>({ it.sortAtMillis }, { it.title.lowercase(locale) }),
        )
        val visibleEntries = allEntries.take(maxAgendaEntries.coerceAtLeast(0))
        return CalendarWidgetModel(
            monthTitle = DateTimeFormatter.ofPattern("MMMM uuuu", locale).format(selectedDate),
            days = days,
            entries = visibleEntries,
            moreCount = (allEntries.size - visibleEntries.size).coerceAtLeast(0),
        )
    }
}
