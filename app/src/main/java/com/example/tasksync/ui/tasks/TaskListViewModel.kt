package com.example.tasksync.ui.tasks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tasksync.data.local.AppDatabase
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.data.local.entity.ProjectEntity
import com.example.tasksync.data.local.entity.SubtaskEntity
import com.example.tasksync.data.repository.ProjectRepository
import com.example.tasksync.data.repository.TaskRepository
import com.example.tasksync.ai.ProposedVoiceAction
import com.example.tasksync.ai.VoiceActionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TaskListViewModel(application: Application) : AndroidViewModel(application) {

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

    fun deleteTask(task: TaskEntity) {
        viewModelScope.launch {
            repository.deleteTask(task)
        }
    }

    /** Called only by the voice review's explicit Apply button. */
    suspend fun applyConfirmedVoiceActions(actions: List<ProposedVoiceAction>) {
        val current = allTasks.value.associateBy { it.id }
        val rollback = mutableListOf<suspend () -> Unit>()
        try {
            actions.forEach { action ->
                when (action.type) {
                    VoiceActionType.CREATE -> {
                        val created = repository.createTask(
                    title = action.title.trim(),
                    description = action.details?.takeIf(String::isNotBlank)
                        ?: action.whenText?.takeIf { action.dueDate == null }?.let { "When: $it" },
                    dueDate = action.dueDate,
                    dueTime = action.dueTime,
                    priority = action.priority ?: 4,
                            projectId = action.project?.let { name -> projects.value.firstOrNull { it.name.equals(name, true) }?.id }
                        )
                        rollback += { repository.deleteTask(created) }
                    }
                    VoiceActionType.UPDATE -> {
                        val old = action.targetTaskId?.let(current::get) ?: error("The selected target task no longer exists.")
                    val description = action.details?.takeIf(String::isNotBlank)
                        ?: action.whenText?.takeIf { action.dueDate == null }?.let { "When: $it" }
                        ?: old.description
                        repository.updateTask(old.copy(
                        title = action.title.takeIf(String::isNotBlank) ?: old.title,
                        description = description,
                        dueDate = action.dueDate ?: old.dueDate,
                        dueTime = action.dueTime ?: old.dueTime,
                        priority = action.priority ?: old.priority,
                        projectId = action.project?.let { name -> projects.value.firstOrNull { it.name.equals(name, true) }?.id } ?: old.projectId
                        ))
                        rollback += { repository.updateTask(old) }
                    }
                    VoiceActionType.COMPLETE -> {
                        val id = action.targetTaskId ?: error("Missing task target.")
                        val old = current[id] ?: error("The selected target task no longer exists.")
                        val nextOccurrence = repository.markTaskCompleted(id)
                        rollback += {
                            repository.reopenTask(id, old.status)
                            nextOccurrence?.let { repository.deleteTask(it) }
                        }
                    }
                    VoiceActionType.DELETE -> {
                        val task = action.targetTaskId?.let(current::get) ?: error("The selected target task no longer exists.")
                        repository.deleteTask(task)
                        rollback += { repository.restoreTask(task) }
                    }
                    VoiceActionType.NONE -> Unit
                }
            }
        } catch (error: Throwable) {
            rollback.asReversed().forEach { undo -> runCatching { undo() } }
            throw error
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
