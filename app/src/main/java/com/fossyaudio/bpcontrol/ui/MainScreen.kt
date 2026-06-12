package com.fossyaudio.bpcontrol.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fossyaudio.bpcontrol.presentation.MainViewModel
import com.fossyaudio.bpcontrol.ui.screens.EqScreen
import com.fossyaudio.bpcontrol.ui.screens.SettingsScreen

@Composable
fun MainScreen(viewModel: MainViewModel, actions: AppActions) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentRoute == ROUTE_SETTINGS,
                    onClick = {
                        navController.navigate(ROUTE_SETTINGS) {
                            launchSingleTop = true
                            popUpTo(ROUTE_SETTINGS)
                        }
                    },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                )
                NavigationBarItem(
                    selected = currentRoute == ROUTE_EQ,
                    onClick = {
                        navController.navigate(ROUTE_EQ) {
                            launchSingleTop = true
                            popUpTo(ROUTE_SETTINGS)
                        }
                    },
                    icon = { Icon(Icons.Filled.Equalizer, contentDescription = "PEQ") },
                    label = { Text("PEQ") },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_SETTINGS,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(ROUTE_SETTINGS) {
                SettingsScreen(viewModel = viewModel, actions = actions)
            }
            composable(ROUTE_EQ) {
                EqScreen(viewModel = viewModel, actions = actions)
            }
        }
    }
}
