package com.example.tasksync.ui.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

enum class SpeechLanguage(val tag: String) { EnglishIndia("en-IN"), HindiIndia("hi-IN") }
enum class SpeechCaptureState { Idle, Listening, Finalizing, Reviewing, Error }
enum class SpeechSessionEndReason { USER_STOP, NATURAL_END, INTERRUPTED, CANCELLED }

internal interface SpeechRecognizerHandle {
    fun setRecognitionListener(listener: RecognitionListener)
    fun startListening(intent: Intent)
    fun stopListening()
    fun cancel()
    fun destroy()
    fun clearListener()
}

internal interface SpeechRecognitionProvider {
    fun isAvailable(context: Context): Boolean
    fun create(context: Context): SpeechRecognizerHandle
}

private object AndroidSpeechRecognitionProvider : SpeechRecognitionProvider {
    override fun isAvailable(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)
    override fun create(context: Context): SpeechRecognizerHandle {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        return object : SpeechRecognizerHandle {
            override fun setRecognitionListener(listener: RecognitionListener) = recognizer.setRecognitionListener(listener)
            override fun startListening(intent: Intent) = recognizer.startListening(intent)
            override fun stopListening() = recognizer.stopListening()
            override fun cancel() = recognizer.cancel()
            override fun destroy() = recognizer.destroy()
            override fun clearListener() = recognizer.setRecognitionListener(null)
        }
    }
}

/** Native Android speech-to-text only. Final recognition results are the only text emitted. */
class SpeechCaptureController internal constructor(
    context: Context,
    private val onState: (SpeechCaptureState, String?) -> Unit,
    private val onFinalSegment: (String) -> Unit,
    private val provider: SpeechRecognitionProvider = AndroidSpeechRecognitionProvider,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val maxDurationMs: Long = 60_000L,
    private val finalizationTimeoutMs: Long = 5_000L
) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizerHandle? = null
    private var generation = 0
    private var activeSessionId: Long? = null
    private var nextSessionId = 0L
    private var active = false
    private var stopRequested = false
    private var endReason: SpeechSessionEndReason? = null
    private var language = SpeechLanguage.EnglishIndia
    private var fallbackUsed = false
    private var durationTask: Runnable? = null
    private var finalizationTask: Runnable? = null
    private var lastError: String? = null

    fun start(language: SpeechLanguage = SpeechLanguage.EnglishIndia) {
        if (active) return
        generation++
        val token = generation
        activeSessionId = ++nextSessionId
        endReason = null
        this.language = language
        stopRequested = false
        fallbackUsed = false
        if (!provider.isAvailable(appContext)) {
            onState(SpeechCaptureState.Error, "Speech recognition is unavailable on this device.")
            return
        }
        try {
            recognizer = provider.create(appContext)
            active = true
            recognizer?.setRecognitionListener(listener(token))
            onState(SpeechCaptureState.Listening, "Listening. Your speech will appear after recognition finishes.")
            recognizer?.startListening(recognitionIntent(language.tag))
            durationTask = Runnable { if (isCurrent(token)) stop() }
            mainHandler.postDelayed(durationTask!!, maxDurationMs)
        } catch (_: Throwable) {
            finishError(token, "Couldn't start speech recognition. Check microphone access and try again.")
        }
    }

    fun stop() {
        if (!active || stopRequested) return
        stopRequested = true
        endReason = SpeechSessionEndReason.USER_STOP
        cancelDuration()
        onState(SpeechCaptureState.Finalizing, "Finalizing speech…")
        finalizationTask = Runnable { if (active) finishError(generation, "Speech recognition timed out while finalizing. Try again.") }
            .also { mainHandler.postDelayed(it, finalizationTimeoutMs) }
        try { recognizer?.stopListening() } catch (_: Throwable) {
            finishError(generation, "Couldn't stop speech recognition. Please try again.")
        }
    }

    fun cancel() {
        if (!active && recognizer == null) return
        endReason = SpeechSessionEndReason.CANCELLED
        generation++
        activeSessionId = null
        active = false
        cancelTimers()
        releaseRecognizer()
        onState(SpeechCaptureState.Idle, null)
    }

    /** Backgrounding abandons only the in-flight segment; the UI-owned finalized draft is retained. */
    fun interrupt() {
        if (!active) return
        endReason = SpeechSessionEndReason.INTERRUPTED
        generation++
        activeSessionId = null
        active = false
        stopRequested = false
        cancelTimers()
        releaseRecognizer()
        onState(SpeechCaptureState.Error, "Speech capture was interrupted when TaskSync went to the background. Your earlier transcript is preserved; retry or continue speaking.")
    }

    fun close() {
        if (!active && recognizer == null) return
        generation++
        active = false
        activeSessionId = null
        endReason = SpeechSessionEndReason.INTERRUPTED
        cancelTimers()
        releaseRecognizer()
    }

    private fun listener(token: Int) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onResults(results: Bundle?) {
            if (!isCurrent(token)) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (text.isBlank()) finishError(token, "No speech was recognized. Try again or continue with your draft.")
            else {
                if (!stopRequested) endReason = SpeechSessionEndReason.NATURAL_END
                finishSuccess(token, text)
            }
        }
        override fun onError(error: Int) {
            if (!isCurrent(token)) return
            if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                if (!fallbackUsed && language == SpeechLanguage.EnglishIndia) {
                    fallbackUsed = true
                    try {
                        onState(SpeechCaptureState.Listening, "English (India) is unavailable. Trying English (United States).")
                        recognizer?.cancel()
                        recognizer?.startListening(recognitionIntent(Locale.US.toLanguageTag()))
                        return
                    } catch (_: Throwable) { }
                }
            }
            finishError(token, errorMessage(error))
        }
    }

    private fun recognitionIntent(locale: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun isCurrent(token: Int) = active && token == generation
    private fun finishSuccess(token: Int, text: String) {
        if (!isCurrent(token)) return
        onState(SpeechCaptureState.Finalizing, "Finalizing speech…")
        val reason = endReason ?: SpeechSessionEndReason.NATURAL_END
        active = false; activeSessionId = null; cancelTimers(); releaseRecognizer()
        onFinalSegment(text)
        onState(SpeechCaptureState.Reviewing, reason.name)
    }
    private fun finishError(token: Int, message: String) {
        if (!isCurrent(token)) return
        active = false; activeSessionId = null; lastError = message; cancelTimers(); releaseRecognizer()
        onState(SpeechCaptureState.Error, message)
    }
    private fun errorMessage(error: Int) = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "The microphone couldn't provide audio. Check that it is available and try again."
        SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition was interrupted or is busy. Try again."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for speech capture."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech service needs a network connection. Check your connection and try again."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was detected. Try speaking closer to the microphone."
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "The speech service is unavailable right now. Try again."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech recognition is temporarily busy. Wait a moment and retry."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "That language is unavailable in this device's speech service. Choose another language."
        else -> "Speech recognition failed. Please try again."
    }
    private fun cancelDuration() { durationTask?.let(mainHandler::removeCallbacks); durationTask = null }
    private fun cancelTimers() { cancelDuration(); finalizationTask?.let(mainHandler::removeCallbacks); finalizationTask = null }
    private fun releaseRecognizer() {
        recognizer?.let {
            runCatching { it.clearListener() }
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    internal fun currentSessionIdForTest(): Long? = activeSessionId
    internal fun lastEndReasonForTest(): SpeechSessionEndReason? = endReason
}

internal object TranscriptAccumulator {
    fun append(existing: String, segment: String): String {
        val old = existing.trim()
        val next = segment.trim()
        if (next.isEmpty()) return old
        if (old.isEmpty()) return next
        return "$old $next"
    }
}

/** UI-owned transcript operations, deliberately independent of task persistence or interpretation. */
internal class TranscriptReviewDraft(initial: String = "") {
    var text: String = initial
        private set

    fun edit(exactText: String) { text = exactText }
    fun retry() { text = "" }
    fun appendFinalSegment(segment: String) { text = TranscriptAccumulator.append(text, segment) }
    fun confirm(onConfirmed: (String) -> Unit): Boolean {
        if (text.isBlank()) return false
        onConfirmed(text)
        return true
    }
}
