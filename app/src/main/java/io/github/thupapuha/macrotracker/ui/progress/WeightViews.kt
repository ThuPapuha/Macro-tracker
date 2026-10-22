package io.github.thupapuha.macrotracker.ui.progress

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import io.github.thupapuha.macrotracker.core.gym.Body
import io.github.thupapuha.macrotracker.core.gym.BodyLog
import io.github.thupapuha.macrotracker.core.gym.BodyMetric
import io.github.thupapuha.macrotracker.core.gym.Gym
import io.github.thupapuha.macrotracker.core.gym.WeightGoal
import io.github.thupapuha.macrotracker.data.WeightEntry
import io.github.thupapuha.macrotracker.ui.common.ErrorText
import io.github.thupapuha.macrotracker.ui.common.NumberField
import io.github.thupapuha.macrotracker.ui.common.Pill
import io.github.thupapuha.macrotracker.ui.common.StatTile
import io.github.thupapuha.macrotracker.ui.common.dayLabel
import io.github.thupapuha.macrotracker.ui.common.inputAmount
import io.github.thupapuha.macrotracker.ui.common.toAmount
import io.github.thupapuha.macrotracker.ui.theme.Brand
import java.time.LocalDate
import kotlin.math.abs

/** Accepted weights, same range the server checks. */
val WEIGHT_RANGE = BodyMetric.WEIGHT.range

private fun round1(value: Double) = Math.round(value * 10) / 10.0

fun kg(value: Double): String = "${Gym.formatKg(round1(value))} kg"

/** "18.5 %" or "3.1 kg" depending on the measurement. */
fun metricText(metric: BodyMetric, value: Double): String =
    if (metric.unit == "%") "${Gym.formatKg(round1(value))}%" else kg(value)

fun metricColor(metric: BodyMetric): Color = when (metric) {
    BodyMetric.WEIGHT -> Brand.Amber
    BodyMetric.FAT -> Brand.Pink
    BodyMetric.MUSCLE -> Brand.Emerald
    BodyMetric.BONE -> Brand.Sky
}

/** "Grasa 18.5% · Músculo 41% · Hueso 3.1 kg" for the measurements taken. */
fun compositionLine(log: BodyLog): String =
    listOf(BodyMetric.FAT, BodyMetric.MUSCLE, BodyMetric.BONE)
        .mapNotNull { metric -> metric.of(log)?.let { "${metric.label} ${metricText(metric, it)}" } }
        .joinToString(" · ")

/**
 * "▼ 0.4%" in green when the change is good news (less fat, more muscle, weight toward the goal),
 * amber when it is not. Nothing for changes too small to matter.
 */
@Composable
fun TrendPill(metric: BodyMetric, change: Double?, goalLosing: Boolean? = null) {
    if (change == null || abs(change) < 0.05) return
    val good = when (metric.upIsGood) {
        true -> change > 0
        false -> change < 0
        null -> if (metric == BodyMetric.WEIGHT && goalLosing != null) (change < 0) == goalLosing else null
    }
    Pill(
        (if (change < 0) "▼ " else "▲ ") + metricText(metric, abs(change)),
        container = when (good) {
            true -> Brand.Emerald.copy(alpha = 0.22f)
            false -> Brand.Amber.copy(alpha = 0.22f)
            null -> null
        },
    )
}

/** Latest body fat, muscle and bone mass with their change since the previous measurement. */
@Composable
fun CompositionRow(logs: List<BodyLog>, modifier: Modifier = Modifier) {
    val metrics = listOf(BodyMetric.FAT, BodyMetric.MUSCLE, BodyMetric.BONE)
        .mapNotNull { metric -> Body.trend(logs, metric)?.let { metric to it } }
    if (metrics.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            metrics.forEach { (metric, trend) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    StatTile(metricText(metric, trend.current), metric.label, color = metricColor(metric))
                    TrendPill(metric, trend.change)
                }
            }
        }
        logs.filter { it.fatPercent != null }.maxByOrNull { it.day }?.let { log ->
            Text(
                "Masa grasa ${kg(log.fatMassKg!!)} · masa magra ${kg(log.leanMassKg!!)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Chart of the last 180 days, with chips to switch between weight, fat, muscle and bone. */
@Composable
fun BodyChart(logs: List<BodyLog>, goalKg: Double?, modifier: Modifier = Modifier) {
    val since = LocalDate.now().minusDays(180).toString()
    val recent = logs.filter { it.day >= since }
    val metrics = Body.chartable(recent)
    if (metrics.isEmpty()) return
    var chosen by remember { mutableStateOf(BodyMetric.WEIGHT) }
    val metric = chosen.takeIf { it in metrics } ?: metrics.first()
    val series = Body.series(recent, metric)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (metrics.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                metrics.forEach { option ->
                    FilterChip(selected = option == metric, onClick = { chosen = option }, label = { Text(option.label) })
                }
            }
        }
        LineChart(
            series.map { it.second },
            reference = goalKg.takeIf { metric == BodyMetric.WEIGHT },
            color = metricColor(metric),
        )
        val change = series.last().second - series.first().second
        Text(
            (if (change <= 0) "▼ " else "▲ ") + metricText(metric, abs(change)) +
                " desde ${dayLabel(LocalDate.parse(series.first().first))}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Current weight, goal progress, body composition and the chart. */
@Composable
fun WeightCard(
    weights: List<WeightEntry>,
    goal: WeightGoal?,
    onLog: () -> Unit,
    onGoal: () -> Unit,
    onHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val logs = remember(weights) { weights.map { it.toLog() } }
    val current = weights.lastOrNull()
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("⚖️ Peso y composición", style = MaterialTheme.typography.titleMedium)
                    Text(
                        current?.let { "Último registro: ${dayLabel(it.date)}" } ?: "Registra tu peso para ver tu progreso",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(current?.let { kg(it.kg) } ?: "—", style = MaterialTheme.typography.headlineMedium)
                    TrendPill(BodyMetric.WEIGHT, Body.trend(logs, BodyMetric.WEIGHT)?.change, goal?.losing)
                }
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
            if (logs.any { it.hasComposition }) {
                HorizontalDivider()
                CompositionRow(logs)
            }
            BodyChart(logs, goal?.goal)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLog, modifier = Modifier.weight(1f)) { Text("Registrar") }
                OutlinedButton(onClick = onGoal, modifier = Modifier.weight(1f)) { Text(if (goal == null) "Poner meta" else "Editar meta") }
            }
            if (weights.isNotEmpty()) {
                TextButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("Ver mis registros (${weights.size})") }
            }
        }
    }
}

/**
 * Logs (or edits) a day's weight and, optionally, body fat %, muscle % and bone mass. Without
 * [fixedDate] it offers today or yesterday and loads what that day already has.
 */
@Composable
fun BodyLogDialog(
    entries: List<WeightEntry>,
    onDismiss: () -> Unit,
    onSave: (WeightEntry) -> Unit,
    fixedDate: LocalDate? = null,
    onDelete: ((LocalDate) -> Unit)? = null,
) {
    var yesterday by remember { mutableStateOf(false) }
    val date = fixedDate ?: LocalDate.now().minusDays(if (yesterday) 1 else 0)
    val existing = entries.firstOrNull { it.date == date }
    // Earlier values are shown as hints, not filled in, so an old measurement is not saved by mistake.
    val previous = entries.lastOrNull { it.date < date }
    var weight by remember(date) { mutableStateOf((existing?.kg ?: previous?.kg)?.let(::inputAmount).orEmpty()) }
    var fat by remember(date) { mutableStateOf(existing?.fatPercent?.let(::inputAmount).orEmpty()) }
    var muscle by remember(date) { mutableStateOf(existing?.musclePercent?.let(::inputAmount).orEmpty()) }
    var bone by remember(date) { mutableStateOf(existing?.boneKg?.let(::inputAmount).orEmpty()) }

    val kgValue = weight.toAmount()
    val optional = listOf(BodyMetric.FAT to fat, BodyMetric.MUSCLE to muscle, BodyMetric.BONE to bone)
    val problems = buildList {
        if (kgValue == null || kgValue !in WEIGHT_RANGE) add("El peso debe estar entre 20 y 400 kg.")
        optional.forEach { (metric, text) ->
            val value = text.toAmount()
            if (text.isNotBlank() && (value == null || value !in metric.range)) {
                add("${metric.label}: entre ${Gym.formatKg(metric.range.start)} y ${Gym.formatKg(metric.range.endInclusive)} ${metric.unit}.")
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing != null) "Editar registro" else "Registrar peso") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (fixedDate == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !yesterday, onClick = { yesterday = false }, label = { Text("Hoy") })
                        FilterChip(selected = yesterday, onClick = { yesterday = true }, label = { Text("Ayer") })
                    }
                } else {
                    Text(dayLabel(date), style = MaterialTheme.typography.titleSmall)
                }
                NumberField(weight, { weight = it }, "Peso (kg)", Modifier.fillMaxWidth())
                Text("Composición corporal (opcional)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(fat, { fat = it }, "Grasa %", Modifier.weight(1f), placeholder = previous?.fatPercent?.let(::inputAmount))
                    NumberField(muscle, { muscle = it }, "Músculo %", Modifier.weight(1f), placeholder = previous?.musclePercent?.let(::inputAmount))
                }
                NumberField(bone, { bone = it }, "Masa ósea (kg)", Modifier.fillMaxWidth(), placeholder = previous?.boneKg?.let(::inputAmount))
                Text(
                    "Anota los valores de tu báscula o análisis (InBody, bioimpedancia…). Pésate a la misma hora, " +
                        "idealmente en la mañana, para comparar mejor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (weight.isNotBlank()) problems.forEach { ErrorText(it) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (kgValue != null) {
                        onSave(WeightEntry(date, kgValue, fat.toAmount(), muscle.toAmount(), bone.toAmount()))
                    }
                },
                enabled = problems.isEmpty(),
            ) { Text("Guardar") }
        },
        dismissButton = {
            Row {
                if (existing != null && onDelete != null) TextButton(onClick = { onDelete(date) }) { Text("Borrar") }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}

/** Every weigh-in, newest first; tapping one opens it for editing. */
@Composable
fun BodyHistoryDialog(entries: List<WeightEntry>, onEdit: (WeightEntry) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mis registros") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                entries.sortedByDescending { it.date }.forEach { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onEdit(entry) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(dayLabel(entry.date), style = MaterialTheme.typography.bodyLarge)
                            val composition = compositionLine(entry.toLog())
                            if (composition.isNotEmpty()) {
                                Text(
                                    composition,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(kg(entry.kg), style = MaterialTheme.typography.titleMedium)
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
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
                            "Tus amigos verán tu peso, tu composición corporal, tu meta y tu avance.",
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
