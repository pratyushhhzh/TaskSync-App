package com.example.tasksync.ui.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tasksync.ai.ProposedVoiceAction
import com.example.tasksync.ai.TaskSyncInterpreter
import com.example.tasksync.ai.VoiceActionParser
import com.example.tasksync.ai.VoiceActionType
import com.example.tasksync.ui.tasks.TaskListViewModel
import kotlinx.coroutines.launch

private enum class VoiceDialogState { Ready, Starting, Listening, Review, Interpreting, Actions, Applying, Done, Error }

/** Speech-to-text only. Interpretation and task actions belong to a later stage. */
@Composable
fun VoiceTaskAssistantDialog(onDismiss: () -> Unit, viewModel: TaskListViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(VoiceDialogState.Ready) }
    var transcript by remember { mutableStateOf("") }
    var alternatives by remember { mutableStateOf<List<String>>(emptyList()) }
    var message by remember { mutableStateOf("Tap the microphone to speak, then tap it again to stop.") }
    var rawModelOutput by remember { mutableStateOf("") }
    val proposedActions = remember { mutableStateListOf<ProposedVoiceAction>() }
    var parseErrors by remember { mutableStateOf<List<String>>(emptyList()) }

    val controller = remember(context) {
        SpeechCaptureController(
            context = context,
            onStarting = {
                transcript = ""
                alternatives = emptyList()
                state = VoiceDialogState.Starting
                message = "Preparing the microphone… Wait for the Listening indicator before speaking."
            },
            onListening = {
                transcript = ""
                alternatives = emptyList()
                state = VoiceDialogState.Listening
                message = "Listening… Tap the microphone again when you're done."
            },
            onPartialTranscript = { transcript = it },
            onTranscript = { recognized, otherCandidates ->
                transcript = recognized
                alternatives = otherCandidates
                state = VoiceDialogState.Review
                message = "Review the transcript, then ask the local model to prepare task actions. Nothing is saved yet."
            },
            onError = {
                message = it
                state = VoiceDialogState.Error
            }
        )
    }
    DisposableEffect(controller) { onDispose { controller.destroy() } }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.start() else {
            message = "Microphone permission is needed to use voice capture."
            state = VoiceDialogState.Error
        }
    }

    fun startCapture() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            controller.start()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            androidx.compose.material3.Icon(
                Icons.Default.Mic,
                contentDescription = null,
                tint = if (state == VoiceDialogState.Listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("TaskSync voice capture") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (state == VoiceDialogState.Starting) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
                if (state == VoiceDialogState.Listening || state == VoiceDialogState.Review || state == VoiceDialogState.Interpreting || state == VoiceDialogState.Actions || state == VoiceDialogState.Applying || state == VoiceDialogState.Done) {
                    OutlinedTextField(
                        value = transcript,
                            onValueChange = { if (state == VoiceDialogState.Review) transcript = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (state == VoiceDialogState.Listening) "Live transcript" else "Transcript") },
                        supportingText = {
                            Text(
                                when (state) {
                                    VoiceDialogState.Listening -> "Speech-to-text only. No task processing runs while you speak."
                                    VoiceDialogState.Interpreting -> "The local model is preparing proposed actions."
                                    VoiceDialogState.Actions -> "Review every proposed change. Nothing is saved until Apply."
                                    VoiceDialogState.Applying -> "Applying the changes you confirmed."
                                    VoiceDialogState.Done -> "Confirmed actions were sent to the existing task repository."
                                    else -> "Edit recognition errors before confirming the transcript."
                                }
                            )
                        },
                        readOnly = state != VoiceDialogState.Review,
                        minLines = 3,
                        maxLines = 8
                    )
                }
                if (state == VoiceDialogState.Review && alternatives.isNotEmpty()) {
                    Text("Other speech recognition results", style = MaterialTheme.typography.labelMedium)
                    alternatives.forEachIndexed { index, candidate ->
                        TextButton(onClick = { transcript = candidate }) {
                            Text("Use alternative ${index + 1}: $candidate")
                        }
                    }
                }
                if (state == VoiceDialogState.Interpreting || state == VoiceDialogState.Applying) {
                    CircularProgressIndicator()
                }
                if (state == VoiceDialogState.Actions) {
                    parseErrors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (parseErrors.isNotEmpty()) Text("Unapplied model response: $rawModelOutput", style = MaterialTheme.typography.bodySmall)
                    proposedActions.forEachIndexed { index, action ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            Text("${index + 1}. ${action.type.name.lowercase().replaceFirstChar(Char::uppercase)}", style = MaterialTheme.typography.titleSmall)
                            if (action.type == VoiceActionType.CREATE || action.type == VoiceActionType.UPDATE) {
                                OutlinedTextField(
                                    value = action.title,
                                    onValueChange = { proposedActions[index] = action.copy(title = it) },
                                    label = { Text("Task title") }, modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (action.target != null) {
                                OutlinedTextField(
                                    value = action.target,
                                    onValueChange = { proposedActions[index] = action.copy(target = it, targetTaskId = VoiceActionParser.resolveTargetId(it, viewModel.allTasks.value)) },
                                    label = { Text("Existing task to change") }, modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (action.type == VoiceActionType.CREATE || action.type == VoiceActionType.UPDATE) {
                                OutlinedTextField(value = action.whenText.orEmpty(), onValueChange = { raw ->
                                    val parsed = VoiceActionParser.resolveWhen(raw)
                                    proposedActions[index] = action.copy(whenText = raw, dueDate = parsed?.first, dueTime = parsed?.second,
                                        warning = if (parsed == null) "Date/time ‘$raw’ could not be resolved; it will be retained as text." else null)
                                }, label = { Text("Date and time (original phrase shown)") }, modifier = Modifier.fillMaxWidth())
                                Text("Resolved: ${action.dueDate ?: "not resolved"}${action.dueTime?.let { " at $it" }.orEmpty()}")
                            }
                            if (action.type == VoiceActionType.CREATE || action.type == VoiceActionType.UPDATE) {
                                OutlinedTextField(value = listOf("", "High", "Medium", "Low", "None")[action.priority ?: 4], onValueChange = { raw ->
                                    VoiceActionParser.parsePriorityValue(raw)?.let { proposedActions[index] = action.copy(priority = it) }
                                }, label = { Text("Priority (high/1, medium/2, low/3, none/4)") }, modifier = Modifier.fillMaxWidth())
                            }
                            if (action.type == VoiceActionType.CREATE || action.type == VoiceActionType.UPDATE) {
                                OutlinedTextField(value = action.project.orEmpty(), onValueChange = { proposedActions[index] = action.copy(project = it.ifBlank { null }) }, label = { Text("Project") }, modifier = Modifier.fillMaxWidth())
                            }
                            if (action.type == VoiceActionType.CREATE || action.type == VoiceActionType.UPDATE) {
                                OutlinedTextField(value = action.details.orEmpty(), onValueChange = { proposedActions[index] = action.copy(details = it.ifBlank { null }) }, label = { Text("Details") }, modifier = Modifier.fillMaxWidth())
                            }
                            action.warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            if (action.type == VoiceActionType.DELETE) Text("This task will be permanently deleted after you apply.", color = MaterialTheme.colorScheme.error)
                            if (action.type == VoiceActionType.CREATE && viewModel.allTasks.value.any { it.status != "completed" && it.title.equals(action.title.trim(), true) }) {
                                Text("A task with this title already exists. Check for a duplicate.", color = MaterialTheme.colorScheme.error)
                            }
                            TextButton(onClick = { proposedActions.removeAt(index) }) { Text("Remove this action") }
                        }
                    }
                    if (proposedActions.isEmpty()) Text("No task actions remain. Close to discard.")
                    if (parseErrors.isNotEmpty()) TextButton(onClick = { state = VoiceDialogState.Review }) { Text("Edit transcript and retry") }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    VoiceDialogState.Starting -> Text("Starting microphone…")
                    VoiceDialogState.Listening -> Button(onClick = controller::stop) {
                        androidx.compose.material3.Icon(Icons.Default.MicOff, contentDescription = null)
                        Text("  Stop")
                    }
                    VoiceDialogState.Review -> Button(onClick = {
                        state = VoiceDialogState.Interpreting
                        message = "Running the on-device model…"
                        scope.launch {
                            TaskSyncInterpreter.interpret(context, transcript, viewModel.allTasks.value)
                                .onSuccess { result ->
                                    rawModelOutput = result.rawModelOutput
                                    proposedActions.clear()
                                    proposedActions.addAll(result.parsed.actions)
                                    parseErrors = result.parsed.errors
                                    state = VoiceDialogState.Actions
                                    message = if (result.parsed.errors.isEmpty()) "Review the proposed changes. Apply saves them; Close discards them." else "The model output needs attention. Invalid actions are blocked."
                                }
                                .onFailure { error ->
                                    message = "Could not interpret this transcript: ${error.message ?: "model error"}. Your transcript is still here; edit it or retry."
                                    state = VoiceDialogState.Review
                                }
                        }
                    }, enabled = transcript.isNotBlank()) { Text("Prepare task changes") }
                    VoiceDialogState.Actions -> {
                        val tasks = viewModel.allTasks.value
                        val projects = viewModel.projects.value
                        val canApply = parseErrors.isEmpty() && proposedActions.isNotEmpty() && proposedActions.all { action ->
                            when (action.type) {
                                VoiceActionType.NONE -> true
                                VoiceActionType.CREATE -> action.title.isNotBlank() && (action.project == null || projects.any { it.name.equals(action.project, true) })
                                else -> action.target?.let { VoiceActionParser.resolveTargetId(it, tasks) } != null && (action.project == null || projects.any { it.name.equals(action.project, true) })
                            }
                        }
                        Button(onClick = {
                            state = VoiceDialogState.Applying
                            scope.launch {
                                runCatching { viewModel.applyConfirmedVoiceActions(proposedActions.toList()) }
                                    .onSuccess { state = VoiceDialogState.Done; message = "Changes applied to your local task list." }
                                    .onFailure { state = VoiceDialogState.Actions; message = "Could not apply all changes: ${it.message ?: "storage error"}. Review before retrying." }
                            }
                        }, enabled = canApply) { Text("Apply confirmed actions") }
                    }
                    VoiceDialogState.Ready, VoiceDialogState.Error -> Button(onClick = ::startCapture) {
                        androidx.compose.material3.Icon(Icons.Default.Mic, contentDescription = null)
                        Text("  Speak")
                    }
                    VoiceDialogState.Done -> TextButton(onClick = onDismiss) { Text("Done") }
                    VoiceDialogState.Interpreting -> Text("Preparing…")
                    VoiceDialogState.Applying -> Text("Saving…")
                }
                if (state == VoiceDialogState.Review) {
                    OutlinedButton(onClick = ::startCapture) { Text("Re-record") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = state != VoiceDialogState.Applying) { Text(if (state == VoiceDialogState.Actions) "Cancel all" else "Close") } }
    )
}
