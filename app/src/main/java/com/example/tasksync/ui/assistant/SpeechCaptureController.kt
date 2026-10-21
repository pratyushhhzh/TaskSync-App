package com.example.tasksync.ui.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Captures successive speech segments until the user explicitly taps Stop. */
class SpeechCaptureController(
    context: Context,
    private val onListening: () -> Unit,
    private val onTranscript: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private val completedSegments = mutableListOf<String>()
    private var isListening = false
    private var stopRequested = false

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onError("Speech recognition isn't available on this device. Try installing or enabling its speech service.")
            return
        }
        runCatching {
            completedSegments.clear()
            stopRequested = false
            isListening = true
            ensureRecognizer()
            onListening()
            startSegment()
        }.onFailure {
            isListening = false
            destroyRecognizer()
            onError("Couldn't start voice recognition. Check microphone permission and try again.")
        }
    }

    /** Stops recording and processes the accumulated speech. */
    fun stop() {
        if (!isListening) return
        isListening = false
        stopRequested = true
        runCatching { recognizer?.stopListening() }
            .onFailure { finishWithAccumulatedSpeech("I couldn't finish voice recognition. Please try again.") }
    }

    fun destroy() {
        isListening = false
        stopRequested = false
        destroyRecognizer()
        completedSegments.clear()
    }

    private fun startSegment() {
        if (!isListening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
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

    private fun finishWithAccumulatedSpeech(emptyMessage: String) {
        stopRequested = false
        val transcript = completedSegments.joinToString(". ").trim()
        if (transcript.isBlank()) onError(emptyMessage) else onTranscript(transcript)
    }

    private fun destroyRecognizer() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onResults(results: Bundle?) {
            val segment = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            if (!segment.isNullOrBlank()) completedSegments += segment

            if (stopRequested || !isListening) {
                finishWithAccumulatedSpeech("I couldn't hear a clear request. Try speaking again.")
            } else {
                runCatching { startSegment() }.onFailure {
                    isListening = false
                    destroyRecognizer()
                    finishWithAccumulatedSpeech("Voice recognition stopped unexpectedly. Please try again.")
                }
            }
        }

        override fun onError(error: Int) {
            if (!isListening && !stopRequested) return

            if (stopRequested || error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                if (stopRequested) {
                    finishWithAccumulatedSpeech("I couldn't hear a clear request. Try speaking again.")
                } else {
                    // Silence between phrases ends one recognition segment, not the full voice session.
                    runCatching { startSegment() }.onFailure {
                        isListening = false
                        destroyRecognizer()
                        finishWithAccumulatedSpeech("Voice recognition stopped unexpectedly. Please try again.")
                    }
                }
                return
            }

            isListening = false
            destroyRecognizer()
            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Microphone audio failed. Please try again."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed for voice capture."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition couldn't connect. Check the device's speech service or try again."
                else -> "Voice recognition had a problem. Please try again."
            }
            if (completedSegments.isNotEmpty()) finishWithAccumulatedSpeech(message) else onError(message)
        }
    }
}
