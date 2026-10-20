package com.example.tasksync.data.repository

import com.example.tasksync.data.model.UserSession
import java.util.TimeZone

/**
 * Placeholder retained for the unlinked sign-in prototype. The current app is
 * local-first, so authentication is intentionally unavailable in this phase.
 */
class AuthRepository {
    val isConfigured: Boolean = false

    suspend fun currentSession(): UserSession? = null

    suspend fun login(email: String, password: String): Result<UserSession> =
        Result.failure(UnsupportedOperationException("Sign-in is not part of the local task manager yet."))

    suspend fun register(email: String, password: String, fullName: String): Result<UserSession?> =
        Result.failure(UnsupportedOperationException("Account creation is not part of the local task manager yet."))

    suspend fun logout() = Unit

    fun getUserTimezone(): String = TimeZone.getDefault().id
}
