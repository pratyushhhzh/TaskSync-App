package com.example.tasksync.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.tasksync.ui.projects.ProjectsScreen
import com.example.tasksync.ui.tasks.CalendarScreen
import com.example.tasksync.ui.tasks.InboxScreen
import com.example.tasksync.ui.tasks.TasksScreen

@Composable
fun AppNavigation(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Tasks.route,
        modifier = modifier
    ) {
        composable(Screen.Tasks.route) { TasksScreen() }
        composable(Screen.Inbox.route) { InboxScreen() }
        composable(Screen.Calendar.route) { CalendarScreen() }
        composable(Screen.Projects.route) { ProjectsScreen() }
    }
}
