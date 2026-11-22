package com.example.tasksync.data.interpretation

import com.example.tasksync.data.local.entity.TaskEntity
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max

enum class MatchQuality { EXACT, CONFIDENT, AMBIGUOUS, LOW_CONFIDENCE, NONE }

data class TaskMatch(val task: TaskEntity, val score: Double)

data class TaskMatchResult(val quality: MatchQuality, val matches: List<TaskMatch>) {
    val automaticMatch: TaskEntity?
        get() = matches.singleOrNull()?.task?.takeIf { quality == MatchQuality.EXACT || quality == MatchQuality.CONFIDENT }
}

class TaskProposalMatcher {
    fun match(target: String, tasks: List<TaskEntity>): TaskMatchResult {
        val normalized = normalize(target)
        if (normalized.isBlank()) return TaskMatchResult(MatchQuality.NONE, emptyList())
        val queryTokens = tokens(normalized).toSet()
        val documentFrequency = mutableMapOf<String, Int>()
        tasks.forEach { task ->
            tokens(normalize("${task.title} ${task.description.orEmpty()} ${task.projectId.orEmpty()}" )).toSet()
                .forEach { token -> documentFrequency[token] = (documentFrequency[token] ?: 0) + 1 }
        }
        val scored = tasks.mapNotNull { task ->
            val title = normalize(task.title)
            val description = normalize(task.description.orEmpty())
            val exact = normalized == title || normalized == description
            val titleTokens = tokens(title).toSet()
            val descriptionTokens = tokens(description).toSet()
            val titleScore = lexicalMeaningScore(queryTokens, titleTokens, documentFrequency, tasks.size)
            val descriptionScore = if (description.isNotBlank()) lexicalMeaningScore(queryTokens, descriptionTokens, documentFrequency, tasks.size) else 0.0
            val combinedScore = max(titleScore * 0.62, descriptionScore * 0.92)
            val typoScore = max(tokenTypoScore(queryTokens, titleTokens), tokenTypoScore(queryTokens, descriptionTokens))
            val phraseScore = max(similarity(normalized, title), if (description.isNotBlank()) similarity(normalized, description) else 0.0)
            val score = when {
                normalized == title -> 1.0
                normalized == description -> 0.98
                else -> max(phraseScore * 0.72, max(typoScore * 0.70, max(titleScore * 0.90, max(descriptionScore, combinedScore))))
            }
            // Retrieval is deliberately broader than automatic confidence: one-edit
            // token neighbors remain visible for review even when their score is weak.
            if (score >= RETRIEVAL_THRESHOLD || typoScore > 0.0 || exact || queryTokens.any { it in titleTokens || it in descriptionTokens }) TaskMatch(task, score) else null
        }.sortedByDescending(TaskMatch::score)
        if (scored.isEmpty()) return TaskMatchResult(MatchQuality.NONE, emptyList())
        val high = scored.filter { it.score >= STRONG_THRESHOLD }
        if (scored.first().score == 1.0 || scored.first().score == 0.98) {
            val exacts = scored.filter { it.score >= 0.95 && scored.first().score - it.score < 0.08 }
            return TaskMatchResult(if (exacts.size == 1) MatchQuality.EXACT else MatchQuality.AMBIGUOUS, exacts)
        }
        val plausible = scored.filter { it.score >= PLAUSIBLE_THRESHOLD }
        if (plausible.size > 1) {
            // Strong evidence for more than one existing task is ambiguity even if
            // the ranking scores differ slightly; only the user may disambiguate.
            return TaskMatchResult(MatchQuality.AMBIGUOUS, plausible.take(MAX_CANDIDATES))
        }
        if (scored.first().score >= STRONG_THRESHOLD) return TaskMatchResult(MatchQuality.CONFIDENT, listOf(scored.first()))
        return if (scored.first().score >= PLAUSIBLE_THRESHOLD) {
            TaskMatchResult(MatchQuality.LOW_CONFIDENCE, scored.take(MAX_CANDIDATES))
        } else TaskMatchResult(MatchQuality.NONE, emptyList())
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun tokens(value: String): List<String> {
        var normalized = normalize(value)
        PHRASE_ALIASES.forEach { (phrase, canonical) ->
            // Punctuation is already normalized to spaces, so match phrase tokens
            // directly rather than relying on regex word-boundary semantics.
            normalized = normalized.replace(phrase, " $canonical ")
        }
        return normalized.split(' ').asSequence().filter { it.isNotBlank() }.map { token ->
            val canonical = TOKEN_ALIASES[token] ?: token
            if ('_' in canonical) canonical else stem(canonical) ?: canonical
        }.filterNot { it in STOP_WORDS || it.length == 1 }.toList()
    }

    private fun lexicalMeaningScore(
        query: Set<String>,
        candidate: Set<String>,
        documentFrequency: Map<String, Int>,
        documentCount: Int,
    ): Double {
        if (query.isEmpty() || candidate.isEmpty()) return 0.0
        val weights = query.associateWith { token -> 1.0 + ln((documentCount + 1.0) / ((documentFrequency[token] ?: 0) + 1.0)) }
        val total = weights.values.sum().coerceAtLeast(1.0)
        val matched = query.intersect(candidate).sumOf { weights[it] ?: 0.0 }
        val coverage = matched / total
        val precision = matched / candidate.sumOf { weights[it] ?: 1.0 }.coerceAtLeast(1.0)
        return coverage * 0.78 + precision * 0.22
    }

    private fun stem(token: String): String? = when {
        token.length > 5 && token.endsWith("ies") -> token.dropLast(3) + "y"
        token.length > 5 && token.endsWith("ing") -> token.dropLast(3).removeSuffix(token.lastOrNull()?.toString() ?: "")
        token.length > 4 && token.endsWith("ed") -> token.dropLast(2)
        token.length > 4 && token.endsWith("s") && !token.endsWith("ss") -> token.dropLast(1)
        else -> null
    }

    private fun tokenTypoScore(query: Set<String>, candidate: Set<String>): Double {
        if (query.isEmpty() || candidate.isEmpty()) return 0.0
        val matched = query.count { queryToken ->
            candidate.any { candidateToken ->
                queryToken.length >= 4 && candidateToken.length >= 4 &&
                    levenshtein(queryToken, candidateToken) <= max(1, max(queryToken.length, candidateToken.length) / 5)
            }
        }
        return matched.toDouble() / query.size
    }

    private fun similarity(left: String, right: String): Double {
        if (left.isBlank() || right.isBlank()) return 0.0
        val edit = 1.0 - levenshtein(left, right).toDouble() / max(left.length, right.length)
        val a = left.split(' ').toSet()
        val b = right.split(' ').toSet()
        val overlap = a.intersect(b).size.toDouble() / a.union(b).size
        return max(edit, overlap)
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }

    private companion object {
        const val STRONG_THRESHOLD = 0.82
        const val PLAUSIBLE_THRESHOLD = 0.26
        const val RETRIEVAL_THRESHOLD = 0.20
        const val MAX_CANDIDATES = 5
        // Generic artifact/task-kind labels help describe a record, but do not
        // distinguish its subject. Remove them symmetrically from queries and
        // stored fields; meaningful topic/modifier words remain in the score.
        val STOP_WORDS = setOf(
            "my", "the", "a", "an", "to", "for", "of", "on", "by", "please", "me", "i", "need", "want",
            "task", "it", "update", "finish", "work", "assignment", "homework", "coursework", "notes", "note",
            "deadline", "due", "document", "paper", "worksheet", "lab", "project",
        )
        val TOKEN_ALIASES = mapOf(
            "dsa" to "dsa", "dbms" to "dbms", "os" to "operating_systems", "cn" to "computer_networks", "oop" to "object_programming",
            "pay" to "payment", "settle" to "payment", "bill" to "payment",
            "buy" to "purchase", "get" to "purchase", "purchase" to "purchase",
            "clean" to "clean", "tidy" to "clean",
            "appointment" to "appointment", "visit" to "appointment", "dentist" to "appointment",
            "phone" to "call", "ring" to "call",
            "complete" to "finish", "submit" to "finish",
            "move" to "reschedule", "change" to "reschedule", "shift" to "reschedule",
            "homework" to "assignment", "coursework" to "assignment",
            "electricity" to "power", "vegetable" to "grocery", "vegetables" to "grocery", "groceries" to "grocery",
            "bedroom" to "room", "dental" to "dentist", "visit" to "appointment", "meeting" to "appointment",
            "exercise" to "workout", "train" to "workout", "training" to "workout",
        )
        val PHRASE_ALIASES = mapOf(
            "data structures and algorithms" to "dsa", "data structures algorithms" to "dsa", "data structures" to "dsa",
            "database management systems" to "dbms", "database management system" to "dbms", "operating systems" to "operating_systems",
            "computer networks" to "computer_networks", "object oriented programming" to "object_programming",
            "object oriented" to "object_programming",
        )
    }
}
