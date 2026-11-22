package com.example.tasksync.data.interpretation

import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

data class ResolvedDateTime(
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val unresolved: List<String> = emptyList(),
    val dateWasInferred: Boolean = false,
    val dateUnresolved: Boolean = false,
    val timeUnresolved: Boolean = false,
) {
    val isResolved: Boolean get() = unresolved.isEmpty()
}

/** Conservative device-local parser. Bare weekdays use the nearest occurrence on or after today. */
class LocalDateTimeResolver(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val locale: Locale = Locale.getDefault(),
) {
    fun resolve(datePhrase: String?, timePhrase: String?): ResolvedDateTime {
        val now = java.time.LocalDateTime.now(clock)
        val today = now.toLocalDate()
        val issues = mutableListOf<String>()
        val dateText = clean(datePhrase)
        val timeText = clean(timePhrase)
        val dateOnlyText = dateText
            .replace(Regex("\\s+(?:at|by)\\s+\\d{1,2}(?::\\d{2})?\\s*(?:a\\.?\\s*m\\.?|p\\.?\\s*m\\.?)\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:at|by)\\s+\\d{1,2}:\\d{2}\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^\\d{1,2}(?::\\d{2})?\\s*(?:a\\.?\\s*m\\.?|p\\.?\\s*m\\.?|noon|midnight)\\s+(?:on\\s+)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^\\d{1,2}:\\d{2}\\s+(?:on\\s+)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?:^|\\s+)(?:(?:this|in the)\\s+)?(?:morning|afternoon|evening|tonight|noon|midnight)$", RegexOption.IGNORE_CASE), "")
            .trim()
        val parseTimeFrom = timeText.ifBlank { dateText }
        val parsedTime = parsePreciseTime(parseTimeFrom)
        val timeCandidate = hasTimeCandidate(parseTimeFrom)
        val time = parsedTime
        var date = resolveDate(dateOnlyText, today, datePhrase, issues, time, now)
        if (date == null && dateOnlyText.isBlank()) {
            when {
                hasAny(dateText, "tomorrow", "day after tomorrow") || hasAny(timeText, "tomorrow", "day after tomorrow") ->
                    date = today.plusDays(if (dateText.contains("day after tomorrow") || timeText.contains("day after tomorrow")) 2 else 1)
                hasAny(dateText, "today", "tonight", "this evening", "this morning", "this afternoon", "आज", "aaj") ||
                    hasAny(timeText, "today", "tonight", "this evening", "this morning", "this afternoon", "आज", "aaj") -> date = today
                hasAny(dateText, "kal", "कल") || hasAny(timeText, "kal", "कल") -> issues += "‘kal’ can mean yesterday or tomorrow; choose a date."
            }
        }

        when {
            parsedTime != null -> Unit
            timeText.isBlank() && !timeCandidate && !containsBroadPeriod(dateText) -> Unit
            timeText.isBlank() && !timeCandidate && containsBroadPeriod(dateText) ->
                issues += "Choose an exact time for ‘${datePhrase.orEmpty()}’."
            containsBroadPeriod(timeText) ->
                issues += "‘${timePhrase}’ is a broad time period. Choose an exact time."
            timeCandidate -> issues += "Time phrase ‘${timePhrase ?: datePhrase}’ is not a valid time. Choose a time."
            timeText.isNotBlank() -> issues += "Time phrase ‘$timePhrase’ is not recognized. Choose a time."
        }

        if (date != null && date.isBefore(today)) {
            issues += "The resolved date is in the past. Choose a date in the calendar."
        } else if (date == today && time != null && date.atTime(time).isBefore(now)) {
            issues += "The resolved time is in the past. Choose a future date and time."
        }
        val dateUnresolved = date == null && (dateOnlyText.isNotBlank() || hasAny(dateText, "kal", "कल") || hasAny(timeText, "kal", "कल"))
        val timeUnresolved = parsedTime == null && (
            containsBroadPeriod(timeText) || containsBroadPeriod(dateText) || timeCandidate || timeText.isNotBlank()
        )
        return ResolvedDateTime(
            date = date,
            time = time,
            unresolved = issues.distinct(),
            dateWasInferred = dateOnlyText == "this week",
            dateUnresolved = dateUnresolved,
            timeUnresolved = timeUnresolved,
        )
    }

    private fun resolveDate(
        text: String,
        today: LocalDate,
        original: String?,
        issues: MutableList<String>,
        timeHint: LocalTime?,
        now: java.time.LocalDateTime,
    ): LocalDate? {
        if (text.isBlank()) return null
        when {
            text in setOf("today", "आज", "aaj") -> return today
            text in setOf("tomorrow", "mañana") -> return today.plusDays(1)
            text in setOf("day after tomorrow", "the day after tomorrow") -> return today.plusDays(2)
            text in setOf("कल", "kal") || text.startsWith("कल ") || text.startsWith("kal ") -> {
                issues += "‘kal’ can mean yesterday or tomorrow; choose a date."
                return null
            }
            text == "this week" -> {
                val fields = localeWeekFields()
                return today.with(fields.dayOfWeek(), 7)
            }
            text == "next week" -> {
                issues += "‘${original}’ is a date range. Choose a specific day in the calendar."
                return null
            }
            text.startsWith("अगला ") || text.startsWith("अगली ") -> {
                val prefix = if (text.startsWith("अगला ")) "अगला " else "अगली "
                val day = parseWeekday(text.removePrefix(prefix))
                if (day != null) return nextWeekday(today, day)
            }
        }

        // Explicit “next week” forms refer to the locale's next calendar week,
        // whose first day comes from WeekFields (Monday for en-IN).
        val dayOfNextWeek = Regex("^(.+?)\\s+of\\s+next\\s+weeks?$").matchEntire(text)
        if (dayOfNextWeek != null) {
            val day = parseWeekday(dayOfNextWeek.groupValues[1])
            if (day != null) {
                val fields = localeWeekFields()
                val startOfCurrentWeek = today.with(fields.dayOfWeek(), 1)
                return startOfCurrentWeek.plusWeeks(1).plusDays((day.value - fields.firstDayOfWeek.value + 7L) % 7L)
            }
        }
        val explicitNextWeekDay = Regex(
            "^(.+?)\\s+next\\s+weeks?$|^next\\s+weeks?['’]?s?\\s+(.+)$",
        ).matchEntire(text)
        val explicitDay = explicitNextWeekDay?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
        if (explicitDay != null) {
            val day = parseWeekday(explicitDay)
            if (day != null) {
                val fields = localeWeekFields()
                val startOfCurrentWeek = today.with(fields.dayOfWeek(), 1)
                return startOfCurrentWeek.plusWeeks(1).plusDays((day.value - fields.firstDayOfWeek.value + 7L) % 7L)
            }
        }

        val nextWeekPrefix = Regex("^next\\s+week\\s+(.+)$").matchEntire(text)
        if (nextWeekPrefix != null) {
            val day = parseWeekday(nextWeekPrefix.groupValues[1])
            if (day != null) {
                val fields = localeWeekFields()
                val startOfCurrentWeek = today.with(fields.dayOfWeek(), 1)
                return startOfCurrentWeek.plusWeeks(1).plusDays((day.value - fields.firstDayOfWeek.value + 7L) % 7L)
            }
        }

        resolveOrdinalWeekday(text, today)?.let { return it }

        val weekdayAtNextWeek = Regex("^(.+?)\\s+next week$").matchEntire(text)
        if (weekdayAtNextWeek != null) {
            val day = parseWeekday(weekdayAtNextWeek.groupValues[1])
            if (day != null) {
                val fields = localeWeekFields()
                val startOfCurrentWeek = today.with(fields.dayOfWeek(), 1)
                return startOfCurrentWeek.plusWeeks(1).plusDays((day.value - fields.firstDayOfWeek.value + 7L) % 7L)
            }
        }

        val prefixedWeekday = Regex("^(this|next)\\s+(.+)$").matchEntire(text)
        if (prefixedWeekday != null) {
            val modifier = prefixedWeekday.groupValues[1]
            val day = parseWeekday(prefixedWeekday.groupValues[2])
            if (day != null) {
                if (modifier == "this") {
                    val fields = localeWeekFields()
                    return today.with(fields.dayOfWeek(), 1).plusDays((day.value - fields.firstDayOfWeek.value + 7L) % 7L)
                }
                return nextWeekday(today, day)
            }
        }
        parseWeekday(text)?.let { day ->
            val offset = (day.value - today.dayOfWeek.value + 7) % 7
            val occurrence = today.plusDays(offset.toLong())
            return if (occurrence == today && timeHint != null && today.atTime(timeHint).isBefore(now)) {
                today.plusWeeks(1)
            } else occurrence
        }

        parseMonthDate(text, today)?.let { return it }
        parseNumericDate(text, today)?.let { return it }
        if (text.contains("week")) {
            issues += "‘${original}’ does not identify one date. Choose a specific day in the calendar."
        } else {
            issues += "Date phrase ‘$original’ is not recognized. Choose a date."
        }
        return null
    }

    private fun nextWeekday(today: LocalDate, day: DayOfWeek): LocalDate {
        var offset = (day.value - today.dayOfWeek.value + 7) % 7
        if (offset == 0) offset = 7
        return today.plusDays(offset.toLong())
    }

    private fun localeWeekFields(): WeekFields =
        if (locale.country.equals("IN", ignoreCase = true)) WeekFields.of(DayOfWeek.MONDAY, 1)
        else WeekFields.of(locale)

    private fun parseWeekday(raw: String): DayOfWeek? {
        val value = raw.trim().trimEnd('.', ',')
        return DayOfWeek.entries.firstOrNull { day ->
            value.equals(day.getDisplayName(TextStyle.FULL, locale), ignoreCase = true) ||
                value.equals(day.getDisplayName(TextStyle.SHORT, locale).trimEnd('.'), ignoreCase = true) ||
                value.equals(day.getDisplayName(TextStyle.FULL, Locale.ENGLISH), ignoreCase = true) ||
                value.equals(day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).trimEnd('.'), ignoreCase = true)
        }
    }

    private fun parseMonthDate(text: String, today: LocalDate): LocalDate? {
        val months = Month.entries.flatMap { month ->
            listOf(month.getDisplayName(TextStyle.FULL, locale), month.getDisplayName(TextStyle.SHORT, locale).trimEnd('.'),
                month.getDisplayName(TextStyle.FULL, Locale.ENGLISH), month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).trimEnd('.'))
                .distinct().map { it.lowercase(locale) to month }
        }.distinctBy { it.first }.sortedByDescending { it.first.length }
        for ((name, month) in months) {
            val dayFirst = Regex("^(\\d{1,2})(?:st|nd|rd|th)?\\s+${Regex.escape(name)}(?:,?\\s+(\\d{4}))?$").matchEntire(text)
            val monthFirst = Regex("^${Regex.escape(name)}\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?$").matchEntire(text)
            val match = dayFirst ?: monthFirst ?: continue
            val day = match.groupValues[1].toIntOrNull() ?: continue
            val year = match.groupValues[2].toIntOrNull() ?: today.year
            val parsed = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
            return parsed
        }
        return null
    }

    private fun resolveOrdinalWeekday(text: String, today: LocalDate): LocalDate? {
        val match = Regex("^(?:([a-z]+)['’]s\\s+)?(first|1st|second|2nd|third|3rd|fourth|4th|last)\\s+([a-z]+)(?:\\s+of\\s+(.+))?$")
            .matchEntire(text) ?: return null
        val monthPhrase = match.groupValues[1].ifBlank { match.groupValues[4] }
        val weekday = parseWeekday(match.groupValues[3]) ?: return null
        val ordinal = when (match.groupValues[2]) {
            "first", "1st" -> 1
            "second", "2nd" -> 2
            "third", "3rd" -> 3
            "fourth", "4th" -> 4
            "last" -> -1
            else -> return null
        }
        val targetMonth = when (monthPhrase) {
            "next month" -> today.plusMonths(1).withDayOfMonth(1)
            else -> {
                val month = Month.entries.firstOrNull { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH).equals(monthPhrase, true) || it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).trimEnd('.').equals(monthPhrase, true) }
                    ?: return null
                LocalDate.of(today.year, month, 1)
            }
        }
        val result = if (ordinal == -1) {
            targetMonth.withDayOfMonth(targetMonth.lengthOfMonth()).with(java.time.temporal.TemporalAdjusters.previousOrSame(weekday))
        } else {
            targetMonth.with(java.time.temporal.TemporalAdjusters.dayOfWeekInMonth(ordinal, weekday))
        }
        return result
    }

    private fun parseNumericDate(text: String, today: LocalDate): LocalDate? {
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").matchEntire(text)?.let { match ->
            return runCatching { LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()) }.getOrNull()
        }
        Regex("^(\\d{4})[/.](\\d{1,2})[/.](\\d{1,2})$").matchEntire(text)?.let { match ->
            return runCatching { LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()) }.getOrNull()
        }
        val match = Regex("^(\\d{1,2})[/.\\-](\\d{1,2})(?:[/.\\-](\\d{4}))?$").matchEntire(text) ?: return null
        val first = match.groupValues[1].toInt()
        val second = match.groupValues[2].toInt()
        if (first <= 12 && second <= 12) return null // Deliberately reject ambiguous dates such as 03/04.
        val day: Int
        val month: Int
        if (first > 12) { day = first; month = second } else { month = first; day = second }
        val year = match.groupValues[3].toIntOrNull() ?: today.year
        val parsed = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
        return if (match.groupValues[3].isBlank() && parsed.isBefore(today)) parsed.plusYears(1) else parsed
    }

    private fun parsePreciseTime(raw: String): LocalTime? {
        var text = raw.trim().lowercase(locale)
            .replace(Regex("a\\s*\\.\\s*m\\.?"), "am")
            .replace(Regex("p\\s*\\.\\s*m\\.?"), "pm")
            .trim()
        if (Regex("\\bnoon\\b").containsMatchIn(text)) return LocalTime.NOON
        if (Regex("\\bmidnight\\b").containsMatchIn(text)) return LocalTime.MIDNIGHT
        Regex("(?<![\\p{L}\\d])(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)(?![\\p{L}])", RegexOption.IGNORE_CASE)
            .find(text)?.let { match ->
                val hour = match.groupValues[1].toIntOrNull() ?: return null
                val minute = match.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return null
                val suffix = match.groupValues[3].lowercase(Locale.ROOT)
                if (hour !in 1..12 || minute !in 0..59) return null
                return LocalTime.of((hour % 12) + if (suffix == "pm") 12 else 0, minute)
            }
        Regex("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)").find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return LocalTime.of(hour, minute)
        }
        return null
    }

    private fun hasTimeCandidate(value: String): Boolean =
        Regex("\\b(?:\\d{1,2}:\\d{2}|\\d{1,2}\\s*(?:a\\.?\\s*m\\.?|p\\.?\\s*m\\.?))\\b", RegexOption.IGNORE_CASE).containsMatchIn(value) ||
            Regex("\\b(noon|midnight)\\b", RegexOption.IGNORE_CASE).containsMatchIn(value)

    private fun containsBroadPeriod(value: String): Boolean =
        Regex("\\b(morning|afternoon|evening|tonight)\\b|सुबह|दोपहर|शाम|रात", RegexOption.IGNORE_CASE).containsMatchIn(value)

    private fun clean(value: String?): String = value.orEmpty().trim().lowercase(locale)
        .replace(Regex("^(?:on|by)\\s+"), "")
        .replace(Regex("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$"), "")
        .replace(Regex("\\s+"), " ")

    private fun hasAny(value: String, vararg phrases: String): Boolean = phrases.any {
        Regex("(?<![\\p{L}])${Regex.escape(it)}(?![\\p{L}])", RegexOption.IGNORE_CASE).containsMatchIn(value)
    }
}
