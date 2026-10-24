package io.github.thupapuha.macrotracker.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.thupapuha.macrotracker.core.nutrition.DayStatus
import io.github.thupapuha.macrotracker.core.nutrition.WeekSummary
import io.github.thupapuha.macrotracker.ui.theme.Brand
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

private val spanish: Locale = Locale.forLanguageTag("es")
private val dayMonth = DateTimeFormatter.ofPattern("d MMM", spanish)

/** "20 – 26 oct" */
fun weekRange(week: WeekSummary): String =
    if (week.start.month == week.end.month) {
        "${week.start.dayOfMonth} – ${week.end.format(dayMonth)}"
    } else {
        "${week.start.format(dayMonth)} – ${week.end.format(dayMonth)}"
    }

private fun kcal(value: Double) = "${formatAmount(value)} kcal"

/**
 * The week's calorie budget (daily goal × 7) with each day, the margin left by days under the goal
 * (or taken by days over it), what that leaves for today and the days left, and the weekly macros.
 */
@Composable
fun WeekCard(week: WeekSummary, modifier: Modifier = Modifier) {
    val current = !week.today.isBefore(week.start) && !week.isOver
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (current) "📅 Esta semana" else "📅 Semana",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(weekRange(week), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val consumed = week.consumed.calories
            val budget = week.weekly.calories
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatAmount(consumed), style = MaterialTheme.typography.headlineSmall)
                Text(
                    " / ${kcal(budget)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            LinearProgressIndicator(
                progress = { (consumed / budget).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = if (consumed > budget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                strokeCap = StrokeCap.Round,
            )
            DayStrip(week)

            MarginText(week)

            val skipped = week.skippedDays.size
            if (skipped > 0) {
                Text(
                    if (skipped == 1) {
                        "Un día con muy pocas calorías no cuenta para el margen (¿registro incompleto?)."
                    } else {
                        "$skipped días con muy pocas calorías no cuentan para el margen (¿registros incompletos?)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            Text("Macros de la semana", style = MaterialTheme.typography.labelLarge)
            MacroBars(week.consumed, week.weekly)
        }
    }
}

@Composable
private fun MarginText(week: WeekSummary) {
    val margin = week.margin
    val pace = week.paceForRest
    val small = abs(margin) < 1
    if (week.isOver) {
        Text(
            when {
                small -> "Semana cerrada justo en tu meta. 🎯"
                margin > 0 -> "Semana cerrada ${kcal(margin)} por debajo de tu meta."
                else -> "Semana cerrada ${kcal(-margin)} por encima de tu meta."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    if (small) {
        Text(
            "Sin margen acumulado: vas justo con tu meta semanal.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val positive = margin > 0
    Pill(
        if (positive) "Margen: +${kcal(margin)}" else "Te pasaste ${kcal(-margin)}",
        container = (if (positive) Brand.Emerald else Brand.Amber).copy(alpha = 0.22f),
    )
    val days = week.daysLeft
    val rest = if (days == 1) "hoy" else "los $days días que quedan"
    Text(
        if (positive) {
            "Te sobran calorías de días anteriores. Si quieres usarlas, hoy puedes llegar a " +
                "${kcal(week.todayWithMargin)} o repartirlas: ~${kcal(pace ?: 0.0)} al día $rest."
        } else {
            "Para cerrar la semana en tu meta, apunta a ~${kcal(pace ?: 0.0)} al día $rest."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** One mini bar per day: calories against the daily goal, today outlined. */
@Composable
private fun DayStrip(week: WeekSummary) {
    val primary = MaterialTheme.colorScheme.primary
    val over = MaterialTheme.colorScheme.error
    val track = MaterialTheme.colorScheme.surfaceVariant
    val muted = MaterialTheme.colorScheme.outline
    val goal = week.daily.calories
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            val slot = size.width / 7
            val barWidth = slot * 0.5f
            val maxValue = maxOf(goal * 1.3, week.days.maxOf { it.totals.calories })
            val goalY = size.height - (goal / maxValue * size.height).toFloat()
            week.days.forEachIndexed { index, day ->
                val left = index * slot + (slot - barWidth) / 2
                val radius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                // Track up to the goal height.
                drawRoundRect(track, Offset(left, goalY), Size(barWidth, size.height - goalY), radius)
                val calories = day.totals.calories
                if (calories > 0) {
                    val height = (calories / maxValue * size.height).toFloat()
                    val color = when {
                        day.status == DayStatus.INCOMPLETE -> muted
                        calories > goal * 1.1 -> over
                        else -> primary
                    }
                    drawRoundRect(color, Offset(left, size.height - height), Size(barWidth, height), radius)
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            week.days.forEach { day ->
                val isToday = day.date == week.today
                Text(
                    day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, spanish),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** One line per past week for Historial: eaten against the budget and the difference. */
@Composable
fun WeekRow(week: WeekSummary, today: LocalDate, modifier: Modifier = Modifier) {
    val diff = week.weekly.calories - week.consumed.calories
    val current = !today.isBefore(week.start) && !week.isOver
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                weekRange(week) + if (current) " (esta semana)" else "",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "${formatAmount(week.consumed.calories)} de ${kcal(week.weekly.calories)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (current) {
            Pill(
                (if (week.margin >= 0) "Margen +" else "Margen −") + kcal(abs(week.margin)),
                container = (if (week.margin >= 0) Brand.Emerald else Brand.Amber).copy(alpha = 0.22f),
            )
        } else if (week.consumed.calories > 0) {
            Pill(
                (if (diff >= 0) "−" else "+") + kcal(abs(diff)),
                container = (if (diff >= 0) Brand.Emerald else Brand.Amber).copy(alpha = 0.22f),
            )
        }
    }
}
