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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@OptIn(ExperimentalMaterial3Api::class)
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
    LaunchedEffect(state.finished) {
        if (state.finished) onDismiss()
    }

    if (!state.finished) Dialog(
        onDismissRequest = { if (!state.applying) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.68f)), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(380)) + slideInVertically(tween(550)) { it / 7 },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxHeight(0.84f).heightIn(max = 780.dp),
                shape = RoundedCornerShape(36.dp),
                color = Color(0xFF131A48),
                contentColor = Color(0xFFF2F4FA),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.13f)),
                shadowElevation = 24.dp,
            ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Surface(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 12.dp).size(width = 38.dp, height = 4.dp), color = Color.White.copy(alpha = 0.28f), shape = RoundedCornerShape(4.dp)) {}
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${response.operations.size} changes found", style = MaterialTheme.typography.titleLarge, color = Color(0xFFF2F4FA))
                    Text("${(pagerState.currentPage + 1).coerceAtMost(state.rows.size.coerceAtLeast(1))} of ${state.rows.size}", style = MaterialTheme.typography.labelMedium, color = Color(0xFFA9B3D6))
                }
                IconButton(onClick = onDismiss, enabled = !state.applying) {
                    Icon(Icons.Default.Close, contentDescription = "Close proposal review", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (response.operations.any { it is DeleteProposal }) {
                Surface(Modifier.fillMaxWidth().padding(bottom = 10.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.2f))) {
                    Text("${response.operations.count { it is DeleteProposal }} permanently deletes a task", Modifier.padding(horizontal = 12.dp, vertical = 9.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color(0xFF080B13).copy(alpha = 0.55f), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("ORIGINAL REQUEST", style = MaterialTheme.typography.labelMedium, color = Color(0xFFBFD0FF), letterSpacing = 0.8f.sp)
                        Text(transcript, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("${response.status.name.lowercase().replace('_', ' ')} · ${response.operations.size} operation(s)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                response.clarifications.forEach { item ->
                    ReviewNoticeCard(title = "Needs clarification", text = "${item.sourceText}: ${item.question}", danger = true)
                }
                response.unsupportedItems.forEachIndexed { index, item ->
                    if (index in dismissedUnsupported) return@forEachIndexed
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Not interpreted", style = MaterialTheme.typography.titleSmall)
                            Text("${item.sourceText}: ${item.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { dismissedUnsupported = dismissedUnsupported + index }) { Text("Dismiss notice") }
                        }
                    }
                }
                if (state.loading) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                } else if (state.rows.isEmpty()) {
                    Text(state.loadError ?: "There are no task changes to apply.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Box(Modifier.fillMaxWidth().height(410.dp)) {
                        if (state.rows.size > 2) Surface(
                            Modifier.fillMaxWidth().padding(horizontal = 28.dp).offset(y = 16.dp).height(370.dp),
                            shape = RoundedCornerShape(26.dp), color = Color(0xFF10173D), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        ) { }
                        if (state.rows.size > 1) Surface(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp).offset(y = 8.dp).height(378.dp),
                            shape = RoundedCornerShape(27.dp), color = Color(0xFF121A42), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                        ) { }
                        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                            ProposalReviewCard(state.rows[page], viewModel)
                        }
                    }
                    Text("Swipe to review", modifier = Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelMedium, color = Color(0xFFA9B3D6))
                }
                if (unresolvedCount > 0) {
                    Text("Resolve or exclude $unresolvedCount selected proposal(s) before applying.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else if (approvedCount == 0 && !state.loading) {
                    Text("Select at least one proposal to apply.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.applying) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator()
                        Text("Applying approved changes…")
                    }
                }
                if (!state.finished) {
                    state.summary?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                }
                if (hasReminderOutcomeNeedingSettings) {
                    TextButton(onClick = {
                        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, com.example.tasksync.data.reminder.TaskReminderScheduler.CHANNEL_ID)
                        context.startActivity(intent)
                    }) { Text("Open reminder notification settings") }
                }
            }
            Button(
                onClick = {
                    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        included.any { it.canApply && it.reminderRequested && it.dueDateInput.isNotBlank() && it.dueTimeInput.isNotBlank() } &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    if (needsPermission) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else viewModel.applySelected()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                shape = RoundedCornerShape(28.dp),
                enabled = approvedCount > 0 && unresolvedCount == 0 && !state.applying && !state.loading,
            ) { Text(if (state.applying) "Applying…" else "Confirm & Apply $approvedCount") }
            TextButton(onClick = onDismiss, enabled = !state.applying, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel") }
        }
            }
            }
        }
    }
}

@Composable
private fun ReviewNoticeCard(title: String, text: String, danger: Boolean) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (danger) MaterialTheme.colorScheme.error.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, (if (danger) MaterialTheme.colorScheme.error else Color.White).copy(alpha = 0.2f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141B44)),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
    ) {
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
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (allowKeep) FilterChip(selected = row.selectedPriority == null, onClick = { viewModel.selectPriority(row.operationId, null) }, label = { Text("Keep") })
        listOf(3 to "Low", 2 to "Medium", 1 to "High").forEach { (value, text) ->
            FilterChip(
                selected = row.selectedPriority == value,
                onClick = { viewModel.selectPriority(row.operationId, value) },
                label = { Text(text) },
            )
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
