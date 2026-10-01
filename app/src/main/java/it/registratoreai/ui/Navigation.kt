package it.registratoreai.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import it.registratoreai.MainActivity
import it.registratoreai.ui.screens.DetailScreen
import it.registratoreai.ui.screens.HomeScreen
import it.registratoreai.ui.screens.RecordScreen
import it.registratoreai.ui.screens.SettingsScreen

@Composable
fun AppNavigation(activity: MainActivity) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                activity = activity,
                onOpen = { nav.navigate("detail/$it") },
                onRecord = { nav.navigate("record") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("record") {
            RecordScreen(
                onBack = { nav.popBackStack() },
                onFinished = { id ->
                    nav.popBackStack("home", inclusive = false)
                    nav.navigate("detail/$id")
                },
            )
        }
        composable("detail/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            DetailScreen(id = it.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
