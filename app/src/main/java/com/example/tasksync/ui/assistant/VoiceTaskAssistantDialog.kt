package com.example.tasksync.ui.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import com.example.tasksync.data.local.entity.TaskEntity
import com.example.tasksync.ui.tasks.TaskListViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private enum class VoiceDialogState { Ready, Starting, Listening, TranscriptReview, Review, Interpreting, Actions, Applying, Done, Error }

/** Captures one transcript, sends it to the local model on Stop, then asks once before saving. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun VoiceTaskAssistantDialog(onDismiss: () -> Unit, viewModel: TaskListViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(VoiceDialogState.Ready) }
    var transcript by remember { mutableStateOf("") }
    var transcriptIsPartial by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Tap Speak, say your full request, tap Stop, then review the transcript and choose Interpret.") }
    var rawModelOutput by remember { mutableStateOf("") }
    val proposedActions = remember { mutableStateListOf<ProposedVoiceAction>() }
    var parseErrors by remember { mutableStateOf<List<String>>(emptyList()) }

    fun interpretTranscript(text: String) {
        if (text.isBlank()) {
            state = VoiceDialogState.Error
            message = "No speech was captured. Tap Speak and try again."
            return
        }
        state = VoiceDialogState.Interpreting
        message = "The local model is extracting task titles, dates, times, and repeats…"
        scope.launch {
            TaskSyncInterpreter.interpret(context, text, viewModel.allTasks.value)
                .onSuccess { result ->
                    rawModelOutput = result.rawModelOutput
                    proposedActions.clear()
                    proposedActions.addAll(result.parsed.actions)
                    parseErrors = result.parsed.errors
                    state = VoiceDialogState.Actions
                    message = if (result.parsed.errors.isEmpty()) {
                        "Review all ${result.parsed.actions.count { it.type == VoiceActionType.CREATE || it.type == VoiceActionType.UPDATE }} proposed tasks, then confirm once."
                    } else "The model response needs review. No changes have been saved."
                }
                .onFailure { error ->
                    message = "Could not interpret the transcript: ${error.message ?: "model error"}. Your transcript is kept so you can retry."
                    state = VoiceDialogState.Review
                }
        }
    }

    val controller = remember(context) {
        SpeechCaptureController(
            context = context,
            onStarting = {
                transcript = ""
                transcriptIsPartial = false
                state = VoiceDialogState.Starting
                message = "Preparing the microphone… Wait for Listening before speaking."
            },
            onListening = {
                transcript = ""
                transcriptIsPartial = false
                state = VoiceDialogState.Listening
                message = "Listening to your full request. The model stays idle until you stop."
            },
            onPartialTranscript = { transcript = it },
            onTranscript = { recognized ->
                transcript = recognized
                transcriptIsPartial = false
                state = VoiceDialogState.TranscriptReview
                message = "Speech is complete. Check the transcript, then tap Interpret. The model has not run yet."
            },
            onIncompleteTranscript = { recognized, error ->
                transcript = recognized
                transcriptIsPartial = true
                state = VoiceDialogState.TranscriptReview
                message = "$error This may be incomplete. Correct it or re-record before interpreting."
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
            message = "Microphone permission is needed for voice capture."
            state = VoiceDialogState.Error
        }
    }

    fun startCapture() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            controller.start()
        } else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            androidx.compose.material3.BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = 20.dp).padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    tint = if (state == VoiceDialogState.Listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Text("Voice tasks", style = MaterialTheme.typography.headlineSmall)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("Listened", "Transcript", "Parsed", "Review").forEachIndexed { index, label ->
                    val currentStep = when (state) {
                        VoiceDialogState.Ready, VoiceDialogState.Error -> -1
                        VoiceDialogState.Listening, VoiceDialogState.Starting -> 0
                        VoiceDialogState.TranscriptReview, VoiceDialogState.Review -> 1
                        VoiceDialogState.Interpreting -> 2
                        VoiceDialogState.Actions, VoiceDialogState.Applying, VoiceDialogState.Done -> 3
                    }
                    Text(label, style = MaterialTheme.typography.labelSmall, color = if (index <= currentStep) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state == VoiceDialogState.Ready || state == VoiceDialogState.Error) {
                DebugTranscriptInput { typed -> transcript = typed; interpretTranscript(typed) }
            }
            if (state == VoiceDialogState.Listening || state == VoiceDialogState.TranscriptReview || state == VoiceDialogState.Review) {
                OutlinedTextField(
                    value = transcript,
                    onValueChange = { if (state != VoiceDialogState.Listening) transcript = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (state == VoiceDialogState.Listening) "Live transcript" else "Transcript") },
                    supportingText = { Text(if (state == VoiceDialogState.Listening) "Speech-to-text only. Nothing is interpreted until you stop and choose Interpret." else "Check and edit the recognized words before they are sent to the model.") },
                    readOnly = state == VoiceDialogState.Listening,
                    minLines = 2,
                    maxLines = 5
                )
            }
            if (state == VoiceDialogState.Starting || state == VoiceDialogState.Interpreting || state == VoiceDialogState.Applying) {
                CircularProgressIndicator()
            }
            if (state == VoiceDialogState.Actions) {
                parseErrors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                if (parseErrors.isNotEmpty()) Text("Unapplied model response: $rawModelOutput", style = MaterialTheme.typography.bodySmall)
                if (proposedActions.singleOrNull()?.type == VoiceActionType.NONE) Text("The model found no task changes. Nothing has been saved.")
                else proposedActions.forEachIndexed { index, action ->
                    VoiceProposedActionCard(
                        index = index,
                        action = action,
                        tasks = viewModel.allTasks.value,
                        transcript = transcript,
                        onChange = { proposedActions[index] = it },
                        onRemove = { proposedActions.removeAt(index) }
                    )
                }
                if (proposedActions.isEmpty()) Text("The model proposed no tasks. Close to discard.")
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (state == VoiceDialogState.Review || state == VoiceDialogState.TranscriptReview) OutlinedButton(onClick = ::startCapture) { Text("Re-record") }
                when (state) {
                    VoiceDialogState.Starting -> Text("Starting…")
                    VoiceDialogState.Listening -> Button(onClick = controller::stop) { Text("Stop") }
                    VoiceDialogState.TranscriptReview -> Button(
                        onClick = { interpretTranscript(transcript); transcriptIsPartial = false },
                        enabled = transcript.isNotBlank()
                    ) { Text(if (transcriptIsPartial) "Interpret edited transcript" else "Interpret") }
                    VoiceDialogState.Review -> Button(onClick = { interpretTranscript(transcript) }, enabled = transcript.isNotBlank()) { Text("Retry") }
                    VoiceDialogState.Actions -> {
                        val noOp = proposedActions.singleOrNull()?.type == VoiceActionType.NONE
                        val tasks = viewModel.allTasks.value
                        val createTitles = proposedActions.filter { it.type == VoiceActionType.CREATE }.map { it.title.trim().lowercase() }
                        val proposedDuplicate = createTitles.size != createTitles.distinct().size
                        val existingDuplicate = proposedActions.any { action -> action.type == VoiceActionType.CREATE && tasks.any { task -> task.status != "completed" && task.title.equals(action.title.trim(), ignoreCase = true) } }
                        val canApply = !noOp && !proposedDuplicate && !existingDuplicate && parseErrors.isEmpty() && proposedActions.isNotEmpty() && proposedActions.all { action ->
                            action.titleWarning == null && when (action.type) {
                                VoiceActionType.NONE -> false
                                VoiceActionType.CREATE -> action.title.isNotBlank()
                                VoiceActionType.UPDATE, VoiceActionType.COMPLETE, VoiceActionType.DELETE -> action.target?.let { VoiceActionParser.resolveTargetId(it, tasks) } != null
                            }
                        }
                        val count = proposedActions.count { it.type == VoiceActionType.CREATE || it.type == VoiceActionType.UPDATE }
                        if (noOp) TextButton(onClick = onDismiss) { Text("Done") }
                        else Button(onClick = {
                            state = VoiceDialogState.Applying
                            scope.launch {
                                runCatching { viewModel.applyConfirmedVoiceActions(proposedActions.toList()) }
                                    .onSuccess { warnings ->
                                        state = VoiceDialogState.Done
                                        message = if (warnings.isEmpty()) "Your confirmed tasks were saved." else "Tasks were saved, but ${warnings.size} reminder(s) could not be scheduled."
                                    }
                                    .onFailure { state = VoiceDialogState.Actions; message = "Could not save the changes: ${it.message ?: "storage error"}. No partial changes were kept; review and retry." }
                            }
                        }, enabled = canApply) { Text("Confirm $count ${if (count == 1) "task" else "tasks"}") }
                    }
                    VoiceDialogState.Ready, VoiceDialogState.Error -> Button(onClick = ::startCapture) { Text("Speak") }
                    VoiceDialogState.Done -> TextButton(onClick = onDismiss) { Text("Done") }
                    VoiceDialogState.Interpreting -> Text("Preparing…")
                    VoiceDialogState.Applying -> Text("Saving…")
                }
                if (state != VoiceDialogState.Actions && state != VoiceDialogState.Done) {
                    TextButton(onClick = onDismiss, enabled = state != VoiceDialogState.Applying) { Text("Close") }
                } else if (state == VoiceDialogState.Actions) {
                    TextButton(onClick = onDismiss) { Text("Cancel all") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceProposedActionCard(
    index: Int,
    action: ProposedVoiceAction,
    tasks: List<TaskEntity>,
    transcript: String,
    onChange: (ProposedVoiceAction) -> Unit,
    onRemove: () -> Unit
) {
    var showDatePicker by remember(action.dueDate) { mutableStateOf(false) }
    var showTimePicker by remember(action.dueTime) { mutableStateOf(false) }
    val selectedDate = action.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
    val dateState = rememberDatePickerState(selectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    val parsedTime = action.dueTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    val timeState = rememberTimePickerState(parsedTime?.hour ?: 9, parsedTime?.minute ?: 0, is24Hour = false)
    val editable = action.type == VoiceActionType.CREATE || action.type == VoiceActionType.UPDATE

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Text("${index + 1}. ${action.type.name.lowercase().replaceFirstChar(Char::uppercase)}", style = MaterialTheme.typography.titleSmall)
        if (editable) {
            OutlinedTextField(
                value = action.title,
                onValueChange = { title ->
                    val grounded = VoiceActionParser.isTitleGroundedInTranscript(title, transcript)
                    onChange(action.copy(
                        title = title,
                        titleWarning = if (grounded) null else "This title includes meaningful words not found in the transcript. Edit it before confirming."
                    ))
                },
                label = { Text("Task title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        if (action.target != null) Text("Task: ${action.target}", style = MaterialTheme.typography.bodyMedium)
        if (editable) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showDatePicker = true }) {
                    androidx.compose.material3.Icon(Icons.Default.CalendarMonth, contentDescription = "Choose date")
                    Text(" ${action.dueDate ?: "Choose date"}")
                }
                OutlinedButton(onClick = { showTimePicker = true }) {
                    androidx.compose.material3.Icon(Icons.Default.AccessTime, contentDescription = "Choose time")
                    action.dueTime?.let { Text(" ${runCatching { LocalTime.parse(it).format(DateTimeFormatter.ofPattern("h:mm a")) }.getOrDefault(it)}") }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(null to "No repeat", "daily" to "Daily", "weekdays" to "Weekdays", "weekly" to "Weekly", "monthly" to "Monthly").forEach { (rule, label) ->
                    FilterChip(
                        selected = action.recurrenceRule == rule,
                        onClick = { onChange(action.copy(recurrenceRule = rule)) },
                        label = { Text(label) }
                    )
                }
            }
            if (tasks.any { it.status != "completed" && it.title.equals(action.title.trim(), ignoreCase = true) }) {
                Text("A task with this title already exists.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        action.warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        action.titleWarning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = onRemove) { Text("Remove") }
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let {
                        onChange(action.copy(dueDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString(), warning = null))
                    }
                    showDatePicker = false
                }) { Text("Set date") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = dateState) }
    }
    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Choose time") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    onChange(action.copy(dueTime = LocalTime.of(timeState.hour, timeState.minute).format(DateTimeFormatter.ofPattern("HH:mm:ss")), warning = null))
                    showTimePicker = false
                }) { Text("Set time") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } }
        )
    }
}
