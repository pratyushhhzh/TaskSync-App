package com.example.tasksync.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tasksync.TaskSyncApplication
import com.example.tasksync.data.model.UserSession
import com.example.tasksync.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AuthUiState {
    data object Idle : AuthUiState()
    data object Loading : AuthUiState()
    data class Authenticated(val session: UserSession) : AuthUiState()
    data class Error(val message: String) : AuthUiState()
    data class Message(val message: String) : AuthUiState()
}

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val authRepository: AuthRepository = (application as TaskSyncApplication).authRepository

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            if (!authRepository.isConfigured) {
                _uiState.value = AuthUiState.Error(
                    "Supabase is not configured. Add supabase.url and supabase.anonKey to local.properties."
                )
                return@launch
            }
            runCatching { authRepository.currentSession() }
                .onSuccess { session ->
                    _uiState.value = session?.let(AuthUiState::Authenticated) ?: AuthUiState.Idle
                }
                .onFailure { error ->
                    _uiState.value = AuthUiState.Error(error.localizedMessage ?: "Could not restore your session.")
                }
        }
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            authRepository.login(email, password).fold(
                onSuccess = { _uiState.value = AuthUiState.Authenticated(it) },
                onFailure = { _uiState.value = AuthUiState.Error(it.message ?: "Sign in failed.") }
            )
        }
    }

    fun register(email: String, password: String, fullName: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            authRepository.register(email, password, fullName).fold(
                onSuccess = { session ->
                    _uiState.value = if (session != null) {
                        AuthUiState.Authenticated(session)
                    } else {
                        AuthUiState.Message("Account created. Check your email to confirm it, then sign in.")
                    }
                },
                onFailure = { _uiState.value = AuthUiState.Error(it.message ?: "Sign up failed.") }
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            runCatching { authRepository.logout() }
                .onSuccess { _uiState.value = AuthUiState.Idle }
                .onFailure { _uiState.value = AuthUiState.Error(it.message ?: "Sign out failed.") }
        }
    }
}
