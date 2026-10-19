@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.thupapuha.macrotracker.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.thupapuha.macrotracker.core.ai.EstimatedFood
import io.github.thupapuha.macrotracker.core.model.Macros
import io.github.thupapuha.macrotracker.core.model.MealType
import io.github.thupapuha.macrotracker.data.FoodEntry
import java.time.LocalDate

/** Editable text version of a food, used by the manual form, AI results and the edit dialog. */
data class FoodDraft(
    val name: String = "",
    val quantity: String = "",
    val calories: String = "",
    val protein: String = "",
    val carbs: String = "",
    val fat: String = "",
    val selected: Boolean = true,
) {
    val isValid: Boolean
        get() = name.isNotBlank() &&
            (calories.toAmount() != null || listOf(protein, carbs, fat).any { it.toAmount() != null })

    val macros: Macros
        get() {
            val p = protein.toAmount() ?: 0.0
            val c = carbs.toAmount() ?: 0.0
            val f = fat.toAmount() ?: 0.0
            return Macros(calories.toAmount() ?: Macros.caloriesFrom(p, c, f), p, c, f)
        }

    fun toEntry(date: LocalDate, meal: MealType, source: String, base: FoodEntry? = null): FoodEntry {
        val m = macros
        val entry = base ?: FoodEntry(date = date.toString(), meal = meal.name, name = name, source = source)
        return entry.copy(
            date = date.toString(),
            meal = meal.name,
            name = name.trim(),
            quantity = quantity.trim(),
            calories = m.calories,
            protein = m.protein,
            carbs = m.carbs,
            fat = m.fat,
        )
    }

    companion object {
        fun from(entry: FoodEntry) = FoodDraft(
            name = entry.name,
            quantity = entry.quantity,
            calories = inputAmount(entry.calories),
            protein = inputAmount(entry.protein),
            carbs = inputAmount(entry.carbs),
            fat = inputAmount(entry.fat),
        )

        fun from(food: EstimatedFood) = FoodDraft(
            name = food.name,
            quantity = food.quantity,
            calories = inputAmount(food.calories),
            protein = inputAmount(food.protein),
            carbs = inputAmount(food.carbs),
            fat = inputAmount(food.fat),
        )
    }
}

@Composable
fun FoodFields(draft: FoodDraft, onChange: (FoodDraft) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it)) },
            label = { Text("Alimento") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.quantity,
            onValueChange = { onChange(draft.copy(quantity = it)) },
            label = { Text("Cantidad (ej. 150 g)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(draft.calories, { onChange(draft.copy(calories = it)) }, "kcal", Modifier.weight(1f))
            NumberField(draft.protein, { onChange(draft.copy(protein = it)) }, "Proteína g", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(draft.carbs, { onChange(draft.copy(carbs = it)) }, "Carbos g", Modifier.weight(1f))
            NumberField(draft.fat, { onChange(draft.copy(fat = it)) }, "Grasas g", Modifier.weight(1f))
        }
    }
}

@Composable
fun MealSelector(selected: MealType, onSelect: (MealType) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MealType.entries.forEach { meal ->
            FilterChip(selected = meal == selected, onClick = { onSelect(meal) }, label = { Text(meal.label) })
        }
    }
}

@Composable
fun FoodEditDialog(
    title: String,
    initial: FoodDraft,
    initialMeal: MealType,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (FoodDraft, MealType) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var draft by remember { mutableStateOf(initial) }
    var meal by remember { mutableStateOf(initialMeal) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MealSelector(meal, { meal = it })
                Spacer(Modifier.height(8.dp))
                FoodFields(draft, { draft = it })
                if (draft.calories.isBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Si dejas kcal vacío, se calcula con los macros.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft, meal) }, enabled = draft.isValid) { Text(confirmLabel) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}
