package com.example.tasksync.data.interpretation

import com.example.tasksync.data.local.entity.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskProposalMatcherTest {
    private val matcher = TaskProposalMatcher()

    @Test fun exactAndCaseInsensitiveTitleMatch() {
        val task = task("Design review")
        assertEquals(MatchQuality.EXACT, matcher.match("Design review", listOf(task)).quality)
        assertEquals(task, matcher.match("DESIGN REVIEW", listOf(task)).automaticMatch)
    }

    @Test fun exactDescriptionCanIdentifyTask() {
        val task = task("Review", description = "Prepare the quarterly report")
        assertEquals(task, matcher.match("Prepare the quarterly report", listOf(task)).automaticMatch)
    }

    @Test fun multipleExactTargetsRequireChoice() {
        val tasks = listOf(task("Call dentist", id = "1"), task("Call dentist", id = "2"))
        val result = matcher.match("Call dentist", tasks)
        assertEquals(MatchQuality.AMBIGUOUS, result.quality)
        assertNull(result.automaticMatch)
        assertEquals(2, result.matches.size)
    }

    @Test fun weakFuzzyMatchRequiresManualSelection() {
        val result = matcher.match("Call dntist tomorrow", listOf(task("Call dentist")))
        assertEquals(MatchQuality.LOW_CONFIDENCE, result.quality)
        assertNull(result.automaticMatch)
    }

    @Test fun unrelatedTargetHasNoMatch() {
        assertEquals(MatchQuality.NONE, matcher.match("renew passport", listOf(task("Call dentist"))).quality)
    }

    private fun task(title: String, description: String? = null, id: String = "task-1") = TaskEntity(
        id = id, userId = "local_user", title = title, description = description,
    )
}
