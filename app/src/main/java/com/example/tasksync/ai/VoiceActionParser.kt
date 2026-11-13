package com.example.tasksync.ai

import com.example.tasksync.data.local.entity.TaskEntity
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class VoiceActionType { CREATE, UPDATE, COMPLETE, DELETE, NONE }

data class ProposedVoiceAction(
    val type: VoiceActionType,
    val title: String = "",
    val target: String? = null,
    val targetTaskId: String? = null,
    val whenText: String? = null,
    val dueDate: String? = null,
    val dueTime: String? = null,
    val priority: Int? = null,
    val project: String? = null,
    val details: String? = null,
    val warning: String? = null
)

data class VoiceParseResult(val actions: List<ProposedVoiceAction>, val errors: List<String>)

/** Strict parser for untrusted model output. It never writes to storage. */
object VoiceActionParser {
    private val allowedFields = setOf("action", "target", "title", "when", "priority", "project", "details")

    fun parse(output: String, tasks: List<TaskEntity>, today: LocalDate = LocalDate.now()): VoiceParseResult {
        if (output.isBlank()) return VoiceParseResult(emptyList(), listOf("The model returned an empty response."))
        val actions = mutableListOf<ProposedVoiceAction>()
        val errors = mutableListOf<String>()
        output.split(";;").forEachIndexed { index, raw ->
            val fields = linkedMapOf<String, String>()
            var malformed = false
            raw.split('|').forEach { rawField ->
                val pieces = rawField.trim().split('=', limit = 2)
                if (pieces.size != 2 || pieces[0].trim().lowercase(Locale.ROOT) !in allowedFields) malformed = true
                else {
                    val key = pieces[0].trim().lowercase(Locale.ROOT)
                    if (fields.put(key, pieces[1].trim()) != null) malformed = true
                }
            }
            if (malformed || fields["action"].isNullOrBlank()) {
                errors += "Action ${index + 1} has malformed or unknown fields."
                return@forEachIndexed
            }
            val type = when (fields.getValue("action").lowercase(Locale.ROOT)) {
                "create" -> VoiceActionType.CREATE
                "update" -> VoiceActionType.UPDATE
                "complete" -> VoiceActionType.COMPLETE
                "delete" -> VoiceActionType.DELETE
                "none" -> VoiceActionType.NONE
                else -> null
            }
            if (type == null) {
                errors += "Action ${index + 1} has an unsupported action name."
                return@forEachIndexed
            }
            if (type == VoiceActionType.NONE) {
                if (fields.size != 1) errors += "The none action unexpectedly contains fields." else actions += ProposedVoiceAction(type)
                return@forEachIndexed
            }
            val target = fields["target"]
            val title = fields["title"].orEmpty()
            if (type == VoiceActionType.CREATE && title.isBlank()) {
                errors += "Create action ${index + 1} has no title."
                return@forEachIndexed
            }
            if (type != VoiceActionType.CREATE && target.isNullOrBlank()) {
                errors += "Action ${index + 1} needs a target."
                return@forEachIndexed
            }
            val matches = target?.let { findTargets(it, tasks) }.orEmpty()
            val uniqueTarget = matches.singleOrNull()
            val whenText = fields["when"]
            val date = whenText?.let { resolveDate(it, today) }
            val priority = fields["priority"]?.let(::parsePriority)
            if (fields["priority"] != null && priority == null) {
                errors += "Action ${index + 1} has an unrecognized priority."
                return@forEachIndexed
            }
            val timeWasMentioned = whenText?.let { Regex("\\b\\d{1,2}(:\\d{2})?\\s*(am|pm)?\\b|\\b(hours?|minutes?)\\b").containsMatchIn(it.lowercase(Locale.ROOT)) } == true
            val warning = when {
                target != null && matches.isEmpty() -> "No existing task safely matches ‘$target’. Review or remove this action."
                target != null && matches.size > 1 -> "More than one existing task matches ‘$target’. This action needs a unique target."
                whenText != null && date == null -> "Date/time ‘$whenText’ could not be resolved; the original text is retained."
                whenText != null && date != null && timeWasMentioned && date.second == null -> "Time in ‘$whenText’ could not be resolved; the original text is retained."
                date != null && LocalDate.parse(date.first).isBefore(today) -> "The interpreted date is in the past; review it before applying."
                else -> null
            }
            actions += ProposedVoiceAction(type, title, target, uniqueTarget?.id, whenText, date?.first, date?.second,
                priority, fields["project"], fields["details"], warning)
        }
        if (actions.any { it.type == VoiceActionType.NONE } && actions.size > 1) {
            errors += "The none action cannot be mixed with other proposed actions."
            actions.clear()
        }
        return VoiceParseResult(actions, errors)
    }

    fun resolveTargetId(target: String, tasks: List<TaskEntity>): String? = findTargets(target, tasks).singleOrNull()?.id
    fun resolveWhen(raw: String): Pair<String, String?>? = resolveDate(raw, LocalDate.now())
    fun parsePriorityValue(raw: String): Int? = parsePriority(raw)

    private fun findTargets(target: String, tasks: List<TaskEntity>): List<TaskEntity> {
        val needle = normalize(target)
        if (needle.isBlank()) return emptyList()
        val unfinished = tasks.filter { it.status != "completed" }
        val exact = unfinished.filter { normalize(it.title) == needle }
        if (exact.isNotEmpty()) return exact
        return unfinished.filter { val title = normalize(it.title); title.contains(needle) || needle.contains(title) }
    }

    private fun normalize(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

    private fun parsePriority(raw: String): Int? = when (raw.lowercase(Locale.ROOT)) {
        "high", "urgent", "1" -> 1
        "medium", "normal", "2" -> 2
        "low", "3" -> 3
        "none", "no priority", "4" -> 4
        else -> null
    }

    private fun resolveDate(raw: String, today: LocalDate): Pair<String, String?>? {
        val text = raw.lowercase(Locale.ROOT).trim()
        val date = when {
            Regex("\\btoday\\b").containsMatchIn(text) -> today
            Regex("\\btomorrow\\b").containsMatchIn(text) -> today.plusDays(1)
            Regex("\\bnext\\s+monday\\b").containsMatchIn(text) -> nextDay(today, java.time.DayOfWeek.MONDAY)
            Regex("\\bnext\\s+tuesday\\b").containsMatchIn(text) -> nextDay(today, java.time.DayOfWeek.TUESDAY)
            Regex("\\bnext\\s+wednesday\\b").containsMatchIn(text) -> nextDay(today, java.time.DayOfWeek.WEDNESDAY)
            Regex("\\bnext\\s+thursday\\b").containsMatchIn(text) -> nextDay(today, java.time.DayOfWeek.THURSDAY)
            Regex("\\bnext\\s+friday\\b").containsMatchIn(text) -> nextDay(today, java.time.DayOfWeek.FRIDAY)
            Regex("\\bnext\\s+saturday\\b").containsMatchIn(text) -> nextDay(today, java.time.DayOfWeek.SATURDAY)
            Regex("\\bnext\\s+sunday\\b").containsMatchIn(text) -> nextDay(today, java.time.DayOfWeek.SUNDAY)
            Regex("\\bin\\s+(\\d+)\\s+days?\\b").find(text) != null -> today.plusDays(Regex("\\bin\\s+(\\d+)\\s+days?\\b").find(text)!!.groupValues[1].toLong())
            else -> runCatching { LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
        } ?: return null
        val timeMatch = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b").find(text)
        val time = timeMatch?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let null
            val minute = match.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return@let null
            if (hour !in 1..12 || minute !in 0..59) return@let null
            val hour24 = when {
                match.groupValues[3] == "pm" && hour != 12 -> hour + 12
                match.groupValues[3] == "am" && hour == 12 -> 0
                else -> hour
            }
            LocalTime.of(hour24, minute).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        }
        return date.toString() to time
    }

    private fun nextDay(today: LocalDate, day: java.time.DayOfWeek) = today.with(java.time.temporal.TemporalAdjusters.next(day))
}
