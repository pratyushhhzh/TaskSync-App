package com.example.tasksync.ui.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechCaptureControllerTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun manualStopWaitsForFinalAndReportsUserStop() = onMain {
        val provider = FakeProvider(); val states = mutableListOf<Pair<SpeechCaptureState, String?>>()
        val segments = mutableListOf<String>(); val controller = controller(provider, states, segments)
        controller.start(); val listener = provider.handles.last().listener
        assertEquals(SpeechCaptureState.Listening, states.last().first)
        controller.stop(); controller.stop()
        assertEquals(1, provider.handles.last().stopCalls)
        assertEquals(SpeechCaptureState.Finalizing, states.last().first)
        listener.onResults(results("first segment"))
        listener.onResults(results("duplicate"))
        assertEquals(listOf("first segment"), segments)
        assertEquals(SpeechSessionEndReason.USER_STOP.name, states.last().second)
        assertEquals(SpeechCaptureState.Reviewing, states.last().first)
        controller.close()
    }

    @Test fun naturalSilenceCompletesAndContinueStartsFreshSession() = onMain {
        val p=FakeProvider(); val states=mutableListOf<Pair<SpeechCaptureState,String?>>(); val segments=mutableListOf<String>()
        val c=controller(p,states,segments); c.start(); val old=p.handles.last().listener
        old.onResults(results("one")); assertEquals(SpeechSessionEndReason.NATURAL_END.name,states.last().second)
        c.start(SpeechLanguage.HindiIndia); assertEquals(2,p.handles.size)
        p.handles.last().listener.onResults(results("two")); old.onResults(results("stale"))
        assertEquals(listOf("one","two"),segments)
        assertEquals(SpeechCaptureState.Reviewing,states.last().first); c.close()
    }

    @Test fun cancellationAndInterruptionInvalidateLateCallbacks() = onMain {
        val p=FakeProvider(); val states=mutableListOf<Pair<SpeechCaptureState,String?>>(); val segments=mutableListOf<String>()
        val c=controller(p,states,segments); c.start(); val cancelled=p.handles.last(); val old=cancelled.listener
        c.cancel(); old.onResults(results("discard")); assertEquals(SpeechSessionEndReason.CANCELLED,c.lastEndReasonForTest())
        c.start(); val interrupted=p.handles.last(); val late=interrupted.listener
        c.interrupt(); late.onResults(results("unfinished")); assertEquals(SpeechSessionEndReason.INTERRUPTED,c.lastEndReasonForTest())
        assertTrue(segments.isEmpty()); assertEquals(SpeechCaptureState.Error,states.last().first)
        assertTrue(cancelled.destroyed); assertTrue(interrupted.destroyed); c.close()
    }

    @Test fun laterErrorDoesNotRemoveEarlierFinalizedSegment() = onMain {
        val p=FakeProvider(); val states=mutableListOf<Pair<SpeechCaptureState,String?>>(); val segments=mutableListOf<String>()
        val c=controller(p,states,segments); c.start(); p.handles.last().listener.onResults(results("keep this"))
        c.start(); p.handles.last().listener.onError(SpeechRecognizer.ERROR_NETWORK)
        assertEquals(listOf("keep this"),segments); assertEquals(SpeechCaptureState.Error,states.last().first); c.close()
    }

    @Test fun duplicateStartAndInvalidStopAreNoOps() = onMain {
        val p=FakeProvider(); val states=mutableListOf<Pair<SpeechCaptureState,String?>>(); val c=controller(p,states,mutableListOf())
        c.stop(); assertTrue(p.handles.isEmpty()); c.start(); val id=c.currentSessionIdForTest(); c.start()
        assertEquals(1,p.handles.size); assertEquals(id,c.currentSessionIdForTest()); c.close()
    }

    @Test fun unavailableProviderReportsErrorWithoutCreatingRecognizer() = onMain {
        val p=FakeProvider(available=false); val states=mutableListOf<Pair<SpeechCaptureState,String?>>(); val c=controller(p,states,mutableListOf())
        c.start(); assertEquals(SpeechCaptureState.Error,states.last().first); assertTrue(p.handles.isEmpty()); c.close()
    }

    @Test fun emptyResultAndNetworkErrorsAreTerminalAndUnderstandable() = onMain {
        val p=FakeProvider(); val states=mutableListOf<Pair<SpeechCaptureState,String?>>(); val c=controller(p,states,mutableListOf())
        c.start(); p.handles.last().listener.onResults(results("  "))
        assertEquals(SpeechCaptureState.Error,states.last().first); assertTrue(states.last().second!!.contains("No speech"))
        c.start(); p.handles.last().listener.onError(SpeechRecognizer.ERROR_NETWORK)
        assertEquals(SpeechCaptureState.Error,states.last().first); assertTrue(states.last().second!!.contains("network")); c.close()
    }

    @Test fun englishLocaleFallbackIsVisibleAndHindiIsNotSilentlyChanged() = onMain {
        val p=FakeProvider(); val states=mutableListOf<Pair<SpeechCaptureState,String?>>(); val c=controller(p,states,mutableListOf())
        c.start(SpeechLanguage.EnglishIndia); val first=p.handles.last(); first.listener.onError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        assertTrue(states.any { it.second?.contains("English (United States)") == true })
        assertEquals("en-US",first.startedLocales.last())
        c.close()
        val p2=FakeProvider(); val s2=mutableListOf<Pair<SpeechCaptureState,String?>>(); val c2=controller(p2,s2,mutableListOf())
        c2.start(SpeechLanguage.HindiIndia); p2.handles.last().listener.onError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        assertEquals(SpeechCaptureState.Error,s2.last().first); assertEquals(listOf("hi-IN"),p2.handles.last().startedLocales); c2.close()
    }

    private fun controller(p: FakeProvider, states: MutableList<Pair<SpeechCaptureState,String?>>, segments: MutableList<String>) =
        SpeechCaptureController(context,{s,m->states += s to m},{segments += it},p,Handler(Looper.getMainLooper()),60_000,60_000)
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun results(text: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,arrayListOf(text)) }

    private class FakeProvider(private val available: Boolean=true): SpeechRecognitionProvider {
        val handles=mutableListOf<FakeHandle>()
        override fun isAvailable(context: Context)=available
        override fun create(context: Context)=FakeHandle().also(handles::add)
    }
    private class FakeHandle: SpeechRecognizerHandle {
        lateinit var listener: RecognitionListener; var stopCalls=0; var destroyed=false; val startedLocales=mutableListOf<String>()
        override fun setRecognitionListener(listener: RecognitionListener) { this.listener=listener }
        override fun startListening(intent: Intent) { assertEquals(false,intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)); startedLocales += intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE).orEmpty() }
        override fun stopListening() { stopCalls++ }
        override fun cancel() = Unit
        override fun destroy() { destroyed=true }
        override fun clearListener() { }
    }
}
