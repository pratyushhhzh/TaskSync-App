package com.example.tasksync.ui.tasks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.tasksync.data.local.AppDatabase
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.data.local.entity.ProjectEntity
import com.example.tasksync.data.local.entity.SubtaskEntity
import com.example.tasksync.ui.assistant.VoiceTaskAction
import com.example.tasksync.ui.assistant.VoiceTaskBrain
import com.example.tasksync.ui.assistant.VoiceInterpretation
import com.example.tasksync.data.repository.ProjectRepository
import com.example.tasksync.data.repository.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TaskListViewModel(application: Application) : AndroidViewModel(application) {

    private sealed interface VoiceUndoAction {
        data class Restore(val task: TaskEntity) : VoiceUndoAction
        data class Delete(val taskId: String) : VoiceUndoAction
        data class DeleteSubtask(val subtask: SubtaskEntity) : VoiceUndoAction
    }

    private var lastVoiceUndo: List<VoiceUndoAction> = emptyList()

    private val database: AppDatabase = AppDatabase.getDatabase(application)
    private val repository: TaskRepository
    private val projectRepository: ProjectRepository

    val allTasks: StateFlow<List<TaskEntity>>
    val inboxTasks: StateFlow<List<TaskEntity>>
    val projects: StateFlow<List<ProjectEntity>>

    init {
        val taskDao = database.taskDao()
        repository = TaskRepository(taskDao, application.applicationContext)
        projectRepository = ProjectRepository(database.projectDao(), taskDao)

        allTasks = repository.allTasks.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        inboxTasks = repository.inboxTasks.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        projects = projectRepository.projects.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    fun createTask(
        title: String,
        description: String? = null,
        status: String = "inbox",
        dueDate: String? = null,
        dueTime: String? = null,
        reminderEnabled: Boolean = false,
        recurrenceRule: String? = null,
        priority: Int = 4,
        projectId: String? = null
    ) {
        viewModelScope.launch {
            repository.createTask(
                title = title,
                description = description,
                status = status,
                dueDate = dueDate,
                dueTime = dueTime,
                reminderEnabled = reminderEnabled,
                recurrenceRule = recurrenceRule,
                priority = priority,
                projectId = projectId
            )
        }
    }

    fun updateTask(task: TaskEntity) {
        viewModelScope.launch {
            repository.updateTask(task)
        }
    }

    fun toggleTaskCompletion(task: TaskEntity, isCompleted: Boolean) {
        viewModelScope.launch {
            if (isCompleted) {
                repository.markTaskCompleted(task.id)
            } else {
                repository.reopenTask(task.id, newStatus = "inbox")
            }
        }
    }

    fun moveTaskToStatus(task: TaskEntity, status: String) {
        viewModelScope.launch { repository.setTaskStatus(task.id, status) }
    }

    fun executeVoiceActions(actions: List<VoiceTaskAction>, onFinished: (String, Boolean) -> Unit) {
        viewModelScope.launch {
            val undoActions = mutableListOf<VoiceUndoAction>()
            var created = 0
            var updated = 0
            var completed = 0
            var reopened = 0
            var deleted = 0
            var subtasksAdded = 0
            try {
                database.withTransaction {
                    actions.forEach { action ->
                when (action) {
                    is VoiceTaskAction.Create -> {
                        val task = repository.createTask(
                            title = action.title,
                            description = action.description,
                            dueDate = action.dueDate,
                            dueTime = action.dueTime,
                            priority = action.priority,
                            reminderEnabled = action.reminderEnabled,
                            recurrenceRule = action.recurrenceRule
                        )
                        undoActions += VoiceUndoAction.Delete(task.id)
                        created++
                    }
                    is VoiceTaskAction.Update -> {
                        repository.updateTask(action.after)
                        undoActions += VoiceUndoAction.Restore(action.before)
                        updated++
                    }
                    is VoiceTaskAction.Complete -> {
                        val before = repository.getTaskById(action.task.id) ?: action.task
                        val nextOccurrence = repository.markTaskCompleted(action.task.id)
                        undoActions += VoiceUndoAction.Restore(before)
                        nextOccurrence?.let { undoActions += VoiceUndoAction.Delete(it.id) }
                        completed++
                    }
                    is VoiceTaskAction.Reopen -> {
                        undoActions += VoiceUndoAction.Restore(action.task)
                        repository.reopenTask(action.task.id)
                        reopened++
                    }
                    is VoiceTaskAction.Delete -> {
                        undoActions += VoiceUndoAction.Restore(action.task)
                        repository.deleteTask(action.task)
                        deleted++
                    }
                    is VoiceTaskAction.AddSubtask -> {
                        val dao = AppDatabase.getDatabase(getApplication()).taskDao()
                        val subtask = SubtaskEntity(
                            id = "subtask_${System.currentTimeMillis()}_${(1000..9999).random()}",
                            taskId = action.task.id,
                            title = action.title.trim(),
                            sortOrder = dao.getSubtasksForTask(action.task.id).first().size
                        )
                        dao.insertSubtask(subtask)
                        undoActions += VoiceUndoAction.DeleteSubtask(subtask)
                        subtasksAdded++
                    }
                }
                    }
                }
            } catch (_: Exception) {
                onFinished("I couldn't save those changes. Please review and try again.", false)
                return@launch
            }
            lastVoiceUndo = undoActions
            val summary = buildList {
                if (created > 0) add("Added $created task${if (created == 1) "" else "s"}")
                if (updated > 0) add("Updated $updated task${if (updated == 1) "" else "s"}")
                if (completed > 0) add("Completed $completed task${if (completed == 1) "" else "s"}")
                if (reopened > 0) add("Reopened $reopened task${if (reopened == 1) "" else "s"}")
                if (deleted > 0) add("Deleted $deleted task${if (deleted == 1) "" else "s"}")
                if (subtasksAdded > 0) add("Added $subtasksAdded checklist item${if (subtasksAdded == 1) "" else "s"}")
            }.joinToString(" · ")
            onFinished(summary.ifBlank { "Done." }, true)
        }
    }

    fun interpretVoiceTranscript(
        transcript: String,
        tasks: List<TaskEntity>,
        onFinished: (VoiceInterpretation) -> Unit
    ) {
        viewModelScope.launch {
            onFinished(VoiceTaskBrain.interpret(transcript, tasks))
        }
    }

    fun applyVoiceActions(actions: List<VoiceTaskAction>, onFinished: (String, Boolean) -> Unit) {
        executeVoiceActions(actions, onFinished)
    }

    fun undoLastVoiceActions(onFinished: (String) -> Unit) {
        viewModelScope.launch {
            val undoActions = lastVoiceUndo.asReversed()
            if (undoActions.isEmpty()) {
                onFinished("There is nothing to undo.")
                return@launch
            }
            undoActions.forEach { action ->
                when (action) {
                    is VoiceUndoAction.Restore -> repository.restoreTask(action.task)
                    is VoiceUndoAction.Delete -> repository.getTaskById(action.taskId)?.let { repository.deleteTask(it) }
                    is VoiceUndoAction.DeleteSubtask -> AppDatabase.getDatabase(getApplication()).taskDao().deleteSubtask(action.subtask)
                }
            }
            lastVoiceUndo = emptyList()
            onFinished("Undid the last voice action.")
        }
    }

    fun deleteTask(task: TaskEntity) {
        viewModelScope.launch {
            repository.deleteTask(task)
        }
    }

    fun createProject(name: String, description: String? = null, deadlineDate: String? = null) {
        if (name.isBlank()) return
        viewModelScope.launch { projectRepository.createProject(name, description, deadlineDate) }
    }

    fun deleteProject(project: ProjectEntity) {
        viewModelScope.launch { projectRepository.deleteProject(project) }
    }

    fun tasksForProject(projectId: String): Flow<List<TaskEntity>> = repository.getTasksForProject(projectId)

    fun subtasksForTask(taskId: String): Flow<List<SubtaskEntity>> =
        AppDatabase.getDatabase(getApplication()).taskDao().getSubtasksForTask(taskId)

    fun addSubtask(taskId: String, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val nextOrder = subtasksForTask(taskId).first().size
            AppDatabase.getDatabase(getApplication()).taskDao().insertSubtask(
                SubtaskEntity(
                    id = "subtask_${System.currentTimeMillis()}_${(1000..9999).random()}",
                    taskId = taskId,
                    title = title.trim(),
                    sortOrder = nextOrder
                )
            )
        }
    }

    fun toggleSubtask(subtask: SubtaskEntity, isCompleted: Boolean) {
        viewModelScope.launch {
            AppDatabase.getDatabase(getApplication()).taskDao().updateSubtask(subtask.copy(isCompleted = isCompleted))
        }
    }

    fun deleteSubtask(subtask: SubtaskEntity) {
        viewModelScope.launch { AppDatabase.getDatabase(getApplication()).taskDao().deleteSubtask(subtask) }
    }
}
