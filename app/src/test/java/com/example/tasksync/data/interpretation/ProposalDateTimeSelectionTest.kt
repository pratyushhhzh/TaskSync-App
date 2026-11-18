package com.example.tasksync.data.interpretation

import com.example.tasksync.data.remote.AddProposal
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProposalDateTimeSelectionTest {
    @Test fun calendarSelectionUpdatesStoredDateAndCanBeCleared() {
        val proposal = ReviewProposal(AddProposal("add", "schedule", null, "Schedule", datePhrase = "next week"))
        val selected = proposal.withDateSelection(LocalDate.of(2026, 11, 23))
        assertEquals("2026-11-23", selected.dueDateInput)
        assertTrue(selected.canApply)

        val cleared = selected.withDateSelection(null)
        assertEquals("", cleared.dueDateInput)
        assertTrue(cleared.dateClearedByUser)
        assertTrue(cleared.canApply)
    }

    @Test fun clockSelectionUpdatesTwentyFourHourValueAndCanBeCleared() {
        val proposal = ReviewProposal(AddProposal("add", "schedule", null, "Schedule", timePhrase = "4:00 p.m."))
        val selected = proposal.withTimeSelection(LocalTime.of(16, 0))
        assertEquals("16:00", selected.dueTimeInput)
        assertTrue(selected.canApply)

        val cleared = selected.withTimeSelection(null)
        assertEquals("", cleared.dueTimeInput)
        assertTrue(cleared.timeClearedByUser)
        assertTrue(cleared.canApply)
    }
}
