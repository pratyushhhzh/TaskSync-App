package com.example.tasksync.ui.assistant

import com.example.tasksync.data.local.entity.TaskEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale

sealed interface VoiceTaskAction {
    data class Create(
        val title: String,
        val dueDate: String?,
        val dueTime: String?,
        val priority: Int,
        val recurrenceRule: String?,
        val reminderEnabled: Boolean
    ) : VoiceTaskAction

    data class Update(val before: TaskEntity, val after: TaskEntity) : VoiceTaskAction
    data class Complete(val task: TaskEntity) : VoiceTaskAction
}

sealed interface VoiceInterpretation {
    data class Actions(val actions: List<VoiceTaskAction>) : VoiceInterpretation
    data class NeedsClarification(val message: String) : VoiceInterpretation
    data class NotUnderstood(val message: String) : VoiceInterpretation
}

/** A deliberately small, offline command interpreter. Every write is validated before it reaches Room. */
object VoiceTaskInterpreter {
    private val weekdays = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY
    )

    fun interpret(utterance: String, tasks: List<TaskEntity>, today: LocalDate = LocalDate.now()): VoiceInterpretation {
        val phrase = normalizeRecognizedText(utterance).trim().trimEnd('.', '!', '?')
        if (phrase.isBlank()) return VoiceInterpretation.NotUnderstood("I didn't catch any words. Try speaking again.")

        parseCompletion(phrase, tasks)?.let { return it }
        parsePriorityUpdate(phrase, tasks)?.let { return it }
        parseDeadlineUpdate(phrase, tasks, today)?.let { return it }
        if (looksLikeUpdate(phrase)) {
            return VoiceInterpretation.NeedsClarification("I couldn't identify one clear task to update. Please say the task's full name and the change you want.")
        }

        val clauses = splitTasks(phrase)
        val creates = clauses.filter { isLikelyTaskClause(it, today) }.mapNotNull { clause -> parseCreate(clause, today) }
        return if (creates.isEmpty()) {
            VoiceInterpretation.NotUnderstood("I couldn't identify a task. Try saying what to do, and include a deadline or priority if you want one.")
        } else {
            VoiceInterpretation.Actions(creates)
        }
    }

    private fun parseCompletion(phrase: String, tasks: List<TaskEntity>): VoiceInterpretation? {
        val match = Regex(
            "^(?:please\\s+)?(?:mark|set|complete|finish)\\s+(?:the\\s+task\\s+)?(.+?)\\s+(?:as\\s+)?(?:complete|completed|done)$|^(?:the\\s+task\\s+)?(.+?)\\s+(?:is|was)\\s+(?:complete|completed|done)$",
            RegexOption.IGNORE_CASE
        ).find(phrase) ?: return null
        val requestedTitle = (match.groups[1]?.value ?: match.groups[2]?.value).orEmpty().trim()
        return resolveTask(requestedTitle, tasks.filter { it.status != "completed" }) { VoiceTaskAction.Complete(it) }
    }

    private fun parsePriorityUpdate(phrase: String, tasks: List<TaskEntity>): VoiceInterpretation? {
        val match = Regex(
            "^(?:please\\s+)?(?:make|set|change|update)\\s+(?:the\\s+priority\\s+of\\s+)?(.+?)\\s+(?:to\\s+)?(?:a\\s+)?(high|medium|low|none)(?:\\s+priority)?$|^set\\s+(high|medium|low|none)\\s+priority\\s+for\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(phrase) ?: return null
        val target = (match.groups[1]?.value ?: match.groups[4]?.value).orEmpty().trim()
        val priorityLabel = (match.groups[2]?.value ?: match.groups[3]?.value).orEmpty().lowercase(Locale.ROOT)
        val priority = when (priorityLabel) { "high" -> 1; "medium" -> 2; "low" -> 3; else -> 4 }
        return resolveTask(target, tasks.filter { it.status != "completed" }) { task ->
            VoiceTaskAction.Update(task, task.copy(priority = priority))
        }
    }

    private fun parseDeadlineUpdate(phrase: String, tasks: List<TaskEntity>, today: LocalDate): VoiceInterpretation? {
        val match = Regex(
            "^(?:please\\s+)?(?:move|reschedule|change|update)\\s+(.+?)\\s+(?:(?:deadline|due\\s+date|date)\\s+)?(?:to|until|for)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(phrase) ?: return null
        val target = match.groupValues[1].trim()
        val parsed = parseDateAndTime(match.groupValues[2], today)
        if (parsed.date == null) return VoiceInterpretation.NeedsClarification("I heard a deadline change, but couldn't understand the new date. Please say the task name and a date such as 'next Tuesday'.")
        return resolveTask(target, tasks.filter { it.status != "completed" }) { task ->
            VoiceTaskAction.Update(task, task.copy(dueDate = parsed.date.toString(), dueTime = parsed.time ?: task.dueTime))
        }
    }

    private fun resolveTask(
        requestedTitle: String,
        candidates: List<TaskEntity>,
        action: (TaskEntity) -> VoiceTaskAction
    ): VoiceInterpretation {
        val normalized = normalizeTitle(requestedTitle)
        if (normalized.isBlank()) return VoiceInterpretation.NeedsClarification("Please say the task's name.")
        val matches = candidates.filter { task ->
            val existing = normalizeTitle(task.title)
            existing == normalized || existing.contains(normalized) || normalized.contains(existing)
        }
        return when (matches.size) {
            0 -> VoiceInterpretation.NeedsClarification("I couldn't find a task named '$requestedTitle'. Please say its full name, or create it as a new task.")
            1 -> VoiceInterpretation.Actions(listOf(action(matches.single())))
            else -> VoiceInterpretation.NeedsClarification(
                "I found several tasks that could match '$requestedTitle': ${matches.take(4).joinToString { it.title }}. Please repeat the request using the full task name."
            )
        }
    }

    private fun parseCreate(clause: String, today: LocalDate): VoiceTaskAction.Create? {
        var working = clause.trim().trimStart(',', ' ', '.')
        working = working.replace(
            Regex("^(?:(?:hi|hello|hey|okay|ok|also)[,!. ]+)+", RegexOption.IGNORE_CASE),
            ""
        ).trim()
        working = working.replace(
            Regex("^(?:please\\s+)?(?:add|create|make|remember|remind me to|i (?:also\\s+)?need(?:\\s+to)?|i (?:also\\s+)?have(?:\\s+to)?|i would need(?:\\s+to)?|i should|i must|i will|don't forget to)\\s+(?:a\\s+)?(?:task\\s+to\\s+)?", RegexOption.IGNORE_CASE),
            ""
        )
        working = working.replace(
            Regex("\\s+(?:due\\s+to\\s+which|because\\s+of\\s+which|which\\s+means)\\b.*$", RegexOption.IGNORE_CASE),
            ""
        )
        val dateTime = parseDateAndTime(working, today)
        dateTime.dateToken?.let { working = working.replace(it, " ", ignoreCase = true) }
        dateTime.timeToken?.let { working = working.replace(it, " ", ignoreCase = true) }
        working = working.trim()

        val priority = when {
            Regex("\\b(high|urgent|important|top)\\s+priority\\b|\\burgent\\b", RegexOption.IGNORE_CASE).containsMatchIn(working) -> 1
            Regex("\\bmedium\\s+priority\\b", RegexOption.IGNORE_CASE).containsMatchIn(working) -> 2
            Regex("\\blow\\s+priority\\b", RegexOption.IGNORE_CASE).containsMatchIn(working) -> 3
            else -> 4
        }
        working = working.replace(Regex("\\b(?:high|medium|low|urgent|important|top)\\s+priority\\b|\\burgent\\b", RegexOption.IGNORE_CASE), " ")

        val recurrence = when {
            Regex("\\b(?:every day|daily)\\b", RegexOption.IGNORE_CASE).containsMatchIn(working) -> "daily"
            Regex("\\b(?:every week|weekly)\\b", RegexOption.IGNORE_CASE).containsMatchIn(working) -> "weekly"
            Regex("\\b(?:every month|monthly)\\b", RegexOption.IGNORE_CASE).containsMatchIn(working) -> "monthly"
            else -> null
        }
        working = working.replace(Regex("\\b(?:every day|daily|every week|weekly|every month|monthly)\\b", RegexOption.IGNORE_CASE), " ")

        val dateLead = Regex("^(?:the\\s+)?(?:today|tomorrow|day after tomorrow|next\\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|this\\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday))\\s+(?:is\\s+)?(?:my\\s+)?", RegexOption.IGNORE_CASE)
        working = working.replace(dateLead, "")
        working = working.replace(Regex("^(?:the\\s+)?(?:is|will be)\\s+(?:my\\s+)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:the|my)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:i need|i have|i should)\\s+(?:to\\s+)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^i\\s+(?:would\\s+)?(?:need|have|should)(?:\\s+to)?\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+is$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:a|an|the)\\s+", RegexOption.IGNORE_CASE), "")
        working = working.replace(Regex("\\b(?:due|on|by|at|for)\\s*$", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ").trim(' ', ',', '.', ':', ';')
        if (working.length < 2) return null
        return VoiceTaskAction.Create(
            title = working.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() },
            // A time-only deadline such as “by 9:30 pm” means today unless a date was spoken.
            dueDate = (dateTime.date ?: if (recurrence != null || dateTime.time != null) today else null)?.toString(),
            dueTime = dateTime.time,
            priority = priority,
            recurrenceRule = recurrence,
            reminderEnabled = Regex("\\bremind me\\b", RegexOption.IGNORE_CASE).containsMatchIn(clause) && dateTime.time != null
        )
    }

    private data class ParsedDateTime(
        val date: LocalDate?,
        val time: String?,
        val dateToken: String?,
        val timeToken: String?
    )

    private fun parseDateAndTime(text: String, today: LocalDate): ParsedDateTime {
        val dateMatch = Regex(
            "\\b(day\\s+after\\s+tomorrow|tomorrow|today|next\\s+week(?:['’]s)?\\s+(?:on\\s+)?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|next\\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|this\\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|in\\s+\\d+\\s+days?)\\b",
            RegexOption.IGNORE_CASE
        ).find(text)
        val date = dateMatch?.value?.let { token ->
            val normalized = token.lowercase(Locale.ROOT)
            when {
                normalized == "today" -> today
                normalized == "tomorrow" -> today.plusDays(1)
                normalized.startsWith("day after") -> today.plusDays(2)
                normalized.startsWith("in ") -> today.plusDays(Regex("\\d+").find(normalized)?.value?.toLongOrNull() ?: 1L)
                normalized.startsWith("next week") -> {
                    val dayName = normalized.substringAfter("week").trim()
                        .removePrefix("'").removePrefix("’").removePrefix("s").trim().removePrefix("on ")
                    weekdays[dayName]?.let { day ->
                        today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).with(TemporalAdjusters.nextOrSame(day))
                    }
                }
                else -> {
                    val dayName = normalized.substringAfter(' ').trim()
                    weekdays[dayName]?.let { day ->
                        val currentOrNext = today.with(TemporalAdjusters.nextOrSame(day))
                        if (normalized.startsWith("next") && currentOrNext == today) currentOrNext.plusWeeks(1)
                        else if (normalized.startsWith("next") && today.dayOfWeek == day) currentOrNext.plusWeeks(1)
                        else currentOrNext
                    }
                }
            }
        }
        val timeMatch = Regex(
            "\\b(?:(?:at|by)\\s+)?(\\d{1,2}(?::\\d{2}|\\s+\\d{2})?\\s*(?:a\\.?m\\.?|p\\.?m\\.?|a|p))\\b",
            RegexOption.IGNORE_CASE
        ).find(text)
        val time = timeMatch?.groupValues?.getOrNull(1)?.let(::normalizeTime)
        return ParsedDateTime(date, time, dateMatch?.value, timeMatch?.value)
    }

    private fun normalizeTime(input: String): String? = runCatching {
        var clean = input.trim().lowercase(Locale.ROOT).replace(".", "").trim()
        clean = clean.replace(Regex("^(\\d{1,2})\\s+(\\d{2})\\s*(am|pm|a|p)$"), "$1:$2$3")
            .replace(Regex("\\s+"), "")
        if (clean.endsWith("p")) clean += "m"
        if (clean.endsWith("a")) clean += "m"
        val hasPm = clean.endsWith("pm")
        val hasAm = clean.endsWith("am")
        val timePart = if (hasPm || hasAm) clean.dropLast(2) else clean
        val pieces = timePart.split(":")
        var hour = pieces[0].toInt()
        val minute = pieces.getOrElse(1) { "00" }.toInt()
        if (hasPm && hour < 12) hour += 12
        if (hasAm && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        "%02d:%02d".format(hour, minute)
    }.getOrNull()

    private fun splitTasks(phrase: String): List<String> = phrase
        .replace(Regex("[.!?]+"), ";")
        .replace(Regex("\\s+and also\\s+", RegexOption.IGNORE_CASE), ";")
        .replace(Regex("\\s+and then\\s+", RegexOption.IGNORE_CASE), ";")
        .replace(Regex("\\s+then\\s+", RegexOption.IGNORE_CASE), ";")
        .replace(
            Regex("\\s+and\\s+(?=(?:today|tomorrow|(?:the\\s+)?day after tomorrow|next\\s+\\w+|i need|i have|i would need|i should|add\\s|create\\s|buy\\s|call\\s|send\\s|finish\\s|prepare\\s|submit\\s|review\\s|meet\\s|pay\\s|remind\\s+me|walk\\s|study\\s|write\\s|read\\s|clean\\s|visit\\s|practice\\s|book\\s|email\\s|organize\\s|start\\s|go\\s|do\\s|pick\\s+up)\\b)", RegexOption.IGNORE_CASE),
            ";"
        )
        .split(';', ',', '\n')
        .map(String::trim)
        .filter(String::isNotBlank)

    private fun normalizeRecognizedText(text: String): String {
        var normalized = text
            .replace(Regex("\\btomm?or+ow\\b", RegexOption.IGNORE_CASE), "tomorrow")
            .replace(Regex("\\btmrw\\b", RegexOption.IGNORE_CASE), "tomorrow")
            .replace(Regex("\\bwednsday\\b", RegexOption.IGNORE_CASE), "Wednesday")

        val weekdaysPattern = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
        // A pause can split a date expression between consecutive speech results.
        normalized = normalized.replace(
            Regex("(next\\s+week(?:['’]s)?)[.!?,;\\n]+\\s+((?:on\\s+)?(?:$weekdaysPattern))", RegexOption.IGNORE_CASE),
            "$1 $2"
        )
        // If the date arrives as its own speech segment, attach it to the preceding task.
        normalized = normalized.replace(
            Regex("([\\p{L}\\p{N}][^.!?;\\n]{0,100})[.!?;\\n]+\\s*((?:next\\s+week(?:['’]s)?\\s+(?:on\\s+)?(?:$weekdaysPattern)|next\\s+(?:$weekdaysPattern)|this\\s+(?:$weekdaysPattern)|day\\s+after\\s+tomorrow|tomorrow|today|in\\s+\\d+\\s+days?))", RegexOption.IGNORE_CASE),
            "$1 $2"
        )
        // Speech services sometimes return “9:30. P” for “9:30 PM”.
        normalized = normalized.replace(
            Regex("(\\d{1,2}(?::\\d{2})?)[.!?,;\\n]+\\s*([ap](?:\\.?m\\.?)?)\\b", RegexOption.IGNORE_CASE),
            "$1 $2"
        )
        return normalized
    }

    private fun looksLikeUpdate(phrase: String): Boolean = Regex(
        "^(?:please\\s+)?(?:mark|set|move|reschedule|change|update)\\b",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(phrase)

    private fun isLikelyTaskClause(clause: String, today: LocalDate): Boolean {
        val hasDate = parseDateAndTime(clause, today).date != null
        val taskVerb = Regex(
            "^(?:please\\s+)?(?:add|create|make|remember|remind|buy|call|send|finish|prepare|submit|review|meet|pay|study|write|read|clean|visit|practice|schedule|book|email|walk|take|pick\\s+up|work\\s+on|complete|organize|start|go|fix|do)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(clause.trim())
        val obligation = Regex("\\b(?:i need|i have|i would need|i should|i must|i will|don't forget|remind me)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clause)
        return hasDate || taskVerb || obligation
    }

    private fun normalizeTitle(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("^(?:the|my|a|an|task)\\s+"), "")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
