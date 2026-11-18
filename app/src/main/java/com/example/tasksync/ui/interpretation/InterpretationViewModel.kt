package com.example.tasksync.ui.interpretation

import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tasksync.BuildConfig
import com.example.tasksync.data.remote.InterpretationException
import com.example.tasksync.data.remote.InterpretationRepository
import com.example.tasksync.data.remote.InterpretationResponse
import com.example.tasksync.data.remote.AndroidNetworkDiagnostics
import com.example.tasksync.data.remote.deviceLocaleHint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface InterpretationUiState {
    data object Idle : InterpretationUiState
    data class Loading(val attempt: Int) : InterpretationUiState
    data class Success(val response: InterpretationResponse, val transcript: String, val attempt: Int) : InterpretationUiState
    data class Failure(val message: String, val retryable: Boolean, val attempt: Int) : InterpretationUiState
}

class InterpretationViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val repository = InterpretationRepository(
        baseUrl = BuildConfig.INTERPRETATION_BASE_URL,
        backendToken = BuildConfig.BACKEND_AUTH_TOKEN,
        networkSummary = if (BuildConfig.DEBUG) ({ AndroidNetworkDiagnostics.summary(application) }) else null,
        debugLog = if (BuildConfig.DEBUG) ({ message -> Log.d("TaskSyncNetwork", message) }) else null,
    )
    private val _state = MutableStateFlow<InterpretationUiState>(InterpretationUiState.Idle)
    val state: StateFlow<InterpretationUiState> = _state.asStateFlow()
    private var transcript: String? = null
    private var requestJob: Job? = null
    private var activeAttempt = 0

    fun isCurrentAttempt(attempt: Int): Boolean = attempt == activeAttempt

    fun interpret(confirmedTranscript: String) {
        transcript = confirmedTranscript
        request(confirmedTranscript)
    }

    fun retry() {
        transcript?.let(::request)
    }

    private fun request(text: String) {
        val attempt = ++activeAttempt
        requestJob?.cancel()
        _state.value = InterpretationUiState.Loading(attempt)
        requestJob = viewModelScope.launch {
            _state.value = try {
                InterpretationUiState.Success(repository.interpret(text, deviceLocaleHint()), text, attempt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: InterpretationException) {
                InterpretationUiState.Failure(error.message ?: "Interpretation failed.", error.retryable, attempt)
            } catch (_: Exception) {
                InterpretationUiState.Failure("Could not interpret the transcript. Please retry.", true, attempt)
            }
        }
    }
}
