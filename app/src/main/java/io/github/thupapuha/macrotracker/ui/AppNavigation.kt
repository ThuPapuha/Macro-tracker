package io.github.thupapuha.macrotracker.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.thupapuha.macrotracker.MacroTrackerApp
import io.github.thupapuha.macrotracker.core.model.MealType
import io.github.thupapuha.macrotracker.ui.add.AddFoodScreen
import io.github.thupapuha.macrotracker.ui.groups.GroupsScreen
import io.github.thupapuha.macrotracker.ui.history.HistoryScreen
import io.github.thupapuha.macrotracker.ui.profile.ProfileScreen
import io.github.thupapuha.macrotracker.ui.today.TodayScreen
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("today", "Hoy", Icons.Filled.Home),
    Tab("history", "Historial", Icons.Filled.DateRange),
    Tab("groups", "Grupos", Icons.Filled.Face),
    Tab("profile", "Perfil", Icons.Filled.AccountCircle),
)

private fun NavHostController.openAddFood(date: LocalDate, meal: MealType) = navigate("add/$date/${meal.name}")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppNavigation(openGroupRequests: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    val settings = (LocalContext.current.applicationContext as MacroTrackerApp).container.settings
    val openGroup by openGroupRequests.collectAsStateWithLifecycle()
    LaunchedEffect(openGroup) {
        val groupId = openGroup ?: return@LaunchedEffect
        settings.setLastGroup(groupId)
        nav.navigate("groups") {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        openGroupRequests.value = null
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBottomBar = tabs.any { it.route == route }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "today",
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable("today") {
                TodayScreen(
                    initialDate = null,
                    onBack = null,
                    onAddFood = nav::openAddFood,
                    onOpenProfile = { nav.navigate("profile") { launchSingleTop = true } },
                )
            }
            composable("day/{date}") { entry ->
                val date = entry.arguments?.getString("date")?.let(LocalDate::parse) ?: LocalDate.now()
                TodayScreen(
                    initialDate = date,
                    onBack = { nav.popBackStack() },
                    onAddFood = nav::openAddFood,
                    onOpenProfile = { nav.navigate("profile") { launchSingleTop = true } },
                )
            }
            composable("add/{date}/{meal}") { entry ->
                val date = entry.arguments?.getString("date")?.let(LocalDate::parse) ?: LocalDate.now()
                val meal = MealType.fromName(entry.arguments?.getString("meal"))
                AddFoodScreen(date = date, initialMeal = meal, onDone = { nav.popBackStack() })
            }
            composable("history") {
                HistoryScreen(onOpenDay = { nav.navigate("day/$it") })
            }
            composable("groups") {
                GroupsScreen()
            }
            composable("profile") {
                ProfileScreen()
            }
        }
    }
}
