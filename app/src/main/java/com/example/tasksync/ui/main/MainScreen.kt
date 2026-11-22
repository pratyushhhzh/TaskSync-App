package com.example.tasksync.ui.main

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.tasksync.data.remote.InterpretationResponse
import com.example.tasksync.ui.assistant.VoiceTaskAssistantDialog
import com.example.tasksync.ui.interpretation.InterpretationReviewDialog
import com.example.tasksync.ui.interpretation.ProposalApprovalDialog
import com.example.tasksync.ui.navigation.AppNavigation
import com.example.tasksync.ui.navigation.Screen

@Composable
fun MainScreen(
    openVoiceAssistantSignal: Int = 0,
    openTaskSignal: Int = 0,
    openTaskId: String? = null,
    openCalendarWidgetSignal: Int = 0,
    openCalendarWidgetDate: String? = null,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Screen.Tasks.route
    val screens = Screen.bottomNavScreens
    var showVoiceAssistant by remember { mutableStateOf(false) }
    var confirmedTranscript by remember { mutableStateOf<String?>(null) }
    var proposalResponse by remember { mutableStateOf<InterpretationResponse?>(null) }
    var proposalTranscript by remember { mutableStateOf("") }
    var showCalendarOptIn by remember { mutableStateOf(false) }
    var explainCalendarAccess by remember { mutableStateOf(false) }
    var calendarSetupMessage by remember { mutableStateOf<String?>(null) }
    var calendarSetupSignal by remember { mutableIntStateOf(0) }
    LaunchedEffect(context) {
        val preferences = context.getSharedPreferences("calendar_onboarding", android.content.Context.MODE_PRIVATE)
        if (!preferences.getBoolean("choice_made", false)) showCalendarOptIn = true
    }
    val calendarPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        context.getSharedPreferences("calendar_onboarding", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("choice_made", true).apply()
        if (grants[Manifest.permission.READ_CALENDAR] == true && grants[Manifest.permission.WRITE_CALENDAR] == true) {
            calendarSetupSignal++
            navController.navigate(Screen.Calendar.route) { launchSingleTop = true }
        } else {
            calendarSetupMessage = "Calendar access was not granted. Tasks, reminders and other TaskSync features continue to work locally. You can connect a calendar later from the Calendar tab."
        }
    }
    LaunchedEffect(openVoiceAssistantSignal) { if (openVoiceAssistantSignal > 0) showVoiceAssistant = true }
    LaunchedEffect(openTaskSignal) {
        if (openTaskSignal > 0) navController.navigate(Screen.Tasks.route) { launchSingleTop = true }
    }
    LaunchedEffect(openCalendarWidgetSignal) {
        if (openCalendarWidgetSignal > 0) navController.navigate(Screen.Calendar.route) { launchSingleTop = true }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.weight(1f).widthIn(max = 278.dp).animateContentSize(),
                    shape = RoundedCornerShape(32.dp),
                    color = Color.White.copy(alpha = 0.07f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    shadowElevation = 18.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        screens.forEach { screen ->
                            BottomTab(
                                icon = screen.icon,
                                label = screen.title,
                                selected = currentRoute == screen.route,
                                onClick = {
                                    if (currentRoute != screen.route) {
                                        // Prefer returning to an existing tab destination. This avoids
                                        // stacking another copy when switching back from Calendar/Projects.
                                        val returnedToExistingTab = navController.popBackStack(
                                            screen.route,
                                            inclusive = false,
                                            saveState = true,
                                        )
                                        if (!returnedToExistingTab) {
                                            navController.navigate(screen.route) {
                                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                val pulse = rememberInfiniteTransition(label = "capture-pulse")
                val scale by pulse.animateFloat(1f, 1.055f, infiniteRepeatable(tween(1150), RepeatMode.Reverse), label = "mic-scale")
                Surface(
                    modifier = Modifier.padding(start = 10.dp).offset(y = (-2).dp).size(64.dp).scale(scale)
                        .clickable { showVoiceAssistant = true },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                    shadowElevation = 16.dp,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.Mic, contentDescription = "Capture a thought", tint = Color.White, modifier = Modifier.size(27.dp))
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            AppNavigation(
                navController = navController,
                modifier = Modifier.fillMaxSize(),
                calendarSetupSignal = calendarSetupSignal,
                openTaskSignal = openTaskSignal,
                openTaskId = openTaskId,
                openCalendarWidgetSignal = openCalendarWidgetSignal,
                openCalendarWidgetDate = openCalendarWidgetDate,
            )
        }
    }

    if (showVoiceAssistant) VoiceTaskAssistantDialog(
        onDismiss = { showVoiceAssistant = false },
        onTranscriptConfirmed = { transcript ->
            confirmedTranscript = transcript
            showVoiceAssistant = false
        },
    )
    confirmedTranscript?.let { transcript ->
        InterpretationReviewDialog(
            transcript = transcript,
            onDismiss = { confirmedTranscript = null },
            onReviewProposals = { response ->
                proposalResponse = response
                proposalTranscript = transcript
            },
        )
    }
    proposalResponse?.let { response ->
        ProposalApprovalDialog(
            response = response,
            transcript = proposalTranscript,
            onDismiss = { proposalResponse = null; proposalTranscript = "" },
        )
    }

    if (showCalendarOptIn) {
        AlertDialog(
            onDismissRequest = {
                showCalendarOptIn = false
                context.getSharedPreferences("calendar_onboarding", android.content.Context.MODE_PRIVATE).edit().putBoolean("choice_made", true).apply()
            },
            title = { Text("Connect Google Calendar?") },
            text = { Text("Would you like to connect Google Calendar with TaskSync?") },
            confirmButton = {
                TextButton(onClick = {
                    showCalendarOptIn = false
                    explainCalendarAccess = true
                }) { Text("Connect Google Calendar") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCalendarOptIn = false
                    context.getSharedPreferences("calendar_onboarding", android.content.Context.MODE_PRIVATE).edit().putBoolean("choice_made", true).apply()
                }) { Text("Not now") }
            },
        )
    }
    if (explainCalendarAccess) {
        AlertDialog(
            onDismissRequest = { explainCalendarAccess = false },
            title = { Text("Calendar access") },
            text = { Text("TaskSync uses Android Calendar access to read existing events and add or update events for your tasks. Choose the Google account calendar you want to use next. You can keep using TaskSync locally if you decline.") },
            confirmButton = {
                TextButton(onClick = {
                    explainCalendarAccess = false
                    val hasPermissions = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
                    if (hasPermissions) {
                        context.getSharedPreferences("calendar_onboarding", android.content.Context.MODE_PRIVATE).edit().putBoolean("choice_made", true).apply()
                        calendarSetupSignal++
                        navController.navigate(Screen.Calendar.route) { launchSingleTop = true }
                    } else {
                        calendarPermissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                    }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { explainCalendarAccess = false }) { Text("Cancel") } },
        )
    }
    calendarSetupMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { calendarSetupMessage = null },
            title = { Text("Calendar not connected") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { calendarSetupMessage = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun BottomTab(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(horizontal = 2.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(28.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.24f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (selected) 13.dp else 0.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) Color(0xFFBFD0FF) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            AnimatedVisibility(visible = selected, enter = fadeIn(tween(170)), exit = fadeOut(tween(100))) {
                Text(label, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = Color(0xFFBFD0FF), maxLines = 1)
            }
        }
    }
}
