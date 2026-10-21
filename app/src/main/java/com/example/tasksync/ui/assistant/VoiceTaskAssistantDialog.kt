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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
    onRecognized: (String, (VoiceInterpretation) -> Unit) -> Unit,
    onApply: (List<VoiceTaskAction>, (message: String, canUndo: Boolean) -> Unit) -> Unit,
    onUndo: ((String) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(VoiceDialogState.Ready) }
    var transcript by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Tap Speak, say one or more tasks, then tap Stop when you're done.") }
    var canUndo by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf<List<VoiceTaskAction>>(emptyList()) }
    var canApply by remember { mutableStateOf(false) }

    val currentOnRecognized by rememberUpdatedState(onRecognized)
    val currentOnApply by rememberUpdatedState(onApply)
    val controller = remember(context) {
        SpeechCaptureController(
            context = context,
            onListening = { state = VoiceDialogState.Listening; transcript = ""; actions = emptyList(); applied = false; canUndo = false; canApply = false; message = "Listening… Say as many tasks as you like, then tap Stop." },
            onTranscript = { phrase ->
                transcript = phrase
                state = VoiceDialogState.Processing
                message = "Interpreting what I heard…"
                currentOnRecognized(phrase) { interpretation ->
                    when (interpretation) {
                        is VoiceInterpretation.Actions -> {
                            actions = interpretation.actions
                            canApply = true
                            message = "Review the proposed changes. You can edit each field before saving."
                        }
                        is VoiceInterpretation.NeedsClarification -> {
                            actions = emptyList(); canApply = false; message = interpretation.message
                        }
                        is VoiceInterpretation.NotUnderstood -> {
                            actions = emptyList(); canApply = false; message = interpretation.message
                        }
                    }
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
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (state == VoiceDialogState.Processing) {
                    Spacer(Modifier.height(14.dp))
                    CircularProgressIndicator()
                }
                if (transcript.isNotBlank() && state != VoiceDialogState.Processing) {
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Heard: $transcript", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state == VoiceDialogState.Result && !applied) {
                    actions.forEachIndexed { index, action ->
                        Spacer(Modifier.height(10.dp))
                        when (action) {
                            is VoiceTaskAction.Create -> {
                                Text("New task", style = MaterialTheme.typography.labelLarge)
                                OutlinedTextField(action.title, { value -> actions = actions.updated(index) { action.copy(title = value) } }, label = { Text("Task title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(action.dueDate.orEmpty(), { value -> actions = actions.updated(index) { action.copy(dueDate = value.takeIf(String::isNotBlank)) } }, label = { Text("Due date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(action.dueTime.orEmpty(), { value -> actions = actions.updated(index) { action.copy(dueTime = value.takeIf(String::isNotBlank)) } }, label = { Text("Due time (HH:MM)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(action.priority.toString(), { value -> value.toIntOrNull()?.takeIf { it in 1..4 }?.let { priority -> actions = actions.updated(index) { action.copy(priority = priority) } } }, label = { Text("Priority (1 high – 4 none)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            }
                            is VoiceTaskAction.Update -> {
                                Text("Update: ${action.before.title}", style = MaterialTheme.typography.labelLarge)
                                OutlinedTextField(action.after.title, { value -> actions = actions.updated(index) { action.copy(after = action.after.copy(title = value)) } }, label = { Text("Task title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(action.after.dueDate.orEmpty(), { value -> actions = actions.updated(index) { action.copy(after = action.after.copy(dueDate = value.takeIf(String::isNotBlank))) } }, label = { Text("Due date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(action.after.dueTime.orEmpty(), { value -> actions = actions.updated(index) { action.copy(after = action.after.copy(dueTime = value.takeIf(String::isNotBlank))) } }, label = { Text("Due time (HH:MM)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(action.after.priority.toString(), { value -> value.toIntOrNull()?.takeIf { it in 1..4 }?.let { priority -> actions = actions.updated(index) { action.copy(after = action.after.copy(priority = priority)) } } }, label = { Text("Priority (1 high – 4 none)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            }
                            is VoiceTaskAction.Complete -> Text("Mark completed: ${action.task.title}", style = MaterialTheme.typography.labelLarge)
                        }
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
                if (state == VoiceDialogState.Result && canApply && !applied) {
                    Button(onClick = {
                        state = VoiceDialogState.Processing
                        message = "Saving your changes…"
                        currentOnApply(actions) { result, undoAvailable ->
                            message = result
                            canUndo = undoAvailable
                            applied = true
                            state = VoiceDialogState.Result
                        }
                    }, enabled = actions.all { action -> when (action) {
                        is VoiceTaskAction.Create -> action.title.isNotBlank()
                        is VoiceTaskAction.Update -> action.after.title.isNotBlank()
                        is VoiceTaskAction.Complete -> true
                    } }) { Text("Apply") }
                }
                if (state == VoiceDialogState.Ready || state == VoiceDialogState.Error || (state == VoiceDialogState.Result && applied)) {
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

private inline fun List<VoiceTaskAction>.updated(index: Int, transform: (VoiceTaskAction) -> VoiceTaskAction): List<VoiceTaskAction> =
    mapIndexed { current, action -> if (current == index) transform(action) else action }

@Composable
private fun IconMicListening() {
    androidx.compose.material3.Icon(Icons.Default.Mic, contentDescription = "Listening", tint = MaterialTheme.colorScheme.error)
}
