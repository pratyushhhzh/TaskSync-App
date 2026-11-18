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
) {
    val isResolved: Boolean get() = unresolved.isEmpty()
}

/** Conservative, device-local parser. Weekday phrases without a modifier mean the next occurrence. */
class LocalDateTimeResolver(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val locale: Locale = Locale.getDefault(),
) {
    fun resolve(datePhrase: String?, timePhrase: String?): ResolvedDateTime {
        val today = LocalDate.now(clock)
        val issues = mutableListOf<String>()
        val dateText = clean(datePhrase)
        val timeText = clean(timePhrase)
        val dateOnlyText = dateText
            .replace(Regex("\\s+(?:at|by)\\s+\\d{1,2}(?::\\d{2})?\\s*(?:a\\.?\\s*m\\.?|p\\.?\\s*m\\.?)\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:at|by)\\s+\\d{1,2}:\\d{2}\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^\\d{1,2}(?::\\d{2})?\\s*(?:a\\.?\\s*m\\.?|p\\.?\\s*m\\.?|noon|midnight)\\s+(?:on\\s+)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^\\d{1,2}:\\d{2}\\s+(?:on\\s+)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:morning|afternoon|evening|tonight|noon|midnight)$", RegexOption.IGNORE_CASE), "")
            .trim()
        var date = resolveDate(dateOnlyText, today, datePhrase, issues)
        if (date == null && dateText.isBlank()) {
            when {
                hasAny(timeText, "tomorrow", "day after tomorrow") -> date = today.plusDays(if (timeText.contains("day after tomorrow")) 2 else 1)
                hasAny(timeText, "today", "tonight", "this evening", "this morning", "this afternoon", "आज", "aaj") -> date = today
                hasAny(timeText, "kal", "कल") -> issues += "‘kal’ can mean yesterday or tomorrow; choose a date."
            }
        }

        val parseTimeFrom = timeText.ifBlank { dateText }
        val parsedTime = parsePreciseTime(parseTimeFrom)
        val timeCandidate = hasTimeCandidate(parseTimeFrom)
        val time = parsedTime
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
        } else if (date == today && time != null && date.atTime(time).isBefore(java.time.LocalDateTime.now(clock))) {
            issues += "The resolved time is in the past. Choose a future date and time."
        }
        return ResolvedDateTime(date, time, issues.distinct())
    }

    private fun resolveDate(text: String, today: LocalDate, original: String?, issues: MutableList<String>): LocalDate? {
        if (text.isBlank()) return null
        when {
            text in setOf("today", "आज", "aaj") -> return today
            text in setOf("tomorrow", "mañana") -> return today.plusDays(1)
            text in setOf("day after tomorrow", "the day after tomorrow") -> return today.plusDays(2)
            text in setOf("कल", "kal") || text.startsWith("कल ") || text.startsWith("kal ") -> {
                issues += "‘kal’ can mean yesterday or tomorrow; choose a date."
                return null
            }
            text == "this week" || text == "next week" -> {
                issues += "‘${original}’ is a date range. Choose a specific day in the calendar."
                return null
            }
            text.startsWith("अगला ") || text.startsWith("अगली ") -> {
                val prefix = if (text.startsWith("अगला ")) "अगला " else "अगली "
                val day = parseWeekday(text.removePrefix(prefix))
                if (day != null) return nextWeekday(today, day)
            }
        }

        val weekdayAtNextWeek = Regex("^(.+?)\\s+next week$").matchEntire(text)
        if (weekdayAtNextWeek != null) {
            val day = parseWeekday(weekdayAtNextWeek.groupValues[1])
            if (day != null) {
                val fields = WeekFields.of(locale)
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
                    val fields = WeekFields.of(locale)
                    return today.with(fields.dayOfWeek(), 1).plusDays((day.value - fields.firstDayOfWeek.value + 7L) % 7L)
                }
                return nextWeekday(today, day)
            }
        }
        parseWeekday(text)?.let { return nextWeekday(today, it) }

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
            val dayFirst = Regex("^(\\d{1,2})\\s+${Regex.escape(name)}(?:\\s+(\\d{4}))?$").matchEntire(text)
            val monthFirst = Regex("^${Regex.escape(name)}\\s+(\\d{1,2})(?:,?\\s+(\\d{4}))?$").matchEntire(text)
            val match = dayFirst ?: monthFirst ?: continue
            val day = match.groupValues[1].toIntOrNull() ?: continue
            val year = match.groupValues[2].toIntOrNull() ?: today.year
            val parsed = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
            return if (match.groupValues[2].isBlank() && parsed.isBefore(today)) parsed.plusYears(1) else parsed
        }
        return null
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
        .replace(Regex("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$"), "")
        .replace(Regex("\\s+"), " ")

    private fun hasAny(value: String, vararg phrases: String): Boolean = phrases.any {
        Regex("(?<![\\p{L}])${Regex.escape(it)}(?![\\p{L}])", RegexOption.IGNORE_CASE).containsMatchIn(value)
    }
}
