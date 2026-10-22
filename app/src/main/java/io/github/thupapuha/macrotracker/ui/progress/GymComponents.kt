@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.thupapuha.macrotracker.ui.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thupapuha.macrotracker.core.gym.BestLift
import io.github.thupapuha.macrotracker.core.gym.ExerciseLog
import io.github.thupapuha.macrotracker.core.gym.Gym
import io.github.thupapuha.macrotracker.core.gym.LoadUnit
import io.github.thupapuha.macrotracker.core.gym.WorkoutSet
import io.github.thupapuha.macrotracker.ui.common.NumberField
import io.github.thupapuha.macrotracker.ui.common.Pill
import io.github.thupapuha.macrotracker.ui.common.formatAmount
import io.github.thupapuha.macrotracker.ui.common.inputAmount
import io.github.thupapuha.macrotracker.ui.common.toAmount
import io.github.thupapuha.macrotracker.ui.theme.Brand

/** A set being typed: text fields keep what the user wrote until it is saved. */
data class EditableSet(val reps: String = "", val weight: String = "", val done: Boolean = false) {
    fun toSet() = WorkoutSet(reps = reps.trim().toIntOrNull() ?: 0, weight = weight.toAmount() ?: 0.0, done = done)

    companion object {
        fun from(set: WorkoutSet) = EditableSet(
            reps = set.reps.takeIf { it > 0 }?.toString().orEmpty(),
            weight = set.weight.takeIf { it > 0 }?.let(::inputAmount).orEmpty(),
            done = set.done,
        )
    }
}

data class EditableExercise(
    val key: Long,
    val name: String,
    val sets: List<EditableSet>,
    val unit: LoadUnit = LoadUnit.KG,
) {
    fun toLog() = ExerciseLog(name = name.trim(), sets = sets.map { it.toSet() }, unit = unit)

    companion object {
        private var nextKey = 0L

        fun newKey(): Long = ++nextKey

        fun from(log: ExerciseLog) = EditableExercise(newKey(), log.name, log.sets.map(EditableSet::from), log.unit)

        fun empty(name: String) = EditableExercise(newKey(), name, List(3) { EditableSet(reps = "10") })
    }
}

/** Adds a set copying the previous one, which is what people usually do next. */
fun EditableExercise.withExtraSet(): EditableExercise =
    copy(sets = sets + (sets.lastOrNull()?.copy(done = false) ?: EditableSet(reps = "10")))

val COMMON_EXERCISES = listOf(
    "Sentadilla", "Press banca", "Peso muerto", "Press militar", "Remo con barra", "Dominadas",
    "Jalón al pecho", "Fondos", "Hip thrust", "Prensa", "Zancadas", "Curl de bíceps",
    "Extensión de tríceps", "Elevaciones laterales", "Press inclinado", "Peso muerto rumano",
    "Curl femoral", "Extensión de cuádriceps", "Pantorrillas", "Abdominales", "Plancha",
)

/**
 * Card to edit an exercise's sets. While [training], each set has a check to mark it done and
 * sets that beat the previous [record] get a trophy.
 */
@Composable
fun ExerciseCard(
    exercise: EditableExercise,
    training: Boolean,
    record: BestLift?,
    onChange: (EditableExercise) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (training) {
                    Column(Modifier.weight(1f)) {
                        Text(exercise.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (record != null) {
                            Text(
                                "Récord: ${liftLabel(record, exercise.unit)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = exercise.name,
                        onValueChange = { if (it.length <= 60) onChange(exercise.copy(name = it)) },
                        label = { Text("Ejercicio") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.weight(1f),
                    )
                }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Quitar ejercicio") }
            }
            // Machines are often counted in plates instead of kilos.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Carga en", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilterChip(
                    selected = exercise.unit == LoadUnit.KG,
                    onClick = { onChange(exercise.copy(unit = LoadUnit.KG)) },
                    label = { Text("Kg") },
                )
                FilterChip(
                    selected = exercise.unit == LoadUnit.PLATES,
                    onClick = { onChange(exercise.copy(unit = LoadUnit.PLATES)) },
                    label = { Text("Discos") },
                )
            }
            Row(Modifier.fillMaxWidth().padding(end = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Serie", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
                Text("Repes", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text(
                    if (exercise.unit == LoadUnit.PLATES) "Discos" else "Peso (kg)",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            exercise.sets.forEachIndexed { index, set ->
                val isRecord = training && set.done && Gym.isRecord(Gym.best(listOf(set.toSet())), record)
                SetRow(
                    number = index + 1,
                    set = set,
                    training = training,
                    isRecord = isRecord,
                    onChange = { updated -> onChange(exercise.copy(sets = exercise.sets.toMutableList().also { it[index] = updated })) },
                    onRemove = { onChange(exercise.copy(sets = exercise.sets.filterIndexed { i, _ -> i != index })) },
                )
            }
            TextButton(onClick = { onChange(exercise.withExtraSet()) }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" Agregar serie")
            }
        }
    }
}

@Composable
private fun SetRow(
    number: Int,
    set: EditableSet,
    training: Boolean,
    isRecord: Boolean,
    onChange: (EditableSet) -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (isRecord) "🏆" else "$number",
            style = MaterialTheme.typography.titleMedium,
            color = if (set.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
        )
        OutlinedTextField(
            value = set.reps,
            onValueChange = { new -> if (new.length <= 3 && new.all(Char::isDigit)) onChange(set.copy(reps = new)) },
            singleLine = true,
            placeholder = { Text("0") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        NumberField(set.weight, { onChange(set.copy(weight = it)) }, "", Modifier.weight(1f))
        if (training) {
            FilledIconToggleButton(checked = set.done, onCheckedChange = { onChange(set.copy(done = it)) }) {
                Icon(Icons.Filled.Check, contentDescription = if (set.done) "Serie hecha" else "Marcar serie como hecha")
            }
        } else {
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Quitar serie") }
        }
    }
}

/** Dialog to name a new exercise, with common ones to pick from. */
@Composable
fun AddExerciseDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agregar ejercicio") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 60) name = it },
                    label = { Text("Nombre del ejercicio") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    COMMON_EXERCISES.filter { name.isBlank() || it.contains(name.trim(), ignoreCase = true) }.forEach { option ->
                        SuggestionChip(onClick = { onAdd(option) }, label = { Text(option) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name.trim()) }, enabled = name.isNotBlank()) { Text("Agregar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

fun liftLabel(lift: BestLift, unit: LoadUnit = LoadUnit.KG): String =
    if (lift.weight > 0) "${unit.format(lift.weight)} × ${lift.reps}" else "${lift.reps} repes"

/** "⏱ 55 min · 12 series · 4.520 kg" chips for a finished workout. */
@Composable
fun WorkoutStatsRow(exercises: List<ExerciseLog>, durationMin: Int?, prCount: Int, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (prCount > 0) {
            Pill(
                if (prCount == 1) "🏆 1 récord" else "🏆 $prCount récords",
                container = Brand.Record.copy(alpha = 0.22f),
            )
        }
        durationMin?.let { Pill("⏱ $it min") }
        Pill("${Gym.completedSetCount(exercises)} series")
        val volume = Gym.totalVolume(exercises)
        if (volume > 0) Pill("${formatAmount(volume)} kg movidos")
    }
}

/** One line per exercise: "Sentadilla   3 × 8 · 80 kg  🏆". */
@Composable
fun ExerciseLines(exercises: List<ExerciseLog>, modifier: Modifier = Modifier, limit: Int = Int.MAX_VALUE) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        exercises.take(limit).forEach { exercise ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    exercise.name + if (exercise.pr) "  🏆" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (exercise.pr) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    Gym.describe(exercise.sets, exercise.unit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (exercises.size > limit) {
            Text(
                "y ${exercises.size - limit} ejercicios más",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Simple line chart of [values] (oldest first) with an optional dashed [reference] line, e.g. the
 * goal weight.
 */
@Composable
fun LineChart(values: List<Double>, modifier: Modifier = Modifier, reference: Double? = null, color: Color = MaterialTheme.colorScheme.primary) {
    val referenceColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dotColor = MaterialTheme.colorScheme.surface
    Canvas(modifier.fillMaxWidth().height(140.dp)) {
        if (values.isEmpty()) return@Canvas
        val all = values + listOfNotNull(reference)
        val min = all.min()
        val max = all.max()
        val span = (max - min).takeIf { it > 1e-6 } ?: 1.0
        val padding = 8.dp.toPx()
        fun y(value: Double) = (padding + (1 - (value - min) / span) * (size.height - 2 * padding)).toFloat()
        fun x(index: Int) = if (values.size == 1) size.width / 2 else padding + index * (size.width - 2 * padding) / (values.size - 1)

        if (reference != null) {
            drawLine(
                referenceColor,
                Offset(0f, y(reference)),
                Offset(size.width, y(reference)),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
            )
        }
        val path = Path()
        values.forEachIndexed { index, value ->
            if (index == 0) path.moveTo(x(index), y(value)) else path.lineTo(x(index), y(value))
        }
        drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = values.lastIndex
        drawCircle(color, radius = 5.dp.toPx(), center = Offset(x(last), y(values[last])))
        drawCircle(dotColor, radius = 2.dp.toPx(), center = Offset(x(last), y(values[last])))
    }
}
