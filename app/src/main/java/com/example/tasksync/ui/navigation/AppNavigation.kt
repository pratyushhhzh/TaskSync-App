package com.example.tasksync.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.tasksync.ui.auth.AuthViewModel
import com.example.tasksync.ui.auth.LoginScreen
import com.example.tasksync.ui.auth.RegisterScreen
import com.example.tasksync.ui.projects.ProjectsScreen
import com.example.tasksync.ui.roadmap.BreakItDownScreen
import com.example.tasksync.ui.settings.SettingsScreen
import com.example.tasksync.ui.tasks.CompletedScreen
import com.example.tasksync.ui.tasks.InboxScreen
import com.example.tasksync.ui.tasks.TodayScreen
import com.example.tasksync.ui.tasks.UpcomingScreen

@Composable
fun AppNavigation(
    navController: NavHostController,
    authViewModel: AuthViewModel,
    modifier: Modifier = Modifier
) {
    val authUiState by authViewModel.uiState.collectAsState()

    NavHost(
        navController = navController,
        startDestination = Screen.Login.route,
        modifier = modifier
    ) {
        composable(Screen.Login.route) {
            LoginScreen(
                uiState = authUiState,
                onLoginClick = { email, password ->
                    authViewModel.login(email, password)
                },
                onNavigateToRegister = {
                    navController.navigate(Screen.Register.route)
                }
            )
        }
        composable(Screen.Register.route) {
            RegisterScreen(
                uiState = authUiState,
                onRegisterClick = { email, password, fullName ->
                    authViewModel.register(email, password, fullName)
                },
                onNavigateToLogin = {
                    navController.navigate(Screen.Login.route)
                }
            )
        }
        composable(Screen.Today.route) { TodayScreen() }
        composable(Screen.Inbox.route) { InboxScreen() }
        composable(Screen.Upcoming.route) { UpcomingScreen() }
        composable(Screen.Completed.route) { CompletedScreen() }
        composable(Screen.Projects.route) { ProjectsScreen() }
        composable(Screen.BreakItDown.route) { BreakItDownScreen() }
        composable(Screen.Settings.route) { SettingsScreen(onLogout = authViewModel::logout) }
    }
}
