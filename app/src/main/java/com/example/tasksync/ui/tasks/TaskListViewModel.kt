package com.example.tasksync.ui.tasks

import android.app.Application
import androidx.room.withTransaction
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tasksync.data.local.AppDatabase
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.data.local.entity.ProjectEntity
import com.example.tasksync.data.local.entity.SubtaskEntity
import com.example.tasksync.data.repository.ProjectRepository
import com.example.tasksync.data.repository.TaskRepository
import com.example.tasksync.data.calendar.GoogleDeviceCalendar
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
    private var isApplyingVoiceBatch = false

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

    val connectedGoogleCalendarLabel: String? get() = repository.connectedGoogleCalendarLabel

    suspend fun availableGoogleCalendars(): List<GoogleDeviceCalendar> = repository.availableGoogleCalendars()

    suspend fun connectGoogleCalendar(calendar: GoogleDeviceCalendar): List<String> = repository.connectGoogleCalendar(calendar)

    suspend fun disconnectGoogleCalendar(): List<String> = repository.disconnectGoogleCalendar()

    /** Applies the reviewed action batch atomically; reminders are synchronized after commit. */
    suspend fun applyConfirmedVoiceActions(actions: List<ProposedVoiceAction>): List<String> {
        check(!isApplyingVoiceBatch) { "These task changes are already being saved." }
        if (actions.isEmpty()) return emptyList()
        isApplyingVoiceBatch = true
        val touchedTaskIds = linkedSetOf<String>()
        var tasksBeforeBatch = emptyMap<String, TaskEntity>()
        try {
            val duplicateTargets = actions.mapNotNull { action ->
                if (action.type in setOf(VoiceActionType.UPDATE, VoiceActionType.COMPLETE, VoiceActionType.DELETE)) action.targetTaskId else null
            }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            require(duplicateTargets.isEmpty()) { "More than one proposed change targets the same task. Remove the duplicate change and try again." }

            database.withTransaction {
                val current = database.taskDao().getAllTasksOnce().associateBy { it.id }.toMutableMap()
                tasksBeforeBatch = current.toMap()
                actions.forEach { action ->
                    when (action.type) {
                        VoiceActionType.CREATE -> {
                            val title = action.title.trim()
                            require(title.isNotBlank()) { "A proposed task has no title." }
                            val created = TaskEntity(
                                id = "task_${System.currentTimeMillis()}_${(1000..9999).random()}",
                                userId = "local_user",
                                title = title,
                                dueDate = action.dueDate,
                                dueTime = action.dueTime,
                                reminderEnabled = action.dueTime != null,
                                recurrenceRule = action.recurrenceRule,
                                priority = 4
                            )
                            touchedTaskIds += created.id
                            database.taskDao().insertTask(created)
                            current[created.id] = created
                        }
                        VoiceActionType.UPDATE -> {
                            val id = action.targetTaskId ?: error("Missing task target.")
                            val old = current[id] ?: error("The selected target task no longer exists.")
                            val updated = old.copy(
                                title = action.title.takeIf(String::isNotBlank) ?: old.title,
                                dueDate = action.dueDate ?: old.dueDate,
                                dueTime = action.dueTime ?: old.dueTime,
                                recurrenceRule = action.recurrenceRule ?: old.recurrenceRule,
                                reminderEnabled = if (action.dueTime != null) true else old.reminderEnabled,
                                updatedAt = System.currentTimeMillis()
                            )
                            touchedTaskIds += id
                            database.taskDao().updateTask(updated)
                            current[id] = updated
                        }
                        VoiceActionType.COMPLETE -> {
                            val id = action.targetTaskId ?: error("Missing task target.")
                            val old = current[id] ?: error("The selected target task no longer exists.")
                            touchedTaskIds += id
                            check(database.taskDao().updateTaskStatus(id, "completed", System.currentTimeMillis()) == 1) {
                                "The selected task could not be completed."
                            }
                            current[id] = old.copy(status = "completed", updatedAt = System.currentTimeMillis())
                            repository.buildNextOccurrence(old)?.let { next ->
                                touchedTaskIds += next.id
                                database.taskDao().insertTask(next)
                                current[next.id] = next
                            }
                        }
                        VoiceActionType.DELETE -> {
                            val id = action.targetTaskId ?: error("Missing task target.")
                            val task = current[id] ?: error("The selected target task no longer exists.")
                            touchedTaskIds += id
                            database.taskDao().deleteTaskAndSubtasks(task)
                            current.remove(id)
                        }
                        VoiceActionType.NONE -> Unit
                    }
                }
            }

            val reminderErrors = mutableListOf<String>()
            for (id in touchedTaskIds) {
                val task = runCatching { database.taskDao().getTaskById(id) }.getOrNull()
                runCatching { repository.synchronizeReminder(id, task) }
                    .onFailure { reminderErrors += "Reminder for ‘${task?.title ?: "task"}’ could not be scheduled." }
                repository.synchronizeCalendar(task, tasksBeforeBatch[id])?.let(reminderErrors::add)
            }
            repository.refreshTaskWidget()
            return reminderErrors
        } catch (error: Throwable) {
            val restored = runCatching { database.taskDao().getAllTasksOnce().associateBy { it.id } }.getOrDefault(emptyMap())
            for (id in touchedTaskIds) {
                runCatching { repository.synchronizeReminder(id, restored[id]) }
            }
            throw error
        } finally {
            isApplyingVoiceBatch = false
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
