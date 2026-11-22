package com.example.tasksync.data.interpretation

/** Cleans only clear conversational framing and spoken schedule clauses from ADD titles. */
object TaskTitleNormalizer {
    fun normalize(title: String, sourceText: String, datePhrase: String?, timePhrase: String?): String {
        var value = title.trim()
        value = removeSpokenPhrase(value, timePhrase)
        value = removeSpokenPhrase(value, datePhrase)
        value = stripRecognizedTrailingSchedule(value)

        val sourceHaveIsFraming = Regex("^\\s*i\\s+have\\s+(?:to|a|an|the)\\b", RegexOption.IGNORE_CASE).containsMatchIn(sourceText)
        val actionFiller = Regex("^\\s*(?:i\\s+)?(?:need\\s+to|have\\s+to)\\s+", RegexOption.IGNORE_CASE)
        val requestFiller = Regex("^\\s*(?:please\\s+)?(?:remind\\s+me\\s+to|add\\s+(?:a\\s+)?task\\s+to|create\\s+(?:a\\s+)?task\\s+to)\\s+", RegexOption.IGNORE_CASE)
        val haveFiller = Regex("^\\s*(?:i\\s+)?have\\s+(?:(?:a|an|the)\\s+)?", RegexOption.IGNORE_CASE)
        value = when {
            requestFiller.containsMatchIn(value) -> requestFiller.replaceFirst(value, "")
            actionFiller.containsMatchIn(value) -> actionFiller.replaceFirst(value, "")
            sourceHaveIsFraming && Regex("^\\s*have\\b", RegexOption.IGNORE_CASE).containsMatchIn(value) -> haveFiller.replaceFirst(value, "")
            Regex("^\\s*i\\s+have\\s+(?:a|an|the)\\s+", RegexOption.IGNORE_CASE).containsMatchIn(value) -> haveFiller.replaceFirst(value, "")
            else -> value
        }
        return value.trim().trimEnd('.', ',', ';', ':', '!', '?').replace(Regex("\\s+"), " ")
    }

    private fun removeSpokenPhrase(title: String, phrase: String?): String {
        if (phrase.isNullOrBlank()) return title
        val escaped = Regex.escape(phrase.trim().trimEnd('.', ',', ';', ':'))
        val exactAtEnd = Regex("(?i)\\s+(?:(?:on|by|at|for)\\s+)?$escaped\\s*[.,;:!?]*$")
        return exactAtEnd.replace(title, "")
    }

    private fun stripRecognizedTrailingSchedule(title: String): String {
        val weekday = "(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)"
        val time = "(?:\\d{1,2}(?::\\d{2})?\\s*(?:a\\.?m\\.?|p\\.?m\\.?)|\\d{1,2}:\\d{2}|noon|midnight)"
        val date = "(?:day after tomorrow|tomorrow|today|tonight|(?:this|next)\\s+$weekday|$weekday\\s+next week|\\d{1,2}\\s+[a-z]+(?:\\s+\\d{4})?|[a-z]+\\s+\\d{1,2}(?:,?\\s+\\d{4})?|\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|\\d{1,2}[-/.]\\d{1,2}(?:[-/.]\\d{4})?)"
        var value = title
        value = value.replace(Regex("(?i)\\s+(?:by|at)\\s+$time(?:\\s+(?:on\\s+)?$date)?[.,;:!?]*$"), "")
        value = value.replace(Regex("(?i)\\s+(?:(?:on|by)\\s+)?$date[.,;:!?]*$"), "")
        return value.trimEnd()
    }
}
