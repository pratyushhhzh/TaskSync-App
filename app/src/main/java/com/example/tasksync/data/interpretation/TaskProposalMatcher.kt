package com.example.tasksync.data.interpretation

import com.example.tasksync.data.local.entity.TaskEntity
import java.util.Locale
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
        val scored = tasks.mapNotNull { task ->
            val title = normalize(task.title)
            val description = normalize(task.description.orEmpty())
            val exact = normalized == title || normalized == description
            val score = when {
                normalized == title -> 1.0
                normalized == description -> 0.98
                else -> max(similarity(normalized, title), if (description.isNotBlank()) similarity(normalized, description) else 0.0)
            }
            if (score >= 0.48 || exact) TaskMatch(task, score) else null
        }.sortedByDescending(TaskMatch::score)
        if (scored.isEmpty()) return TaskMatchResult(MatchQuality.NONE, emptyList())
        val high = scored.filter { it.score >= 0.88 }
        if (scored.first().score == 1.0 || scored.first().score == 0.98) {
            val exacts = scored.filter { it.score >= 0.95 && scored.first().score - it.score < 0.08 }
            return TaskMatchResult(if (exacts.size == 1) MatchQuality.EXACT else MatchQuality.AMBIGUOUS, exacts)
        }
        if (high.size > 1 && high[0].score - high[1].score < 0.08) {
            return TaskMatchResult(MatchQuality.AMBIGUOUS, high.take(5))
        }
        if (scored.first().score >= 0.88) return TaskMatchResult(MatchQuality.CONFIDENT, listOf(scored.first()))
        return TaskMatchResult(MatchQuality.LOW_CONFIDENCE, scored.take(5))
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

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
}
