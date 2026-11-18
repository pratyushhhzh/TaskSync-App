package com.example.tasksync.data.interpretation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tasksync.data.local.AppDatabase
import com.example.tasksync.data.local.entity.SubtaskEntity
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.data.interpretation.MatchQuality
import com.example.tasksync.data.interpretation.TaskProposalMatcher
import com.example.tasksync.data.remote.AddProposal
import com.example.tasksync.data.remote.DeleteProposal
import com.example.tasksync.data.remote.UpdateChanges
import com.example.tasksync.data.remote.UpdateProposal
import com.example.tasksync.data.repository.TaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProposalExecutionRoomTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: TaskRepository
    private lateinit var store: TaskRepositoryProposalStore

    @Before fun createDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = TaskRepository(database.taskDao(), context)
        store = TaskRepositoryProposalStore(repository, database.taskDao())
    }

    @After fun closeDatabase() = database.close()

    @Test fun approvedAddAndUpdateUseRoomAndPreserveExistingTaskFields() = runBlocking {
        val original = TaskEntity(
            id = "local-1", userId = "local_user", projectId = "project-3", title = "Quarterly review",
            description = "Keep source notes", dueDate = "2020-12-01", dueTime = "09:30:00",
            reminderEnabled = true, priority = 1, recurrenceRule = "weekly",
        )
        database.taskDao().insertTask(original)
        val add = ReviewProposal(AddProposal("op-add", "add a task", null, "Send invoice"), editedTitle = "Send invoice")
        val update = ReviewProposal(
            UpdateProposal("op-update", "rename review", null, original.title, UpdateChanges(title = "Annual review")),
            matchedTaskId = original.id, editedTitle = "Annual review",
        )
        val coordinator = ProposalExecutionCoordinator(store)

        assertEquals(1, store.allTasks().size)
        assertTrue(coordinator.executeAfterApproval(listOf(add, update), setOf("op-add", "op-update")).all { it.succeeded })

        val updated = store.taskById(original.id)!!
        assertEquals("Annual review", updated.title)
        assertEquals("Keep source notes", updated.description)
        assertEquals("2020-12-01", updated.dueDate)
        assertEquals("09:30:00", updated.dueTime)
        assertTrue(updated.reminderEnabled)
        assertEquals(1, updated.priority)
        assertEquals("weekly", updated.recurrenceRule)
        assertEquals("project-3", updated.projectId)
        assertEquals(2, store.allTasks().size)
    }

    @Test fun approvedDeleteRemovesTaskAndSubtasksThroughExistingRepository() = runBlocking {
        val task = TaskEntity(id = "local-delete", userId = "local_user", title = "Delete me")
        database.taskDao().insertTask(task)
        database.taskDao().insertSubtask(SubtaskEntity("sub-1", task.id, "Child"))
        val proposal = ReviewProposal(DeleteProposal("op-delete", "remove task", null, task.title), matchedTaskId = task.id)
        val match = TaskProposalMatcher().match(task.title, store.allTasks())
        assertEquals(MatchQuality.EXACT, match.quality)
        assertEquals(task.id, match.automaticMatch?.id)

        val matchedProposal = proposal.copy(matchedTaskId = match.automaticMatch!!.id)
        assertTrue(ProposalExecutionCoordinator(store).executeAfterApproval(listOf(matchedProposal), setOf("op-delete")).single().succeeded)
        assertEquals(null, store.taskById(task.id))
        assertTrue(database.taskDao().getSubtasksForTaskOnce(task.id).isEmpty())
    }

    @Test fun completionUsesExistingRecurringTaskBehavior() = runBlocking {
        val task = TaskEntity(
            id = "local-repeat", userId = "local_user", title = "Weekly review",
            dueDate = "2030-01-01", recurrenceRule = "weekly",
        )
        database.taskDao().insertTask(task)
        val proposal = ReviewProposal(
            com.example.tasksync.data.remote.CompleteProposal("op-complete", "finish weekly review", null, task.title),
            matchedTaskId = task.id,
        )

        val outcome = ProposalExecutionCoordinator(store).executeAfterApproval(listOf(proposal), setOf("op-complete")).single()
        assertTrue(outcome.succeeded)
        assertEquals("completed", store.taskById(task.id)?.status)
        assertTrue(store.allTasks().any { it.id != task.id && it.recurrenceRule == "weekly" })
    }

    @Test fun unapprovedProposalDoesNotMutateRoom() = runBlocking {
        val proposal = ReviewProposal(AddProposal("op-add", "add a task", null, "Send invoice"), editedTitle = "Send invoice")
        val result = ProposalExecutionCoordinator(store).executeAfterApproval(listOf(proposal), emptySet())
        assertTrue(result.isEmpty())
        assertFalse(store.allTasks().any { it.title == "Send invoice" })
    }
}
