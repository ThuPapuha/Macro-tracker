package io.github.thupapuha.macrotracker.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import io.github.thupapuha.macrotracker.core.gym.Gym
import io.github.thupapuha.macrotracker.core.gym.WeightGoal
import io.github.thupapuha.macrotracker.data.WeightEntry
import io.github.thupapuha.macrotracker.ui.common.NumberField
import io.github.thupapuha.macrotracker.ui.common.StatTile
import io.github.thupapuha.macrotracker.ui.common.dayLabel
import io.github.thupapuha.macrotracker.ui.common.inputAmount
import io.github.thupapuha.macrotracker.ui.common.toAmount
import io.github.thupapuha.macrotracker.ui.theme.Brand
import java.time.LocalDate
import kotlin.math.abs

/** Accepted weights, same range the server checks. */
val WEIGHT_RANGE = 20.0..400.0

fun kg(value: Double): String = "${Gym.formatKg(Math.round(value * 10) / 10.0)} kg"

/** Current weight, goal progress and the weight chart. */
@Composable
fun WeightCard(
    weights: List<WeightEntry>,
    goal: WeightGoal?,
    onLog: () -> Unit,
    onGoal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = weights.lastOrNull()
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("⚖️ Peso", style = MaterialTheme.typography.titleMedium)
                    Text(
                        current?.let { "Último registro: ${dayLabel(it.date)}" } ?: "Registra tu peso para ver tu progreso",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(current?.let { kg(it.kg) } ?: "—", style = MaterialTheme.typography.headlineMedium)
            }
            if (goal != null) {
                val now = current?.kg ?: goal.start
                Row(Modifier.fillMaxWidth()) {
                    StatTile(kg(goal.start), "Inicio", Modifier.weight(1f))
                    StatTile(kg(goal.goal), "Meta", Modifier.weight(1f))
                    val remaining = goal.remaining(now)
                    StatTile(
                        if (remaining < 0.05) "¡Lograda!" else kg(remaining),
                        if (goal.losing) "Por bajar" else "Por subir",
                        Modifier.weight(1f),
                    )
                }
                val progress = goal.progress(now)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                    color = Brand.Amber,
                    trackColor = Brand.Amber.copy(alpha = 0.18f),
                    strokeCap = StrokeCap.Round,
                )
                Text(
                    "${(progress * 100).toInt()}% del camino a tu meta",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val recent = weights.filter { it.date >= LocalDate.now().minusDays(90) }
            if (recent.size >= 2) {
                LineChart(recent.map { it.kg }, reference = goal?.goal, color = Brand.Amber)
                val change = recent.last().kg - recent.first().kg
                Text(
                    (if (change <= 0) "▼ " else "▲ ") + kg(abs(change)) + " desde ${dayLabel(recent.first().date)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLog, modifier = Modifier.weight(1f)) { Text("Registrar peso") }
                OutlinedButton(onClick = onGoal, modifier = Modifier.weight(1f)) { Text(if (goal == null) "Poner meta" else "Editar meta") }
            }
        }
    }
}

@Composable
fun WeightLogDialog(lastKg: Double?, onDismiss: () -> Unit, onSave: (LocalDate, Double) -> Unit) {
    var value by remember { mutableStateOf(lastKg?.let(::inputAmount).orEmpty()) }
    var yesterday by remember { mutableStateOf(false) }
    val kgValue = value.toAmount()
    val valid = kgValue != null && kgValue in WEIGHT_RANGE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registrar peso") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(value, { value = it }, "Peso en kg", Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !yesterday, onClick = { yesterday = false }, label = { Text("Hoy") })
                    FilterChip(selected = yesterday, onClick = { yesterday = true }, label = { Text("Ayer") })
                }
                Text(
                    "Para comparar mejor, pésate a la misma hora, idealmente en la mañana.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (kgValue != null) onSave(LocalDate.now().minusDays(if (yesterday) 1 else 0), kgValue) },
                enabled = valid,
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun WeightGoalDialog(
    goal: WeightGoal?,
    currentKg: Double?,
    share: Boolean,
    onDismiss: () -> Unit,
    onSave: (WeightGoal?, Boolean) -> Unit,
) {
    var start by remember { mutableStateOf((goal?.start ?: currentKg)?.let(::inputAmount).orEmpty()) }
    var target by remember { mutableStateOf(goal?.goal?.let(::inputAmount).orEmpty()) }
    var shared by remember { mutableStateOf(share) }
    val startKg = start.toAmount()
    val targetKg = target.toAmount()
    val valid = startKg != null && targetKg != null && startKg in WEIGHT_RANGE && targetKg in WEIGHT_RANGE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Meta de peso") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(start, { start = it }, "Peso inicial", Modifier.weight(1f))
                    NumberField(target, { target = it }, "Peso meta", Modifier.weight(1f))
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mostrarlo en mis grupos", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Tus amigos verán tu peso, tu meta y tu avance.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = shared, onCheckedChange = { shared = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (startKg != null && targetKg != null) onSave(WeightGoal(startKg, targetKg), shared) },
                enabled = valid,
            ) { Text("Guardar") }
        },
        dismissButton = {
            Row {
                if (goal != null) TextButton(onClick = { onSave(null, shared) }) { Text("Quitar meta") }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}
