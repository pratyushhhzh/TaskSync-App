package com.example.tasksync.ui.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** Stage 1 transcript capture. The callback receives only the exact edited text the user confirms. */
@Composable
fun VoiceTaskAssistantDialog(onDismiss: () -> Unit, onTranscriptConfirmed: (String) -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(SpeechCaptureState.Idle) }
    var message by remember { mutableStateOf<String?>(null) }
    val draft = remember { TranscriptReviewDraft() }
    var transcript by remember { mutableStateOf("") }
    var language by remember { mutableStateOf(SpeechLanguage.EnglishIndia) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    var retryAfterDiscard by remember { mutableStateOf(false) }
    val controller = remember(context) {
        SpeechCaptureController(context, { next, text -> state = next; message = text }, { segment -> draft.appendFinalSegment(segment); transcript = draft.text })
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(controller) { onDispose { controller.close() } }
    DisposableEffect(controller, lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) controller.interrupt()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.start(language) else { state = SpeechCaptureState.Error; message = "Microphone permission was denied. Allow it in app settings to record speech." }
    }
    fun begin(fresh: Boolean) {
        if (state == SpeechCaptureState.Listening || state == SpeechCaptureState.Finalizing) return
        if (fresh) { draft.retry(); transcript = draft.text }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) controller.start(language)
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun requestDismiss() {
        retryAfterDiscard = false
        if (transcript.isNotBlank() || state == SpeechCaptureState.Listening || state == SpeechCaptureState.Finalizing) {
            showDiscardConfirmation = true
        } else {
            controller.cancel()
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = ::requestDismiss,
        title = { Text(if (state == SpeechCaptureState.Listening) "Listening" else if (state == SpeechCaptureState.Finalizing) "Finalizing speech…" else "Voice transcript") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (state == SpeechCaptureState.Listening) {
                    val transition = rememberInfiniteTransition(label = "mic-pulse")
                    val scale by transition.animateFloat(1f, 1.2f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse")
                    androidx.compose.material3.Icon(Icons.Default.Mic, null, Modifier.size(58.dp).scale(scale), tint = MaterialTheme.colorScheme.primary)
                    Text(message ?: "Listening… Your speech will appear after recognition finishes.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(SpeechLanguage.EnglishIndia, SpeechLanguage.HindiIndia).forEach { option ->
                            TextButton(onClick = { if (state != SpeechCaptureState.Listening) language = option }) { Text(if (option == language) "✓ ${option.tag}" else option.tag) }
                        }
                    }
                    Button(onClick = controller::stop) { Text("Stop") }
                } else if (state == SpeechCaptureState.Finalizing) {
                    CircularProgressIndicator()
                    Text("Finalizing speech…")
                } else {
                    if (transcript.isNotBlank() || state == SpeechCaptureState.Reviewing) {
                        OutlinedTextField(transcript, { transcript = it; draft.edit(it) }, Modifier.fillMaxWidth(), label = { Text("Transcript") }, minLines = 3)
                    } else Text(message ?: "Choose a language, then tap the microphone to start.")
                    message?.let { if (state == SpeechCaptureState.Error) Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(SpeechLanguage.EnglishIndia, SpeechLanguage.HindiIndia).forEach { option ->
                            TextButton(onClick = { language = option }) { Text(if (option == language) "✓ ${option.tag}" else option.tag) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (transcript.isBlank()) begin(true)
                            else { retryAfterDiscard = true; showDiscardConfirmation = true }
                        }) { Text(if (transcript.isBlank()) "Start speaking" else "Retry") }
                        if (transcript.isNotBlank()) OutlinedButton(onClick = { begin(false) }) { Text("Continue speaking") }
                    }
                    Button(onClick = { if (draft.confirm(onTranscriptConfirmed)) { controller.cancel() } }, enabled = transcript.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Confirm transcript") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (state == SpeechCaptureState.Listening) controller.stop() else requestDismiss() }) { Text(if (state == SpeechCaptureState.Listening) "Stop" else "Cancel") } },
        dismissButton = { if (state != SpeechCaptureState.Listening && state != SpeechCaptureState.Finalizing) TextButton(onClick = ::requestDismiss) { Text("Close") } }
    )
    if (showDiscardConfirmation) AlertDialog(
        onDismissRequest = { showDiscardConfirmation = false },
        title = { Text(if (retryAfterDiscard) "Replace transcript?" else "Discard transcript?") },
        text = { Text(if (retryAfterDiscard) "Retry starts a fresh recognition attempt and replaces your current transcript." else "Your current transcript will be lost.") },
        confirmButton = { TextButton(onClick = {
            showDiscardConfirmation = false
            if (retryAfterDiscard) {
                retryAfterDiscard = false
                begin(true)
            } else {
                controller.cancel()
                onDismiss()
            }
        }) { Text(if (retryAfterDiscard) "Replace and retry" else "Discard") } },
        dismissButton = { TextButton(onClick = { showDiscardConfirmation = false }) { Text("Keep editing") } }
    )
}
