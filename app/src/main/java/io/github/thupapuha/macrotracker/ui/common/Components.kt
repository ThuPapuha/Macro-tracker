package io.github.thupapuha.macrotracker.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thupapuha.macrotracker.AppContainer
import io.github.thupapuha.macrotracker.MacroTrackerApp
import io.github.thupapuha.macrotracker.core.model.MacroTargets
import io.github.thupapuha.macrotracker.core.model.Macros
import io.github.thupapuha.macrotracker.ui.theme.MacroColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Creates a ViewModel with access to the app's [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = (LocalContext.current.applicationContext as MacroTrackerApp).container
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

private val spanish: Locale = Locale.forLanguageTag("es")
private val dayFormatter = DateTimeFormatter.ofPattern("EEE d 'de' MMM", spanish)

fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Hoy"
    today.minusDays(1) -> "Ayer"
    today.plusDays(1) -> "Mañana"
    else -> date.format(dayFormatter).replaceFirstChar { it.titlecase(spanish) }
}

/** 1234.4 -> "1234", 12.25 -> "12,3", 12.0 -> "12". */
fun formatAmount(value: Double): String =
    if (abs(value) >= 100 || value == floor(value)) {
        value.roundToInt().toString()
    } else {
        String.format(spanish, "%.1f", value)
    }

/** Value for an editable text field: no thousands separators, dot decimals. */
fun inputAmount(value: Double): String {
    val rounded = (value * 10).roundToInt() / 10.0
    return if (rounded == floor(rounded)) rounded.toLong().toString() else rounded.toString()
}

fun String.toAmount(): Double? = trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }

@Composable
fun CalorieRing(consumed: Double, target: Double?, modifier: Modifier = Modifier, diameter: Dp = 124.dp) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val over = target != null && consumed > target * 1.1
    val color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val progress = if (target != null && target > 0) (consumed / target).toFloat().coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(targetValue = progress, label = "calorieRing")

    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val style = Stroke(width = stroke, cap = StrokeCap.Round)
            drawArc(color = track, startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = topLeft, size = arcSize, style = style)
            if (animated > 0f) {
                drawArc(color = color, startAngle = 135f, sweepAngle = 270f * animated, useCenter = false, topLeft = topLeft, size = arcSize, style = style)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatAmount(consumed), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                if (target != null) "de ${formatAmount(target)} kcal" else "kcal",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (target != null) {
                val left = target - consumed
                Text(
                    if (left >= 0) "Quedan ${formatAmount(left)}" else "+${formatAmount(-left)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
fun MacroBar(label: String, consumed: Double, target: Double?, color: Color, modifier: Modifier = Modifier) {
    val hasTarget = target != null && target > 0
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                if (hasTarget) "${formatAmount(consumed)} / ${formatAmount(target!!)} g" else "${formatAmount(consumed)} g",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (hasTarget) (consumed / target!!).toFloat().coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = color.copy(alpha = 0.18f),
            strokeCap = StrokeCap.Round,
        )
    }
}

@Composable
fun MacroBars(totals: Macros, targets: MacroTargets?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MacroBar("Proteína", totals.protein, targets?.protein, MacroColors.Protein)
        MacroBar("Carbohidratos", totals.carbs, targets?.carbs, MacroColors.Carbs)
        MacroBar("Grasas", totals.fat, targets?.fat, MacroColors.Fat)
    }
}

@Composable
fun MacroSummaryCard(totals: Macros, targets: MacroTargets?, modifier: Modifier = Modifier) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CalorieRing(totals.calories, targets?.calories?.takeIf { it > 0 })
            Spacer(Modifier.width(16.dp))
            MacroBars(totals, targets, Modifier.weight(1f))
        }
    }
}

/** "P 30 · C 45 · G 12" */
fun macroLine(macros: Macros): String =
    "P ${formatAmount(macros.protein)} · C ${formatAmount(macros.carbs)} · G ${formatAmount(macros.fat)}"

@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { new ->
            if (new.length <= 7 && new.all { it.isDigit() || it == '.' || it == ',' }) onValueChange(new)
        },
        label = { Text(label, maxLines = 1) },
        placeholder = placeholder?.let { { Text(it, maxLines = 1) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
fun ErrorText(message: String, modifier: Modifier = Modifier) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
}
