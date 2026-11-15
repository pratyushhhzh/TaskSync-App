package com.example.tasksync.ui.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechCaptureControllerTest {
    @Test
    fun partialAndSegmentResultsDoNotCompleteUntilUserStops() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = FakeSpeechRecognitionProvider()
        val completed = mutableListOf<String>()
        val partial = mutableListOf<String>()
        lateinit var controller: SpeechCaptureController

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller = SpeechCaptureController(
                context = context,
                onStarting = {},
                onListening = {},
                onPartialTranscript = partial::add,
                onTranscript = completed::add,
                onIncompleteTranscript = { _, _ -> error("Final recognition should not be marked incomplete.") },
                onError = { error(it) },
                speechProvider = provider
            )
            controller.start()
            val listener = provider.handle.listener
            listener.onPartialResults(results("Remind me to buy milk"))
            assertTrue("Partial speech was treated as complete.", completed.isEmpty())

            listener.onResults(results("Remind me to buy milk"))
            assertTrue("A recognizer segment triggered completion before Stop.", completed.isEmpty())

            controller.stop()
            assertEquals(listOf("Remind me to buy milk"), completed)
            controller.destroy()
        }
        assertTrue(partial.isNotEmpty())
    }

    @Test
    fun stopWithoutFinalRecognitionReturnsEditablePartialInsteadOfCompleting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = FakeSpeechRecognitionProvider()
        val completed = mutableListOf<String>()
        val incomplete = mutableListOf<String>()
        lateinit var controller: SpeechCaptureController

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller = SpeechCaptureController(
                context = context,
                onStarting = {},
                onListening = {},
                onPartialTranscript = {},
                onTranscript = completed::add,
                onIncompleteTranscript = { text, _ -> incomplete += text },
                onError = { error(it) },
                speechProvider = provider
            )
            controller.start()
            provider.handle.listener.onPartialResults(results("Remind me to buy"))
            controller.stop()
            provider.handle.listener.onError(SpeechRecognizer.ERROR_CLIENT)

            assertTrue("An incomplete recognition was sent to interpretation.", completed.isEmpty())
            assertEquals(listOf("Remind me to buy"), incomplete)
            controller.destroy()
        }
    }

    private class FakeSpeechRecognitionProvider : SpeechRecognitionProvider {
        val handle = FakeSpeechRecognizerHandle()
        override fun isAvailable(context: Context) = true
        override fun isOnDeviceAvailable(context: Context) = false
        override fun create(context: Context, onDevice: Boolean): SpeechRecognizerHandle = handle
    }

    private class FakeSpeechRecognizerHandle : SpeechRecognizerHandle {
        lateinit var listener: RecognitionListener
        override fun setRecognitionListener(listener: RecognitionListener) { this.listener = listener }
        override fun startListening(intent: Intent) {
            assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
            assertEquals(true, intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
            assertEquals(1, intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0))
        }
        override fun stopListening() = Unit
        override fun cancel() = Unit
        override fun destroy() = Unit
    }

    private companion object {
        fun results(text: String) = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        }
    }
}
