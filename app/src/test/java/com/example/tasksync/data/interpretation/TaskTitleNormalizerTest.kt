package com.example.tasksync.data.interpretation

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskTitleNormalizerTest {
    @Test fun removesConversationalFillerAndSpokenDateFromExamTitle() {
        assertEquals(
            "MLW exam lab exam",
            TaskTitleNormalizer.normalize("have MLW exam lab exam on Monday next week", "I have an MLW exam lab exam on Monday next week.", "Monday next week", null),
        )
    }

    @Test fun removesNeedToAndDottedTimeAndDateFromSubmissionTitle() {
        assertEquals(
            "submit a PPT",
            TaskTitleNormalizer.normalize("need to submit a PPT by 4:00 p.m. today", "I need to submit a PPT by 4:00 p.m. today.", "today", "4:00 p.m."),
        )
    }

    @Test fun keepsFootballMatchMeaningWhileRemovingItsSchedule() {
        assertEquals(
            "football match",
            TaskTitleNormalizer.normalize("I have a football match today at 7:30 p.m.", "I have a football match today at 7:30 p.m.", "today", "7:30 p.m."),
        )
    }

    @Test fun keepsTaskActionAndJavaSubject() {
        assertEquals("Finish Java assignment", TaskTitleNormalizer.normalize("Finish Java assignment tomorrow", "Finish Java assignment tomorrow.", "tomorrow", null))
    }

    @Test fun preservesMeaningfulBareHaveAndSeparatelyHandledTaskTitles() {
        assertEquals("have lunch", TaskTitleNormalizer.normalize("have lunch", "have lunch tomorrow", "tomorrow", null))
        assertEquals("send report", TaskTitleNormalizer.normalize("I need to send report tomorrow", "I need to send report tomorrow", "tomorrow", null))
    }

    @Test fun normalizesMultipleTaskSegmentsIndependently() {
        val titles = listOf(
            TaskTitleNormalizer.normalize("need to submit a PPT by 4:00 p.m. today", "I need to submit a PPT by 4:00 p.m. today", "today", "4:00 p.m."),
            TaskTitleNormalizer.normalize("Finish Java assignment tomorrow", "Finish Java assignment tomorrow", "tomorrow", null),
        )
        assertEquals(listOf("submit a PPT", "Finish Java assignment"), titles)
    }
}
