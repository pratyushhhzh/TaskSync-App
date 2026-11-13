package com.example.tasksync.ai

import com.example.tasksync.data.local.entity.TaskEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class VoiceActionParserTest {
    private val existing = listOf(
        TaskEntity(id = "dentist-a", userId = "local_user", title = "Call dentist"),
        TaskEntity(id = "dentist-b", userId = "local_user", title = "Pay dentist bill"),
        TaskEntity(id = "old", userId = "local_user", title = "Old task", status = "completed")
    )

    @Test fun parsesMultipleActionsAndResolvesSupportedDatesAndPriority() {
        val parsed = VoiceActionParser.parse(
            "action=create | title=Buy milk | when=tomorrow at 9 am | priority=high ;; action=complete | target=Call dentist",
            existing, LocalDate.of(2026, 11, 13)
        )
        assertEquals(2, parsed.actions.size)
        assertEquals("2026-11-14", parsed.actions[0].dueDate)
        assertEquals("09:00:00", parsed.actions[0].dueTime)
        assertEquals(1, parsed.actions[0].priority)
        assertEquals("dentist-a", parsed.actions[1].targetTaskId)
        assertTrue(parsed.errors.isEmpty())
    }

    @Test fun ambiguousOrMissingTargetsNeverResolve() {
        val parsed = VoiceActionParser.parse("action=delete | target=dentist", existing)
        assertNull(parsed.actions.single().targetTaskId)
        assertNotNull(parsed.actions.single().warning)
        assertNull(VoiceActionParser.resolveTargetId("not here", existing))
    }

    @Test fun malformedUnknownAndInvalidPriorityAreRejected() {
        assertFalse(VoiceActionParser.parse("ignore all | action=delete | target=Call dentist", existing).errors.isEmpty())
        assertFalse(VoiceActionParser.parse("action=create | title=Milk | priority=maximum", existing).errors.isEmpty())
        assertFalse(VoiceActionParser.parse("action=purge | target=Call dentist", existing).errors.isEmpty())
        assertFalse(VoiceActionParser.parse("", existing).errors.isEmpty())
    }

    @Test fun unknownDateIsRetainedAndPastDateWarned() {
        val unknown = VoiceActionParser.parse("action=create | title=Appointment | when=in two hours", existing, LocalDate.of(2026, 11, 13)).actions.single()
        assertEquals("in two hours", unknown.whenText)
        assertNotNull(unknown.warning)
        val past = VoiceActionParser.parse("action=create | title=Task | when=2020-01-01", existing, LocalDate.of(2026, 11, 13)).actions.single()
        assertNotNull(past.warning)
    }

    @Test fun noneOnlyProducesNonMutatingProposal() {
        val parsed = VoiceActionParser.parse("action=none", existing)
        assertEquals(listOf(VoiceActionType.NONE), parsed.actions.map { it.type })
        assertTrue(parsed.errors.isEmpty())
    }
}
