package com.example.tasksync.ui.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private enum class VoiceDialogState { Ready, Listening, Processing, Result, Error }

@Composable
fun VoiceTaskAssistantDialog(
    onDismiss: () -> Unit,
    onRecognized: (String, (message: String, canUndo: Boolean) -> Unit) -> Unit,
    onUndo: ((String) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(VoiceDialogState.Ready) }
    var transcript by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Tap Speak, say one or more tasks, then tap Stop when you're done.") }
    var canUndo by remember { mutableStateOf(false) }

    val currentOnRecognized by rememberUpdatedState(onRecognized)
    val controller = remember(context) {
        SpeechCaptureController(
            context = context,
            onListening = { state = VoiceDialogState.Listening; transcript = ""; message = "Listening… Say as many tasks as you like, then tap Stop." },
            onTranscript = { phrase ->
                transcript = phrase
                state = VoiceDialogState.Processing
                message = "Working on that…"
                currentOnRecognized(phrase) { result, undoAvailable ->
                    message = result
                    canUndo = undoAvailable
                    state = VoiceDialogState.Result
                }
            },
            onError = { error -> message = error; state = VoiceDialogState.Error }
        )
    }
    DisposableEffect(controller) { onDispose { controller.destroy() } }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.start() else {
            message = "Microphone permission is needed to use the voice assistant."
            state = VoiceDialogState.Error
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            if (state == VoiceDialogState.Listening) IconMicListening()
            else androidx.compose.material3.Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        title = { Text("TaskSync voice assistant") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (state == VoiceDialogState.Processing) {
                    Spacer(Modifier.height(14.dp))
                    CircularProgressIndicator()
                }
                if (transcript.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(transcript, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state == VoiceDialogState.Result && canUndo) {
                    TextButton(onClick = {
                        state = VoiceDialogState.Processing
                        message = "Undoing…"
                        onUndo { result -> message = result; canUndo = false; state = VoiceDialogState.Result }
                    }) { Text("Undo") }
                }
                if (state == VoiceDialogState.Ready || state == VoiceDialogState.Error || state == VoiceDialogState.Result) {
                    Button(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            controller.start()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }) {
                        androidx.compose.material3.Icon(Icons.Default.Mic, contentDescription = null)
                        Text("  Speak")
                    }
                }
            }
        },
        dismissButton = {
            if (state == VoiceDialogState.Listening) {
                OutlinedButton(onClick = { state = VoiceDialogState.Processing; message = "Finishing your voice request…"; controller.stop() }) {
                    androidx.compose.material3.Icon(Icons.Default.MicOff, contentDescription = null)
                    Text("  Stop")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun IconMicListening() {
    androidx.compose.material3.Icon(Icons.Default.Mic, contentDescription = "Listening", tint = MaterialTheme.colorScheme.error)
}
