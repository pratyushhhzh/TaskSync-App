package com.example.tasksync.ui.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat

/** Stage 1 transcript capture. The callback receives only the exact edited text the user confirms. */
@OptIn(ExperimentalMaterial3Api::class)
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

    Dialog(
        onDismissRequest = ::requestDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.68f)), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = true, enter = fadeIn(tween(350)) + slideInVertically(tween(550)) { it / 6 }, modifier = Modifier.align(Alignment.BottomCenter)) {
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp).heightIn(min = 380.dp, max = 560.dp),
            shape = RoundedCornerShape(36.dp),
            color = Color(0xFF131A48),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
            shadowElevation = 22.dp,
        ) {
        Column(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF1A2A6E).copy(alpha = 0.42f), Color(0xFF131A48))))
                .padding(horizontal = 22.dp).padding(bottom = 24.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 4.dp).size(width = 38.dp, height = 4.dp), color = Color.White.copy(alpha = 0.28f), shape = CircleShape) {}
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (state) {
                            SpeechCaptureState.Listening -> "Listening"
                            SpeechCaptureState.Finalizing -> "Finalizing speech…"
                            SpeechCaptureState.Reviewing -> "Here’s what I heard"
                            else -> "Capture a thought"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        when (state) {
                            SpeechCaptureState.Listening -> "Speak naturally. Your words stay hidden until recognition finishes."
                            SpeechCaptureState.Finalizing -> "Finishing this speech segment…"
                            SpeechCaptureState.Reviewing -> "Fix any word before anything is sent."
                            else -> "Add a task, or say several in one breath."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = ::requestDismiss) { Icon(Icons.Default.Close, contentDescription = "Cancel voice capture", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            AnimatedContent(targetState = state, label = "speech-capture-content") { current ->
                when (current) {
                    SpeechCaptureState.Listening -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        val transition = rememberInfiniteTransition(label = "mic-pulse")
                        val scale by transition.animateFloat(1f, 1.09f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse")
                        Surface(Modifier.size(88.dp).scale(scale), shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Mic, null, Modifier.size(36.dp), tint = Color(0xFFBFD0FF)) }
                        }
                        SpeechWaveform()
                        Text("Listening…", style = MaterialTheme.typography.labelLarge, color = Color(0xFFBFD0FF))
                        LanguageSelector(language, enabled = false) { language = it }
                        Button(onClick = controller::stop, modifier = Modifier.fillMaxWidth()) { Text("Done speaking") }
                    }
                    SpeechCaptureState.Finalizing -> Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(15.dp)) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text("Finalizing speech…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (transcript.isNotBlank() || current == SpeechCaptureState.Reviewing) {
                            OutlinedTextField(
                                value = transcript,
                                onValueChange = { transcript = it; draft.edit(it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Transcript") },
                                minLines = 4,
                                shape = RoundedCornerShape(22.dp),
                            )
                            Text("Tap to edit. Nothing is sent until you confirm.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Color(0xFF080B13).copy(alpha = 0.52f), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Ready when you are", style = MaterialTheme.typography.titleMedium)
                                    Text(message ?: "Choose a language, then start speaking. Your transcript appears only after recognition completes.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (current == SpeechCaptureState.Error) message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        LanguageSelector(language, enabled = true) { language = it }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (transcript.isBlank()) begin(true)
                                    else { retryAfterDiscard = true; showDiscardConfirmation = true }
                                },
                            ) { Text(if (transcript.isBlank()) "Start speaking" else "Retry") }
                            if (transcript.isNotBlank()) OutlinedButton(modifier = Modifier.weight(1f), onClick = { begin(false) }) { Text("Continue speaking") }
                        }
                        Button(
                            onClick = { if (draft.confirm(onTranscriptConfirmed)) controller.cancel() },
                            enabled = transcript.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(28.dp),
                        ) { Text("Interpret tasks") }
                    }
                }
            }
        }
        }
        }
        }
    }

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
        dismissButton = { TextButton(onClick = { showDiscardConfirmation = false }) { Text("Keep editing") } },
    )
}

@Composable
private fun LanguageSelector(selected: SpeechLanguage, enabled: Boolean, onSelect: (SpeechLanguage) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        listOf(SpeechLanguage.EnglishIndia, SpeechLanguage.HindiIndia).forEach { language ->
            val active = language == selected
            TextButton(onClick = { if (enabled) onSelect(language) }, enabled = enabled) {
                Text(if (active) "✓ ${language.tag}" else language.tag, color = if (active) Color(0xFFBFD0FF) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SpeechWaveform() {
    val transition = rememberInfiniteTransition(label = "waveform")
    Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        repeat(25) { index ->
            val height by transition.animateFloat(
                initialValue = 8f,
                targetValue = (12 + (index * 17 % 34)).toFloat(),
                animationSpec = infiniteRepeatable(tween(420 + index * 21, delayMillis = index * 13), RepeatMode.Reverse),
                label = "wave-$index",
            )
            Box(Modifier.padding(horizontal = 2.dp).size(width = 3.dp, height = height.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF8AA9FF)))
        }
    }
}
