package com.example.tasksync.widget

import com.example.tasksync.data.calendar.ExternalCalendarEvent
import com.example.tasksync.data.local.entity.TaskEntity
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarWidgetModelTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val locale = Locale.US

    @Test
    fun monthWeekAndWeekdayLabelsUseSundayToSaturdayForUsLocale() {
        val model = model(LocalDate.parse("2026-11-12"))
        assertEquals("November 2026", model.monthTitle)
        assertEquals(listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"), model.days.map { it.weekday })
        assertEquals(LocalDate.parse("2026-11-08"), model.days.first().date)
        assertEquals(LocalDate.parse("2026-11-14"), model.days.last().date)
    }

    @Test
    fun weekStartsAccordingToDeviceLocaleAndSelectedDateIsUnique() {
        val selected = LocalDate.parse("2026-11-12")
        val model = model(selected, Locale.UK)
        assertEquals(LocalDate.parse("2026-11-09"), model.days.first().date)
        assertEquals(1, model.days.count { it.selected })
        assertTrue(model.days.single { it.selected }.accessibilityLabel.contains("12"))
    }

    @Test
    fun taskOnlyModeMarksDatesAndKeepsUndatedTasksOutOfCalendar() {
        val selected = LocalDate.parse("2026-11-12")
        val tasks = listOf(task("today", "Doctor", "2026-11-12", "09:30:00"), task("undated", "Call Mom", null, null))
        val model = model(selected, tasks = tasks)
        assertEquals(listOf("Doctor"), model.entries.map { it.title })
        assertEquals("09:30", model.entries.single().timeLabel)
        assertTrue(model.days.single { it.date == selected }.hasEntries)
        assertFalse(model.days.first { it.date == selected.minusDays(1) }.hasEntries)
    }

    @Test
    fun optedInExternalEventsJoinTasksChronologicallyAndAllDayEventsStayAllDay() {
        val selected = LocalDate.parse("2026-11-12")
        val tasks = listOf(task("task", "Call Mom", selected.toString(), "13:00:00"))
        val localStart = selected.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val events = mapOf(selected to listOf(
            ExternalCalendarEvent(2, "Lunch", localStart, localStart + 30 * 60_000L, false, zone.id),
            ExternalCalendarEvent(3, "Holiday", selected.atStartOfDay(zone).toInstant().toEpochMilli(), selected.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), true, "UTC"),
        ))
        val model = model(selected, tasks = tasks, events = events)
        assertEquals(listOf("Holiday", "Lunch", "Call Mom"), model.entries.map { it.title })
        assertEquals("All day", model.entries[0].timeLabel)
        assertEquals("10:00 – 10:30", model.entries[1].timeLabel)
        assertEquals(0, model.moreCount)
    }

    @Test
    fun indicatorsReflectOnlyRealTaskOrCalendarEntriesAndAgendaCanBeEmpty() {
        val selected = LocalDate.parse("2026-11-12")
        val eventDate = selected.plusDays(1)
        val event = ExternalCalendarEvent(7, "Meeting", eventDate.atStartOfDay(zone).toInstant().toEpochMilli(), eventDate.atTime(1, 0).atZone(zone).toInstant().toEpochMilli(), false, zone.id)
        val model = model(selected, events = mapOf(eventDate to listOf(event)))
        assertTrue(model.days.single { it.date == eventDate }.hasEntries)
        assertFalse(model.days.single { it.date == selected }.hasEntries)
        assertTrue(model.entries.isEmpty())
    }

    @Test
    fun calendarWeekAndMonthBoundaryRemainConsistentAcrossNewYear() {
        val model = model(LocalDate.parse("2027-01-01"))
        assertEquals("January 2027", model.monthTitle)
        assertEquals(LocalDate.parse("2026-12-27"), model.days.first().date)
        assertEquals(LocalDate.parse("2027-01-02"), model.days.last().date)
        assertEquals(1, model.days.count { it.selected })
    }

    @Test
    fun agendaLimitsVisibleRowsAndReportsRemainingEntries() {
        val selected = LocalDate.parse("2026-11-12")
        val tasks = listOf(
            task("one", "One", selected.toString(), "09:00:00"),
            task("two", "Two", selected.toString(), "10:00:00"),
            task("three", "Three", selected.toString(), "11:00:00"),
        )
        val model = model(selected, tasks = tasks)
        assertEquals(listOf("One", "Two"), model.entries.map { it.title })
        assertEquals(1, model.moreCount)
    }

    private fun model(
        selectedDate: LocalDate,
        locale: Locale = this.locale,
        tasks: List<TaskEntity> = emptyList(),
        events: Map<LocalDate, List<ExternalCalendarEvent>> = emptyMap(),
    ) = CalendarWidgetModelFactory.create(selectedDate, locale, zone, tasks, events, use24HourTime = true)

    private fun task(id: String, title: String, date: String?, time: String?) = TaskEntity(
        id = id,
        userId = "local",
        title = title,
        dueDate = date,
        dueTime = time,
    )
}
