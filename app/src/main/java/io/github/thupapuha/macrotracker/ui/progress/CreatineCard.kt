package io.github.thupapuha.macrotracker.ui.progress

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thupapuha.macrotracker.core.gym.Creatine
import io.github.thupapuha.macrotracker.ui.theme.Brand
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("es"))

/** Daily check-in with an animated, illustrative view of the supplemental creatine level. */
@Composable
fun CreatineCard(takenDays: Set<LocalDate>, dosesByDay: Map<LocalDate, Int>, doseGrams: Int,
                 onToggle: (LocalDate, Boolean) -> Unit) {
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    val today = LocalDate.now()
    val lastDay = today.minusDays(weekOffset * 7L)
    val firstDay = lastDay.minusDays(6)
    val level = Creatine.estimatedLevel(takenDays, today, dosesByDay)
    val animatedLevel by animateFloatAsState(level.toFloat(), tween(800), label = "creatine level")
    val takenToday = today in takenDays

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Creatina", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                val track = MaterialTheme.colorScheme.surfaceVariant
                Canvas(
                    Modifier.size(width = 92.dp, height = 158.dp)
                        .semantics { contentDescription = "Nivel adicional estimado: ${(level * 100).roundToInt()} por ciento" },
                ) {
                    drawBody(SolidColor(track))
                    clipRect(top = size.height * (1f - animatedLevel)) {
                        drawBody(Brush.verticalGradient(listOf(Brand.Sky, Brand.Emerald)))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${(level * 100).roundToInt()}%", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Brand.Emerald)
                    Text("Nivel adicional estimado", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (takenToday) "Toma de hoy: ${dosesByDay[today] ?: 5} g" else "Hoy aún no hay toma registrada",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = { onToggle(today, !takenToday) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (takenToday) "✓ Tomada hoy" else "Marcar toma de hoy")
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { weekOffset++ }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Semana anterior")
                }
                Text(
                    "${firstDay.format(dayMonth)} – ${lastDay.format(dayMonth)}",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = { weekOffset-- }, enabled = weekOffset > 0) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Semana siguiente")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (offset in 6 downTo 0) {
                    val day = lastDay.minusDays(offset.toLong())
                    val taken = day in takenDays
                    Column(
                        Modifier.weight(1f)
                            .background(if (taken) Brand.Emerald.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                                RoundedCornerShape(10.dp))
                            .clickable { onToggle(day, !taken) }
                            .semantics { contentDescription = "$day: ${if (taken) "tomada, ${dosesByDay[day] ?: 5} gramos" else "sin registrar"}" }
                            .padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(listOf("L", "M", "X", "J", "V", "S", "D")[day.dayOfWeek.value - 1],
                            style = MaterialTheme.typography.labelSmall)
                        Text(if (taken) "✓" else "·", style = MaterialTheme.typography.titleMedium)
                        Text("${day.dayOfMonth}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text(
                "Próximas tomas: $doseGrams g. Los días ya registrados conservan su dosis; los anteriores a esta función se estiman con 5 g. La figura es ilustrativa y no mide la creatina muscular.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun DrawScope.drawBody(brush: Brush) {
    val x = size.width / 100f
    val y = size.height / 180f
    drawCircle(brush, radius = 13f * x, center = Offset(50f * x, 15.5f * y))
    fun limb(left: Float, top: Float, width: Float, height: Float, radius: Float) {
        drawRoundRect(brush, topLeft = Offset(left * x, top * y), size = Size(width * x, height * y),
            cornerRadius = CornerRadius(radius * x, radius * y))
    }
    limb(29f, 32f, 42f, 69f, 17f)
    limb(15f, 37f, 15f, 74f, 8f)
    limb(70f, 37f, 15f, 74f, 8f)
    limb(31f, 94f, 17f, 83f, 8f)
    limb(52f, 94f, 17f, 83f, 8f)
}
