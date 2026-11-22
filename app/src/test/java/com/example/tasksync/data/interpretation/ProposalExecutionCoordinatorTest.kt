package com.example.tasksync.data.interpretation

import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.data.remote.AddProposal
import com.example.tasksync.data.remote.CompleteProposal
import com.example.tasksync.data.remote.DeleteProposal
import com.example.tasksync.data.remote.UpdateChanges
import com.example.tasksync.data.remote.UpdateProposal
import com.example.tasksync.data.remote.TaskPriority
import com.example.tasksync.data.reminder.ReminderScheduleStatus
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProposalExecutionCoordinatorTest {
    @Test fun noOperationExecutesBeforeApprovedIdsArePassed() = runBlocking {
        val store = FakeStore()
        val coordinator = ProposalExecutionCoordinator(store)
        assertTrue(coordinator.executeAfterApproval(listOf(addRow("op-add")), emptySet()).isEmpty())
        assertEquals(0, store.addCalls)
    }

    @Test fun rejectedProposalNeverExecutesAndOnlySelectedProposalRuns() = runBlocking {
        val store = FakeStore()
        val rows = listOf(addRow("op-a"), addRow("op-b"))
        val result = ProposalExecutionCoordinator(store).executeAfterApproval(rows, setOf("op-b"))
        assertEquals(1, store.addCalls)
        assertEquals(listOf("op-b"), result.map { it.operationId })
    }

    @Test fun unresolvedProposalCannotExecute() = runBlocking {
        val store = FakeStore()
        val row = addRow("op-add").copy(issues = listOf("Choose a date."))
        val result = ProposalExecutionCoordinator(store).executeAfterApproval(listOf(row), setOf("op-add"))
        assertEquals(0, store.addCalls)
        assertFalse(result.single().succeeded)
    }

    @Test fun repeatedApprovalDoesNotDuplicateAdd() = runBlocking {
        val store = FakeStore()
        val coordinator = ProposalExecutionCoordinator(store)
        val row = addRow("op-add")
        assertTrue(coordinator.executeAfterApproval(listOf(row), setOf("op-add")).single().succeeded)
        assertFalse(coordinator.executeAfterApproval(listOf(row), setOf("op-add")).single().succeeded)
        assertEquals(1, store.addCalls)
    }

    @Test fun approvedTimeIsStoredInExistingTaskTimeFormat() = runBlocking {
        val store = FakeStore()
        val proposal = AddProposal("op-time", "call at five", null, "Call", timePhrase = "at 5 PM")
        val row = ReviewProposal(proposal, dueTimeInput = "17:00", editedTitle = "Call")
        val result = ProposalExecutionCoordinator(store).executeAfterApproval(listOf(row), setOf("op-time"))
        assertTrue(result.single().succeeded)
        assertEquals("17:00:00", store.tasks.single().dueTime)
    }

    @Test fun titleOnlyTaskWithNoOptionalMetadataIsAppliedImmediately() = runBlocking {
        val store = FakeStore()
        val row = ReviewProposal(AddProposal("title-only", "add a task", null, "Task title"), editedTitle = "Task title")
        assertTrue(row.canApply)
        val result = ProposalExecutionCoordinator(store).executeAfterApproval(listOf(row), setOf("title-only"))
        assertTrue(result.single().succeeded)
        assertEquals("Task title", store.tasks.single().title)
        assertEquals(null, store.tasks.single().description)
        assertEquals(null, store.tasks.single().dueDate)
        assertEquals(null, store.tasks.single().dueTime)
    }

    @Test fun explicitHighPriorityIsPersistedAndUpdateWithoutPriorityPreservesExistingValue() = runBlocking {
        val store = FakeStore().apply { tasks += TaskEntity(id = "existing", userId = "local_user", title = "Prepare lab", priority = 3) }
        val high = ReviewProposal(
            AddProposal("priority-add", "high priority task", null, "Prepare lab", priority = TaskPriority.HIGH),
            editedTitle = "Prepare lab",
        )
        assertTrue(ProposalExecutionCoordinator(store).executeAfterApproval(listOf(high), setOf(high.executionKey)).single().succeeded)
        assertEquals(1, store.tasks.last().priority)

        val update = ReviewProposal(
            UpdateProposal("no-priority-change", "rename task", null, "Prepare lab", UpdateChanges(title = "Prepare for lab")),
            matchedTaskId = "existing", editedTitle = "Prepare for lab",
        )
        assertTrue(ProposalExecutionCoordinator(store).executeAfterApproval(listOf(update), setOf(update.executionKey)).single().succeeded)
        assertEquals(3, store.tasks.first { it.id == "existing" }.priority)
    }

    @Test fun reminderRequestWithoutPermissionStillSavesTaskAndReportsReminderSeparately() = runBlocking {
        val store = FakeStore()
        val row = ReviewProposal(
            AddProposal("reminder-op", "Remind me to call a friend", null, "Call a friend", reminderRequested = true),
            editedTitle = "Call a friend",
        )
        val result = ProposalExecutionCoordinator(store).executeAfterApproval(
            listOf(row), setOf(row.executionKey), notificationPermissionGranted = false,
        ).single()
        assertTrue(result.succeeded)
        assertEquals("Call a friend", store.tasks.single().title)
        assertTrue(result.message.contains("Reminder not scheduled"))
        assertFalse(store.tasks.single().reminderEnabled)
    }

    @Test fun failedOperationCanBeRetriedButSuccessfulOperationCannotBeDuplicated() = runBlocking {
        val store = FakeStore().apply { failTitle = "retry me" }
        val coordinator = ProposalExecutionCoordinator(store)
        val row = addRow("retry-op", "retry me")
        assertFalse(coordinator.executeAfterApproval(listOf(row), setOf(row.executionKey)).single().succeeded)
        store.failTitle = null
        assertTrue(coordinator.executeAfterApproval(listOf(row), setOf(row.executionKey)).single().succeeded)
        assertFalse(coordinator.executeAfterApproval(listOf(row), setOf(row.executionKey)).single().succeeded)
        assertEquals(1, store.addCalls)
    }

    @Test fun timeWithoutDateIsOptionalAndKeepsDateNull() = runBlocking {
        val store = FakeStore()
        val row = ReviewProposal(
            AddProposal("time-only", "call at 5 pm", null, "Call", timePhrase = "5 pm"),
            editedTitle = "Call", dueTimeInput = "17:00",
        )
        assertTrue(row.canApply)
        assertTrue(ProposalExecutionCoordinator(store).executeAfterApproval(listOf(row), setOf("time-only")).single().succeeded)
        assertEquals(null, store.tasks.single().dueDate)
        assertEquals("17:00:00", store.tasks.single().dueTime)
    }

    @Test fun unresolvedScheduleWarningsDoNotBlockApplyAndCanBeManuallyCorrected() = runBlocking {
        val store = FakeStore()
        val unresolved = ReviewProposal(
            operation = AddProposal("uncertain-time", "Call Mom in the evening", null, "Call Mom", timePhrase = "in the evening"),
            editedTitle = "Call Mom",
            timeResolutionUnresolved = true,
        )
        assertEquals(listOf("Time could not be determined. Add it manually if needed."), unresolved.scheduleWarnings)
        assertTrue(unresolved.canApply)
        val result = ProposalExecutionCoordinator(store).executeAfterApproval(listOf(unresolved), setOf(unresolved.executionKey))
        assertTrue(result.single().succeeded)
        assertEquals(null, store.tasks.single().dueDate)
        assertEquals(null, store.tasks.single().dueTime)

        val unresolvedDate = ReviewProposal(
            operation = AddProposal("uncertain-date", "Pay the bill sometime later", null, "Pay the bill", datePhrase = "sometime later"),
            editedTitle = "Pay the bill",
            dateResolutionUnresolved = true,
        )
        assertEquals(listOf("Date could not be determined. Add it manually if needed."), unresolvedDate.scheduleWarnings)
        assertTrue(unresolvedDate.canApply)
        val unresolvedDateResult = ProposalExecutionCoordinator(store).executeAfterApproval(listOf(unresolvedDate), setOf(unresolvedDate.executionKey))
        assertTrue(unresolvedDateResult.single().succeeded)
        assertEquals(null, store.tasks.last().dueDate)
        assertEquals(null, store.tasks.last().dueTime)

        val needsDate = unresolved.copy(
            operation = AddProposal("manual-correction", "Finish next month", null, "Finish", datePhrase = "next month"),
            dateResolutionUnresolved = true,
            timeResolutionUnresolved = false,
        )
        assertEquals(listOf("Date could not be determined. Add it manually if needed."), needsDate.scheduleWarnings)
        val corrected = needsDate.withDateSelection(LocalDate.of(2026, 12, 1)).withTimeSelection(LocalTime.of(17, 30))
        assertTrue(corrected.scheduleWarnings.isEmpty())
        assertTrue(corrected.canApply)
        val correctedResult = ProposalExecutionCoordinator(store).executeAfterApproval(listOf(corrected), setOf(corrected.executionKey))
        assertTrue(correctedResult.single().succeeded)
        assertEquals("2026-12-01", store.tasks.last().dueDate)
        assertEquals("17:30:00", store.tasks.last().dueTime)
    }

    @Test fun unresolvedUpdateDateDoesNotClearTheExistingTaskDeadline() = runBlocking {
        val store = FakeStore().apply {
            tasks += TaskEntity(id = "existing", userId = "local_user", title = "Pay bill", dueDate = "2026-12-02", dueTime = "09:00:00")
        }
        val row = ReviewProposal(
            operation = UpdateProposal("update-uncertain-date", "move bill sometime", null, "Pay bill", UpdateChanges(datePhrase = "sometime soon")),
            matchedTaskId = "existing",
            dateResolutionUnresolved = true,
        )
        assertTrue(row.canApply)
        val result = ProposalExecutionCoordinator(store).executeAfterApproval(listOf(row), setOf(row.executionKey))
        assertTrue(result.single().succeeded)
        assertEquals("2026-12-02", store.tasks.single().dueDate)
        assertEquals("09:00:00", store.tasks.single().dueTime)
    }

    @Test fun updatePreservesEveryUnrequestedField() = runBlocking {
        val store = FakeStore().apply {
            tasks += TaskEntity(
                id = "task-1", userId = "local_user", projectId = "project-8", sectionId = "section-2",
                title = "Old title", description = "Old description", status = "inbox", dueDate = "2026-12-01",
                dueTime = "08:30:00", reminderEnabled = true, priority = 1, assigneeId = "person-1",
                recurrenceRule = "weekly", googleCalendarId = 2, googleCalendarEventId = 99,
            )
        }
        val row = ReviewProposal(
            operation = UpdateProposal("op-update", "rename it", null, "Old title", UpdateChanges(title = "New title")),
            matchedTaskId = "task-1", editedTitle = "New title",
        )
        val result = ProposalExecutionCoordinator(store).executeAfterApproval(listOf(row), setOf("op-update"))
        assertTrue(result.single().succeeded)
        val updated = store.tasks.single()
        assertEquals("New title", updated.title)
        assertEquals("Old description", updated.description)
        assertEquals("2026-12-01", updated.dueDate)
        assertEquals("08:30:00", updated.dueTime)
        assertTrue(updated.reminderEnabled)
        assertEquals(1, updated.priority)
        assertEquals("weekly", updated.recurrenceRule)
        assertEquals("project-8", updated.projectId)
        assertEquals(99L, updated.googleCalendarEventId)
    }

    @Test fun partialFailureReportsEachOperation() = runBlocking {
        val store = FakeStore().apply { failTitle = "bad task" }
        val rows = listOf(addRow("op-good"), addRow("op-bad", "bad task"))
        val result = ProposalExecutionCoordinator(store).executeAfterApproval(rows, setOf("op-good", "op-bad"))
        assertEquals(1, result.count { it.succeeded })
        assertEquals(1, result.count { !it.succeeded })
        assertEquals(2, result.size)
    }

    @Test fun completeAndDeleteUseStoreOnlyAfterApproval() = runBlocking {
        val store = FakeStore().apply { tasks += TaskEntity(id = "task-1", userId = "local_user", title = "Existing") }
        val rows = listOf(
            ReviewProposal(CompleteProposal("op-complete", "finish", null, "Existing"), matchedTaskId = "task-1"),
            ReviewProposal(DeleteProposal("op-delete", "remove", null, "Existing"), matchedTaskId = "task-1"),
        )
        val coordinator = ProposalExecutionCoordinator(store)
        assertEquals(0, store.completeCalls + store.deleteCalls)
        coordinator.executeAfterApproval(rows, setOf("op-complete"))
        assertEquals(1, store.completeCalls)
        assertEquals(0, store.deleteCalls)
    }

    @Test fun oneBatchApprovalAppliesAllClearOperationTypes() = runBlocking {
        val store = FakeStore().apply {
            tasks += TaskEntity(id = "update", userId = "local_user", title = "Old")
            tasks += TaskEntity(id = "complete", userId = "local_user", title = "Finish")
            tasks += TaskEntity(id = "delete", userId = "local_user", title = "Remove")
        }
        val rows = listOf(
            addRow("add", "New task"),
            ReviewProposal(UpdateProposal("update-op", "rename", null, "Old", UpdateChanges(title = "New")), matchedTaskId = "update", editedTitle = "New"),
            ReviewProposal(CompleteProposal("complete-op", "finish", null, "Finish"), matchedTaskId = "complete"),
            ReviewProposal(DeleteProposal("delete-op", "remove", null, "Remove"), matchedTaskId = "delete"),
        )
        val results = ProposalExecutionCoordinator(store).executeAfterApproval(rows, rows.map { it.operationId }.toSet())
        assertEquals(4, results.size)
        assertTrue(results.all { it.succeeded })
        assertEquals(1, store.addCalls)
        assertEquals(1, store.completeCalls)
        assertEquals(1, store.deleteCalls)
        assertEquals("New", store.tasks.single { it.id == "update" }.title)
    }

    @Test fun deleteNoOpIsReportedAsFailureInsteadOfSuccess() = runBlocking {
        val store = FakeStore().apply {
            tasks += TaskEntity(id = "delete", userId = "local_user", title = "Remove")
            noOpDelete = true
        }
        val row = ReviewProposal(DeleteProposal("delete-op", "remove", null, "Remove"), matchedTaskId = "delete")
        val outcome = ProposalExecutionCoordinator(store).executeAfterApproval(listOf(row), setOf("delete-op")).single()
        assertFalse(outcome.succeeded)
        assertTrue(outcome.message.contains("still present"))
        assertEquals(1, store.tasks.size)
    }

    @Test fun correctedDateAndTimeCanBeAppliedAndClearedFieldsStayUnset() = runBlocking {
        val proposal = AddProposal("corrected", "schedule", null, "Study", datePhrase = "next week", timePhrase = "4:00 p.m.")
        val corrected = ReviewProposal(proposal, editedTitle = "Study")
            .withDateSelection(LocalDate.of(2026, 11, 23))
            .withTimeSelection(LocalTime.of(16, 0))
        val store = FakeStore()
        val outcome = ProposalExecutionCoordinator(store).executeAfterApproval(listOf(corrected), setOf("corrected")).single()
        assertTrue(outcome.succeeded)
        assertEquals("2026-11-23", store.tasks.single().dueDate)
        assertEquals("16:00:00", store.tasks.single().dueTime)

        val cleared = corrected.withDateSelection(null).withTimeSelection(null)
        assertTrue(cleared.canApply)
        val clearedStore = FakeStore()
        val clearedOutcome = ProposalExecutionCoordinator(clearedStore).executeAfterApproval(listOf(cleared), setOf("corrected")).single()
        assertTrue(clearedOutcome.succeeded)
        assertEquals(null, clearedStore.tasks.single().dueDate)
        assertEquals(null, clearedStore.tasks.single().dueTime)
    }

    private fun addRow(id: String, title: String = "Task") = ReviewProposal(
        operation = AddProposal(id, "add $title", null, title),
        editedTitle = title,
    )

    private class FakeStore : ProposalTaskStore {
        val tasks = mutableListOf<TaskEntity>()
        var addCalls = 0
        var completeCalls = 0
        var deleteCalls = 0
        var failTitle: String? = null
        var noOpDelete: Boolean = false

        override suspend fun allTasks() = tasks.toList()
        override suspend fun taskById(id: String) = tasks.find { it.id == id }
        override suspend fun add(
            title: String,
            description: String?,
            dueDate: String?,
            dueTime: String?,
            priority: Int,
            reminderRequested: Boolean,
            notificationPermissionGranted: Boolean,
        ): ProposalTaskCreationResult {
            if (title == failTitle) error("simulated failure")
            addCalls++
            val task = TaskEntity("new-$addCalls", "local_user", title = title, description = description, dueDate = dueDate, dueTime = dueTime, priority = priority).also(tasks::add)
            val status = when {
                !reminderRequested -> ReminderScheduleStatus.NOT_REQUESTED
                !notificationPermissionGranted -> ReminderScheduleStatus.PERMISSION_REQUIRED
                dueDate.isNullOrBlank() || dueTime.isNullOrBlank() -> ReminderScheduleStatus.MISSING_DATE_TIME
                else -> ReminderScheduleStatus.SCHEDULED
            }
            val finalTask = if (status == ReminderScheduleStatus.SCHEDULED) task.copy(reminderEnabled = true).also { tasks[tasks.lastIndex] = it } else task
            return ProposalTaskCreationResult(finalTask, status)
        }
        override suspend fun update(task: TaskEntity) {
            val index = tasks.indexOfFirst { it.id == task.id }
            if (index < 0) error("missing") else tasks[index] = task
        }
        override suspend fun complete(id: String): Boolean { completeCalls++; return tasks.any { it.id == id } }
        override suspend fun delete(task: TaskEntity) { deleteCalls++; if (!noOpDelete) tasks.removeAll { it.id == task.id } }
    }
}
