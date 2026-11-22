package com.example.tasksync.ui.interpretation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat as AndroidDateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tasksync.data.interpretation.MatchQuality
import com.example.tasksync.data.interpretation.ReviewProposal
import com.example.tasksync.data.interpretation.impliesDate
import com.example.tasksync.data.interpretation.requiresExactTime
import com.example.tasksync.data.remote.AddProposal
import com.example.tasksync.data.remote.CompleteProposal
import com.example.tasksync.data.remote.DeleteProposal
import com.example.tasksync.data.remote.InterpretationResponse
import com.example.tasksync.data.remote.ProposedOperation
import com.example.tasksync.data.remote.UpdateProposal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ProposalApprovalDialog(
    response: InterpretationResponse,
    transcript: String,
    onDismiss: () -> Unit,
    viewModel: ProposalReviewViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.applySelected(notificationPermissionGranted = granted)
    }
    var dismissedUnsupported by remember(response.requestId) { mutableStateOf(emptySet<Int>()) }
    LaunchedEffect(response.requestId) { viewModel.load(response) }
    val pagerState = rememberPagerState(pageCount = { state.rows.size })
    val included = state.rows.filter { it.selected }
    val approvedCount = included.count { it.canApply }
    val unresolvedCount = included.count { !it.canApply }
    val hasReminderOutcomeNeedingSettings = state.rows.any { row ->
        row.outcome?.message?.let { message ->
            message.contains("notification", ignoreCase = true) || message.contains("channel", ignoreCase = true)
        } == true
    }

    AlertDialog(
        onDismissRequest = { if (!state.applying) onDismiss() },
        title = { Text("Review proposed changes") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Confirmed transcript", style = MaterialTheme.typography.titleSmall)
                Text(transcript, style = MaterialTheme.typography.bodySmall)
                Text("${response.status.name.lowercase().replace('_', ' ')} · ${response.operations.size} operation(s)", style = MaterialTheme.typography.labelMedium)
                state.loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                response.clarifications.forEach { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Needs clarification", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                            Text("${item.sourceText}: ${item.question}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                response.unsupportedItems.forEachIndexed { index, item ->
                    if (index in dismissedUnsupported) return@forEachIndexed
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Not interpreted", style = MaterialTheme.typography.titleSmall)
                            Text("${item.sourceText}: ${item.reason}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { dismissedUnsupported = dismissedUnsupported + index }) { Text("Dismiss notice") }
                        }
                    }
                }
                if (state.loading) {
                    CircularProgressIndicator()
                } else if (state.rows.isEmpty()) {
                    Text(state.loadError ?: "There are no task changes to apply.")
                } else {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 250.dp, max = 400.dp),
                    ) { page ->
                        ProposalReviewCard(state.rows[page], viewModel)
                    }
                    Text("${pagerState.currentPage + 1} of ${state.rows.size} · Swipe to review", modifier = Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelMedium)
                }
                if (unresolvedCount > 0) {
                    Text("Resolve or exclude $unresolvedCount selected proposal(s) before applying.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else if (approvedCount == 0 && !state.loading) {
                    Text("Select at least one proposal to apply.", style = MaterialTheme.typography.bodySmall)
                }
                if (state.applying) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator()
                        Text("Applying approved changes…")
                    }
                }
                state.summary?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                if (hasReminderOutcomeNeedingSettings) {
                    TextButton(onClick = {
                        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, com.example.tasksync.data.reminder.TaskReminderScheduler.CHANNEL_ID)
                        context.startActivity(intent)
                    }) { Text("Open reminder notification settings") }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (state.finished) {
                        onDismiss()
                    } else {
                        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            included.any { it.canApply && it.reminderRequested && it.dueDateInput.isNotBlank() && it.dueTimeInput.isNotBlank() } &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        if (needsPermission) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else viewModel.applySelected()
                    }
                },
                enabled = state.finished || (approvedCount > 0 && unresolvedCount == 0 && !state.applying && !state.loading),
            ) { Text(if (state.finished) "Done" else if (state.applying) "Applying…" else "Confirm & Apply ($approvedCount)") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.applying) { Text(if (state.finished) "Done" else "Close") }
        },
    )
}

@Composable
private fun ProposalReviewCard(row: ReviewProposal, viewModel: ProposalReviewViewModel) {
    val type = when (row.operation) {
        is AddProposal -> "ADD"
        is UpdateProposal -> "UPDATE"
        is CompleteProposal -> "COMPLETE"
        is DeleteProposal -> "DELETE"
    }
    val typeColor = when (row.operation) {
        is AddProposal -> MaterialTheme.colorScheme.primary
        is UpdateProposal -> MaterialTheme.colorScheme.tertiary
        is CompleteProposal -> MaterialTheme.colorScheme.secondary
        is DeleteProposal -> MaterialTheme.colorScheme.error
    }
    val originalDatePhrase = when (val op = row.operation) {
        is AddProposal -> op.datePhrase
        is UpdateProposal -> op.changes.datePhrase
        else -> null
    }
    val originalTimePhrase = when (val op = row.operation) {
        is AddProposal -> op.timePhrase
        is UpdateProposal -> op.changes.timePhrase
        else -> null
    }
    val filteredMatches = remember(row.matches) { row.matches.take(8) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = row.selected, onCheckedChange = { viewModel.setSelected(row.operationId, it) })
                Column {
                    Text(type, style = MaterialTheme.typography.titleSmall, color = typeColor)
                    Text(row.operation.sourceText, style = MaterialTheme.typography.bodySmall)
                }
            }
            when (val op = row.operation) {
                is AddProposal -> {
                    OutlinedTextField(row.editedTitle, { viewModel.editTitle(row.operationId, it) }, label = { Text("Task title") }, modifier = Modifier.fillMaxWidth())
                    if (op.description != null) OutlinedTextField(row.editedDescription, { viewModel.editDescription(row.operationId, it) }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                    PriorityField(row, viewModel, allowKeep = false)
                    DateField(row, viewModel)
                    TimeField(row, viewModel)
                    if (row.reminderRequested) {
                        Text(
                            if (row.dueDateInput.isBlank() || row.dueTimeInput.isBlank()) "Reminder requested; choose both a date and time to schedule it."
                            else "A reminder will be scheduled after you confirm this task.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                is UpdateProposal -> {
                    Text("Requested target: ${op.targetText}", style = MaterialTheme.typography.bodyMedium)
                    if (op.changes.title != null) OutlinedTextField(row.editedTitle, { viewModel.editTitle(row.operationId, it) }, label = { Text("New title") }, modifier = Modifier.fillMaxWidth())
                    if (op.changes.description != null) OutlinedTextField(row.editedDescription, { viewModel.editDescription(row.operationId, it) }, label = { Text("New description") }, modifier = Modifier.fillMaxWidth())
                    PriorityField(row, viewModel, allowKeep = true)
                    if (op.changes.datePhrase != null || op.changes.timePhrase.impliesDate()) DateField(row, viewModel)
                    if (op.changes.timePhrase != null || op.changes.datePhrase.requiresExactTime()) TimeField(row, viewModel)
                    TargetPicker(row, filteredMatches, viewModel)
                }
                is CompleteProposal -> {
                    Text("Requested target: ${op.targetText}")
                    Text("Uses the app’s existing completion and recurrence behavior.", style = MaterialTheme.typography.bodySmall)
                    TargetPicker(row, filteredMatches, viewModel)
                }
                is DeleteProposal -> {
                    Text("Requested target: ${op.targetText}")
                    Text("Permanent deletion: this task and its subtasks will be removed. There is no undo.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TargetPicker(row, filteredMatches, viewModel)
                }
            }
            originalDatePhrase?.let { Text("Original date phrase: $it", style = MaterialTheme.typography.bodySmall) }
            originalTimePhrase?.let { Text("Original time phrase: $it", style = MaterialTheme.typography.bodySmall) }
            originalDatePhrase?.let { Text("Spoken date phrase: $it", style = MaterialTheme.typography.bodySmall) }
            originalTimePhrase?.let { Text("Spoken time phrase: $it", style = MaterialTheme.typography.bodySmall) }
            row.scheduleWarnings.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
            row.issues.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            row.outcome?.let { result ->
                Text(if (result.succeeded) result.message else "Not applied: ${result.message}", color = if (result.succeeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(row: ReviewProposal, viewModel: ProposalReviewViewModel) {
    var open by remember(row.operationId) { mutableStateOf(false) }
    val initialDate = runCatching { LocalDate.parse(row.dueDateInput) }.getOrNull()
    val pickerState = key(row.operationId, row.dueDateInput) {
        rememberDatePickerState(initialSelectedDateMillis = initialDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { open = true }) {
            Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
            Text(initialDate?.format(DateTimeFormatter.ofPattern("EEEE, d MMMM uuuu", Locale.getDefault())) ?: "Choose due date")
        }
        if (row.dueDateInput.isNotBlank()) TextButton(onClick = { viewModel.selectDate(row.operationId, null) }) { Text("Clear") }
    }
    if (row.inferredDeadline) Text("Inferred deadline: end of this locale week. You can edit it.", style = MaterialTheme.typography.bodySmall)
    if (open) DatePickerDialog(
        onDismissRequest = { open = false },
        confirmButton = {
            TextButton(onClick = {
                val selected = pickerState.selectedDateMillis?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                viewModel.selectDate(row.operationId, selected)
                open = false
            }) { Text("Set date") }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
    ) { DatePicker(state = pickerState) }
}

@Composable
private fun PriorityField(row: ReviewProposal, viewModel: ProposalReviewViewModel, allowKeep: Boolean) {
    var expanded by remember(row.operationId) { mutableStateOf(false) }
    val label = when (row.selectedPriority) {
        1 -> "High"
        2 -> "Medium"
        3 -> "Low"
        4 -> "No priority"
        else -> "Keep existing priority"
    }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Priority: $label") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowKeep) DropdownMenuItem(text = { Text("Keep existing") }, onClick = { expanded = false; viewModel.selectPriority(row.operationId, null) })
            listOf(1 to "High", 2 to "Medium", 3 to "Low", 4 to "No priority").forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; viewModel.selectPriority(row.operationId, value) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(row: ReviewProposal, viewModel: ProposalReviewViewModel) {
    var open by remember(row.operationId) { mutableStateOf(false) }
    val context = LocalContext.current
    val is24Hour = AndroidDateFormat.is24HourFormat(context)
    val initialTime = runCatching { LocalTime.parse(row.dueTimeInput) }.getOrNull() ?: LocalTime.now()
    val pickerState = key(row.operationId, row.dueTimeInput) { rememberTimePickerState(initialTime.hour, initialTime.minute, is24Hour) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { open = true }) {
            Icon(Icons.Outlined.AccessTime, contentDescription = null)
            Text(if (row.dueTimeInput.isBlank()) "Choose due time" else initialTime.format(DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", Locale.getDefault())))
        }
        if (row.dueTimeInput.isNotBlank()) TextButton(onClick = { viewModel.selectTime(row.operationId, null) }) { Text("Clear") }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Choose due time") },
        text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = pickerState) } },
        confirmButton = {
            TextButton(onClick = { viewModel.selectTime(row.operationId, LocalTime.of(pickerState.hour, pickerState.minute)); open = false }) { Text("Set time") }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
    )
}

@Composable
private fun TargetPicker(row: ReviewProposal, matches: List<com.example.tasksync.data.interpretation.TaskMatch>, viewModel: ProposalReviewViewModel) {
    var targetSearch by remember(row.operationId) { mutableStateOf("") }
    val filteredMatches = matches.filter { match ->
        targetSearch.isBlank() || match.task.title.contains(targetSearch, ignoreCase = true) ||
            match.task.description.orEmpty().contains(targetSearch, ignoreCase = true)
    }.take(8)
    Text(when (row.matchQuality) {
        MatchQuality.EXACT, MatchQuality.CONFIDENT -> "Matched task — verify before applying"
        MatchQuality.AMBIGUOUS -> "Choose the intended task (ambiguous match)"
        MatchQuality.LOW_CONFIDENCE -> "Choose a task (match is uncertain)"
        MatchQuality.NONE -> "Search existing tasks to choose the target"
        null -> "Choose a task"
    }, style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(targetSearch, { targetSearch = it }, label = { Text("Search existing tasks") }, modifier = Modifier.fillMaxWidth())
    if (matches.isEmpty()) Text("No matching tasks were found. This proposal cannot be applied until a target is selected.", style = MaterialTheme.typography.bodySmall)
    filteredMatches.forEach { match ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = row.matchedTaskId == match.task.id, onClick = { viewModel.chooseTask(row.operationId, match.task.id) })
            Column {
                Text(match.task.title)
                Text("${match.task.status} · ${match.task.description.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
