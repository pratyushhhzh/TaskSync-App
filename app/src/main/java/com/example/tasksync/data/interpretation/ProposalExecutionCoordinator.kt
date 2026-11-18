package com.example.tasksync.data.interpretation

import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.data.remote.AddProposal
import com.example.tasksync.data.remote.CompleteProposal
import com.example.tasksync.data.remote.DeleteProposal
import com.example.tasksync.data.remote.ProposedOperation
import com.example.tasksync.data.remote.UpdateProposal
import java.util.concurrent.ConcurrentHashMap
import java.time.LocalTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

interface ProposalTaskStore {
    suspend fun allTasks(): List<TaskEntity>
    suspend fun taskById(id: String): TaskEntity?
    suspend fun add(title: String, description: String?, dueDate: String?, dueTime: String?): TaskEntity
    suspend fun update(task: TaskEntity)
    suspend fun complete(id: String): Boolean
    suspend fun delete(task: TaskEntity)
}

data class ReviewProposal(
    val operation: ProposedOperation,
    val executionKey: String = operation.operationId,
    val selected: Boolean = false,
    val editedTitle: String = (operation as? AddProposal)?.title ?: (operation as? UpdateProposal)?.changes?.title.orEmpty(),
    val editedDescription: String = when (operation) {
        is AddProposal -> operation.description.orEmpty()
        is UpdateProposal -> operation.changes.description.orEmpty()
        else -> ""
    },
    val dueDateInput: String = "",
    val dueTimeInput: String = "",
    val dateClearedByUser: Boolean = false,
    val timeClearedByUser: Boolean = false,
    val matchedTaskId: String? = null,
    val matches: List<TaskMatch> = emptyList(),
    val matchQuality: MatchQuality? = null,
    val issues: List<String> = emptyList(),
    val outcome: OperationOutcome? = null,
) {
    val operationId: String get() = operation.operationId
    fun withDateSelection(value: LocalDate?): ReviewProposal = copy(
        dueDateInput = value?.toString().orEmpty(),
        dateClearedByUser = value == null,
    )
    fun withTimeSelection(value: LocalTime?): ReviewProposal = copy(
        dueTimeInput = value?.format(DateTimeFormatter.ofPattern("HH:mm")).orEmpty(),
        timeClearedByUser = value == null,
    )
    val canApply: Boolean
        get() = when (operation) {
            is AddProposal -> editedTitle.isNotBlank() && issues.isEmpty() && validDates()
            is UpdateProposal -> matchedTaskId != null && issues.isEmpty() && validDates() && hasRequestedChanges()
            is CompleteProposal, is DeleteProposal -> matchedTaskId != null
        }

    private fun validDates(): Boolean {
        val phrases = operation.dateAndTimePhrases()
        val hasDatePhrase = phrases.first != null || phrases.second.impliesDate()
        val hasTimePhrase = phrases.second != null || phrases.first.requiresExactTime()
        val dateOkay = dateClearedByUser || !hasDatePhrase || runCatching { java.time.LocalDate.parse(dueDateInput) }.isSuccess
        val timeOkay = timeClearedByUser || !hasTimePhrase || runCatching { java.time.LocalTime.parse(dueTimeInput) }.isSuccess
        return dateOkay && timeOkay
    }

    private fun hasRequestedChanges(): Boolean {
        val update = operation as? UpdateProposal ?: return true
        return update.changes.title != null || update.changes.description != null ||
            update.changes.datePhrase != null || update.changes.timePhrase != null
    }
}

internal fun ProposedOperation.dateAndTimePhrases(): Pair<String?, String?> = when (this) {
    is AddProposal -> datePhrase to timePhrase
    is UpdateProposal -> changes.datePhrase to changes.timePhrase
    else -> null to null
}

internal fun String?.requiresExactTime(): Boolean = this?.let {
    Regex("(?:\\b(?:morning|afternoon|evening|tonight|noon|midnight)\\b|(?:at\\s+)?\\d{1,2}(?::\\d{2})?\\s*(?:a\\.?\\s*m\\.?|p\\.?\\s*m\\.?)(?=$|[\\s,.;!?])|(?:at\\s+)?(?:[01]?\\d|2[0-3]):[0-5]\\d(?=$|[\\s,.;!?])|सुबह|दोपहर|शाम|रात)", RegexOption.IGNORE_CASE,
    ).containsMatchIn(it)
} == true

internal fun String?.impliesDate(): Boolean = this?.contains(
    Regex("\\b(today|tomorrow|day after tomorrow|tonight|this\\s+(?:morning|afternoon|evening|week|monday|tuesday|wednesday|thursday|friday|saturday|sunday)|next\\s+(?:week|monday|tuesday|wednesday|thursday|friday|saturday|sunday)|monday\\s+next week|tuesday\\s+next week|wednesday\\s+next week|thursday\\s+next week|friday\\s+next week|saturday\\s+next week|sunday\\s+next week|(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\s+next week|aaj|आज|kal|कल)\\b|आज|कल", RegexOption.IGNORE_CASE),
) == true

data class OperationOutcome(val operationId: String, val succeeded: Boolean, val message: String)

/** Executes only operation IDs passed after the user's final approval. Attempted IDs are never replayed. */
class ProposalExecutionCoordinator(private val store: ProposalTaskStore) {
    private val attempted = ConcurrentHashMap.newKeySet<String>()

    suspend fun executeAfterApproval(rows: List<ReviewProposal>, approvedIds: Set<String>): List<OperationOutcome> {
        val selected = rows.filter { it.executionKey in approvedIds }
        if (selected.map { it.executionKey }.distinct().size != selected.size) return emptyList()
        return selected.map { row ->
            when {
                !row.canApply -> OperationOutcome(row.executionKey, false, "Needs a target or valid date/time before it can be applied.")
                !attempted.add(row.executionKey) -> OperationOutcome(row.executionKey, false, "This proposal was already attempted; it was not repeated.")
                else -> try {
                    OperationOutcome(row.executionKey, true, apply(row))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    OperationOutcome(row.executionKey, false, failure.message ?: "The operation failed.")
                }
            }
        }
    }

    private suspend fun apply(row: ReviewProposal): String = when (val proposal = row.operation) {
        is AddProposal -> {
            val date = row.dueDateInput.takeIf(String::isNotBlank)
            val time = row.dueTimeInput.takeIf(String::isNotBlank)?.toTaskTime()
            store.add(row.editedTitle, row.editedDescription.ifBlank { null }, date, time)
            "Task added."
        }
        is UpdateProposal -> {
            val taskId = row.matchedTaskId ?: error("Choose a task first.")
            val task = store.taskById(taskId) ?: error("The selected task no longer exists.")
            val changes = proposal.changes
            store.update(task.copy(
                title = if (changes.title != null) row.editedTitle else task.title,
                description = if (changes.description != null) row.editedDescription.ifBlank { null } else task.description,
                dueDate = if (changes.datePhrase != null || changes.timePhrase.impliesDate()) row.dueDateInput.takeIf(String::isNotBlank) else task.dueDate,
                dueTime = if (changes.timePhrase != null || changes.datePhrase.requiresExactTime()) row.dueTimeInput.takeIf(String::isNotBlank)?.toTaskTime() else task.dueTime,
            ))
            "Task updated."
        }
        is CompleteProposal -> {
            if (!store.complete(row.matchedTaskId ?: error("Choose a task first."))) error("The task no longer exists.")
            else "Task completed using the app's existing completion and recurrence behavior."
        }
        is DeleteProposal -> {
            val taskId = row.matchedTaskId ?: error("Choose a task first.")
            val task = store.taskById(taskId) ?: error("The selected task no longer exists.")
            store.delete(task)
            check(store.taskById(taskId) == null) { "The task is still present after the delete request. No deletion was confirmed." }
            "Task and its subtasks deleted. This app currently has no undo path."
        }
    }
}

private fun String.toTaskTime(): String = LocalTime.parse(this).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
