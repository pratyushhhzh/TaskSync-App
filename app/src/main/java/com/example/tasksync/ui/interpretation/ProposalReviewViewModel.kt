package com.example.tasksync.ui.interpretation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tasksync.data.interpretation.LocalDateTimeResolver
import com.example.tasksync.data.interpretation.MatchQuality
import com.example.tasksync.data.interpretation.ProposalExecutionCoordinator
import com.example.tasksync.data.interpretation.ProposalTaskStore
import com.example.tasksync.data.interpretation.ReviewProposal
import com.example.tasksync.data.interpretation.TaskMatch
import com.example.tasksync.data.interpretation.TaskRepositoryProposalStore
import com.example.tasksync.data.interpretation.TaskProposalMatcher
import com.example.tasksync.data.interpretation.TaskTitleNormalizer
import com.example.tasksync.data.interpretation.dateAndTimePhrases
import com.example.tasksync.data.interpretation.requiresExactTime
import com.example.tasksync.data.interpretation.impliesDate
import com.example.tasksync.data.local.AppDatabase
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.data.remote.AddProposal
import com.example.tasksync.data.remote.CompleteProposal
import com.example.tasksync.data.remote.DeleteProposal
import com.example.tasksync.data.remote.InterpretationResponse
import com.example.tasksync.data.remote.ProposedOperation
import com.example.tasksync.data.remote.UpdateProposal
import com.example.tasksync.data.repository.TaskRepository
import java.time.LocalDate
import java.time.LocalTime
import java.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProposalReviewState(
    val loading: Boolean = true,
    val rows: List<ReviewProposal> = emptyList(),
    val applying: Boolean = false,
    val summary: String? = null,
)

class ProposalReviewViewModel(application: Application) : AndroidViewModel(application) {
    private val taskRepository: TaskRepository
    private val store: ProposalTaskStore
    private val matcher = TaskProposalMatcher()
    private val clock = Clock.systemDefaultZone()
    private val resolver = LocalDateTimeResolver(clock)
    private val coordinator: ProposalExecutionCoordinator
    private val _state = MutableStateFlow(ProposalReviewState())
    val state: StateFlow<ProposalReviewState> = _state.asStateFlow()
    private var loadedRequestId: String? = null

    init {
        val dao = AppDatabase.getDatabase(application).taskDao()
        taskRepository = TaskRepository(dao, application.applicationContext)
        store = TaskRepositoryProposalStore(taskRepository, dao)
        coordinator = ProposalExecutionCoordinator(store)
    }

    fun load(response: InterpretationResponse) {
        if (loadedRequestId == response.requestId) return
        loadedRequestId = response.requestId
        viewModelScope.launch {
            _state.value = ProposalReviewState(loading = true)
            val tasks = store.allTasks()
            _state.value = ProposalReviewState(loading = false, rows = response.operations.map { seed(response.requestId, it, tasks) })
        }
    }

    fun setSelected(id: String, selected: Boolean) = update(id) { row -> row.copy(selected = selected) }

    fun chooseTask(id: String, taskId: String) = update(id) { row -> normalize(row.copy(matchedTaskId = taskId)) }
    fun editTitle(id: String, value: String) = update(id) { row -> normalize(row.copy(editedTitle = value)) }
    fun editDescription(id: String, value: String) = update(id) { row -> normalize(row.copy(editedDescription = value)) }
    fun selectDate(id: String, value: LocalDate?) = update(id) { row ->
        normalize(row.withDateSelection(value))
    }
    fun selectTime(id: String, value: LocalTime?) = update(id) { row ->
        normalize(row.withTimeSelection(value))
    }

    fun applySelected() {
        if (_state.value.applying) return
        val rows = _state.value.rows
        val approved = rows.filter { it.selected && it.canApply }.map { it.executionKey }.toSet()
        if (approved.isEmpty() || rows.any { it.selected && !it.canApply }) return
        _state.value = _state.value.copy(applying = true, summary = null)
        viewModelScope.launch {
            val outcomes = coordinator.executeAfterApproval(rows, approved).associateBy { it.operationId }
            val latest = _state.value.rows.map { row ->
                val result = outcomes[row.executionKey] ?: return@map row
                row.copy(selected = false, outcome = result)
            }
            val success = outcomes.values.count { it.succeeded }
            val failure = outcomes.size - success
            _state.value = _state.value.copy(
                rows = latest,
                applying = false,
                summary = "$success operation(s) applied; $failure operation(s) failed or were not applied.",
            )
        }
    }

    private fun seed(requestId: String, operation: ProposedOperation, tasks: List<TaskEntity>): ReviewProposal {
        val target = when (operation) {
            is UpdateProposal -> operation.targetText
            is CompleteProposal -> operation.targetText
            is DeleteProposal -> operation.targetText
            is AddProposal -> null
        }
        val result = target?.let { matcher.match(it, tasks) }
        val candidateTasks = when (result?.quality) {
            MatchQuality.NONE -> tasks.map { TaskMatch(it, 0.0) }
            else -> result?.matches.orEmpty()
        }
        val (datePhrase, timePhrase) = operation.dateAndTimePhrases()
        val resolved = resolver.resolve(datePhrase, timePhrase)
        val addProposal = operation as? AddProposal
        val matchIssue = when (result?.quality) {
            MatchQuality.AMBIGUOUS -> "Several tasks match. Choose the intended task."
            MatchQuality.LOW_CONFIDENCE -> "A possible match was found, but it is uncertain. Choose a task manually."
            MatchQuality.NONE -> "No confident task match. Search the existing tasks and choose one manually."
            else -> null
        }
        val row = ReviewProposal(
            operation = operation,
            executionKey = "$requestId:${operation.operationId}",
            matchedTaskId = result?.automaticMatch?.id,
            matches = candidateTasks,
            matchQuality = result?.quality,
            dueDateInput = resolved.date?.toString().orEmpty(),
            dueTimeInput = resolved.time?.toString()?.take(5).orEmpty(),
            editedTitle = addProposal?.let { TaskTitleNormalizer.normalize(it.title, it.sourceText, it.datePhrase, it.timePhrase) }
                ?: (operation as? UpdateProposal)?.changes?.title.orEmpty(),
            issues = listOfNotNull(matchIssue) + resolved.unresolved,
        )
        // All proposals start included. A user may explicitly exclude an unresolved
        // proposal, or resolve it inline before the single batch approval.
        return row.copy(selected = true)
    }

    private fun normalize(row: ReviewProposal): ReviewProposal {
        val (datePhrase, explicitTimePhrase) = row.operation.dateAndTimePhrases()
        val timePhrase = explicitTimePhrase ?: datePhrase.takeIf { it.requiresExactTime() }
        val dateErrors = mutableListOf<String>()
        if (!row.dateClearedByUser && (datePhrase != null || timePhrase.impliesDate()) && runCatching { LocalDate.parse(row.dueDateInput) }.isFailure) dateErrors += "Choose a specific date in the calendar."
        if (!row.timeClearedByUser && timePhrase != null && runCatching { LocalTime.parse(row.dueTimeInput) }.isFailure) dateErrors += "Choose a valid time in the clock picker."
        val chosenDate = runCatching { LocalDate.parse(row.dueDateInput) }.getOrNull()
        val chosenTime = runCatching { LocalTime.parse(row.dueTimeInput) }.getOrNull()
        val now = java.time.LocalDateTime.now(clock)
        if (chosenDate != null && (chosenDate.isBefore(now.toLocalDate()) ||
                (chosenTime != null && chosenDate.atTime(chosenTime).isBefore(now)))) {
            dateErrors += "This is in the past. Select a future date and time."
        }
        val targetIssue = row.issues.firstOrNull { it.startsWith("Several tasks") || it.startsWith("A possible match") || it.startsWith("No confident") }
            ?.takeIf { row.matchedTaskId == null }
        val normalized = row.copy(issues = listOfNotNull(targetIssue) + dateErrors)
        return normalized
    }

    private inline fun update(id: String, transform: (ReviewProposal) -> ReviewProposal) {
        _state.value = _state.value.copy(rows = _state.value.rows.map { row ->
            if (row.operationId == id) transform(row) else row
        })
    }
}
