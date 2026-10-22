@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.thupapuha.macrotracker.ui.progress

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.thupapuha.macrotracker.core.gym.BestLift
import io.github.thupapuha.macrotracker.core.gym.Gym
import io.github.thupapuha.macrotracker.data.ActiveWorkout
import io.github.thupapuha.macrotracker.data.ProgressRepository
import io.github.thupapuha.macrotracker.data.SettingsStore
import io.github.thupapuha.macrotracker.data.Workout
import io.github.thupapuha.macrotracker.ui.common.ConfirmDialog
import io.github.thupapuha.macrotracker.ui.common.ErrorText
import io.github.thupapuha.macrotracker.ui.common.TextInputDialog
import io.github.thupapuha.macrotracker.ui.common.appViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

class WorkoutViewModel(
    private val requestedRoutineId: String?,
    private val repository: ProgressRepository,
    private val settings: SettingsStore,
) : ViewModel() {
    var title by mutableStateOf("Entrenamiento")
        private set
    var exercises by mutableStateOf<List<EditableExercise>>(emptyList())
        private set
    var startedAt by mutableLongStateOf(0L)
        private set
    var loaded by mutableStateOf(false)
        private set

    /** Section of the routine (Hipertrofia, Fuerza...), shown and saved with the workout. */
    var section by mutableStateOf<String?>(null)
        private set

    /** Best lift ever per exercise (by [Gym.key]) before this workout. */
    var records by mutableStateOf<Map<String, BestLift>>(emptyMap())
        private set
    var finished by mutableStateOf<Workout?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var saving by mutableStateOf(false)
        private set

    private var routineId: String? = null
    private var date: String = LocalDate.now().toString()
    private var persistJob: Job? = null

    init {
        viewModelScope.launch {
            // A workout left open (app closed at the gym) always comes back first.
            val draft = settings.activeWorkout()
            if (draft != null) {
                title = draft.title
                routineId = draft.routineId
                date = draft.date
                startedAt = draft.startedAt
                section = draft.section
                exercises = draft.exercises.map(EditableExercise::from)
            } else {
                val routine = requestedRoutineId?.let { repository.routine(it) }
                title = routine?.name ?: "Entrenamiento libre"
                routineId = routine?.id
                section = repository.sectionName(routine?.sectionId)
                startedAt = System.currentTimeMillis()
                exercises = Gym.startFrom(routine?.exercises.orEmpty()).map(EditableExercise::from)
                persist()
            }
            records = Gym.bests(repository.history())
            loaded = true
        }
    }

    val doneSets: Int get() = exercises.sumOf { exercise -> exercise.sets.count { it.done } }
    val totalSets: Int get() = exercises.sumOf { it.sets.size }

    fun update(index: Int, exercise: EditableExercise) {
        exercises = exercises.toMutableList().also { it[index] = exercise }
        schedulePersist()
    }

    fun remove(index: Int) {
        exercises = exercises.filterIndexed { i, _ -> i != index }
        schedulePersist()
    }

    fun add(name: String) {
        exercises = exercises + EditableExercise.empty(name)
        schedulePersist()
    }

    fun rename(newTitle: String) {
        title = newTitle.take(60)
        schedulePersist()
    }

    /** Saves the workout. With [markAll], sets not ticked count as done (people forget to tick). */
    fun finish(markAll: Boolean) {
        if (saving) return
        val logs = exercises.map { exercise ->
            val log = exercise.toLog()
            if (markAll) log.copy(sets = log.sets.map { it.copy(done = true) }) else log
        }
        viewModelScope.launch {
            saving = true
            persistJob?.cancel()
            val workout = repository.finishWorkout(title, routineId, logs, LocalDate.parse(date), startedAt, section)
            if (workout == null) {
                error = "Marca al menos una serie con repeticiones como hecha."
            } else {
                settings.setActiveWorkout(null)
                finished = workout
            }
            saving = false
        }
    }

    fun discard(onDiscarded: () -> Unit) {
        persistJob?.cancel()
        viewModelScope.launch {
            settings.setActiveWorkout(null)
            onDiscarded()
        }
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(400)
            persist()
        }
    }

    private suspend fun persist() {
        settings.setActiveWorkout(ActiveWorkout(title, routineId, date, startedAt, exercises.map { it.toLog() }, section))
    }
}

private fun elapsed(startedAt: Long, now: Long): String {
    val seconds = ((now - startedAt) / 1000).coerceAtLeast(0)
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** A workout in progress: edit reps and kilos of each set and tick them as you go. */
@Composable
fun WorkoutScreen(routineId: String?, onDone: () -> Unit) {
    val vm = appViewModel(key = "workout") { WorkoutViewModel(routineId, it.progressRepository, it.settings) }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    // Leaving keeps the workout saved as "in progress".
    BackHandler(onBack = onDone)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vm.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (vm.loaded) {
                            Text(
                                listOfNotNull(
                                    vm.section,
                                    "⏱ ${elapsed(vm.startedAt, now)}",
                                    "${vm.doneSets}/${vm.totalSets} series",
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
                actions = {
                    TextButton(onClick = { confirmFinish = true }, enabled = vm.loaded && !vm.saving) { Text("Terminar") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Opciones") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Cambiar nombre") }, onClick = {
                                menu = false
                                renaming = true
                            })
                            DropdownMenuItem(text = { Text("Descartar entrenamiento") }, onClick = {
                                menu = false
                                confirmDiscard = true
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (!vm.loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.exercises.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Agrega los ejercicios que vas a hacer. Cambia las repes y los kilos de cada serie y " +
                            "márcala con ✓ cuando la termines.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            vm.error?.let { message -> item(key = "error") { ErrorText(message) } }
            items(vm.exercises.size, key = { vm.exercises[it].key }) { index ->
                val exercise = vm.exercises[index]
                ExerciseCard(
                    exercise = exercise,
                    training = true,
                    record = vm.records[Gym.key(exercise.name, exercise.unit)],
                    onChange = { vm.update(index, it) },
                    onRemove = { vm.remove(index) },
                )
            }
            item(key = "add") {
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(" Agregar ejercicio")
                }
            }
            item(key = "finish") {
                Button(onClick = { confirmFinish = true }, enabled = !vm.saving, modifier = Modifier.fillMaxWidth()) {
                    Text("Terminar entrenamiento")
                }
            }
        }
    }

    if (adding) {
        AddExerciseDialog(
            onDismiss = { adding = false },
            onAdd = {
                vm.add(it)
                adding = false
            },
        )
    }
    if (renaming) {
        TextInputDialog(
            title = "Nombre del entrenamiento",
            label = "Nombre",
            initial = vm.title,
            confirmLabel = "Guardar",
            onDismiss = { renaming = false },
            onConfirm = {
                vm.rename(it)
                renaming = false
            },
        )
    }
    if (confirmFinish) {
        val pending = vm.totalSets - vm.doneSets
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("Terminar entrenamiento") },
            text = {
                Text(
                    if (pending == 0) {
                        "Se guardará y lo verán tus grupos. La rutina recordará las repes y kilos de hoy."
                    } else {
                        "Marcaste ${vm.doneSets} de ${vm.totalSets} series como hechas. Las que no marques no se guardan."
                    },
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (pending > 0) {
                        TextButton(onClick = {
                            confirmFinish = false
                            vm.finish(markAll = true)
                        }) { Text("Marcar todas y guardar") }
                    }
                    TextButton(onClick = {
                        confirmFinish = false
                        vm.finish(markAll = false)
                    }, enabled = vm.doneSets > 0) { Text(if (pending > 0) "Guardar solo las marcadas" else "Guardar") }
                }
            },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Seguir entrenando") } },
        )
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Descartar entrenamiento",
            message = "Se borrará lo que registraste en este entrenamiento.",
            confirmLabel = "Descartar",
            onDismiss = { confirmDiscard = false },
            onConfirm = {
                confirmDiscard = false
                vm.discard(onDone)
            },
        )
    }
    vm.finished?.let { workout ->
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text(if (workout.prCount > 0) "🏆 ¡Nuevos récords!" else "💪 ¡Buen entrenamiento!") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    WorkoutStatsRow(workout.exercises, workout.durationMin, workout.prCount)
                    ExerciseLines(workout.exercises)
                }
            },
            confirmButton = { TextButton(onClick = onDone) { Text("Listo") } },
        )
    }
}
