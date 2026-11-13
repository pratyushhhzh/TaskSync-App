package com.example.tasksync.ui.assistant

/** Classifies a transcript clause before field extraction and task resolution. */
internal object VoiceIntentClassifier {
    enum class Intent { COMPLETE, REOPEN, DELETE, ADD_SUBTASK, UPDATE, CREATE, UNKNOWN }

    fun classify(clause: String): Intent {
        val text = clause.trim().trimEnd('.', '!', '?')
        return when {
            Regex("^(?:please\\s+)?(?:mark|set|complete|finish|i\\s+finished|i\\s+completed|i\\s+am\\s+done|done\\s+with)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
                Regex("\\b(?:is|was)\\s+(?:complete|completed|done)$", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Intent.COMPLETE
            Regex("^(?:please\\s+)?(?:add|remove|clear|change|update|set)\\s+(?:the\\s+)?(?:note|description|details?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Intent.UPDATE
            Regex("^(?:please\\s+)?(?:reopen|uncomplete|mark\\s+.+\\s+as\\s+incomplete|mark\\s+.+\\s+not\\s+done)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Intent.REOPEN
            Regex("^(?:please\\s+)?(?:delete|remove)\\s+(?:the\\s+)?(?:task\\s+)?", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Intent.DELETE
            Regex("^(?:please\\s+)?(?:add|create)\\s+(?:a\\s+)?(?:subtask|checklist\\s+item)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Intent.ADD_SUBTASK
            Regex("^(?:please\\s+)?(?:set|make|change|update|move|reschedule|push|postpone|defer|repeat|add|remove|clear)\\b|\\b(?:will now be|is now scheduled for|has been (?:moved|changed|rescheduled|postponed|shifted)|change in plan|change of plan|rescheduled|postponed|pushed back|is changed to|was changed to|has moved to)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Intent.UPDATE
            Regex("^(?:please\\s+)?(?:${VoiceTaskIntentPhrases.taskStarters.joinToString("|") { Regex.escape(it) }})\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Intent.CREATE
            else -> Intent.UNKNOWN
        }
    }
}

internal object VoiceTaskIntentPhrases {
    val taskStarters = listOf(
        "i need", "i also need", "i have", "i also have", "i have to", "i have got to", "i should", "i must", "i will", "i would need", "i want to",
        "please add", "add", "create", "remind me to", "don't forget to", "buy", "get", "pick up", "call", "send", "finish", "prepare", "submit", "review", "meet",
        "pay", "study", "write", "read", "clean", "visit", "practice", "schedule", "book", "email", "walk", "take", "work on", "organize", "start", "go", "fix", "do",
        "attend", "make", "renew", "collect", "return", "order", "cancel", "print", "pack", "bring", "drop off", "fill out"
    )
}
