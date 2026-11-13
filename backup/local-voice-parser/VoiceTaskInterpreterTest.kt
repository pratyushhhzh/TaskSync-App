package com.example.tasksync

import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.ui.assistant.VoiceInterpretation
import com.example.tasksync.ui.assistant.VoiceTaskInterpreter
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTaskInterpreterTest {
    private val emptyTasks = emptyList<TaskEntity>()

    @Test
    fun extractsConciseTitleFromOrdinaryTaskStatement() {
        val proposal = proposal("Hi, I need to buy some football boots tomorrow")
        assertEquals("Buy Some Football Boots", proposal.title)
        assertEquals("Buy Some Football Boots", proposal.action.title)
        assertEquals("2026-10-22", proposal.dueDate.toString())
    }

    @Test
    fun missingDateTimeAndPriorityRemainUnspecified() {
        val proposal = proposal("Please submit my science report")
        assertEquals("Submit Science Report", proposal.title)
        assertNull(proposal.dueDate)
        assertNull(proposal.dueTime)
        assertNull(proposal.priority)
        assertNull(proposal.action.dueDate)
        assertNull(proposal.action.dueTime)
        assertEquals(4, proposal.action.priority) // Existing TaskEntity default means “no priority”.
    }

    @Test
    fun extractsExplicitDateAndTime() {
        val proposal = proposal("I have a football tournament on November 12 at 9:30 pm")
        assertEquals("Football Tournament", proposal.title)
        assertEquals("2026-11-12", proposal.dueDate.toString())
        assertEquals("21:30", proposal.dueTime)
    }

    @Test
    fun preservesTheFullSpokenClauseAsTaskDetails() {
        val spokenClause = "I have a football tournament on November 12 at 9:30 pm because the team needs everyone early"
        val proposal = proposal(spokenClause)

        assertEquals("Football Tournament", proposal.title)
        assertEquals(spokenClause, proposal.action.description)
    }

    @Test
    fun preservesTheWholeInstructionOnEverySplitTask() {
        val instruction = "I need to call the dentist tomorrow, and also pick up my prescription because it is running low"
        val proposals = proposals(instruction)

        assertEquals(2, proposals.size)
        assertTrue(proposals.all { it.action.description == instruction })
    }

    @Test
    fun extractsExplicitPriorityWithoutInventingWhenAbsent() {
        val urgent = proposal("Finish the project urgently, high priority")
        assertEquals(1, urgent.priority)
        val low = proposal("Read the book with low priority")
        assertEquals(3, low.priority)
        assertNull(proposal("Read the book").priority)
    }

    @Test
    fun resolvesRelativeDatesAgainstInjectedClockZone() {
        val clock = Clock.fixed(Instant.parse("2026-10-21T20:30:00Z"), ZoneId.of("Asia/Kolkata"))
        val tomorrow = proposal("Buy medicine tomorrow", clock)
        assertEquals("2026-10-23", tomorrow.dueDate.toString())
        val weekday = proposal("I have a quiz test next week's Wednesday", clock)
        assertEquals("2026-10-28", weekday.dueDate.toString())
    }

    @Test
    fun sameInstantUsesInjectedLocalTimezoneForRelativeDate() {
        val instant = Instant.parse("2026-10-21T20:30:00Z")
        val india = proposal("Call mom tomorrow", Clock.fixed(instant, ZoneId.of("Asia/Kolkata")))
        val losAngeles = proposal("Call mom tomorrow", Clock.fixed(instant, ZoneId.of("America/Los_Angeles")))
        assertEquals("2026-10-23", india.dueDate.toString())
        assertEquals("2026-10-22", losAngeles.dueDate.toString())
    }

    @Test
    fun extractsMultipleTasksWithTheirOwnRelativeDeadlines() {
        val proposals = proposals(
            "Tomorrow I have my DBMS exam, the day after tomorrow I need to submit my project, and next Wednesday at 7:30 PM I have football."
        )
        assertEquals(listOf("DBMS Exam", "Submit Project", "Football"), proposals.map { it.title })
        assertEquals(listOf("2026-10-22", "2026-10-23", "2026-10-28"), proposals.map { it.dueDate.toString() })
        assertEquals(listOf(null, null, "19:30"), proposals.map { it.dueTime })
    }

    @Test
    fun extractsSeparateDatesTimesAndPrioritiesInOneTranscript() {
        val proposals = proposals(
            "Tomorrow I have an exam P1, and Friday at 9:30 am I need to submit the report P2"
        )
        assertEquals(listOf("2026-10-22", "2026-10-23"), proposals.map { it.dueDate.toString() })
        assertEquals(listOf(null, "09:30"), proposals.map { it.dueTime })
        assertEquals(listOf(1, 2), proposals.map { it.priority })
    }

    @Test
    fun doesNotSplitTaskTitleAtOrdinaryAndConjunction() {
        val proposal = proposal("I need to plan and organize a charity event next Friday")
        assertEquals("Plan And Organize Charity Event", proposal.title)
        assertEquals("2026-10-23", proposal.dueDate.toString())
    }

    @Test
    fun handlesFillerAndImperfectPunctuation() {
        val proposals = proposals(
            "Um... tomorrow I have my DBMS exam, uh, the day after tomorrow I need to submit my project"
        )
        assertEquals(listOf("DBMS Exam", "Submit Project"), proposals.map { it.title })
        assertEquals(listOf("2026-10-22", "2026-10-23"), proposals.map { it.dueDate.toString() })
    }

    @Test
    fun exactDeviceTranscriptKeepsThreeTasksAndFlagsUnclearEnglishExamDate() {
        val result = VoiceTaskInterpreter.interpret(
            "I have a football tournament How long that I need you to remind me for my medication at 9:00 p.m. And from next week I have my English I have my English exam from the next week's Monday",
            emptyTasks,
            Clock.fixed(Instant.parse("2026-10-23T04:00:00Z"), ZoneId.of("Asia/Kolkata"))
        )
        assertTrue("Expected reviewable proposals, got $result", result is VoiceInterpretation.Proposals)
        val proposals = (result as VoiceInterpretation.Proposals).proposals
        assertEquals(listOf("Football Tournament", "Medication", "English Exam"), proposals.map { it.title })
        assertNull(proposals[0].dueDate)
        assertEquals("21:00", proposals[1].dueTime)
        assertNull(proposals[1].dueDate)
        assertTrue(proposals[1].action.reminderEnabled)
        assertNull(proposals[2].dueDate)
        assertTrue(proposals[2].clarification?.contains("date phrase") == true)
    }

    @Test
    fun medicationReminderDoesNotInventDateAndHasConciseTitle() {
        val proposal = proposal("I need you to remind me for my medication at 9:00 p.m.")
        assertEquals("Medication", proposal.title)
        assertNull(proposal.dueDate)
        assertEquals("21:00", proposal.dueTime)
        assertTrue(proposal.action.reminderEnabled)
    }

    @Test
    fun repeatedSelfCorrectionDoesNotCreateDuplicateTasks() {
        val proposals = proposals("I have my English I have my English exam next Monday")
        assertEquals(listOf("English Exam"), proposals.map { it.title })
        assertEquals("2026-10-26", proposals.single().dueDate.toString())
    }

    @Test
    fun removesDanglingConversationalFillerButKeepsMeaningfulHowLongTitle() {
        val proposals = proposals("I have a football tournament how long that, and I need to submit report")
        assertEquals("Football Tournament", proposals.first().title)
        assertEquals("How Long Is The Football Game", proposal("How long is the football game tomorrow").title)
    }

    @Test
    fun fromNextWeekWeekdayIsMarkedUnclearInsteadOfUsedAsDeadline() {
        val proposal = proposal("I have my English exam from next week and from next week's Monday")
        assertEquals("English Exam", proposal.title)
        assertNull(proposal.dueDate)
        assertTrue(proposal.clarification?.contains("date phrase") == true)
    }

    @Test
    fun ambiguousOrUnassociatedDateRequiresClarification() {
        val result = VoiceTaskInterpreter.interpret("Tomorrow and I have something", emptyTasks, fixedClock())
        assertTrue(result is VoiceInterpretation.NeedsClarification || result is VoiceInterpretation.NotUnderstood)
    }

    @Test
    fun unsupportedOrVagueStatementIsNotUnderstood() {
        val result = VoiceTaskInterpreter.interpret("I need to do that thing sometime", emptyTasks, fixedClock())
        assertTrue(result is VoiceInterpretation.NotUnderstood)
    }

    @Test
    fun interpretationReturnsProposalWithoutMutatingProvidedTasks() {
        val existing = TaskEntity(id = "existing", userId = "local_user", title = "Read book")
        val result = VoiceTaskInterpreter.interpret("Submit the report tomorrow", listOf(existing), fixedClock())
        assertTrue(result is VoiceInterpretation.Proposal)
        assertEquals("Read book", existing.title)
        assertNull(existing.dueDate)
    }

    private fun proposal(text: String, clock: Clock = fixedClock()): com.example.tasksync.ui.assistant.VoiceTaskProposal {
        val result = VoiceTaskInterpreter.interpret(text, emptyTasks, clock)
        assertTrue("Expected one task proposal, got $result", result is VoiceInterpretation.Proposal)
        return (result as VoiceInterpretation.Proposal).proposal
    }

    private fun proposals(text: String, clock: Clock = fixedClock()): List<com.example.tasksync.ui.assistant.VoiceTaskProposal> {
        val result = VoiceTaskInterpreter.interpret(text, emptyTasks, clock)
        return when (result) {
            is VoiceInterpretation.Proposal -> listOf(result.proposal)
            is VoiceInterpretation.Proposals -> result.proposals
            else -> throw AssertionError("Expected task proposals, got $result")
        }
    }

    private fun fixedClock(): Clock = Clock.fixed(
        Instant.parse("2026-10-21T04:00:00Z"),
        ZoneId.of("Asia/Kolkata")
    )
}
