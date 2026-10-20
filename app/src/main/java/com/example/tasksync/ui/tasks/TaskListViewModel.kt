package com.example.tasksync.ui.tasks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tasksync.data.local.AppDatabase
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.data.repository.TaskRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TaskListViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: TaskRepository

    val todayTasks: StateFlow<List<TaskEntity>>
    val inboxTasks: StateFlow<List<TaskEntity>>
    val upcomingTasks: StateFlow<List<TaskEntity>>
    val completedTasks: StateFlow<List<TaskEntity>>

    init {
        val taskDao = AppDatabase.getDatabase(application).taskDao()
        repository = TaskRepository(taskDao)

        todayTasks = repository.todayTasks.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        inboxTasks = repository.inboxTasks.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        upcomingTasks = repository.upcomingTasks.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        completedTasks = repository.completedTasks.stateIn(
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
                repository.reopenTask(task.id, newStatus = if (!task.dueDate.isNullOrEmpty()) "today" else "inbox")
            }
        }
    }

    fun deleteTask(task: TaskEntity) {
        viewModelScope.launch {
            repository.deleteTask(task)
        }
    }
}
