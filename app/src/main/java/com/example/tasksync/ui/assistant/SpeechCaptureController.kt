package com.example.tasksync.ui.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Captures speech until the user explicitly taps the microphone to stop. */
class SpeechCaptureController(
    context: Context,
    private val onStarting: () -> Unit,
    private val onListening: () -> Unit,
    private val onPartialTranscript: (String) -> Unit,
    private val onTranscript: (String, List<String>) -> Unit,
    private val onError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var completedTranscript = ""
    private var currentPartial = ""
    private var finalAlternatives = emptyList<String>()
    private var isListening = false
    private var readyNotified = false
    private var stopRequested = false
    private var restartFailures = 0
    private var pendingRestart: Runnable? = null
    private var recreateBeforeRestart = false
    private var lastRestartError = "Speech recognition kept stopping. Please try again."
    private var languageTag = ENGLISH_INDIA

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onError("Speech recognition isn't available on this device. Try installing or enabling its speech service.")
            return
        }
        runCatching {
            cancelPendingRestart()
            completedTranscript = ""
            currentPartial = ""
            finalAlternatives = emptyList()
            restartFailures = 0
            readyNotified = false
            recreateBeforeRestart = false
            lastRestartError = "Speech recognition kept stopping. Please try again."
            languageTag = ENGLISH_INDIA
            stopRequested = false
            isListening = true
            ensureRecognizer()
            onStarting()
            startSegment()
        }.onFailure {
            isListening = false
            destroyRecognizer()
            onError("Couldn't start voice recognition. Check microphone permission and try again.")
        }
    }

    /** Ends capture only after the user taps the microphone again. */
    fun stop() {
        if (!isListening || stopRequested) return
        stopRequested = true
        if (pendingRestart != null) {
            finishWithAccumulatedSpeech("I couldn't hear a clear request. Try speaking again.")
            return
        }
        cancelPendingRestart()
        runCatching { recognizer?.stopListening() }
            .onFailure { finishWithAccumulatedSpeech("I couldn't finish voice recognition. Please try again.") }
    }

    fun destroy() {
        isListening = false
        stopRequested = false
        cancelPendingRestart()
        destroyRecognizer()
        completedTranscript = ""
        currentPartial = ""
        finalAlternatives = emptyList()
    }

    private fun startSegment() {
        if (!isListening || stopRequested) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Keep a few alternatives so the user can select the transcript that
            // best matches what they actually said.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_TRANSCRIPT_CANDIDATES)
            // Do not configure short silence thresholds. Silence is a segment boundary,
            // not an instruction to finish the user's push-to-talk session.
        }
        recognizer?.startListening(intent)
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }
        recognizer?.setRecognitionListener(listener)
    }

    private fun scheduleRestart(
        delayMillis: Long,
        countFailure: Boolean = false,
        recreate: Boolean = false,
        failureMessage: String = lastRestartError
    ) {
        if (!isListening || stopRequested) return
        if (countFailure) {
            restartFailures++
            lastRestartError = failureMessage
            if (restartFailures > MAX_RESTART_FAILURES) {
                finishWithAccumulatedSpeech(lastRestartError)
                return
            }
        }
        recreateBeforeRestart = recreateBeforeRestart || recreate
        cancelPendingRestart()
        pendingRestart = Runnable {
            pendingRestart = null
            runCatching {
                if (recreateBeforeRestart) {
                    destroyRecognizer()
                    ensureRecognizer()
                    recreateBeforeRestart = false
                }
                startSegment()
            }.onFailure {
                scheduleRestart(RETRY_DELAY_MS, countFailure = true, recreate = true)
            }
        }.also { handler.postDelayed(it, delayMillis) }
    }

    private fun acceptSegment(candidates: List<String>?) {
        val cleanCandidates = candidates.orEmpty().map { it.trim() }.filter(String::isNotBlank).distinct()
        val fragment = cleanCandidates.firstOrNull() ?: currentPartial
        val precedingText = completedTranscript
        completedTranscript = VoiceTranscriptStitcher.append(completedTranscript, fragment)
        if (cleanCandidates.isNotEmpty()) {
            finalAlternatives = cleanCandidates.drop(1)
                .map { VoiceTranscriptStitcher.append(precedingText, it) }
                .filter { it != completedTranscript }
        } else if (currentPartial.isNotBlank()) {
            finalAlternatives = emptyList()
        }
        currentPartial = ""
        onPartialTranscript(completedTranscript)
    }

    private fun finishWithAccumulatedSpeech(emptyMessage: String) {
        isListening = false
        stopRequested = false
        cancelPendingRestart()
        val transcript = VoiceTranscriptStitcher.append(completedTranscript, currentPartial)
        currentPartial = ""
        if (transcript.isBlank()) onError(emptyMessage) else onTranscript(transcript, finalAlternatives)
    }

    private fun cancelPendingRestart() {
        pendingRestart?.let(handler::removeCallbacks)
        pendingRestart = null
    }

    private fun destroyRecognizer() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun notifyReadyForSpeech() {
        if (!isListening || stopRequested || readyNotified) return
        readyNotified = true
        onListening()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { notifyReadyForSpeech() }
        override fun onBeginningOfSpeech() {
            restartFailures = 0
            notifyReadyForSpeech()
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            notifyReadyForSpeech()
            currentPartial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            onPartialTranscript(VoiceTranscriptStitcher.append(completedTranscript, currentPartial))
        }

        override fun onResults(results: Bundle?) {
            notifyReadyForSpeech()
            val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            acceptSegment(candidates)
            restartFailures = 0
            if (stopRequested) {
                finishWithAccumulatedSpeech("I couldn't hear a clear request. Try speaking again.")
            } else {
                scheduleRestart(SEGMENT_RESTART_DELAY_MS)
            }
        }

        override fun onError(error: Int) {
            if (!isListening) return
            acceptSegment(null)
            if (stopRequested) {
                finishWithAccumulatedSpeech("I couldn't hear a clear request. Try speaking again.")
                return
            }

            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // These are normal pauses between utterances. Reuse the ready recognizer;
                    // destroying/rebinding it for every silence timeout can make it busy.
                    scheduleRestart(SEGMENT_RESTART_DELAY_MS)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    finishWithAccumulatedSpeech("Microphone permission is needed for voice capture.")
                SpeechRecognizer.ERROR_AUDIO ->
                    finishWithAccumulatedSpeech("Microphone audio failed. Please try again.")
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> scheduleRestart(
                    RETRY_DELAY_MS,
                    countFailure = true,
                    recreate = true,
                    failureMessage = restartErrorMessage(error)
                )
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    if (languageTag == ENGLISH_INDIA) {
                        languageTag = ENGLISH_US
                        restartFailures = 0
                        scheduleRestart(RETRY_DELAY_MS, recreate = true)
                    } else {
                        finishWithAccumulatedSpeech("No supported English voice is available in the installed speech service.")
                    }
                }
                else -> scheduleRestart(RETRY_DELAY_MS, countFailure = true, recreate = true, failureMessage = restartErrorMessage(error))
            }
        }
    }

    private fun restartErrorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "The speech service couldn't connect. Check its network connection and try again."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
            "The speech service is busy. Wait a moment, then try again."
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            "The speech service stopped responding. Please try again."
        else -> "Speech recognition failed repeatedly (error $error). Check the speech service and try again."
    }

    private companion object {
        val ENGLISH_INDIA = Locale("en", "IN").toLanguageTag()
        val ENGLISH_US = Locale("en", "US").toLanguageTag()
        const val SEGMENT_RESTART_DELAY_MS = 700L
        const val RETRY_DELAY_MS = 1_000L
        const val MAX_RESTART_FAILURES = 5
        const val MAX_TRANSCRIPT_CANDIDATES = 3
    }
}

/** Joins finalized recognizer segments without deleting any recognized words. */
internal object VoiceTranscriptStitcher {
    fun append(existing: String, fragment: String?): String {
        val left = existing.trimEnd()
        val right = fragment?.trim().orEmpty()
        if (right.isBlank()) return left
        if (left.isBlank()) return right
        return "$left $right"
    }
}
