package com.example.tasksync.data.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ReminderSchedulePlannerTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test
    fun fivePmDeadlineSchedulesFiveMinutesBefore() {
        val deadline = LocalDateTime.of(2026, 11, 23, 17, 0).atZone(zone).toInstant().toEpochMilli()
        val now = LocalDateTime.of(2026, 11, 23, 16, 0).atZone(zone).toInstant().toEpochMilli()
        val triggers = ReminderSchedulePlanner.reminderTriggers(deadline, listOf(5), now)
        assertEquals(1, triggers.size)
        assertEquals(LocalDateTime.of(2026, 11, 23, 16, 55).atZone(zone).toInstant().toEpochMilli(), triggers.single().first)
    }

    @Test
    fun multipleOffsetsRemainIndependentAndAtDeadlineIsSupported() {
        val deadline = LocalDateTime.of(2026, 11, 23, 17, 0).atZone(zone).toInstant().toEpochMilli()
        val now = LocalDateTime.of(2026, 11, 23, 15, 0).atZone(zone).toInstant().toEpochMilli()
        val triggers = ReminderSchedulePlanner.reminderTriggers(deadline, listOf(60, 15, 0, 5, 15), now)
        assertEquals(listOf(60, 15, 5, 0), triggers.map { it.second })
        assertEquals(deadline, triggers.last().first)
    }

    @Test
    fun previousDayDigestUsesDeviceLocalTimeAndAdvancesAfterConfiguredTime() {
        val now = LocalDateTime.of(2026, 11, 23, 20, 15).atZone(zone).toInstant().toEpochMilli()
        val digest = ReminderSchedulePlanner.nextDigestMillis(now, LocalTime.of(19, 0), zone)
        assertEquals(LocalDateTime.of(2026, 11, 24, 19, 0).atZone(zone).toInstant().toEpochMilli(), digest)
        assertEquals("2026-11-24", ReminderSchedulePlanner.tomorrowDate(now, zone).toString())
    }

    @Test
    fun upcomingDigestTodayUsesTodayRatherThanTomorrow() {
        val now = LocalDateTime.of(2026, 11, 23, 18, 0).atZone(zone).toInstant().toEpochMilli()
        val digest = ReminderSchedulePlanner.nextDigestMillis(now, LocalTime.of(19, 0), zone)
        assertEquals(LocalDateTime.of(2026, 11, 23, 19, 0).atZone(zone).toInstant().toEpochMilli(), digest)
        assertTrue(digest > now)
    }

    @Test
    fun staleReminderOffsetsAreNotScheduledAgain() {
        val deadline = LocalDateTime.of(2026, 11, 23, 17, 0).atZone(zone).toInstant().toEpochMilli()
        val now = LocalDateTime.of(2026, 11, 23, 17, 1).atZone(zone).toInstant().toEpochMilli()
        assertTrue(ReminderSchedulePlanner.reminderTriggers(deadline, listOf(5, 0), now).isEmpty())
    }

    @Test
    fun digestTimeUsesLocalZoneAcrossDaylightSavingTransition() {
        val newYork = ZoneId.of("America/New_York")
        val now = LocalDateTime.of(2026, 3, 8, 18, 0).atZone(newYork).toInstant().toEpochMilli()
        val digest = ReminderSchedulePlanner.nextDigestMillis(now, LocalTime.of(19, 0), newYork)
        assertEquals(LocalDateTime.of(2026, 3, 8, 19, 0).atZone(newYork).toInstant().toEpochMilli(), digest)
        assertEquals(19, java.time.Instant.ofEpochMilli(digest).atZone(newYork).hour)
    }
}
