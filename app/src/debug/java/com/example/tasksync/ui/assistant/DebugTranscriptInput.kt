package com.example.tasksync.ui.assistant

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
internal fun DebugTranscriptInput(onInterpret: (String) -> Unit) {
    var typedTranscript by remember { mutableStateOf("") }
    Text("Debug test input")
    OutlinedTextField(
        value = typedTranscript,
        onValueChange = { typedTranscript = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Type a transcript") },
        supportingText = { Text("Runs the local model and the same review flow without starting speech capture.") },
        minLines = 2,
        maxLines = 5
    )
    OutlinedButton(
        onClick = { onInterpret(typedTranscript) },
        enabled = typedTranscript.isNotBlank()
    ) { Text("Interpret typed transcript") }
}
