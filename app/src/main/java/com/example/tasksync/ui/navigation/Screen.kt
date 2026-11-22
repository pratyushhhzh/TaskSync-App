package com.example.tasksync.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Tasks : Screen("tasks", "Home", Icons.Outlined.Home)
    object Inbox : Screen("inbox", "Inbox", Icons.AutoMirrored.Filled.List)
    object Calendar : Screen("calendar", "Calendar", Icons.Outlined.CalendarMonth)
    object Projects : Screen("projects", "Projects", Icons.Outlined.GridView)

    companion object {
        // Defer reading the nested object singletons until Screen initialization
        // has completed; an eager list here can capture null during class startup.
        val bottomNavScreens by lazy(LazyThreadSafetyMode.NONE) {
            listOf(Tasks, Calendar, Projects)
        }
    }
}
