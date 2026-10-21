package com.example.tasksync.ui.assistant

import com.example.tasksync.data.local.entity.TaskEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.max

sealed interface VoiceTaskAction {
    data class Create(
        val title: String,
        val dueDate: String?,
        val dueTime: String?,
        val priority: Int,
        val recurrenceRule: String?,
        val reminderEnabled: Boolean,
        val description: String? = null
    ) : VoiceTaskAction

    data class Update(val before: TaskEntity, val after: TaskEntity) : VoiceTaskAction
    data class Complete(val task: TaskEntity) : VoiceTaskAction
    data class Reopen(val task: TaskEntity) : VoiceTaskAction
    data class Delete(val task: TaskEntity) : VoiceTaskAction
    data class AddSubtask(val task: TaskEntity, val title: String) : VoiceTaskAction
}

sealed interface VoiceInterpretation {
    data class Actions(val actions: List<VoiceTaskAction>) : VoiceInterpretation
    data class Reply(val message: String) : VoiceInterpretation
    data class NeedsClarification(val message: String) : VoiceInterpretation
    data class NotUnderstood(val message: String) : VoiceInterpretation
}

/** Offline intent parser for voice transcripts. It emits proposed actions and never writes data itself. */
object VoiceTaskInterpreter {
    private val weekdays = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY
    )
    private val months = Month.values().flatMap { month ->
        listOf(month.name.lowercase(Locale.ROOT) to month, month.name.take(3).lowercase(Locale.ROOT) to month)
    }.toMap()
    private val actionVerbs = listOf(
        "buy", "get", "pick up", "call", "send", "finish", "prepare", "submit", "review", "meet",
        "pay", "study", "write", "read", "clean", "visit", "practice", "schedule", "book", "email",
        "walk", "take", "work on", "organize", "start", "go", "fix", "do", "attend", "make", "renew",
        "collect", "return", "order", "cancel", "print", "pack", "bring", "drop off", "fill out"
    )
    private val taskStarter = Regex(
        "^(?:please\\s+)?(?:${VoiceTaskIntentPhrases.taskStarters.joinToString("|") { Regex.escape(it) }})\\b",
        RegexOption.IGNORE_CASE
    )

    fun interpret(utterance: String, tasks: List<TaskEntity>, today: LocalDate = LocalDate.now()): VoiceInterpretation {
        val normalized = normalizeRecognizedText(utterance).trim()
        if (normalized.isBlank()) return VoiceInterpretation.NotUnderstood("I didn't catch any words. Try speaking again.")

        parseTaskQuery(normalized, tasks, today)?.let { return VoiceInterpretation.Reply(it) }

        val actions = mutableListOf<VoiceTaskAction>()
        val clarifications = mutableListOf<String>()
        for (clause in splitTasks(normalized)) {
            val command = parseExistingTaskCommand(clause, tasks, today)
            when (command) {
                is CommandResult.Action -> actions += command.action
                is CommandResult.Clarification -> clarifications += command.message
                CommandResult.NotACommand -> {
                    if (isLikelyTaskClause(clause, today)) {
                        parseCreate(clause, today)?.let(actions::add)
                    }
                }
            }
        }

        // Do not partially apply a multi-command utterance if any existing-task command is ambiguous.
        if (clarifications.isNotEmpty()) return VoiceInterpretation.NeedsClarification(clarifications.distinct().joinToString(" "))
        if (actions.isNotEmpty()) return VoiceInterpretation.Actions(actions)
        return VoiceInterpretation.NotUnderstood(
            "I couldn't identify a task. Say what needs doing; you can include a date, time, priority, or repeat schedule."
        )
    }

    private sealed interface CommandResult {
        data class Action(val action: VoiceTaskAction) : CommandResult
        data class Clarification(val message: String) : CommandResult
        data object NotACommand : CommandResult
    }

    private fun parseExistingTaskCommand(clause: String, tasks: List<TaskEntity>, today: LocalDate): CommandResult {
        return when (VoiceIntentClassifier.classify(clause)) {
            VoiceIntentClassifier.Intent.COMPLETE -> parseCompletion(clause, tasks, today) ?: CommandResult.NotACommand
            VoiceIntentClassifier.Intent.REOPEN -> parseReopen(clause, tasks) ?: CommandResult.Clarification("Please name the completed task you want to reopen.")
            VoiceIntentClassifier.Intent.DELETE -> parseDelete(clause, tasks) ?: CommandResult.Clarification("Please name the task you want to delete.")
            VoiceIntentClassifier.Intent.ADD_SUBTASK -> parseSubtask(clause, tasks) ?: CommandResult.Clarification("Say the checklist item and the task it belongs to.")
            VoiceIntentClassifier.Intent.UPDATE -> {
                parseDescriptionUpdate(clause, tasks)
                    ?: parsePriorityUpdate(clause, tasks)
                    ?: parseRecurrenceUpdate(clause, tasks, today)
                    ?: parseDateOrTimeUpdate(clause, tasks, today)
                    ?: CommandResult.Clarification("I heard a request to change a task, but couldn't identify the task or change. Please say its name and the new value.")
            }
            VoiceIntentClassifier.Intent.CREATE, VoiceIntentClassifier.Intent.UNKNOWN -> CommandResult.NotACommand
        }
    }

    private fun parseReopen(clause: String, tasks: List<TaskEntity>): CommandResult? {
        val match = Regex("^(?:please\\s+)?(?:reopen|uncomplete|mark\\s+(.+?)\\s+as\\s+incomplete|mark\\s+(.+?)\\s+not\\s+done)\\s*(.*)$", RegexOption.IGNORE_CASE).find(clause.trim().trimEnd('.', '!', '?'))
        if (match != null) {
            val target = match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value.orEmpty()
            return resolveTask(target, tasks.filter { it.status == "completed" }) { CommandResult.Action(VoiceTaskAction.Reopen(it)) }
        }
        val named = Regex("^(?:please\\s+)?reopen\\s+(?:the\\s+task\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(clause.trim().trimEnd('.', '!', '?')) ?: return null
        return resolveTask(named.groupValues[1], tasks.filter { it.status == "completed" }) { CommandResult.Action(VoiceTaskAction.Reopen(it)) }
    }

    private fun parseDelete(clause: String, tasks: List<TaskEntity>): CommandResult? {
        val match = Regex("^(?:please\\s+)?(?:delete|remove)\\s+(?:the\\s+)?(?:task\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(clause.trim().trimEnd('.', '!', '?')) ?: return null
        return resolveTask(match.groupValues[1], tasks) { CommandResult.Action(VoiceTaskAction.Delete(it)) }
    }

    private fun parseSubtask(clause: String, tasks: List<TaskEntity>): CommandResult? {
        val match = Regex("^(?:please\\s+)?(?:add|create)\\s+(?:a\\s+)?(?:subtask|checklist\\s+item)\\s+(.+?)\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE).find(clause.trim().trimEnd('.', '!', '?')) ?: return null
        val itemTitle = match.groupValues[1].trim().trim('"', '\'')
        if (itemTitle.isBlank()) return CommandResult.Clarification("What should the subtask be called?")
        return resolveTask(match.groupValues[2], tasks.filter { it.status != "completed" }) {
            CommandResult.Action(VoiceTaskAction.AddSubtask(it, itemTitle))
        }
    }

    private fun parseDescriptionUpdate(clause: String, tasks: List<TaskEntity>): CommandResult? {
        val add = Regex("^(?:please\\s+)?(?:add|set|change|update)\\s+(?:a\\s+)?(?:note|description|details?)\\s+(?:for|on|to)\\s+(.+?)\\s+(?:to|as)\\s+(.+)$", RegexOption.IGNORE_CASE).find(clause.trim().trimEnd('.', '!', '?'))
        val remove = Regex("^(?:please\\s+)?(?:remove|clear|delete)\\s+(?:the\\s+)?(?:note|description|details?)\\s+(?:for|on|from)\\s+(.+)$", RegexOption.IGNORE_CASE).find(clause.trim().trimEnd('.', '!', '?'))
        if (add == null && remove == null) return null
        val target = add?.groupValues?.get(1) ?: remove!!.groupValues[1]
        val description = add?.groupValues?.get(2)?.trim()?.takeIf(String::isNotBlank)
        return resolveTask(target, tasks.filter { it.status != "completed" }) { task ->
            CommandResult.Action(VoiceTaskAction.Update(task, task.copy(description = description)))
        }
    }

    private fun parseTaskQuery(utterance: String, tasks: List<TaskEntity>, today: LocalDate): String? {
        if (!Regex("^(?:what|which|show|list|do\\s+i\\s+have)\\b", RegexOption.IGNORE_CASE).containsMatchIn(utterance)) return null
        val date = parseDateAndTime(utterance, today).date
        val open = tasks.filter { it.status != "completed" }
        val selected = if (date != null) open.filter { it.dueDate == date.toString() } else open
        return if (selected.isEmpty()) {
            if (date != null) "You have no open tasks due ${date}." else "You have no open tasks."
        } else {
            val heading = if (date != null) "Tasks due ${date}:" else "Your open tasks:"
            heading + " " + selected.take(8).joinToString("; ") { task ->
                task.title + (task.dueTime?.let { " at $it" } ?: "")
            } + if (selected.size > 8) "; and ${selected.size - 8} more." else "."
        }
    }

    private fun parseCompletion(clause: String, tasks: List<TaskEntity>, today: LocalDate): CommandResult? {
        val match = Regex(
            "^(?:please\\s+)?(?:mark|set|complete|finish)\\s+(?:the\\s+task\\s+)?(.+?)\\s+(?:as\\s+)?(?:complete|completed|done)$" +
                "|^(?:the\\s+task\\s+)?(.+?)\\s+(?:is|was)\\s+(?:complete|completed|done)$" +
                "|^(?:i\\s+)?(?:finished|completed)\\s+(?:the\\s+task\\s+)?(.+)$" +
                "|^(?:i\\s+)?(?:am\\s+)?done\\s+with\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(clause.trim().trimEnd('.', '!', '?')) ?: return null
        var title = (1..4).mapNotNull { match.groups[it]?.value }.firstOrNull().orEmpty().trim()
        val metadata = parseDateAndTime(title, today)
        metadata.dateToken?.let { title = title.replace(it, " ", ignoreCase = true) }
        metadata.timeToken?.let { title = title.replace(it, " ", ignoreCase = true) }
        title = title.replace(Regex("\\b(?:today|tomorrow|yesterday|please)\\b", RegexOption.IGNORE_CASE), " ").trim()
        return resolveTask(title, tasks.filter { it.status != "completed" }) { CommandResult.Action(VoiceTaskAction.Complete(it)) }
    }

    private fun parsePriorityUpdate(clause: String, tasks: List<TaskEntity>): CommandResult? {
        val clean = clause.trim().trimEnd('.', '!', '?')
        val targetFirst = Regex("^(?:please\\s+)?(?:set|make|change|update)\\s+(?:the\\s+)?(?:priority\\s+of\\s+)?(.+?)\\s+(?:to|as)\\s+(?:a\\s+)?(high|medium|med|low|none|normal|p[1-4])(?:\\s+priority)?$", RegexOption.IGNORE_CASE).find(clean)
            ?: Regex("^(?:please\\s+)?(?:change|update)\\s+(.+?)\\s+(?:task\\s+)?priority\\s+(?:to|as)\\s+(high|medium|med|low|none|normal|p[1-4])$", RegexOption.IGNORE_CASE).find(clean)
            ?: Regex("^(?:please\\s+)?(?:set|make|change|update)\\s+(.+?)\\s+(high|medium|med|low|none|normal|p[1-4])\\s+priority$", RegexOption.IGNORE_CASE).find(clean)
            ?: Regex("^make\\s+(.+?)\\s+(?:a\\s+)?(high|medium|med|low|none|normal|p[1-4])\\s+priority$", RegexOption.IGNORE_CASE).find(clean)
        val priorityFirst = Regex("^(?:please\\s+)?(?:set|give)\\s+(high|medium|med|low|none|normal|p[1-4])\\s+priority\\s+(?:to|for)\\s+(.+)$", RegexOption.IGNORE_CASE).find(clean)
        val title: String
        val priorityText: String
        if (targetFirst != null) {
            title = targetFirst.groupValues[1].trim()
            priorityText = targetFirst.groupValues[2].trim()
        } else if (priorityFirst != null) {
            title = priorityFirst.groupValues[2].trim()
            priorityText = priorityFirst.groupValues[1].trim()
        } else return null
        val priority = when (priorityText.lowercase(Locale.ROOT)) {
            "high", "p1" -> 1
            "medium", "med", "p2" -> 2
            "low", "p3" -> 3
            else -> 4
        }
        return resolveTask(title, tasks.filter { it.status != "completed" }) { task ->
            CommandResult.Action(VoiceTaskAction.Update(task, task.copy(priority = priority)))
        }
    }

    private fun parseDateOrTimeUpdate(clause: String, tasks: List<TaskEntity>, today: LocalDate): CommandResult? {
        val text = clause.trim().trimEnd('.', '!', '?')
        // Conversational rescheduling: "there was a change of plan, the
        // football match will now be at 7 pm". The task name may only be a
        // fragment of its saved title, so identify it against existing tasks.
        val conversationalReschedule = Regex(
            "\\b(?:change in plan|change of plan|reschedul(?:e|ed|ing)|moved|postponed|pushed back|scheduled)\\b.*?\\b(?:now|instead|new time|new date|to|until)\\b|\\bwill now be\\b|\\bwill now start\\b|\\bhas been (?:moved|changed|rescheduled|postponed|shifted)\\b|\\bhas moved to\\b|\\bis now scheduled for\\b|\\b(?:is|was) changed to\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text)
        if (conversationalReschedule) {
            val parsed = parseDateAndTime(text, today)
            if (parsed.date != null || parsed.time != null) {
                val candidates = tasks.filter { it.status != "completed" }
                val spokenTarget = extractRescheduleTarget(text)
                val resolver: (TaskEntity) -> CommandResult = { task ->
                    val dueDate = parsed.date?.toString() ?: task.dueDate ?: if (parsed.time != null) today.toString() else null
                    CommandResult.Action(VoiceTaskAction.Update(task, task.copy(
                        dueDate = dueDate,
                        dueTime = parsed.time ?: task.dueTime
                    )))
                }
                return if (spokenTarget != null) resolveTask(spokenTarget, candidates, resolver)
                else resolveTaskMentionedIn(text, candidates, resolver)
            }
        }
        val patterns = listOf(
            Regex("^(?:please\\s+)?(?:set|change|update|move|reschedule|push|postpone|defer)\\s+(?:the\\s+)?(?:deadline|due\\s+date|date|time)\\s+(?:for|of)\\s+(.+?)\\s+(?:to|until|for)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:please\\s+)?(?:set|change|update|move|reschedule|push|postpone|defer)\\s+(.+?)\\s+(?:deadline|due\\s+date|date|time)\\s+(?:to|until|for)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:please\\s+)?(?:move|reschedule|push|postpone|defer)\\s+(.+?)\\s+(?:to|until)\\s+(.+)$", RegexOption.IGNORE_CASE)
        )
        val match = patterns.firstNotNullOfOrNull { it.find(text) } ?: return null
        val target = match.groupValues[1].trim()
        val value = match.groupValues[2].trim()
        val parsed = parseDateAndTime(value, today)
        if (parsed.date == null && parsed.time == null) {
            return CommandResult.Clarification("I heard a deadline or time change, but couldn't understand the new date or time.")
        }
        return resolveTask(target, tasks.filter { it.status != "completed" }) { task ->
            val dueDate = parsed.date?.toString() ?: task.dueDate ?: if (parsed.time != null) today.toString() else null
            CommandResult.Action(VoiceTaskAction.Update(task, task.copy(
                dueDate = dueDate,
                dueTime = parsed.time ?: task.dueTime
            )))
        }
    }

    private fun extractRescheduleTarget(text: String): String? {
        val namedChange = Regex(
            "^(?:actually[, ]+)?(?:the\\s+)?(.+?)\\s+(?:has|have|is|was)\\s+(?:been\\s+)?(?:rescheduled|postponed|moved|shifted|changed|pushed)\\b",
            RegexOption.IGNORE_CASE
        ).find(text)
        if (namedChange != null) return namedChange.groupValues[1].trim().trim(',', ' ')

        val conversational = Regex(
            "(?:change in plan|change of plan)[, ]+(?:the\\s+)?(.+?)\\s+(?:which was planned.*?\\s+)?(?:will now be|will now start|is now scheduled)",
            RegexOption.IGNORE_CASE
        ).find(text)
        return conversational?.groupValues?.get(1)?.trim()?.trim(',', ' ')
    }

    /** Match a spoken fragment such as "football match" to the saved task
     * "Have a football match", while refusing equally plausible candidates. */
    private fun resolveTaskMentionedIn(
        utterance: String,
        candidates: List<TaskEntity>,
        action: (TaskEntity) -> CommandResult
    ): CommandResult {
        val spoken = normalizeTitle(utterance).split(' ').toSet()
        val scored = candidates.mapNotNull { task ->
            val titleTokens = normalizeTitle(task.title).split(' ').filter { it.length > 1 }.toSet()
            val overlap = titleTokens.intersect(spoken).size
            val coverage = overlap.toDouble() / titleTokens.size.coerceAtLeast(1)
            if (overlap >= 2 && coverage >= 0.5) task to coverage else null
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull()?.second
            ?: return CommandResult.Clarification("I heard a reschedule, but couldn't match it to an existing task. Please include the task's name.")
        val matches = scored.filter { it.second == best }
        val closeAlternative = scored.drop(1).firstOrNull()?.second?.let { best - it < 0.08 } == true
        return if (matches.size == 1 && !closeAlternative) action(matches.single().first) else {
            CommandResult.Clarification("Which task should I reschedule: ${matches.take(4).joinToString { it.first.title }}?")
        }
    }

    private fun parseRecurrenceUpdate(clause: String, tasks: List<TaskEntity>, today: LocalDate): CommandResult? {
        val pattern = Regex(
            "^(?:please\\s+)?(?:set|make|change|update|repeat)\\s+(?:the\\s+)?(.+?)\\s+(?:to\\s+)?(?:repeat\\s+)?(daily|every\\s+(?:single\\s+)?day|weekdays|every\\s+weekday|weekly|every\\s+week|monthly|every\\s+month|never|not\\s+repeat|no\\s+repeat)$",
            RegexOption.IGNORE_CASE
        )
        val match = pattern.find(clause.trim().trimEnd('.', '!', '?')) ?: return null
        val target = match.groupValues[1].removeSuffix("'s").trim()
        val phrase = match.groupValues[2].lowercase(Locale.ROOT)
        val recurrence = when {
            phrase == "never" || phrase == "not repeat" || phrase == "no repeat" -> null
            phrase.contains("day") && !phrase.contains("weekday") -> "daily"
            phrase.contains("weekday") -> "weekdays"
            phrase.contains("month") -> "monthly"
            else -> "weekly"
        }
        return resolveTask(target, tasks.filter { it.status != "completed" }) { task ->
            CommandResult.Action(VoiceTaskAction.Update(task, task.copy(
                recurrenceRule = recurrence,
                dueDate = if (recurrence != null && task.dueDate == null) today.toString() else task.dueDate
            )))
        }
    }

    private fun resolveTask(
        requestedTitle: String,
        candidates: List<TaskEntity>,
        action: (TaskEntity) -> CommandResult
    ): CommandResult {
        val normalized = normalizeTitle(requestedTitle)
        if (normalized.isBlank()) return CommandResult.Clarification("Please say the task's name.")
        val scored = candidates.map { it to titleMatchScore(normalized, normalizeTitle(it.title)) }
            .filter { it.second >= MATCH_THRESHOLD }
            .sortedByDescending { it.second }
        val bestScore = scored.firstOrNull()?.second
            ?: return CommandResult.Clarification("I couldn't find a task named ‘$requestedTitle’. Please say its full name.")
        val best = scored.filter { it.second == bestScore }
        val closeAlternative = scored.drop(1).firstOrNull()?.second?.let { bestScore - it < 0.08 } == true
        return if (best.size == 1 && !closeAlternative) action(best.single().first) else {
            CommandResult.Clarification(
                "I found several tasks that could match ‘$requestedTitle’: ${best.take(4).joinToString { it.first.title }}. Please say the full task name."
            )
        }
    }

    private fun titleMatchScore(requested: String, existing: String): Double {
        if (requested == existing) return 1.0
        if (requested.length >= 5 && (existing.contains(requested) || requested.contains(existing))) return 0.92
        val a = requested.split(' ').filter(String::isNotBlank).toSet()
        val b = existing.split(' ').filter(String::isNotBlank).toSet()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val overlap = a.intersect(b).size.toDouble()
        val recall = overlap / a.size
        val precision = overlap / b.size
        if (recall >= 0.8 && precision >= 0.5) return 0.72 + 0.2 * minOf(recall, precision)
        val normalizedA = a.map(::singularize).toSet()
        val normalizedB = b.map(::singularize).toSet()
        val stemRecall = normalizedA.intersect(normalizedB).size.toDouble() / max(1, normalizedA.size)
        val stemPrecision = normalizedA.intersect(normalizedB).size.toDouble() / max(1, normalizedB.size)
        if (stemRecall >= 0.8 && stemPrecision >= 0.5) return 0.72 + 0.2 * minOf(stemRecall, stemPrecision)
        val fuzzyOverlap = normalizedA.count { left -> normalizedB.any { right -> isLikelySameWord(left, right) } }.toDouble()
        val fuzzyRecall = fuzzyOverlap / max(1, normalizedA.size)
        val fuzzyPrecision = fuzzyOverlap / max(1, normalizedB.size)
        return if (fuzzyRecall >= 0.8 && fuzzyPrecision >= 0.5) 0.72 + 0.2 * minOf(fuzzyRecall, fuzzyPrecision) else 0.0
    }

    private fun isLikelySameWord(left: String, right: String): Boolean {
        if (left.length < 5 || right.length < 5) return false
        val allowedDistance = if (minOf(left.length, right.length) >= 9) 2 else 1
        if (kotlin.math.abs(left.length - right.length) > allowedDistance) return false
        return editDistance(left, right) <= allowedDistance
    }

    private fun editDistance(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = i + 1
            for (j in right.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (left[i] == right[j]) 0 else 1
                )
            }
            previous = current
        }
        return previous[right.length]
    }

    private fun singularize(token: String): String = when {
        token.length > 4 && token.endsWith("ies") -> token.dropLast(3) + "y"
        token.length > 3 && token.endsWith("s") && !token.endsWith("ss") -> token.dropLast(1)
        else -> token
    }

    private fun parseCreate(clause: String, today: LocalDate): VoiceTaskAction.Create? {
        val metadata = parseDateAndTime(clause, today)
        val dayPart = Regex("\\b(morning|afternoon|evening|night)\\b", RegexOption.IGNORE_CASE).find(clause)?.value
        if (Regex("\\b(?:that thing|something|some stuff|sometime|someday|whatever|not sure)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clause)) return null
        var title = clause
        metadata.dateToken?.let { title = title.replace(it, " ", ignoreCase = true) }
        metadata.timeToken?.let { title = title.replace(it, " ", ignoreCase = true) }
        dayPart?.let { title = title.replace(it, " ", ignoreCase = true) }
        title = title.trim()

        val priorityMatch = Regex("\\b(?:high|medium|med|low|normal|none)\\s+priority\\b|\\b(?:urgent|important|p[1-4])\\b", RegexOption.IGNORE_CASE).find(title)
        val priority = when (priorityMatch?.value?.lowercase(Locale.ROOT)?.replace(" priority", "")) {
            "high", "urgent", "important", "p1" -> 1
            "medium", "med", "p2" -> 2
            "low", "p3" -> 3
            "p4" -> 4
            else -> 4
        }
        title = title.replace(Regex("\\b(?:high|medium|med|low|normal|none)\\s+priority\\b|\\b(?:urgent|important|p[1-4])\\b", RegexOption.IGNORE_CASE), " ")

        val recurrenceMatch = Regex(
            "\\b(?:every\\s+(?:single\\s+)?day|daily|every\\s+week|weekly|every\\s+month|monthly|every\\s+weekday)\\b",
            RegexOption.IGNORE_CASE
        ).find(title)
        val recurrence = recurrenceMatch?.value?.lowercase(Locale.ROOT)?.let {
            when {
                "day" in it || it == "daily" -> "daily"
                "weekday" in it -> "weekdays"
                "month" in it -> "monthly"
                else -> "weekly"
            }
        }
        recurrenceMatch?.value?.let { title = title.replace(it, " ", ignoreCase = true) }

        val explicitReminder = Regex("\\b(?:remind me|set (?:a )?reminder|notify me)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clause)
        title = title
            .replace(Regex("^(?:(?:hi|hello|hey|okay|ok|so|also|and|then|please)[,!. ]+)+", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("^(?:i\\s+(?:also\\s+)?|i\\s+)?(?:would\\s+)?(?:need|have|want|should|must|will)\\s+(?:to\\s+)?", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("^(?:i am going to|i'm going to|i have got to|i've got to|i plan to)\\s+", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("^(?:add|create|make|remember(?:\\s+to)?|remind me to|don't forget to|need to|have to)\\s+", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b(buy|get|submit|prepare|finish|complete|study|write|read|make|do|attend|schedule|book|print|pack|bring|organize|clean|review|fix|create|call|send|pay|visit|practice|collect|return|order|cancel|email|renew|drop off|fill out)\\s+(?:a|an|the|my)\\s+", RegexOption.IGNORE_CASE), "$1 ")
            .replace(Regex("^go\\s+(?:and\\s+)?(?=(?:buy|get|pick up|call|send|collect|return|order|bring|drop off)\\b)", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("^(?:is|will be)\\s+(?:my|a|the)\\s+", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("^(?:a|an|the|my)\\s+", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("^on\\s+", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b(?:please )?remind me to\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b(?:by|at|around|before|on|for|due)\\s*$", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', '.', ';', ':', '-', '—')

        // Remove a reason clause after a task only when it was not split by the utterance segmenter.
        title = title.replace(Regex("\\s+(?:due to which|because(?: of which)?|which means)\\b.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:is|will be|scheduled|happening|coming up)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:a|an|the|my)\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
        title = title.replace(
            Regex("^(?:(?:i|we)\\s+)?(?:(?:also|then)\\s+)?(?:need|have|want|should|must|will)\\s+(?:to\\s+)?", RegexOption.IGNORE_CASE),
            ""
        ).replace(Regex("^(?:a|an|the|my)\\s+", RegexOption.IGNORE_CASE), "").trim()
        if (title.length !in 2..120 || !title.any(Char::isLetter)) return null
        title = title.split(' ').joinToString(" ") { word ->
            if (word.length <= 4 && word.all(Char::isUpperCase)) word
            else word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }

        return VoiceTaskAction.Create(
            title = title,
            dueDate = (metadata.date ?: if (metadata.time != null || recurrence != null) today else null)?.toString(),
            dueTime = metadata.time,
            priority = priority,
            recurrenceRule = recurrence,
            reminderEnabled = explicitReminder && metadata.time != null,
            description = dayPart?.let { "Preferred time of day: ${it.lowercase(Locale.ROOT)}" }
        )
    }

    private data class ParsedDateTime(
        val date: LocalDate?,
        val time: String?,
        val dateToken: String?,
        val timeToken: String?
    )

    private fun parseDateAndTime(text: String, today: LocalDate): ParsedDateTime {
        val dateMatch = datePattern.find(text)
        val date = dateMatch?.value?.let { parseDateToken(it, today) }
        val timeMatch = timePattern.find(text)
        val time = timeMatch?.let(::parseTimeMatch)
        return ParsedDateTime(date, time, dateMatch?.value, timeMatch?.value)
    }

    private fun parseDateToken(token: String, today: LocalDate): LocalDate? {
        val normalized = token.lowercase(Locale.ROOT).replace(Regex("\\b(on|the)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        when {
            normalized == "today" -> return today
            normalized == "tomorrow" -> return today.plusDays(1)
            normalized == "day after tomorrow" -> return today.plusDays(2)
            normalized == "next week" -> return today.plusWeeks(1)
            normalized.startsWith("in ") -> return today.plusDays(Regex("\\d+").find(normalized)?.value?.toLongOrNull() ?: return null)
            normalized.startsWith("next week") -> {
                val dayName = normalized.substringAfter("week").replace(Regex("['’]s?"), " ").trim().removePrefix("on ").trim()
                return weekdays[dayName]?.let { today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).with(TemporalAdjusters.nextOrSame(it)) }
            }
            normalized.startsWith("next ") || normalized.startsWith("this ") -> {
                val day = weekdays[normalized.substringAfter(' ').trim()] ?: return null
                val next = today.with(TemporalAdjusters.nextOrSame(day))
                return if (normalized.startsWith("next") && next == today) next.plusWeeks(1) else next
            }
            normalized in weekdays -> return today.with(TemporalAdjusters.nextOrSame(weekdays.getValue(normalized)))
            normalized.matches(Regex("\\d{4}-\\d{1,2}-\\d{1,2}")) -> return runCatching { LocalDate.parse(normalized) }.getOrNull()
            normalized.matches(Regex("\\d{1,2}/\\d{1,2}/\\d{4}")) -> {
                val (day, month, year) = normalized.split('/').map(String::toInt)
                return runCatching { LocalDate.of(year, month, day) }.getOrNull()
            }
            normalized.matches(Regex("\\d{1,2}/\\d{1,2}")) -> {
                val (day, month) = normalized.split('/').map(String::toInt)
                return runCatching { LocalDate.of(today.year, month, day).let { if (it.isBefore(today)) it.plusYears(1) else it } }.getOrNull()
            }
        }

        val monthFirst = Regex("^(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?$").find(normalized)
        val dayFirst = Regex("^(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)(?:\\s+(\\d{4}))?$").find(normalized)
        val match = monthFirst ?: dayFirst
        if (match != null) {
            val monthName = match.groupValues[if (monthFirst != null) 1 else 2].take(3)
            val month = months[monthName]?.value ?: return null
            val day = match.groupValues[if (monthFirst != null) 2 else 1].toIntOrNull() ?: return null
            val year = match.groupValues[3].toIntOrNull() ?: today.year
            return runCatching { LocalDate.of(year, month, day).let { if (match.groupValues[3].isBlank() && it.isBefore(today)) it.plusYears(1) else it } }.getOrNull()
        }
        return null
    }

    private fun parseTimeMatch(match: MatchResult): String? {
        val raw = match.value.lowercase(Locale.ROOT).replace(".", "").trim()
        if (raw.endsWith("noon")) return "12:00"
        if (raw.endsWith("midnight")) return "00:00"
        val clock = Regex("(\\d{1,2})(?::(\\d{2})|\\s+(\\d{2}))?\\s*(am|pm|a|p)?$").find(raw) ?: return null
        var hour = clock.groupValues[1].toIntOrNull() ?: return null
        val minute = (clock.groupValues[2].ifBlank { clock.groupValues[3] }).ifBlank { "00" }.toIntOrNull() ?: return null
        val meridiem = clock.groupValues[4]
        if (meridiem in setOf("p", "pm") && hour in 1..11) hour += 12
        if (meridiem in setOf("a", "am") && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return "%02d:%02d".format(hour, minute)
    }

    private fun splitTasks(raw: String): List<String> {
        var text = raw
            .replace(Regex("a\\.m\\.?", RegexOption.IGNORE_CASE), "am")
            .replace(Regex("p\\.m\\.?", RegexOption.IGNORE_CASE), "pm")
            .replace(Regex("\\b(?:and also|and then|after that|due to which|because(?: of which| of that)?|which means)\\b", RegexOption.IGNORE_CASE), ";")
            .replace(Regex("[.!?;\\n]+"), ";")
        val actionLookahead = actionVerbs.filterNot { it == "go" }.joinToString("|") { Regex.escape(it) }
        text = text.replace(
            Regex("\\bgo\\s+and\\s+(?=(?:$actionLookahead)\\b)", RegexOption.IGNORE_CASE),
            ""
        )
        val starterAlternatives = (listOf(
            "i need", "i also need", "also need", "i have", "i also have", "also have", "i have to", "i have got to", "i should", "i must", "i will", "i would need", "i want to",
            "please add", "add", "create", "remind me to", "don't forget to", "mark", "set", "change",
            "update", "complete", "finish", "reschedule", "move", "push"
        ) + actionVerbs).distinct().joinToString("|") { Regex.escape(it) }
        text = text.replace(
            Regex("\\s+and\\s+(?=(?:$starterAlternatives)\\b)", RegexOption.IGNORE_CASE),
            ";"
        )
        val weekdayNames = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
        text = text.replace(
            Regex("\\band\\s+(?=(?:today|tomorrow|(?:next|this)\\s+(?:$weekdayNames)|$weekdayNames)\\s+(?:i|we)\\s+(?:also\\s+)?(?:need|have|should|must|will|want)\\b)", RegexOption.IGNORE_CASE),
            "; "
        )
        text = text.replace(
            Regex("(?<![iI])\\s+(?=(?:also|then)\\s+(?:(?:i|we)\\s+)?(?:need|have|should|must|will|want|buy|call|submit|finish|prepare|study|attend|complete)\\b)", RegexOption.IGNORE_CASE),
            "; "
        )
        text = text.replace(
            Regex("\\s+(?=(?:i|we)\\s+(?:also\\s+)?(?:need|have|should|must|will|want)\\b)", RegexOption.IGNORE_CASE),
            "; "
        )
        // When two coordinated phrases each carry their own deadline, treat
        // them as separate responsibilities (e.g. "exam tomorrow and a
        // submission on Friday"). Avoid splitting ordinary noun lists.
        text = Regex("\\s+and\\s+(?=(?:a|an|the|my)\\s+[^;.!?]{1,60}\\b(?:today|tomorrow|on\\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|by\\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|next\\s+(?:week|monday|tuesday|wednesday|thursday|friday|saturday|sunday))\\b)", RegexOption.IGNORE_CASE)
            .replace(text, "; ")
        text = text.replace(
            Regex(",\\s*(?=(?:$starterAlternatives)\\b)", RegexOption.IGNORE_CASE),
            ";"
        )
        val pieces = text.split(';').map { it.trim().trim(',', ' ') }.filter(String::isNotBlank)
        val merged = mutableListOf<String>()
        val dateLeadIn = Regex("^(?:today|tomorrow|day after tomorrow|next week(?:\\s+(?:$weekdayNames))?|next\\s+(?:$weekdayNames)|this\\s+(?:$weekdayNames)|$weekdayNames)(?:\\s+(?:morning|afternoon|evening|night))?$", RegexOption.IGNORE_CASE)
        for (piece in pieces) {
            if (merged.isNotEmpty() && dateLeadIn.matches(merged.last()) && Regex("^(?:i|we)\\s+", RegexOption.IGNORE_CASE).containsMatchIn(piece)) {
                merged[merged.lastIndex] = "${merged.last()} $piece"
            } else {
                merged += piece
            }
        }
        return merged
    }

    private fun normalizeRecognizedText(text: String): String {
        var normalized = text
            .replace(Regex("\\b(?:i\\s+)?do\\s+have\\b", RegexOption.IGNORE_CASE), "I have")
            .replace(Regex("\\btomm?or+ow\\b", RegexOption.IGNORE_CASE), "tomorrow")
            .replace(Regex("\\btmrw\\b", RegexOption.IGNORE_CASE), "tomorrow")
            .replace(Regex("\\b(?:wednsday|wednesdy|wensday)\\b", RegexOption.IGNORE_CASE), "Wednesday")
            .replace(Regex("\\bthrusday\\b|\\bthurday\\b", RegexOption.IGNORE_CASE), "Thursday")
            .replace(Regex("\\b(?:medcine|medecine)\\b", RegexOption.IGNORE_CASE), "medicine")
            .replace(Regex("\\btourn(?:ment|ement)\\b", RegexOption.IGNORE_CASE), "tournament")
            .replace(Regex("\\bassesment\\b", RegexOption.IGNORE_CASE), "assessment")
            .replace(Regex("\\bassignement\\b|\\bassigment\\b", RegexOption.IGNORE_CASE), "assignment")
            .replace(Regex("\\bappointmant\\b", RegexOption.IGNORE_CASE), "appointment")

        val weekday = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
        normalized = normalized.replace(
            Regex("(next\\s+week(?:['’]s)?)[.!?,;\\n]+\\s+((?:on\\s+)?(?:$weekday))", RegexOption.IGNORE_CASE),
            "$1 $2"
        )
        normalized = normalized.replace(
            Regex("([\\p{L}\\p{N}][^.!?;\\n]{0,100})[.!?;\\n]+\\s*((?:next\\s+week(?:['’]s)?\\s+(?:on\\s+)?(?:$weekday)|next\\s+(?:$weekday)|this\\s+(?:$weekday)|day\\s+after\\s+tomorrow|tomorrow|today|in\\s+\\d+\\s+days?))(?=$|[.!?;,\\n])", RegexOption.IGNORE_CASE),
            "$1 $2"
        )
        normalized = normalized.replace(
            Regex("(\\d{1,2}(?::\\d{2})?)[.!?,;\\n]+\\s*([ap](?:\\.?m\\.?)?)\\b", RegexOption.IGNORE_CASE),
            "$1 $2"
        )
        return normalized
    }

    private fun isLikelyTaskClause(clause: String, today: LocalDate): Boolean {
        val metadata = parseDateAndTime(clause, today)
        val hasRepeat = Regex("\\b(?:daily|weekly|monthly|every\\s+(?:single\\s+)?day|every\\s+week|every\\s+month|every\\s+weekday)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clause)
        val obligation = Regex("\\b(?:i\\s+(?:also\\s+)?need|i have|i would need|i should|i must|i will|don't forget|remind me)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clause)
        return metadata.date != null || metadata.time != null || hasRepeat || obligation || taskStarter.containsMatchIn(clause.trim())
    }

    private fun normalizeTitle(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("^(?:the|my|a|an|task)\\s+"), "")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val datePattern = Regex(
        "\\b(?:day\\s+after\\s+tomorrow|tomorrow|today|next\\s+week(?:['’]s)?\\s+(?:on\\s+)?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|next\\s+week|next\\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|this\\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|in\\s+\\d+\\s+days?|\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}/\\d{1,2}(?:/\\d{4})?|(?:on\\s+)?(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\s+\\d{1,2}(?:st|nd|rd|th)?(?:,?\\s+\\d{4})?|(?:on\\s+)?\\d{1,2}(?:st|nd|rd|th)?\\s+(?:of\\s+)?(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)(?:\\s+\\d{4})?|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
        RegexOption.IGNORE_CASE
    )
    private val timePattern = Regex(
        "\\b(?:(?:at|by|around|before)\\s+)?\\d{1,2}(?::\\d{2}|\\s+\\d{2})?\\s*(?:a\\.?m\\.?|p\\.?m\\.?|a|p)\\b" +
            "|\\b(?:at|by|around|before)\\s+(?:[01]?\\d|2[0-3])(?::[0-5]\\d)?\\b" +
            "|\\b(?:at\\s+)?(?:noon|midnight)\\b",
        RegexOption.IGNORE_CASE
    )
    private val MATCH_THRESHOLD = 0.75
}
