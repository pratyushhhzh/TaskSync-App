package com.example.tasksync.ui.main

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.tasksync.ui.auth.AuthViewModel
import com.example.tasksync.ui.navigation.AppNavigation
import com.example.tasksync.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    authViewModel: AuthViewModel = viewModel()
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val authUiState by authViewModel.uiState.collectAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Today.route

    LaunchedEffect(authUiState, currentRoute) {
        when (authUiState) {
            is com.example.tasksync.ui.auth.AuthUiState.Authenticated -> {
                if (currentRoute == Screen.Login.route || currentRoute == Screen.Register.route) {
                    navController.navigate(Screen.Today.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            com.example.tasksync.ui.auth.AuthUiState.Idle -> {
                if (currentRoute != Screen.Login.route && currentRoute != Screen.Register.route) {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Today.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            else -> Unit
        }
    }

    val showBottomBarAndFab = currentRoute != Screen.Login.route && currentRoute != Screen.Register.route

    val currentScreen = Screen.bottomNavScreens.find { it.route == currentRoute }
        ?: when (currentRoute) {
            Screen.Settings.route -> Screen.Settings
            Screen.Login.route -> Screen.Login
            Screen.Register.route -> Screen.Register
            else -> Screen.Today
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = currentScreen.title,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    if (showBottomBarAndFab) {
                        IconButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            if (showBottomBarAndFab) {
                NavigationBar {
                    Screen.bottomNavScreens.forEach { screen ->
                        NavigationBarItem(
                            selected = currentRoute == screen.route,
                            onClick = {
                                if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = screen.icon,
                                    contentDescription = screen.title
                                )
                            },
                            label = { Text(screen.title) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (showBottomBarAndFab) {
                FloatingActionButton(
                    onClick = {
                        // Quick Capture Assistant trigger
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Voice / Quick Capture"
                    )
                }
            }
        }
    ) { innerPadding ->
        AppNavigation(
            navController = navController,
            authViewModel = authViewModel,
            modifier = Modifier.padding(innerPadding)
        )
    }
}
