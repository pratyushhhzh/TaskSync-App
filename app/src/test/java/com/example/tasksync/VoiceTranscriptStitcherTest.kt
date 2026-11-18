package com.example.tasksync

import com.example.tasksync.ui.assistant.TranscriptAccumulator
import com.example.tasksync.ui.assistant.TranscriptReviewDraft
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTranscriptStitcherTest {
    @Test fun appendsSegmentsWithOneSpace() = assertEquals("Call the dentist tomorrow", TranscriptAccumulator.append("Call the", " dentist tomorrow "))
    @Test fun emptySegmentsDoNotEraseDraft() = assertEquals("Earlier words", TranscriptAccumulator.append(" Earlier words ", "  "))
    @Test fun repeatedWordsArePreservedAsRecognized() = assertEquals("call dentist dentist tomorrow", TranscriptAccumulator.append("call dentist", "dentist tomorrow"))

    @Test fun reviewConfirmsExactEditedText() {
        val draft = TranscriptReviewDraft("recognized")
        val exact = "  edited text  \n"
        draft.edit(exact)
        var submitted: String? = null
        assertEquals(true, draft.confirm { submitted = it })
        assertEquals(exact, submitted)
    }

    @Test fun whitespaceDraftCannotConfirmAndCancelHasNoSubmissionPath() {
        val draft = TranscriptReviewDraft(" \n ")
        var called = false
        assertEquals(false, draft.confirm { called = true })
        assertEquals(false, called)
    }

    @Test fun retryClearsPreviousAttemptThenContinueAppendsOnlyFinalSegments() {
        val draft = TranscriptReviewDraft("old attempt")
        draft.retry()
        assertEquals("", draft.text)
        draft.appendFinalSegment("new first")
        draft.appendFinalSegment("new second")
        assertEquals("new first new second", draft.text)
    }
}
