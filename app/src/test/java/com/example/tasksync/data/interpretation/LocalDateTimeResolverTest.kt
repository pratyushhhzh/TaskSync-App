package com.example.tasksync.data.interpretation

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDateTimeResolverTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val clock = Clock.fixed(Instant.parse("2026-11-18T10:15:00Z"), zone)
    private val resolver = LocalDateTimeResolver(clock)

    @Test fun todayAndTomorrowUseDeviceZoneDate() {
        assertEquals("2026-11-18", resolver.resolve("Today", null).date.toString())
        assertEquals("2026-11-19", resolver.resolve("tomorrow", null).date.toString())
    }

    @Test fun nextFridayUsesNextOccurrenceEvenWhenTodayIsFriday() {
        val fridayClock = Clock.fixed(Instant.parse("2026-11-20T04:00:00Z"), zone)
        assertEquals("2026-11-27", LocalDateTimeResolver(fridayClock).resolve("next Friday", null).date.toString())
    }

    @Test fun exactTimeIsResolvedAndBroadPeriodRequiresClarification() {
        val exact = resolver.resolve("tomorrow", "at 5 PM")
        assertEquals("17:00", exact.time.toString())
        assertTrue(exact.isResolved)
        val broad = resolver.resolve("tomorrow morning", null)
        assertEquals("2026-11-19", broad.date.toString())
        assertFalse(broad.isResolved)
    }

    @Test fun unresolvedEveningTimeDoesNotInventAnExactTimeOrDate() {
        val result = resolver.resolve("in the evening", null)
        assertEquals(null, result.date)
        assertEquals(null, result.time)
        assertFalse(result.dateUnresolved)
        assertTrue(result.timeUnresolved)

        val timeFieldResult = resolver.resolve(null, "in the evening")
        assertEquals(null, timeFieldResult.date)
        assertEquals(null, timeFieldResult.time)
        assertFalse(timeFieldResult.dateUnresolved)
        assertTrue(timeFieldResult.timeUnresolved)
    }

    @Test fun tonightResolvesTodayButLeavesItsUnspecifiedTimeUnset() {
        val result = resolver.resolve("tonight", null)
        assertEquals("2026-11-18", result.date.toString())
        assertEquals(null, result.time)
        assertFalse(result.dateUnresolved)
        assertTrue(result.timeUnresolved)
    }

    @Test fun unknownDateIsSeparatelyMarkedUnresolved() {
        val result = resolver.resolve("sometime after the next milestone", null)
        assertEquals(null, result.date)
        assertTrue(result.dateUnresolved)
        assertFalse(result.timeUnresolved)
    }

    @Test fun resolvedDateSurvivesAnUnresolvedTimePhrase() {
        val result = resolver.resolve("next Friday", "in the evening")
        assertEquals("2026-11-20", result.date.toString())
        assertEquals(null, result.time)
        assertFalse(result.dateUnresolved)
        assertTrue(result.timeUnresolved)
    }

    @Test fun dateAndTimeMayArriveTogetherInOnePhrase() {
        val result = resolver.resolve("tomorrow at 5 PM", null)
        assertEquals("2026-11-19", result.date.toString())
        assertEquals("17:00", result.time.toString())
        assertTrue(result.isResolved)
    }

    @Test fun relativeDateInTimePhraseStillRequiresAnExactTime() {
        val result = resolver.resolve(null, "this evening")
        assertEquals("2026-11-18", result.date.toString())
        assertFalse(result.isResolved)
    }

    @Test fun localizedNextWeekdayUsesDeviceLocale() {
        val hindiResolver = LocalDateTimeResolver(clock, Locale.forLanguageTag("hi-IN"))
        assertEquals("2026-11-20", hindiResolver.resolve("अगला शुक्रवार", null).date.toString())
    }

    @Test fun nextWeekMondaySpokenVariantsUseLocaleCalendarWeek() {
        val expected = "2026-11-23"
        val thursday = LocalDateTimeResolver(Clock.fixed(Instant.parse("2026-11-19T10:15:00Z"), zone), Locale.forLanguageTag("en-IN"))
        listOf("next week's Monday", "next weeks Monday", "Monday next week", "Monday of next week")
            .forEach { phrase -> assertEquals(phrase, expected, thursday.resolve(phrase, null).date.toString()) }
        assertEquals("2026-11-25", thursday.resolve("Next week Wednesday", null).date.toString())
        assertEquals("2026-11-20", thursday.resolve("This Friday", null).date.toString())
        assertEquals("2026-11-23", thursday.resolve("Next Monday", null).date.toString())
    }

    @Test fun ambiguousHindiKalIsNeverGuessed() {
        val result = resolver.resolve("kal", null)
        assertEquals(null, result.date)
        assertFalse(result.isResolved)
    }

    @Test fun pastTimeOnTodayIsFlaggedForReview() {
        val result = resolver.resolve("today", "at 8 AM")
        assertEquals("2026-11-18", result.date.toString())
        assertFalse(result.isResolved)
        assertTrue(result.unresolved.single().contains("past"))
    }

    @Test fun unknownDateAndTimeAreMarkedUnresolved() {
        val result = resolver.resolve("sometime soon", "after lunch")
        assertEquals(null, result.date)
        assertEquals(null, result.time)
        assertEquals(2, result.unresolved.size)
    }

    @Test fun timezoneBoundaryControlsToday() {
        val lateUtc = Clock.fixed(Instant.parse("2026-11-18T20:00:00Z"), zone)
        assertEquals("2026-11-19", LocalDateTimeResolver(lateUtc).resolve("today", null).date.toString())
    }

    @Test fun supportsDayAfterTomorrowAndPunctuation() {
        assertEquals("2026-11-20", resolver.resolve("Day after tomorrow!", null).date.toString())
        assertEquals("2026-11-19", resolver.resolve("TOMORROW,", null).date.toString())
    }

    @Test fun weekdayRulesUseNextOccurrenceAndLocaleWeekStart() {
        assertEquals("2026-11-23", resolver.resolve("Next Monday", null).date.toString())
        assertEquals("2026-11-23", resolver.resolve("Monday next week", null).date.toString())
        val sunday = Clock.fixed(Instant.parse("2026-11-22T04:00:00Z"), zone)
        assertEquals("2026-11-30", LocalDateTimeResolver(sunday, Locale.US).resolve("Monday next week", null).date.toString())
        assertEquals("2026-11-23", LocalDateTimeResolver(sunday, Locale.UK).resolve("Monday next week", null).date.toString())
        val enInResolver = LocalDateTimeResolver(clock, Locale.forLanguageTag("en-IN"))
        val inferredWeek = enInResolver.resolve("this week", null)
        assertEquals("2026-11-22", inferredWeek.date.toString())
        assertTrue(inferredWeek.dateWasInferred)
        assertTrue(inferredWeek.isResolved)
        assertFalse(resolver.resolve("next week", null).isResolved)
    }

    @Test fun bareWeekdayUsesNearestOccurrenceIncludingTodayAndHonorsPastTime() {
        val saturday = LocalDateTimeResolver(Clock.fixed(Instant.parse("2026-11-21T06:00:00Z"), zone), Locale.forLanguageTag("en-IN"))
        assertEquals("2026-11-23", saturday.resolve("on Monday", null).date.toString())
        assertEquals("2026-11-23", saturday.resolve("Monday next week", null).date.toString())
        val mondayMorning = LocalDateTimeResolver(Clock.fixed(Instant.parse("2026-11-23T04:00:00Z"), zone), Locale.forLanguageTag("en-IN"))
        assertEquals("2026-11-23", mondayMorning.resolve("Monday", null).date.toString())
        assertEquals("2026-11-30", mondayMorning.resolve("Monday", "9 AM").date.toString())
        assertEquals("2026-11-30", mondayMorning.resolve("Next Monday", null).date.toString())
    }

    @Test fun everyWeekdaySupportsThisNextAndFollowingCalendarWeekForms() {
        val today = LocalDate.of(2026, 11, 18)
        val weekStart = today.with(java.time.temporal.WeekFields.of(Locale.forLanguageTag("en-IN")).dayOfWeek(), 1)
        for (day in DayOfWeek.entries) {
            val name = day.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)
            val inThisWeek = weekStart.plusDays((day.value - java.time.temporal.WeekFields.of(Locale.forLanguageTag("en-IN")).firstDayOfWeek.value + 7L) % 7L)
            var nextOccurrence = (day.value - today.dayOfWeek.value + 7) % 7
            if (nextOccurrence == 0) nextOccurrence = 7
            assertEquals(inThisWeek.toString(), resolver.resolve("this $name", null).date.toString())
            assertEquals(today.plusDays(nextOccurrence.toLong()).toString(), resolver.resolve("next $name", null).date.toString())
            assertEquals(weekStart.plusWeeks(1).plusDays((day.value - java.time.temporal.WeekFields.of(Locale.forLanguageTag("en-IN")).firstDayOfWeek.value + 7L) % 7L).toString(), resolver.resolve("$name next week", null).date.toString())
        }
    }

    @Test fun thisWeekdayUsesCurrentLocaleWeekAndReportsPastDates() {
        assertEquals("2026-11-16", resolver.resolve("this Monday", null).date.toString())
        assertFalse(resolver.resolve("this Monday", null).isResolved)
        assertEquals("2026-11-19", resolver.resolve("Thursday", null).date.toString())
    }

    @Test fun monthAndUnambiguousNumericDatesHandleYearBoundaries() {
        assertEquals("2026-11-23", resolver.resolve("23 November", null).date.toString())
        assertEquals("2026-12-31", resolver.resolve("31 Dec", null).date.toString())
        val januaryDate = resolver.resolve("1 January", null)
        assertEquals("2026-01-01", januaryDate.date.toString())
        assertFalse(januaryDate.isResolved)
        assertEquals("2026-11-23", resolver.resolve("23/11/2026", null).date.toString())
        assertEquals("2026-11-23", resolver.resolve("11/23/2026", null).date.toString())
        assertEquals("2026-11-23", resolver.resolve("2026/11/23", null).date.toString())
        assertFalse(resolver.resolve("03/04/2026", null).isResolved)
        assertFalse(resolver.resolve("2025-11-23", null).isResolved)
    }

    @Test fun spokenExplicitCalendarDatesDoNotSilentlyMovePastDatesToNextYear() {
        val pastMonthDate = resolver.resolve("November 3rd", null)
        assertEquals("2026-11-03", pastMonthDate.date.toString())
        assertFalse(pastMonthDate.isResolved)
        assertFalse(resolver.resolve("3rd November 2026", null).isResolved) // supplied date is in the past
        assertEquals("2026-01-01", resolver.resolve("January 1st", null).date.toString())
        assertEquals("2026-11-23", resolver.resolve("November 23, 2026", null).date.toString())
        assertEquals("2026-11-23", resolver.resolve("23/11/2026", null).date.toString())
        assertFalse(resolver.resolve("03/04/2026", null).isResolved)
    }

    @Test fun ordinalWeekdaysResolveStrictlyAcrossMonthsAndYears() {
        assertEquals("2026-11-18", resolver.resolve("November's third Wednesday", null).date.toString())
        assertEquals("2026-11-18", resolver.resolve("Third Wednesday of November", null).date.toString())
        assertEquals("2026-12-16", resolver.resolve("Third Wednesday of next month", null).date.toString())
        val yearEnd = LocalDateTimeResolver(Clock.fixed(Instant.parse("2026-12-31T06:00:00Z"), zone), Locale.forLanguageTag("en-IN"))
        assertEquals("2027-01-20", yearEnd.resolve("Third Wednesday of next month", null).date.toString())
        val afterNovemberOrdinal = LocalDateTimeResolver(Clock.fixed(Instant.parse("2026-11-19T04:45:00Z"), zone), Locale.forLanguageTag("en-IN"))
            .resolve("Third Wednesday of November", null)
        assertEquals("2026-11-18", afterNovemberOrdinal.date.toString())
        assertFalse(afterNovemberOrdinal.isResolved)
        assertFalse(resolver.resolve("November's eighth Wednesday", null).isResolved)
    }

    @Test fun relativeDateExpressionsUseInjectedLocalDate() {
        val onNov19 = LocalDateTimeResolver(Clock.fixed(Instant.parse("2026-11-19T10:15:00Z"), zone), Locale.forLanguageTag("en-IN"))
        assertEquals("2026-11-19", onNov19.resolve("Today", null).date.toString())
        assertEquals("2026-11-20", onNov19.resolve("Tomorrow", null).date.toString())
        assertEquals("2026-11-21", onNov19.resolve("Day after tomorrow", null).date.toString())
        assertEquals(null, onNov19.resolve(null, null).date)
        assertTrue(onNov19.resolve(null, null).isResolved)
    }

    @Test fun supportsCommonTwelveAndTwentyFourHourTimes() {
        val phrases = mapOf(
            "4 PM" to "16:00", "4:00 PM" to "16:00", "4 p.m." to "16:00",
            "7:30 p.m." to "19:30", "7:30 am" to "07:30", "07:30" to "07:30",
            "19:30" to "19:30", "noon" to "12:00", "midnight" to "00:00",
        )
        phrases.forEach { (phrase, expected) ->
            val result = resolver.resolve(null, phrase)
            assertEquals("$phrase should parse", expected, result.time.toString())
            assertTrue("$phrase should not be ambiguous", result.isResolved)
        }
    }

    @Test fun invalidTimeAndAmbiguousDateRemainUnresolvedButMissingTimeIsOptional() {
        assertTrue(resolver.resolve("tomorrow", null).isResolved)
        assertFalse(resolver.resolve("tomorrow", "25:99").isResolved)
        assertFalse(resolver.resolve("tomorrow evening", null).isResolved)
    }

    @Test fun combinedDateTimeDoesNotTreatItsTimeAsPartOfDate() {
        val result = resolver.resolve("today at 4:00 p.m.", null)
        assertEquals("2026-11-18", result.date.toString())
        assertEquals("16:00", result.time.toString())
        assertTrue(result.isResolved)
        val timeFirst = resolver.resolve("4:00 p.m. today", null)
        assertEquals("2026-11-18", timeFirst.date.toString())
        assertEquals("16:00", timeFirst.time.toString())
    }
}
