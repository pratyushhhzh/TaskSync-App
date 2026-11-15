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

internal interface SpeechRecognizerHandle {
    fun setRecognitionListener(listener: RecognitionListener)
    fun startListening(intent: Intent)
    fun stopListening()
    fun cancel()
    fun destroy()
}

internal interface SpeechRecognitionProvider {
    fun isAvailable(context: Context): Boolean
    fun isOnDeviceAvailable(context: Context): Boolean
    fun create(context: Context, onDevice: Boolean): SpeechRecognizerHandle
}

private object AndroidSpeechRecognitionProvider : SpeechRecognitionProvider {
    override fun isAvailable(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)
    override fun isOnDeviceAvailable(context: Context) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun create(context: Context, onDevice: Boolean): SpeechRecognizerHandle {
        val recognizer = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        return object : SpeechRecognizerHandle {
            override fun setRecognitionListener(listener: RecognitionListener) = recognizer.setRecognitionListener(listener)
            override fun startListening(intent: Intent) = recognizer.startListening(intent)
            override fun stopListening() = recognizer.stopListening()
            override fun cancel() = recognizer.cancel()
            override fun destroy() = recognizer.destroy()
        }
    }
}

/** Captures speech until the user explicitly taps the microphone to stop. */
class SpeechCaptureController internal constructor(
    context: Context,
    private val onStarting: () -> Unit,
    private val onListening: () -> Unit,
    private val onPartialTranscript: (String) -> Unit,
    private val onTranscript: (String) -> Unit,
    private val onIncompleteTranscript: (String, String) -> Unit,
    private val onError: (String) -> Unit,
    private val speechProvider: SpeechRecognitionProvider = AndroidSpeechRecognitionProvider
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizerHandle? = null
    private var completedTranscript = ""
    private var currentPartial = ""
    private var isListening = false
    private var readyNotified = false
    private var stopRequested = false
    private var restartFailures = 0
    private var pendingRestart: Runnable? = null
    private var recreateBeforeRestart = false
    private var lastRestartError = "Speech recognition kept stopping. Please try again."
    private var languageTag = ENGLISH_INDIA

    fun start() {
        if (!speechProvider.isAvailable(appContext)) {
            onError("Speech recognition isn't available on this device. Try installing or enabling its speech service.")
            return
        }
        runCatching {
            cancelPendingRestart()
            completedTranscript = ""
            currentPartial = ""
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
            finishAfterExplicitStop("I couldn't hear a clear request. Try speaking again.")
            return
        }
        cancelPendingRestart()
        runCatching { recognizer?.stopListening() }
            .onFailure { finishIncomplete("I couldn't finish voice recognition. Please check the partial transcript.") }
    }

    fun destroy() {
        isListening = false
        stopRequested = false
        cancelPendingRestart()
        destroyRecognizer()
        completedTranscript = ""
        currentPartial = ""
    }

    private fun startSegment() {
        if (!isListening || stopRequested) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Keep recognition limited to plain transcript capture. The AI stage
            // interprets the text only after the user reviews and submits it.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Do not configure short silence thresholds. Silence is a segment boundary,
            // not an instruction to finish the user's push-to-talk session.
        }
        recognizer?.startListening(intent)
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        val onDevice = speechProvider.isOnDeviceAvailable(appContext)
        recognizer = speechProvider.create(appContext, onDevice)
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
                finishIncomplete(lastRestartError)
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

    private fun acceptSegment(candidate: String?) {
        val fragment = candidate?.trim()?.takeIf(String::isNotBlank) ?: currentPartial
        completedTranscript = VoiceTranscriptStitcher.append(completedTranscript, fragment)
        currentPartial = ""
        onPartialTranscript(completedTranscript)
    }

    /** Only the explicit Stop path with a final recognizer result may release text to inference. */
    private fun finishAfterExplicitStop(emptyMessage: String) {
        isListening = false
        stopRequested = false
        cancelPendingRestart()
        val transcript = completedTranscript.trim()
        currentPartial = ""
        if (transcript.isBlank()) onError(emptyMessage) else onTranscript(transcript)
    }

    /** Recognition errors keep any text for review but never mark it as a complete transcript. */
    private fun finishIncomplete(message: String) {
        isListening = false
        stopRequested = false
        cancelPendingRestart()
        val transcript = VoiceTranscriptStitcher.append(completedTranscript, currentPartial)
        currentPartial = ""
        if (transcript.isBlank()) onError(message) else onIncompleteTranscript(transcript, message)
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
            if (!isListening) return
            notifyReadyForSpeech()
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                ?.takeIf(String::isNotBlank)
            if (partial != null) currentPartial = partial
            onPartialTranscript(VoiceTranscriptStitcher.append(completedTranscript, currentPartial))
        }

        override fun onResults(results: Bundle?) {
            if (!isListening) return
            notifyReadyForSpeech()
            val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            restartFailures = 0
            if (stopRequested) {
                if (transcript.isNullOrBlank()) {
                    finishIncomplete("Speech recognition did not return a final transcript. Please check the partial text.")
                } else {
                    acceptSegment(transcript)
                    finishAfterExplicitStop("I couldn't hear a clear request. Try speaking again.")
                }
            } else {
                acceptSegment(transcript)
                scheduleRestart(SEGMENT_RESTART_DELAY_MS)
            }
        }

        override fun onError(error: Int) {
            if (!isListening) return
            if (stopRequested) {
                finishIncomplete("Speech recognition ended before it returned a final transcript. Please check the partial text.")
                return
            }
            acceptSegment(null)

            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // These are normal pauses between utterances. Reuse the ready recognizer;
                    // destroying/rebinding it for every silence timeout can make it busy.
                    scheduleRestart(SEGMENT_RESTART_DELAY_MS)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    finishIncomplete("Microphone permission is needed for voice capture.")
                SpeechRecognizer.ERROR_AUDIO ->
                    finishIncomplete("Microphone audio failed. Please check the partial transcript or try again.")
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
                        finishIncomplete("No supported English voice is available in the installed speech service.")
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
