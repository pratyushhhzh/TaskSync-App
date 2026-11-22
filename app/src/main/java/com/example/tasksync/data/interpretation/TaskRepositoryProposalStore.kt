package com.example.tasksync.data.interpretation

import com.example.tasksync.data.local.dao.TaskDao
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.data.repository.TaskRepository

/** Keeps proposal mutations on the existing repository path so reminders, calendar, and widget stay synchronized. */
class TaskRepositoryProposalStore(
    private val repository: TaskRepository,
    private val taskDao: TaskDao,
) : ProposalTaskStore {
    override suspend fun allTasks(): List<TaskEntity> = taskDao.getAllTasksOnce()
    override suspend fun taskById(id: String): TaskEntity? = repository.getTaskById(id)
    override suspend fun add(
        title: String,
        description: String?,
        dueDate: String?,
        dueTime: String?,
        priority: Int,
        reminderRequested: Boolean,
        notificationPermissionGranted: Boolean,
    ): ProposalTaskCreationResult {
        val result = repository.createTaskWithReminderOutcome(
            title, description, dueDate, dueTime, reminderRequested, notificationPermissionGranted, priority,
        )
        return ProposalTaskCreationResult(result.task, result.reminderStatus)
    }
    override suspend fun update(task: TaskEntity) = repository.updateTask(task)
    override suspend fun complete(id: String): Boolean {
        val task = repository.getTaskById(id) ?: return false
        if (task.status == "completed") return true
        repository.markTaskCompleted(id)
        return repository.getTaskById(id)?.status == "completed"
    }
    override suspend fun delete(task: TaskEntity) {
        repository.deleteTask(task)
    }
}
