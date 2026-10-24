@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)

package io.github.thupapuha.macrotracker.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.thupapuha.macrotracker.core.model.MacroTargets
import io.github.thupapuha.macrotracker.core.model.Macros
import io.github.thupapuha.macrotracker.core.nutrition.WeekSummary
import io.github.thupapuha.macrotracker.core.nutrition.WeeklyBudget
import io.github.thupapuha.macrotracker.data.DayTotals
import io.github.thupapuha.macrotracker.data.FoodRepository
import io.github.thupapuha.macrotracker.data.SettingsStore
import io.github.thupapuha.macrotracker.data.macros
import io.github.thupapuha.macrotracker.data.toIntake
import io.github.thupapuha.macrotracker.ui.common.MacroBars
import io.github.thupapuha.macrotracker.ui.common.WeekRow
import io.github.thupapuha.macrotracker.ui.common.appViewModel
import io.github.thupapuha.macrotracker.ui.common.dayLabel
import io.github.thupapuha.macrotracker.ui.common.formatAmount
import io.github.thupapuha.macrotracker.ui.common.macroLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max

data class HistoryDay(val date: LocalDate, val totals: Macros, val entries: Int)

class HistoryViewModel(repository: FoodRepository, settings: SettingsStore) : ViewModel() {
    val range = MutableStateFlow(7)

    val days: StateFlow<List<HistoryDay>> = range
        .flatMapLatest { length ->
            val today = LocalDate.now()
            val from = today.minusDays(length - 1L)
            repository.observeTotals(from, today).map { totals -> fillDays(from, today, totals) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val targets: StateFlow<MacroTargets?> = settings.settings
        .map { it.targets }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** This week and the 3 before it against the weekly budget, newest first. */
    val weeks: StateFlow<List<WeekSummary>> = LocalDate.now().let { today ->
        combine(
            repository.observeTotals(WeeklyBudget.weekStart(today).minusWeeks(3), today),
            settings.settings.map { it.targets },
        ) { totals, goals ->
            goals?.takeIf { it.calories > 0 }
                ?.let { WeeklyBudget.lastWeeks(totals.map { t -> t.toIntake() }, it, today, 4) }
                .orEmpty()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun fillDays(from: LocalDate, to: LocalDate, totals: List<DayTotals>): List<HistoryDay> {
        val byDate = totals.associateBy { it.date }
        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .map { date ->
                val t = byDate[date.toString()]
                HistoryDay(date, t?.macros ?: Macros.ZERO, t?.entries ?: 0)
            }
            .toList()
    }
}

@Composable
fun HistoryScreen(onOpenDay: (LocalDate) -> Unit) {
    val vm = appViewModel { HistoryViewModel(it.foodRepository, it.settings) }
    val range by vm.range.collectAsStateWithLifecycle()
    val days by vm.days.collectAsStateWithLifecycle()
    val targets by vm.targets.collectAsStateWithLifecycle()
    val weeks by vm.weeks.collectAsStateWithLifecycle()

    val logged = days.filter { it.entries > 0 }
    val average = if (logged.isEmpty()) Macros.ZERO else Macros.sum(logged.map { it.totals }) * (1.0 / logged.size)
    val onTarget = targets?.let { t -> logged.count { t.isOnTarget(it.totals) } }

    Scaffold(topBar = { TopAppBar(title = { Text("Historial") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7 to "7 días", 14 to "14 días", 30 to "30 días").forEach { (length, label) ->
                        FilterChip(
                            selected = range == length,
                            onClick = { vm.range.value = length },
                            label = { Text(label) },
                        )
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Calorías por día", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        CaloriesChart(days, targets?.calories)
                        if (days.size <= 14) {
                            Row(Modifier.fillMaxWidth()) {
                                days.forEach { day ->
                                    Text(
                                        day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.forLanguageTag("es")),
                                        style = MaterialTheme.typography.labelSmall,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                        if (targets != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Línea punteada: tu objetivo de ${formatAmount(targets!!.calories)} kcal",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Promedio diario", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (logged.isEmpty()) {
                                "Sin registros en este periodo"
                            } else {
                                "${formatAmount(average.calories)} kcal · ${logged.size} de ${days.size} días registrados"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (onTarget != null && logged.isNotEmpty()) {
                            Text(
                                "🎯 $onTarget días dentro de ±10% de tu objetivo",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        MacroBars(average, targets)
                    }
                }
            }
            if (weeks.isNotEmpty()) {
                item(key = "weeks") {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Por semana", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Tu meta semanal es tu meta diaria × 7. En verde, lo que quedó por debajo; " +
                                    "en la semana actual, el margen que puedes usar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val today = LocalDate.now()
                            weeks.forEach { week -> WeekRow(week, today) }
                        }
                    }
                }
            }
            items(logged.reversed(), key = { it.date.toString() }) { day ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(dayLabel(day.date)) },
                        supportingContent = { Text(macroLine(day.totals)) },
                        trailingContent = { Text("${formatAmount(day.totals.calories)} kcal") },
                        modifier = Modifier.clickable { onOpenDay(day.date) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CaloriesChart(days: List<HistoryDay>, target: Double?) {
    val barColor = MaterialTheme.colorScheme.primary
    val overColor = MaterialTheme.colorScheme.error
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        if (days.isEmpty()) return@Canvas
        val maxValue = max(days.maxOf { it.totals.calories }, target ?: 0.0).coerceAtLeast(1.0) * 1.1
        val slot = size.width / days.size
        val barWidth = slot * 0.6f
        days.forEachIndexed { index, day ->
            val height = (day.totals.calories / maxValue * size.height).toFloat()
            if (height > 0f) {
                val over = target != null && day.totals.calories > target * 1.1
                drawRoundRect(
                    color = if (over) overColor else barColor,
                    topLeft = Offset(index * slot + (slot - barWidth) / 2, size.height - height),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                )
            }
        }
        if (target != null && target > 0) {
            val y = size.height - (target / maxValue * size.height).toFloat()
            drawLine(
                color = lineColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
            )
        }
    }
}
