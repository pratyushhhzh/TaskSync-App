package com.example.tasksync.ai

import android.content.Context
import com.example.tasksync.data.local.entity.TaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import java.util.Locale

data class VoiceInterpretation(val rawModelOutput: String, val parsed: VoiceParseResult)

object TaskSyncInterpreter {
    suspend fun interpret(context: Context, transcript: String, tasks: List<TaskEntity>): Result<VoiceInterpretation> = withContext(Dispatchers.IO) {
        runCatching {
            require(transcript.isNotBlank()) { "Please enter a voice transcript first." }
            val candidates = relevantRecentTasks(transcript, tasks).take(5)
            val existing = if (candidates.isEmpty()) "none" else candidates.joinToString(" | ") { it.title }
            val modelInput = "existing: $existing || said: ${transcript.trim()}"
            val output = withTimeout(INFERENCE_TIMEOUT_MS) {
                TaskSyncModel.get(context).generate(modelInput).getOrThrow()
            }
            val parsed = VoiceActionParser.parse(output, tasks, LocalDate.now())
            require(parsed.actions.isNotEmpty() || parsed.errors.isNotEmpty()) { "The model did not propose any actions." }
            VoiceInterpretation(output, parsed)
        }
    }

    private fun relevantRecentTasks(transcript: String, tasks: List<TaskEntity>): List<TaskEntity> {
        val words = normalize(transcript).split(' ').filter { it.length > 2 }.toSet()
        return tasks.asSequence().filter { it.status != "completed" }
            .map { task -> task to normalize(task.title).split(' ').toSet().intersect(words).size }
            .sortedWith(compareByDescending<Pair<TaskEntity, Int>> { it.second }.thenByDescending { it.first.createdAt })
            .map { it.first }.toList()
    }

    private fun normalize(text: String) = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private const val INFERENCE_TIMEOUT_MS = 120_000L
}
