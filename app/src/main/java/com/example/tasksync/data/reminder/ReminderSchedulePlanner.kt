package com.example.tasksync.data.reminder

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal object ReminderSchedulePlanner {
    fun reminderTriggers(deadlineMillis: Long, offsetsMinutes: List<Int>, nowMillis: Long): List<Pair<Long, Int>> =
        offsetsMinutes.distinct().map { offset -> deadlineMillis - offset * 60_000L to offset }
            .filter { (trigger, _) -> trigger > nowMillis }
            .sortedBy { it.first }

    fun nextDigestMillis(nowMillis: Long, time: LocalTime, zone: ZoneId): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        var next = now.toLocalDate().atTime(time).atZone(zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next.toInstant().toEpochMilli()
    }

    fun tomorrowDate(nowMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().plusDays(1)
}
