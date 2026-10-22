@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.thupapuha.macrotracker.ui.progress

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.thupapuha.macrotracker.core.gym.BestLift
import io.github.thupapuha.macrotracker.core.gym.ExercisePoint
import io.github.thupapuha.macrotracker.core.gym.Gym
import io.github.thupapuha.macrotracker.core.gym.LoadUnit
import io.github.thupapuha.macrotracker.core.gym.WeightGoal
import io.github.thupapuha.macrotracker.data.ProgressRepository
import io.github.thupapuha.macrotracker.data.Routine
import io.github.thupapuha.macrotracker.data.Section
import io.github.thupapuha.macrotracker.data.SettingsStore
import io.github.thupapuha.macrotracker.data.UserSettings
import io.github.thupapuha.macrotracker.data.WeightEntry
import io.github.thupapuha.macrotracker.data.Workout
import io.github.thupapuha.macrotracker.ui.common.ConfirmDialog
import io.github.thupapuha.macrotracker.ui.common.ErrorText
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
    val sections: StateFlow<List<Section>> = repository.sections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
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

    fun saveSection(name: String, id: String?) {
        viewModelScope.launch { repository.saveSection(name, id) }
    }

    fun deleteSection(section: Section) {
        viewModelScope.launch { repository.deleteSection(section.id) }
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
private data class ExerciseRecord(
    val name: String,
    val unit: LoadUnit,
    val best: BestLift,
    val sessions: Int,
    val points: List<ExercisePoint>,
)

private fun exerciseRecords(workouts: List<Workout>): List<ExerciseRecord> {
    val ordered = workouts.sortedBy { it.finishedAt }
    val history = ordered.map { it.date.toString() to it.exercises }
    // Show each exercise with the name it was last written with.
    val latest = ordered.flatMap { it.exercises }.associateBy { Gym.key(it) }
    return Gym.bests(history.map { it.second })
        .mapNotNull { (key, best) ->
            val exercise = latest[key] ?: return@mapNotNull null
            val points = Gym.progress(exercise.name, history, exercise.unit)
            ExerciseRecord(exercise.name, exercise.unit, best, points.size, points)
        }
        .sortedByDescending { it.sessions }
}

/** Ideas offered when creating a section. */
private val SECTION_IDEAS = listOf("Hipertrofia", "Fuerza", "Resistencia", "Cardio", "Calistenia", "Movilidad")

/** The Progreso tab: body weight, routines, records and workout history. */
@Composable
fun ProgressScreen(
    onStartWorkout: (routineId: String?) -> Unit,
    onEditRoutine: (routineId: String?, sectionId: String?) -> Unit,
) {
    val vm = appViewModel { ProgressViewModel(it.progressRepository, it.settings) }
    val sections by vm.sections.collectAsStateWithLifecycle()
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
    // Section being renamed ("" = creating one) and section waiting for delete confirmation.
    var editingSection by remember { mutableStateOf<Pair<Section?, Boolean>?>(null) }
    var deletingSection by remember { mutableStateOf<Section?>(null) }
    // Ids of folded sections, joined with commas so it survives rotation.
    var collapsed by rememberSaveable { mutableStateOf("") }
    var historyFilter by rememberSaveable { mutableStateOf<String?>(null) }
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
                SectionHeader("Entrenar", action = "+ Sección", onAction = { editingSection = null to true })
            }
            if (routines.isEmpty() && sections.isEmpty()) {
                item(key = "routines-empty") {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Arma tus rutinas", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Ordénalas en secciones como Hipertrofia o Fuerza, o crea las tuyas. En cada rutina pones " +
                                    "tus ejercicios con sus series, repeticiones y peso (en kg o en discos). Al entrenar " +
                                    "cambias las repes y la carga de cada serie, y la rutina recuerda lo último que hiciste.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { editingSection = null to true }) { Text("Crear sección") }
                                OutlinedButton(onClick = { onEditRoutine(null, null) }) { Text("Crear rutina") }
                            }
                        }
                    }
                }
            }
            val folded = collapsed.split(',').filter { it.isNotEmpty() }.toSet()
            sections.forEach { section ->
                val inSection = routines.filter { it.sectionId == section.id }
                val isFolded = section.id in folded
                item(key = "section-${section.id}") {
                    RoutineSectionHeader(
                        title = section.name,
                        count = inSection.size,
                        folded = isFolded,
                        onToggle = {
                            collapsed = (if (isFolded) folded - section.id else folded + section.id).joinToString(",")
                        },
                        onAdd = { onEditRoutine(null, section.id) },
                        onRename = { editingSection = section to false },
                        onDelete = { deletingSection = section },
                    )
                }
                if (!isFolded) {
                    if (inSection.isEmpty()) {
                        item(key = "section-empty-${section.id}") {
                            OutlinedButton(onClick = { onEditRoutine(null, section.id) }, modifier = Modifier.fillMaxWidth()) {
                                Text("+ Agregar rutina a ${section.name}")
                            }
                        }
                    }
                    items(inSection, key = { "routine-${it.id}" }) { routine ->
                        RoutineCard(routine, onStart = { start(routine) }, onEdit = { onEditRoutine(routine.id, null) })
                    }
                }
            }
            val known = sections.mapTo(HashSet()) { it.id }
            val loose = routines.filter { it.sectionId == null || it.sectionId !in known }
            if (loose.isNotEmpty()) {
                if (sections.isNotEmpty()) {
                    item(key = "section-none") {
                        RoutineSectionHeader(title = "Sin sección", count = loose.size, onAdd = { onEditRoutine(null, null) })
                    }
                }
                items(loose, key = { "routine-${it.id}" }) { routine ->
                    RoutineCard(routine, onStart = { start(routine) }, onEdit = { onEditRoutine(routine.id, null) })
                }
            }
            item(key = "free") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onEditRoutine(null, null) }, modifier = Modifier.weight(1f)) { Text("Nueva rutina") }
                    OutlinedButton(onClick = { start(null) }, modifier = Modifier.weight(1f)) { Text("Entreno libre") }
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
                                    Text("🏆 ${liftLabel(record.best, record.unit)}  ›")
                                }
                            }
                        }
                    }
                }
            }

            if (workouts.isNotEmpty()) {
                item(key = "history-header") { SectionHeader("Historial") }
            }
            val workoutSections = workouts.mapNotNull { it.section }.distinct()
            if (workoutSections.isNotEmpty()) {
                item(key = "history-filter") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = historyFilter == null, onClick = { historyFilter = null }, label = { Text("Todos") })
                        workoutSections.forEach { name ->
                            FilterChip(selected = historyFilter == name, onClick = { historyFilter = name }, label = { Text(name) })
                        }
                    }
                }
            }
            val shown = historyFilter?.let { name -> workouts.filter { it.section == name } } ?: workouts
            items(shown, key = { "workout-${it.id}" }) { workout ->
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

    editingSection?.let { (section, _) ->
        SectionDialog(
            section = section,
            existing = sections.map { it.name },
            onDismiss = { editingSection = null },
            onSave = { name ->
                vm.saveSection(name, section?.id)
                editingSection = null
            },
        )
    }

    deletingSection?.let { section ->
        ConfirmDialog(
            title = "Borrar sección",
            message = "Se borrará la sección \"${section.name}\". Sus rutinas no se borran: quedan en \"Sin sección\".",
            confirmLabel = "Borrar",
            onDismiss = { deletingSection = null },
            onConfirm = {
                vm.deleteSection(section)
                deletingSection = null
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
                    Text("Récord: ${liftLabel(record.best, record.unit)}", style = MaterialTheme.typography.titleMedium)
                    if (record.best.weight > 0 && record.unit == LoadUnit.KG) {
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
                            Text(liftLabel(point.best, record.unit), style = MaterialTheme.typography.bodySmall)
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
        val bySection = week.mapNotNull { it.section }.groupingBy { it }.eachCount()
        if (bySection.isNotEmpty()) {
            Text(
                bySection.entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key} ${it.value}" },
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        }
    }
}

/** Title of a section of routines, with a button to add a routine to it and a menu. */
@Composable
private fun RoutineSectionHeader(
    title: String,
    count: Int,
    onAdd: () -> Unit,
    folded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onToggle != null) {
            Icon(
                if (folded) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (folded) "Mostrar rutinas" else "Ocultar rutinas",
            )
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                if (count == 1) "1 rutina" else "$count rutinas",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Agregar rutina a $title") }
        if (onRename != null || onDelete != null) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Opciones de $title") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    onRename?.let { rename ->
                        DropdownMenuItem(text = { Text("Cambiar nombre") }, onClick = {
                            menu = false
                            rename()
                        })
                    }
                    onDelete?.let { delete ->
                        DropdownMenuItem(text = { Text("Borrar sección") }, onClick = {
                            menu = false
                            delete()
                        })
                    }
                }
            }
        }
    }
}

/** Creates a section (with ideas to pick from) or renames [section]. */
@Composable
fun SectionDialog(section: Section?, existing: List<String>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(section?.name.orEmpty()) }
    val taken = existing.filter { it != section?.name }.map { it.lowercase() }
    val duplicate = name.trim().lowercase() in taken
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (section == null) "Nueva sección" else "Cambiar nombre") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    label = { Text("Nombre de la sección") },
                    singleLine = true,
                    isError = duplicate,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (duplicate) ErrorText("Ya tienes una sección con ese nombre")
                if (section == null) {
                    val ideas = SECTION_IDEAS.filter { it.lowercase() !in taken }
                    if (ideas.isNotEmpty()) {
                        Text("Ideas", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ideas.forEach { idea -> SuggestionChip(onClick = { name = idea }, label = { Text(idea) }) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank() && !duplicate) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
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
                    Text(
                        listOfNotNull(dayLabel(workout.date), workout.section).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
