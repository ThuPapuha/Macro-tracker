@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.thupapuha.macrotracker.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.thupapuha.macrotracker.data.ProgressRepository
import io.github.thupapuha.macrotracker.data.Routine
import io.github.thupapuha.macrotracker.ui.common.ConfirmDialog
import io.github.thupapuha.macrotracker.ui.common.ErrorText
import io.github.thupapuha.macrotracker.ui.common.appViewModel
import kotlinx.coroutines.launch
import java.util.UUID

class RoutineEditorViewModel(
    private val routineId: String?,
    private val repository: ProgressRepository,
) : ViewModel() {
    val isNew = routineId == null
    var name by mutableStateOf("")
    var exercises by mutableStateOf<List<EditableExercise>>(emptyList())
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var position = 0

    init {
        viewModelScope.launch {
            val routine = routineId?.let { repository.routine(it) }
            if (routine != null) {
                name = routine.name
                exercises = routine.exercises.map(EditableExercise::from)
                position = routine.position
            }
            loaded = true
        }
    }

    fun update(index: Int, exercise: EditableExercise) {
        exercises = exercises.toMutableList().also { it[index] = exercise }
    }

    fun remove(index: Int) {
        exercises = exercises.filterIndexed { i, _ -> i != index }
    }

    fun add(exerciseName: String) {
        exercises = exercises + EditableExercise.empty(exerciseName)
    }

    fun save(onSaved: () -> Unit) {
        val logs = exercises.map { it.toLog() }
            .map { log -> log.copy(sets = log.sets.filter { it.reps > 0 }.map { it.copy(done = true) }) }
        error = when {
            name.isBlank() -> "Ponle un nombre a la rutina."
            logs.isEmpty() -> "Agrega al menos un ejercicio."
            logs.any { it.name.isBlank() } -> "Hay un ejercicio sin nombre."
            logs.any { it.sets.isEmpty() } -> "Cada ejercicio necesita al menos una serie con repeticiones."
            else -> null
        }
        if (error != null) return
        viewModelScope.launch {
            repository.saveRoutine(Routine(routineId ?: UUID.randomUUID().toString(), name.trim().take(60), logs, position))
            onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = routineId ?: return onDeleted()
        viewModelScope.launch {
            repository.deleteRoutine(id)
            onDeleted()
        }
    }
}

/** Create or edit a routine: its exercises and, for each one, the sets with reps and weight. */
@Composable
fun RoutineEditorScreen(routineId: String?, onDone: () -> Unit) {
    val vm = appViewModel(key = "routine-${routineId ?: "new"}") { RoutineEditorViewModel(routineId, it.progressRepository) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "Nueva rutina" else "Editar rutina") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
                actions = {
                    if (!vm.isNew) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Borrar rutina") }
                    }
                    TextButton(onClick = { vm.save(onDone) }) { Text("Guardar") }
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
            item(key = "name") {
                OutlinedTextField(
                    value = vm.name,
                    onValueChange = { if (it.length <= 60) vm.name = it },
                    label = { Text("Nombre de la rutina") },
                    placeholder = { Text("Ej: Pierna, Empuje, Full body") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "hint") {
                Text(
                    "Pon las series con las repeticiones y el peso que haces hoy. Al terminar cada entrenamiento, " +
                        "la rutina se actualiza con lo que levantaste.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            vm.error?.let { message -> item(key = "error") { ErrorText(message) } }
            items(vm.exercises.size, key = { vm.exercises[it].key }) { index ->
                ExerciseCard(
                    exercise = vm.exercises[index],
                    training = false,
                    record = null,
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
    if (confirmDelete) {
        ConfirmDialog(
            title = "Borrar rutina",
            message = "Se borrará la rutina \"${vm.name}\". Tus entrenamientos guardados no se borran.",
            confirmLabel = "Borrar",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                vm.delete(onDone)
            },
        )
    }
}
