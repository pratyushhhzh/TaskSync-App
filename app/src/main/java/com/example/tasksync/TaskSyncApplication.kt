package com.example.tasksync

import android.app.Application
import com.example.tasksync.data.repository.AuthRepository

class TaskSyncApplication : Application() {
    lateinit var authRepository: AuthRepository
        private set

    override fun onCreate() {
        super.onCreate()
        authRepository = AuthRepository()
    }
}
