package com.example.tasksync.ui.interpretation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tasksync.data.remote.AddProposal
import com.example.tasksync.data.remote.CompleteProposal
import com.example.tasksync.data.remote.DeleteProposal
import com.example.tasksync.data.remote.ProposedOperation
import com.example.tasksync.data.remote.UpdateProposal

@Composable
fun InterpretationReviewDialog(
    transcript: String,
    onDismiss: () -> Unit,
    onReviewProposals: (com.example.tasksync.data.remote.InterpretationResponse) -> Unit = {},
    viewModel: InterpretationViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(transcript) { viewModel.interpret(transcript) }
    val success = (state as? InterpretationUiState.Success)?.takeIf { it.transcript == transcript }
    LaunchedEffect(success?.attempt) {
        success?.takeIf { viewModel.isCurrentAttempt(it.attempt) }?.let {
            onReviewProposals(it.response)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Interpretation review") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Confirmed transcript", style = MaterialTheme.typography.titleSmall)
                Text(transcript)
                when (val current = state) {
                    InterpretationUiState.Idle, is InterpretationUiState.Loading -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text("Requesting proposals…")
                        }
                    }
                    is InterpretationUiState.Failure -> {
                        Text(current.message, color = MaterialTheme.colorScheme.error)
                        if (current.retryable) OutlinedButton(onClick = viewModel::retry) { Text("Retry") }
                    }
                    is InterpretationUiState.Success -> Text(if (current.transcript == transcript && viewModel.isCurrentAttempt(current.attempt)) "Preparing the proposal review…" else "Requesting proposals…")
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun ProposalCard(operation: ProposedOperation) {
    val kind = when (operation) {
        is AddProposal -> "ADD"
        is UpdateProposal -> "UPDATE"
        is CompleteProposal -> "COMPLETE"
        is DeleteProposal -> "DELETE"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(kind, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            when (operation) {
                is AddProposal -> {
                    Text(operation.title, style = MaterialTheme.typography.titleMedium)
                    operation.description?.let { Text("Description: $it") }
                    operation.datePhrase?.let { Text("Date: $it") }
                    operation.timePhrase?.let { Text("Time: $it") }
                }
                is UpdateProposal -> {
                    Text("Target: ${operation.targetText}")
                    operation.changes.title?.let { Text("New title: $it") }
                    operation.changes.description?.let { Text("Description: $it") }
                    operation.changes.datePhrase?.let { Text("Date: $it") }
                    operation.changes.timePhrase?.let { Text("Time: $it") }
                }
                is CompleteProposal -> Text("Task: ${operation.targetText}")
                is DeleteProposal -> Text("Task: ${operation.targetText}")
            }
            Text("Source: ${operation.sourceText}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun com.example.tasksync.data.remote.InterpretationStatus.label(): String = when (this) {
    com.example.tasksync.data.remote.InterpretationStatus.PROPOSALS -> "Proposals"
    com.example.tasksync.data.remote.InterpretationStatus.NEEDS_CLARIFICATION -> "Needs clarification"
    com.example.tasksync.data.remote.InterpretationStatus.UNSUPPORTED -> "Unsupported instruction"
    com.example.tasksync.data.remote.InterpretationStatus.PARTIAL -> "Partial interpretation"
}
