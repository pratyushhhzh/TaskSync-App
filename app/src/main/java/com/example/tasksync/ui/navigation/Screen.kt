package com.example.tasksync.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Login : Screen("login", "Sign In", Icons.Default.Lock)
    object Register : Screen("register", "Sign Up", Icons.Default.PersonAdd)
    object Today : Screen("today", "Today", Icons.Default.Today)
    object Inbox : Screen("inbox", "Inbox", Icons.AutoMirrored.Filled.List)
    object Upcoming : Screen("upcoming", "Upcoming", Icons.Default.DateRange)
    object Completed : Screen("completed", "Completed", Icons.Default.CheckCircle)
    object Projects : Screen("projects", "Projects", Icons.Default.Folder)
    object BreakItDown : Screen("break_it_down", "Break It Down", Icons.Default.AutoAwesome)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)

    companion object {
        val bottomNavScreens = listOf(Today, Inbox, Upcoming, Projects, BreakItDown)
    }
}
