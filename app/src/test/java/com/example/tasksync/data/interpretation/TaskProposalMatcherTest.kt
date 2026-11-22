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
        assertEquals("task-1", result.matches.single().task.id)
    }

    @Test fun minorSpellingErrorIsRetrievedButNeverAutomaticallySelected() {
        val result = matcher.match("submit the assigment", listOf(task("Submit assignment")))
        assertEquals(MatchQuality.LOW_CONFIDENCE, result.quality)
        assertNull(result.automaticMatch)
    }

    @Test fun dsaAndExpandedCourseworkWordingCanIdentifyUniqueTask() {
        val existing = task("DSA Assignment")
        val query = "Update my data structures coursework"
        val result = matcher.match(query, listOf(existing))
        assertEquals(existing, result.automaticMatch)
    }

    @Test fun descriptionSignalsCanFindTaskWithDifferentTitle() {
        val existing = task("Semester project", description = "Algorithms and data structures coursework")
        val result = matcher.match("Update my DSA assignment", listOf(existing))
        assertEquals(existing, result.automaticMatch)
    }

    @Test fun twoSimilarTasksRemainCandidatesAndRequireManualChoice() {
        val first = task("DSA Assignment", id = "dsa-1")
        val second = task("Data Structures Coursework", id = "dsa-2")
        val result = matcher.match("Update my DSA assignment", listOf(first, second))
        assertEquals(MatchQuality.AMBIGUOUS, result.quality)
        assertNull(result.automaticMatch)
        assertEquals(setOf("dsa-1", "dsa-2"), result.matches.map { it.task.id }.toSet())
    }

    @Test fun unrelatedTargetHasNoMatch() {
        assertEquals(MatchQuality.NONE, matcher.match("renew passport", listOf(task("Call dentist"))).quality)
    }

    @Test fun unrelatedCandidatesDoNotBecomeWeakMatches() {
        val result = matcher.match("submit astronomy paper", listOf(task("Call dentist"), task("Buy groceries")))
        assertEquals(MatchQuality.NONE, result.quality)
        assertNull(result.automaticMatch)
    }

    @Test fun semanticMatchingGeneralizesToPersonalWorkFitnessAndErrands() {
        val cases = listOf(
            "phone the dentist" to task("Call dentist"),
            "purchase groceries" to task("Buy groceries"),
            "move my project meeting" to task("Reschedule project meeting"),
            "exercise tomorrow" to task("Workout"),
            "settle electricity bill" to task("Pay electric bill"),
        )
        cases.forEach { (request, existing) ->
            val result = matcher.match(request, listOf(existing))
            assertEquals("$request should retrieve its matching task", existing, result.matches.singleOrNull()?.task)
        }
    }

    @Test fun allRequestedAcademicAbbreviationsNormalizeConservatively() {
        val cases = listOf(
            "DBMS" to "Database Management Systems assignment",
            "OS" to "Operating Systems notes",
            "CN" to "Computer Networks lab",
            "OOP" to "Object-Oriented Programming project",
        )
        cases.forEach { (short, title) ->
            val existing = task(title)
            val result = matcher.match(short, listOf(existing))
            assertEquals(short, existing, result.automaticMatch)
        }
    }

    @Test fun dbmsAbbreviationMatchesExpandedTitle() = assertAbbreviationMatch("DBMS", "Database Management Systems assignment")
    @Test fun osAbbreviationMatchesExpandedTitleWithGenericArtifactLabel() = assertAbbreviationMatch("OS", "Operating Systems notes")
    @Test fun cnAbbreviationMatchesExpandedTitle() = assertAbbreviationMatch("CN", "Computer Networks lab")
    @Test fun oopAbbreviationMatchesPunctuatedExpandedTitle() = assertAbbreviationMatch("OOP", "Object-Oriented Programming project")

    private fun assertAbbreviationMatch(query: String, title: String) {
        val existing = task(title)
        val result = matcher.match(query, listOf(existing))
        assertEquals("$query should strongly identify $title", existing, result.automaticMatch)
    }

    private fun task(title: String, description: String? = null, id: String = "task-1") = TaskEntity(
        id = id, userId = "local_user", title = title, description = description,
    )
}
