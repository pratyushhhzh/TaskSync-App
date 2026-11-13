package com.example.tasksync

import com.example.tasksync.ui.assistant.VoiceTranscriptStitcher
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTranscriptStitcherTest {
    @Test
    fun appendsNonOverlappingSegments() {
        assertEquals("Call the dentist tomorrow", VoiceTranscriptStitcher.append("Call the", "dentist tomorrow"))
    }

    @Test
    fun preservesWordsRepeatedAtSegmentBoundary() {
        assertEquals("Call the dentist. Dentist, tomorrow", VoiceTranscriptStitcher.append("Call the dentist.", "Dentist, tomorrow"))
    }

    @Test
    fun preservesDuplicateSegmentsAndIgnoresBlankSegments() {
        assertEquals("Call the dentist call the dentist", VoiceTranscriptStitcher.append("Call the dentist", "call the dentist"))
        assertEquals("Call the dentist", VoiceTranscriptStitcher.append("Call the dentist", "  "))
    }
}
