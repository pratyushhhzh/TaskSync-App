package com.example.tasksync.ui.assistant

import com.example.tasksync.data.local.entity.TaskEntity
import java.time.LocalDate
import java.time.Clock

/** Local-only intent extraction entry point. It never calls a remote model or service. */
object VoiceTaskBrain {
    fun interpret(
        transcript: String,
        existingTasks: List<TaskEntity>,
        today: LocalDate = LocalDate.now()
    ): VoiceInterpretation = VoiceTaskInterpreter.interpret(transcript, existingTasks, today)

    fun interpret(transcript: String, existingTasks: List<TaskEntity>, clock: Clock): VoiceInterpretation =
        VoiceTaskInterpreter.interpret(transcript, existingTasks, clock)
}
