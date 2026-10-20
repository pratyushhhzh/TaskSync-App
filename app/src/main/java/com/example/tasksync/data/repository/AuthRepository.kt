package com.example.tasksync.data.repository

import com.example.tasksync.data.model.UserSession
import com.example.tasksync.data.remote.SupabaseConfig
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.gotrue.SessionStatus
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.TimeZone

class AuthRepository {
    val isConfigured: Boolean
        get() = SupabaseConfig.isConfigured

    private val client by lazy { SupabaseConfig.client }

    suspend fun currentSession(): UserSession? {
        if (!isConfigured) return null
        client.auth.sessionStatus.first { it !is SessionStatus.LoadingFromStorage }
        return client.auth.currentSessionOrNull()?.let { session ->
            val user = session.user ?: return@let null
            UserSession(
                userId = user.id,
                email = user.email.orEmpty(),
                accessToken = session.accessToken,
                refreshToken = session.refreshToken
            )
        }
    }

    suspend fun login(email: String, password: String): Result<UserSession> = runCatching {
        requireConfigured()
        require(email.isNotBlank() && password.isNotBlank()) { "Enter your email and password." }
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        currentSession() ?: error("Sign in completed, but no session was returned.")
    }

    suspend fun register(email: String, password: String, fullName: String): Result<UserSession?> = runCatching {
        requireConfigured()
        require(email.isNotBlank()) { "Enter your email address." }
        require(password.length >= 6) { "Password must be at least 6 characters." }

        client.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject { put("full_name", fullName.trim()) }
        }
        // Supabase may require email confirmation, in which case it intentionally
        // returns without an authenticated session.
        currentSession()
    }

    suspend fun logout() {
        if (isConfigured) client.auth.signOut()
    }

    fun getUserTimezone(): String = TimeZone.getDefault().id

    private fun requireConfigured() {
        check(isConfigured) {
            "Supabase is not configured. Add supabase.url and supabase.anonKey to local.properties."
        }
    }
}
