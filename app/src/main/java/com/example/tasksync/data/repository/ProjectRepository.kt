package com.example.tasksync.data.repository

import com.example.tasksync.data.local.dao.ProjectDao
import com.example.tasksync.data.local.dao.TaskDao
import com.example.tasksync.data.local.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow

class ProjectRepository(
    private val projectDao: ProjectDao,
    private val taskDao: TaskDao
) {
    val projects: Flow<List<ProjectEntity>> = projectDao.observeProjects()

    suspend fun createProject(name: String, description: String?) {
        projectDao.insertProject(
            ProjectEntity(
                id = "project_${System.currentTimeMillis()}_${(1000..9999).random()}",
                name = name.trim(),
                description = description?.trim()?.ifBlank { null }
            )
        )
    }

    suspend fun deleteProject(project: ProjectEntity) {
        taskDao.removeProjectFromTasks(project.id, System.currentTimeMillis())
        projectDao.deleteProject(project)
    }
}
