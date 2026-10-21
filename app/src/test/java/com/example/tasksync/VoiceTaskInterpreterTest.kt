package com.example.tasksync

import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.ui.assistant.VoiceInterpretation
import com.example.tasksync.ui.assistant.VoiceTaskAction
import com.example.tasksync.ui.assistant.VoiceTaskInterpreter
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTaskInterpreterTest {
    private val today = LocalDate.of(2026, 10, 21)

    @Test
    fun createsMultipleTasksWithDatesFromOneUtterance() {
        val result = VoiceTaskInterpreter.interpret(
            "I need to buy boots tomorrow and call mom next Friday",
            emptyList(),
            today
        )

        val actions = (result as VoiceInterpretation.Actions).actions
        assertEquals(2, actions.size)
        assertEquals("Buy Boots", (actions[0] as VoiceTaskAction.Create).title)
        assertEquals("2026-10-22", (actions[0] as VoiceTaskAction.Create).dueDate)
        assertEquals("Call Mom", (actions[1] as VoiceTaskAction.Create).title)
        assertEquals("2026-10-23", (actions[1] as VoiceTaskAction.Create).dueDate)
    }

    @Test
    fun extractsThreeNaturalSpeechTasksAndKeepsDayPartOutOfTitle() {
        val result = VoiceTaskInterpreter.interpret(
            "I have my environmental science exam next Monday. I have a football match today. I also need to complete a website by Saturday morning.",
            emptyList(),
            today
        )
        val actions = (result as VoiceInterpretation.Actions).actions.map { it as VoiceTaskAction.Create }
        assertEquals(3, actions.size)
        assertEquals("Environmental Science Exam", actions[0].title)
        assertEquals("2026-10-26", actions[0].dueDate)
        assertEquals("Football Match", actions[1].title)
        assertEquals("2026-10-21", actions[1].dueDate)
        assertEquals("Complete Website", actions[2].title)
        assertEquals("2026-10-24", actions[2].dueDate)
        assertEquals("Preferred time of day: morning", actions[2].description)
        assertEquals(null, actions[2].dueTime)
    }

    @Test
    fun splitsTasksWithoutPunctuationAndAssociatesDatesAndTime() {
        val result = VoiceTaskInterpreter.interpret(
            "Tomorrow I have a DBMS exam and Friday I need to submit my AI project also I have football practice next Wednesday at 7:30 PM.",
            emptyList(),
            today
        )

        val actions = (result as VoiceInterpretation.Actions).actions.map { it as VoiceTaskAction.Create }
        assertEquals(3, actions.size)
        assertEquals("DBMS Exam", actions[0].title)
        assertEquals("2026-10-22", actions[0].dueDate)
        assertEquals("Submit AI Project", actions[1].title)
        assertEquals("2026-10-23", actions[1].dueDate)
        assertEquals("Football Practice", actions[2].title)
        assertEquals("2026-10-28", actions[2].dueDate)
        assertEquals("19:30", actions[2].dueTime)
    }

    @Test
    fun splitsActionListsIntoIndependentResponsibilities() {
        val result = VoiceTaskInterpreter.interpret(
            "I need to study DBMS, finish the report, and prepare the presentation.",
            emptyList(),
            today
        )

        val actions = (result as VoiceInterpretation.Actions).actions.map { it as VoiceTaskAction.Create }
        assertEquals(listOf("Study DBMS", "Finish Report", "Prepare Presentation"), actions.map { it.title })
    }

    @Test
    fun postponedExistingTaskIsUpdated() {
        val task = task("Environmental Science Exam", dueDate = "2026-10-26")
        val result = VoiceTaskInterpreter.interpret(
            "My environmental science exam has been postponed to next Tuesday",
            listOf(task),
            today
        )
        val action = (result as VoiceInterpretation.Actions).actions.single() as VoiceTaskAction.Update
        assertEquals(task.id, action.before.id)
        assertEquals("2026-10-27", action.after.dueDate)
    }

    @Test
    fun unclearVagueRequestDoesNotBecomeInventedTask() {
        val result = VoiceTaskInterpreter.interpret("I need to do that thing sometime", emptyList(), today)
        assertTrue(result is VoiceInterpretation.NotUnderstood)
    }

    @Test
    fun conversationalRescheduleUpdatesExistingTaskInsteadOfCreating() {
        val task = task("Have a football match")
        val result = VoiceTaskInterpreter.interpret(
            "There was a change in plan the football match which was planned will now be at 7:00 pm",
            listOf(task),
            today
        )

        val action = (result as VoiceInterpretation.Actions).actions.single() as VoiceTaskAction.Update
        assertEquals(task.id, action.before.id)
        assertEquals(task.title, action.after.title)
        assertEquals("19:00", action.after.dueTime)
    }

    @Test
    fun ambiguousTaskReferenceAsksInsteadOfChoosing() {
        val tasks = listOf(task("Football match"), task("Football tournament"))
        val result = VoiceTaskInterpreter.interpret("Reschedule football to 7 pm", tasks, today)

        assertTrue(result is VoiceInterpretation.NeedsClarification)
    }

    @Test
    fun supportsDeleteReopenAndChecklistActions() {
        val open = task("Buy medicine")
        val completed = task("Submit report", status = "completed")

        val delete = VoiceTaskInterpreter.interpret("Delete Buy medicine", listOf(open), today)
        assertTrue((delete as VoiceInterpretation.Actions).actions.single() is VoiceTaskAction.Delete)

        val reopen = VoiceTaskInterpreter.interpret("Reopen Submit report", listOf(completed), today)
        assertTrue((reopen as VoiceInterpretation.Actions).actions.single() is VoiceTaskAction.Reopen)

        val subtask = VoiceTaskInterpreter.interpret("Add a subtask bring boots to Have a football match", listOf(task("Have a football match")), today)
        val action = (subtask as VoiceInterpretation.Actions).actions.single() as VoiceTaskAction.AddSubtask
        assertEquals("bring boots", action.title)
    }

    @Test
    fun answersTaskListQueryWithoutProducingMutation() {
        val task = task("Buy medicine", dueDate = "2026-10-22")
        val result = VoiceTaskInterpreter.interpret("What tasks are due tomorrow?", listOf(task), today)

        assertTrue(result is VoiceInterpretation.Reply)
        assertTrue((result as VoiceInterpretation.Reply).message.contains("Buy medicine"))
    }

    private fun task(title: String, status: String = "inbox", dueDate: String? = null) = TaskEntity(
        id = "id_$title",
        userId = "local_user",
        title = title,
        status = status,
        dueDate = dueDate
    )
}
