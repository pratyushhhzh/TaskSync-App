package com.example.tasksync.ui.interpretation

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterpretationReviewDialog(
    transcript: String,
    onDismiss: () -> Unit,
    onReviewProposals: (com.example.tasksync.data.remote.InterpretationResponse) -> Unit = {},
    viewModel: InterpretationViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val dismiss = {
        viewModel.cancelActiveRequest()
        onDismiss()
    }
    LaunchedEffect(transcript) { viewModel.interpret(transcript) }
    val success = (state as? InterpretationUiState.Success)?.takeIf { it.transcript == transcript }
    LaunchedEffect(success?.attempt) {
        success?.takeIf { viewModel.isCurrentAttempt(it.attempt) }?.let {
            onReviewProposals(it.response)
            onDismiss()
        }
    }

    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.68f)), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = true, enter = fadeIn(tween(350)) + slideInVertically(tween(550)) { it / 6 }, modifier = Modifier.align(Alignment.BottomCenter)) {
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp).heightIn(min = 420.dp, max = 700.dp),
            shape = RoundedCornerShape(36.dp),
            color = Color(0xFF131A48),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
            shadowElevation = 22.dp,
        ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 700.dp).padding(horizontal = 22.dp).padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Surface(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 3.dp).size(width = 38.dp, height = 4.dp), color = Color.White.copy(alpha = 0.28f), shape = CircleShape) {}
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reading your tasks…", style = MaterialTheme.typography.titleLarge)
                    Text("Finding tasks, dates and priorities", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = dismiss) { Icon(Icons.Default.Close, contentDescription = "Close interpretation", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Surface(
                Modifier.fillMaxWidth().heightIn(max = 190.dp),
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF080B13).copy(alpha = 0.55f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("CONFIRMED TRANSCRIPT", style = MaterialTheme.typography.labelMedium, color = Color(0xFFBFD0FF), letterSpacing = 0.8.sp)
                    SelectionContainer { Text(transcript, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground) }
                }
            }
            when (val current = state) {
                InterpretationUiState.Idle, is InterpretationUiState.Loading -> {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        val attempt = (state as? InterpretationUiState.Loading)?.attempt ?: 1
                        Text(if (attempt > 1) "Retrying interpretation…" else "Requesting proposals…", modifier = Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is InterpretationUiState.Failure -> {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f))) {
                        Text(current.message, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (current.canRetryInterpretation()) {
                        Button(onClick = viewModel::retry, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp)) { Text("Retry interpretation") }
                    } else {
                        Text("Your confirmed transcript is still here. Close when you’re ready to try again.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
                is InterpretationUiState.Success -> Text("Preparing the proposal review…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = dismiss, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp)) { Text("Close") }
        }
        }
        }
        }
    }
}
