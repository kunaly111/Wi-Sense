package com.wisense.resident.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.wisense.resident.presentation.MainViewModel
import com.wisense.resident.presentation.screens.EmergencyScreen
import com.wisense.resident.presentation.screens.IdleScreen
import com.wisense.resident.presentation.screens.SettingsScreen
import com.wisense.resident.presentation.screens.SetupScreen

object Routes {
    const val SETUP = "setup"
    const val IDLE = "idle"
    const val SETTINGS = "settings"
    const val EMERGENCY = "emergency"
}

@Composable
fun ResidentNavHost() {
    val navController = rememberNavController()
    val viewModel: MainViewModel = hiltViewModel()

    // ALERT → Emergency screen; CANCEL → back to Idle. Driven by the service's
    // capture session so it works even when the app was backgrounded.
    LaunchedEffect(Unit) {
        viewModel.emergencySession.collect { session ->
            if (session.active) {
                navController.navigateToEmergency()
            } else {
                navController.exitEmergency()
            }
        }
    }

    NavHost(navController = navController, startDestination = Routes.SETUP) {
        composable(Routes.SETUP) {
            SetupScreen(
                viewModel = viewModel,
                onDone = {
                    navController.navigate(Routes.IDLE) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.IDLE) {
            IdleScreen(
                viewModel = viewModel,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.EMERGENCY) {
            EmergencyScreen(viewModel = viewModel)
        }
    }
}

private fun NavHostController.navigateToEmergency() {
    if (currentDestination?.route != Routes.EMERGENCY) {
        navigate(Routes.EMERGENCY)
    }
}

private fun NavHostController.exitEmergency() {
    if (currentDestination?.route == Routes.EMERGENCY) {
        popBackStack()
    }
}
