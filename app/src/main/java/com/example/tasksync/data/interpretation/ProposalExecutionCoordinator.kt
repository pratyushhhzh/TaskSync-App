package com.example.tasksync.data.interpretation

import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.data.remote.AddProposal
import com.example.tasksync.data.remote.CompleteProposal
import com.example.tasksync.data.remote.DeleteProposal
import com.example.tasksync.data.remote.ProposedOperation
import com.example.tasksync.data.remote.UpdateProposal
import com.example.tasksync.data.remote.TaskPriority
import com.example.tasksync.data.reminder.ReminderScheduleStatus
import java.util.concurrent.ConcurrentHashMap
import java.time.LocalTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

interface ProposalTaskStore {
    suspend fun allTasks(): List<TaskEntity>
    suspend fun taskById(id: String): TaskEntity?
    suspend fun add(
        title: String, description: String?, dueDate: String?, dueTime: String?, priority: Int,
        reminderRequested: Boolean, notificationPermissionGranted: Boolean,
    ): ProposalTaskCreationResult
    suspend fun update(task: TaskEntity)
    suspend fun complete(id: String): Boolean
    suspend fun delete(task: TaskEntity)
}

data class ProposalTaskCreationResult(val task: TaskEntity, val reminderStatus: ReminderScheduleStatus)

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
    val selectedPriority: Int? = when (val proposal = operation) {
        is AddProposal -> proposal.priority?.toTaskPriority() ?: 4
        is UpdateProposal -> proposal.changes.priority?.toTaskPriority()
        else -> null
    },
    val inferredDeadline: Boolean = false,
    val dateResolutionUnresolved: Boolean = false,
    val timeResolutionUnresolved: Boolean = false,
    val reminderRequested: Boolean = (operation as? AddProposal)?.reminderRequested == true,
    val matchedTaskId: String? = null,
    val matches: List<TaskMatch> = emptyList(),
    val matchQuality: MatchQuality? = null,
    val issues: List<String> = emptyList(),
    val outcome: OperationOutcome? = null,
) {
    val operationId: String get() = operation.operationId
    val scheduleWarnings: List<String>
        get() = buildList {
            if (dateResolutionUnresolved && !dateClearedByUser && dueDateInput.isBlank()) {
                add("Date could not be determined. Add it manually if needed.")
            }
            if (timeResolutionUnresolved && !timeClearedByUser && dueTimeInput.isBlank()) {
                add("Time could not be determined. Add it manually if needed.")
            }
        }
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
        val dateOkay = dueDateInput.isBlank() || runCatching { LocalDate.parse(dueDateInput) }.isSuccess
        val timeOkay = dueTimeInput.isBlank() || runCatching { LocalTime.parse(dueTimeInput) }.isSuccess
        return dateOkay && timeOkay
    }

    private fun hasRequestedChanges(): Boolean {
        val update = operation as? UpdateProposal ?: return true
        return update.changes.title != null || update.changes.description != null ||
            update.changes.datePhrase != null || update.changes.timePhrase != null || selectedPriority != null
    }
}

internal fun ProposedOperation.dateAndTimePhrases(): Pair<String?, String?> = when (this) {
    is AddProposal -> datePhrase to timePhrase
    is UpdateProposal -> changes.datePhrase to changes.timePhrase
    else -> null to null
}

private fun TaskPriority.toTaskPriority(): Int = when (this) {
    TaskPriority.HIGH -> 1
    TaskPriority.MEDIUM -> 2
    TaskPriority.LOW -> 3
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
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val completed = ConcurrentHashMap.newKeySet<String>()

    suspend fun executeAfterApproval(
        rows: List<ReviewProposal>,
        approvedIds: Set<String>,
        notificationPermissionGranted: Boolean = true,
    ): List<OperationOutcome> {
        val selected = rows.filter { it.executionKey in approvedIds }
        if (selected.map { it.executionKey }.distinct().size != selected.size) return emptyList()
        return selected.map { row ->
            when {
                !row.canApply -> OperationOutcome(row.executionKey, false, "Needs a target or valid date/time before it can be applied.")
                row.executionKey in completed -> OperationOutcome(row.executionKey, false, "This proposal was already applied; it was not repeated.")
                !inFlight.add(row.executionKey) -> OperationOutcome(row.executionKey, false, "This proposal is already being applied.")
                else -> try {
                    val message = apply(row, notificationPermissionGranted)
                    completed.add(row.executionKey)
                    OperationOutcome(row.executionKey, true, message)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    OperationOutcome(row.executionKey, false, failure.message ?: "The operation failed.")
                } finally {
                    inFlight.remove(row.executionKey)
                }
            }
        }
    }

    private suspend fun apply(row: ReviewProposal, notificationPermissionGranted: Boolean): String = when (val proposal = row.operation) {
        is AddProposal -> {
            val date = row.dueDateInput.takeIf(String::isNotBlank)
            val time = row.dueTimeInput.takeIf(String::isNotBlank)?.toTaskTime()
            val created = store.add(
                row.editedTitle, row.editedDescription.ifBlank { null }, date, time, row.selectedPriority ?: 4,
                row.reminderRequested, notificationPermissionGranted,
            )
            when (created.reminderStatus) {
                ReminderScheduleStatus.NOT_REQUESTED -> "Task added."
                ReminderScheduleStatus.SCHEDULED -> "Task added. Reminder scheduled; Android may deliver it shortly after the selected time."
                ReminderScheduleStatus.SCHEDULED_INEXACT -> "Task added. Reminder scheduled, but Android may delay delivery because exact-alarm access is unavailable."
                ReminderScheduleStatus.MISSING_DATE_TIME -> "Task added. Reminder not scheduled because a date and time are required."
                ReminderScheduleStatus.PERMISSION_REQUIRED -> "Task added. Reminder not scheduled because notification permission is required."
                ReminderScheduleStatus.APP_NOTIFICATIONS_DISABLED -> "Task added. Reminder not scheduled because app notifications are disabled."
                ReminderScheduleStatus.CHANNEL_DISABLED -> "Task added. Reminder not scheduled because the Task reminders channel is disabled."
                ReminderScheduleStatus.PAST_TIME -> "Task added. Reminder not scheduled because the selected time has passed."
                ReminderScheduleStatus.SCHEDULING_FAILED -> "Task added, but Android could not schedule the reminder."
            }
        }
        is UpdateProposal -> {
            val taskId = row.matchedTaskId ?: error("Choose a task first.")
            val task = store.taskById(taskId) ?: error("The selected task no longer exists.")
            val changes = proposal.changes
            store.update(task.copy(
                title = if (changes.title != null) row.editedTitle else task.title,
                description = if (changes.description != null) row.editedDescription.ifBlank { null } else task.description,
                dueDate = when {
                    row.dateClearedByUser -> null
                    row.dueDateInput.isNotBlank() -> row.dueDateInput
                    else -> task.dueDate
                },
                dueTime = when {
                    row.timeClearedByUser -> null
                    row.dueTimeInput.isNotBlank() -> row.dueTimeInput.toTaskTime()
                    else -> task.dueTime
                },
                priority = row.selectedPriority ?: task.priority,
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
