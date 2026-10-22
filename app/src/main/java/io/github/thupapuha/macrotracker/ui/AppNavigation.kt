package io.github.thupapuha.macrotracker.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DateRange
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
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.thupapuha.macrotracker.MacroTrackerApp
import io.github.thupapuha.macrotracker.R
import io.github.thupapuha.macrotracker.core.model.MealType
import io.github.thupapuha.macrotracker.ui.add.AddFoodScreen
import io.github.thupapuha.macrotracker.ui.groups.GroupsScreen
import io.github.thupapuha.macrotracker.ui.history.HistoryScreen
import io.github.thupapuha.macrotracker.ui.profile.ProfileScreen
import io.github.thupapuha.macrotracker.ui.progress.ProgressScreen
import io.github.thupapuha.macrotracker.ui.progress.RoutineEditorScreen
import io.github.thupapuha.macrotracker.ui.progress.WorkoutScreen
import io.github.thupapuha.macrotracker.ui.today.TodayScreen
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

/** A bottom bar tab; [iconRes] is used for icons that are not in the core icon set. */
private data class Tab(val route: String, val label: String, val icon: ImageVector? = null, val iconRes: Int = 0)

private val tabs = listOf(
    Tab("today", "Hoy", Icons.Filled.Home),
    Tab("history", "Historial", Icons.Filled.DateRange),
    Tab("progress", "Progreso", iconRes = R.drawable.ic_dumbbell),
    Tab("groups", "Grupos", iconRes = R.drawable.ic_people),
    Tab("profile", "Perfil", Icons.Filled.AccountCircle),
)

private fun NavHostController.openAddFood(date: LocalDate, meal: MealType) = navigate("add/$date/${meal.name}")

private fun NavHostController.openTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppNavigation(openGroupRequests: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    val settings = (LocalContext.current.applicationContext as MacroTrackerApp).container.settings
    val openGroup by openGroupRequests.collectAsStateWithLifecycle()
    LaunchedEffect(openGroup) {
        val groupId = openGroup ?: return@LaunchedEffect
        settings.setLastGroup(groupId)
        nav.openTab("groups")
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
                            onClick = { nav.openTab(tab.route) },
                            icon = {
                                if (tab.icon != null) {
                                    Icon(tab.icon, contentDescription = null)
                                } else {
                                    Icon(painterResource(tab.iconRes), contentDescription = null)
                                }
                            },
                            label = { Text(tab.label, maxLines = 1) },
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
                    onOpenProfile = { nav.openTab("profile") },
                    onTrain = { nav.openTab("progress") },
                )
            }
            composable("day/{date}") { entry ->
                val date = entry.arguments?.getString("date")?.let(LocalDate::parse) ?: LocalDate.now()
                TodayScreen(
                    initialDate = date,
                    onBack = { nav.popBackStack() },
                    onAddFood = nav::openAddFood,
                    onOpenProfile = { nav.openTab("profile") },
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
            composable("progress") {
                ProgressScreen(
                    onStartWorkout = { routineId -> nav.navigate("workout/${routineId ?: "free"}") },
                    onEditRoutine = { routineId, sectionId ->
                        nav.navigate("routine/${routineId ?: "new"}" + (sectionId?.let { "?section=$it" } ?: ""))
                    },
                )
            }
            composable(
                "routine/{id}?section={section}",
                arguments = listOf(navArgument("section") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                val id = entry.arguments?.getString("id")?.takeIf { it != "new" }
                RoutineEditorScreen(
                    routineId = id,
                    initialSectionId = entry.arguments?.getString("section"),
                    onDone = { nav.popBackStack() },
                )
            }
            composable("workout/{routine}") { entry ->
                val routineId = entry.arguments?.getString("routine")?.takeIf { it != "free" }
                WorkoutScreen(routineId = routineId, onDone = { nav.popBackStack() })
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
