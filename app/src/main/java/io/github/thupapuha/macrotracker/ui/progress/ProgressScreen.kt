@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.thupapuha.macrotracker.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.thupapuha.macrotracker.core.gym.BestLift
import io.github.thupapuha.macrotracker.core.gym.ExercisePoint
import io.github.thupapuha.macrotracker.core.gym.Gym
import io.github.thupapuha.macrotracker.core.gym.WeightGoal
import io.github.thupapuha.macrotracker.data.ProgressRepository
import io.github.thupapuha.macrotracker.data.Routine
import io.github.thupapuha.macrotracker.data.SettingsStore
import io.github.thupapuha.macrotracker.data.UserSettings
import io.github.thupapuha.macrotracker.data.WeightEntry
import io.github.thupapuha.macrotracker.data.Workout
import io.github.thupapuha.macrotracker.ui.common.ConfirmDialog
import io.github.thupapuha.macrotracker.ui.common.GradientCard
import io.github.thupapuha.macrotracker.ui.common.SectionHeader
import io.github.thupapuha.macrotracker.ui.common.StatTile
import io.github.thupapuha.macrotracker.ui.common.appViewModel
import io.github.thupapuha.macrotracker.ui.common.dayLabel
import io.github.thupapuha.macrotracker.ui.common.formatAmount
import io.github.thupapuha.macrotracker.ui.theme.Brand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class ProgressViewModel(
    private val repository: ProgressRepository,
    private val settings: SettingsStore,
) : ViewModel() {
    val routines: StateFlow<List<Routine>> = repository.routines
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val workouts: StateFlow<List<Workout>> = repository.workouts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val weights: StateFlow<List<WeightEntry>> = repository.weights
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val prefs: StateFlow<UserSettings?> = settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val hasActiveWorkout: StateFlow<Boolean> = settings.hasActiveWorkout
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun logWeight(entry: WeightEntry) {
        viewModelScope.launch { repository.logWeight(entry) }
    }

    fun deleteWeight(date: LocalDate) {
        viewModelScope.launch { repository.deleteWeight(date) }
    }

    fun setGoal(goal: WeightGoal?, share: Boolean) {
        viewModelScope.launch { settings.setWeightGoal(goal, share) }
    }

    fun deleteWorkout(workout: Workout) {
        viewModelScope.launch { repository.deleteWorkout(workout.id) }
    }

    /** Throws away the workout in progress, e.g. to start another routine. */
    fun discardActiveWorkout(then: () -> Unit) {
        viewModelScope.launch {
            settings.setActiveWorkout(null)
            then()
        }
    }
}

/** Best lift ever and progress of each exercise, most trained first. */
private data class ExerciseRecord(val name: String, val best: BestLift, val sessions: Int, val points: List<ExercisePoint>)

private fun exerciseRecords(workouts: List<Workout>): List<ExerciseRecord> {
    val ordered = workouts.sortedBy { it.finishedAt }
    val history = ordered.map { it.date.toString() to it.exercises }
    // Show each exercise with the name it was last written with.
    val names = ordered.flatMap { workout -> workout.exercises.map { it.name } }.associateBy { Gym.key(it) }
    return Gym.bests(history.map { it.second })
        .mapNotNull { (key, best) ->
            val name = names[key] ?: return@mapNotNull null
            val points = Gym.progress(name, history)
            ExerciseRecord(name, best, points.size, points)
        }
        .sortedByDescending { it.sessions }
}

/** The Progreso tab: body weight, routines, records and workout history. */
@Composable
fun ProgressScreen(
    onStartWorkout: (routineId: String?) -> Unit,
    onEditRoutine: (routineId: String?) -> Unit,
) {
    val vm = appViewModel { ProgressViewModel(it.progressRepository, it.settings) }
    val routines by vm.routines.collectAsStateWithLifecycle()
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val weights by vm.weights.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val hasActive by vm.hasActiveWorkout.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var editingDay by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingStart by remember { mutableStateOf<Routine?>(null) }
    var askStart by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Workout?>(null) }
    var showRecord by remember { mutableStateOf<ExerciseRecord?>(null) }
    val records = remember(workouts) { exerciseRecords(workouts) }

    fun start(routine: Routine?) {
        if (hasActive) {
            pendingStart = routine
            askStart = true
        } else {
            onStartWorkout(routine?.id)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Progreso") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (hasActive) {
                item(key = "active") {
                    GradientCard(Brand.gym, onClick = { onStartWorkout(null) }) {
                        Text("🔥 Entrenamiento en curso", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Text("Toca para seguir donde lo dejaste.", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    }
                }
            }
            item(key = "stats") { WeekStats(workouts) }
            item(key = "weight") {
                WeightCard(
                    weights = weights,
                    goal = prefs?.weightGoal,
                    onLog = { dialog = "weight" },
                    onGoal = { dialog = "goal" },
                    onHistory = { dialog = "history" },
                )
            }

            item(key = "routines-header") {
                SectionHeader("Mis rutinas", action = "Nueva", onAction = { onEditRoutine(null) })
            }
            if (routines.isEmpty()) {
                item(key = "routines-empty") {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Arma tu rutina", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Agrega tus ejercicios con sus series, repeticiones y peso. Al entrenar puedes " +
                                    "cambiar las repes y los kilos de cada serie, y la rutina recuerda lo último que hiciste.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { onEditRoutine(null) }) { Text("Crear rutina") }
                        }
                    }
                }
            }
            items(routines, key = { "routine-${it.id}" }) { routine ->
                RoutineCard(routine, onStart = { start(routine) }, onEdit = { onEditRoutine(routine.id) })
            }
            item(key = "free") {
                OutlinedButton(onClick = { start(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Entrenamiento libre (sin rutina)")
                }
            }

            if (records.isNotEmpty()) {
                item(key = "records-header") { SectionHeader("Récords") }
                item(key = "records") {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            records.take(8).forEach { record ->
                                TextButton(onClick = { showRecord = record }, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        record.name,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text("🏆 ${liftLabel(record.best)}  ›")
                                }
                            }
                        }
                    }
                }
            }

            if (workouts.isNotEmpty()) {
                item(key = "history-header") { SectionHeader("Historial") }
            }
            items(workouts, key = { "workout-${it.id}" }) { workout ->
                WorkoutHistoryCard(workout, onDelete = { confirmDelete = workout })
            }
        }
    }

    when (dialog) {
        "weight" -> BodyLogDialog(
            entries = weights,
            onDismiss = { dialog = null },
            onSave = {
                vm.logWeight(it)
                dialog = null
            },
        )
        "history" -> BodyHistoryDialog(
            entries = weights,
            onEdit = {
                editingDay = it.date.toString()
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        "goal" -> WeightGoalDialog(
            goal = prefs?.weightGoal,
            currentKg = weights.lastOrNull()?.kg,
            share = prefs?.shareWeight ?: true,
            onDismiss = { dialog = null },
            onSave = { goal, share ->
                vm.setGoal(goal, share)
                dialog = null
            },
        )
    }

    editingDay?.let { day ->
        BodyLogDialog(
            entries = weights,
            fixedDate = LocalDate.parse(day),
            onDismiss = { editingDay = null },
            onSave = {
                vm.logWeight(it)
                editingDay = null
            },
            onDelete = { date ->
                vm.deleteWeight(date)
                editingDay = null
            },
        )
    }

    if (askStart) {
        ConfirmDialog(
            title = "Tienes un entrenamiento en curso",
            message = "Si empiezas otro, se descarta el que tienes sin terminar.",
            confirmLabel = "Descartar y empezar",
            onDismiss = { askStart = false },
            onConfirm = {
                askStart = false
                val routine = pendingStart
                vm.discardActiveWorkout { onStartWorkout(routine?.id) }
            },
        )
    }

    confirmDelete?.let { workout ->
        ConfirmDialog(
            title = "Borrar entrenamiento",
            message = "Se borrará \"${workout.title}\" de ${dayLabel(workout.date)}, también de tus grupos.",
            confirmLabel = "Borrar",
            onDismiss = { confirmDelete = null },
            onConfirm = {
                vm.deleteWorkout(workout)
                confirmDelete = null
            },
        )
    }

    showRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { showRecord = null },
            title = { Text(record.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Récord: ${liftLabel(record.best)}", style = MaterialTheme.typography.titleMedium)
                    if (record.best.weight > 0) {
                        Text(
                            "1RM estimado: ${formatAmount(record.best.estimatedMax)} kg",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (record.points.size >= 2) {
                        Text("Mejor serie de cada sesión", style = MaterialTheme.typography.labelLarge)
                        LineChart(
                            record.points.map { if (record.best.weight > 0) it.best.weight else it.best.reps.toDouble() },
                            color = Brand.Pink,
                        )
                    }
                    record.points.takeLast(6).reversed().forEach { point ->
                        Row {
                            Text(dayLabel(LocalDate.parse(point.day)), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(liftLabel(point.best), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRecord = null }) { Text("Cerrar") } },
        )
    }
}

@Composable
private fun WeekStats(workouts: List<Workout>) {
    val weekAgo = LocalDate.now().minusDays(6)
    val week = workouts.filter { it.date >= weekAgo }
    GradientCard(Brand.gym) {
        Text("Esta semana", style = MaterialTheme.typography.labelLarge, color = Color.White)
        Row(Modifier.fillMaxWidth()) {
            StatTile("${week.size}", "entrenos", Modifier.weight(1f), color = Color.White)
            StatTile("${week.sumOf { it.prCount }}", "récords", Modifier.weight(1f), color = Color.White)
            StatTile(
                formatAmount(week.sumOf { Gym.totalVolume(it.exercises) }),
                "kg movidos",
                Modifier.weight(1f),
                color = Color.White,
            )
        }
    }
}

@Composable
private fun RoutineCard(routine: Routine, onStart: () -> Unit, onEdit: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(routine.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (routine.exercises.isEmpty()) "Sin ejercicios" else {
                        "${routine.exercises.size} ejercicios · " + routine.exercises.joinToString(", ") { it.name }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Editar rutina") }
            FilledTonalButton(onClick = onStart) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text("Empezar")
            }
        }
    }
}

@Composable
private fun WorkoutHistoryCard(workout: Workout, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(workout.title, style = MaterialTheme.typography.titleMedium)
                    Text(dayLabel(workout.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Opciones") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Borrar") }, onClick = {
                            menu = false
                            onDelete()
                        })
                    }
                }
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkoutStatsRow(workout.exercises, workout.durationMin, workout.prCount)
                ExerciseLines(workout.exercises, limit = if (expanded) Int.MAX_VALUE else 3)
            }
        }
    }
}
