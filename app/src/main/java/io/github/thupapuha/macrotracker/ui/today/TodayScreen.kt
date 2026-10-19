@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)

package io.github.thupapuha.macrotracker.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.thupapuha.macrotracker.core.model.MacroTargets
import io.github.thupapuha.macrotracker.core.model.Macros
import io.github.thupapuha.macrotracker.core.model.MealType
import io.github.thupapuha.macrotracker.data.FoodEntry
import io.github.thupapuha.macrotracker.data.FoodRepository
import io.github.thupapuha.macrotracker.data.SettingsStore
import io.github.thupapuha.macrotracker.data.macros
import io.github.thupapuha.macrotracker.data.mealType
import io.github.thupapuha.macrotracker.ui.common.FoodDraft
import io.github.thupapuha.macrotracker.ui.common.FoodEditDialog
import io.github.thupapuha.macrotracker.ui.common.MacroSummaryCard
import io.github.thupapuha.macrotracker.ui.common.appViewModel
import io.github.thupapuha.macrotracker.ui.common.dayLabel
import io.github.thupapuha.macrotracker.ui.common.formatAmount
import io.github.thupapuha.macrotracker.ui.common.macroLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

class TodayViewModel(
    private val repository: FoodRepository,
    settings: SettingsStore,
    initialDate: LocalDate,
) : ViewModel() {
    val date = MutableStateFlow(initialDate)

    val entries: StateFlow<List<FoodEntry>> = date
        .flatMapLatest { repository.observeDay(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val targets: StateFlow<MacroTargets?> = settings.settings
        .map { it.targets }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun shiftDay(days: Long) {
        date.value = date.value.plusDays(days)
    }

    fun goToday() {
        date.value = LocalDate.now()
    }

    fun update(old: FoodEntry, draft: FoodDraft, meal: MealType) {
        viewModelScope.launch {
            repository.update(old, draft.toEntry(LocalDate.parse(old.date), meal, old.source, base = old))
        }
    }

    fun delete(entry: FoodEntry) {
        viewModelScope.launch { repository.delete(entry) }
    }
}

@Composable
fun TodayScreen(
    initialDate: LocalDate?,
    onBack: (() -> Unit)?,
    onAddFood: (LocalDate, MealType) -> Unit,
    onOpenProfile: () -> Unit,
) {
    val vm = appViewModel(key = "day-${initialDate ?: "today"}") {
        TodayViewModel(it.foodRepository, it.settings, initialDate ?: LocalDate.now())
    }
    val date by vm.date.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val targets by vm.targets.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<FoodEntry?>(null) }
    val isToday = date == LocalDate.now()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(dayLabel(date)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.shiftDay(-1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Día anterior")
                    }
                    if (!isToday) TextButton(onClick = vm::goToday) { Text("Hoy") }
                    IconButton(onClick = { vm.shiftDay(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Día siguiente")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onAddFood(date, MealType.forHour(LocalTime.now().hour)) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Agregar comida") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary") {
                MacroSummaryCard(Macros.sum(entries.map { it.macros }), targets)
            }
            if (targets == null) {
                item(key = "no-targets") {
                    Card(
                        onClick = onOpenProfile,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Configura tus objetivos", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Ve a Perfil para subir el plan de tu nutricionista o calcular tus calorías y macros.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            MealType.entries.forEach { meal ->
                item(key = meal.name) {
                    MealCard(
                        meal = meal,
                        entries = entries.filter { it.mealType == meal },
                        onAdd = { onAddFood(date, meal) },
                        onEntryClick = { editing = it },
                    )
                }
            }
        }
    }

    editing?.let { entry ->
        FoodEditDialog(
            title = "Editar alimento",
            initial = FoodDraft.from(entry),
            initialMeal = entry.mealType,
            confirmLabel = "Guardar",
            onDismiss = { editing = null },
            onConfirm = { draft, meal ->
                vm.update(entry, draft, meal)
                editing = null
            },
            onDelete = {
                vm.delete(entry)
                editing = null
            },
        )
    }
}

@Composable
private fun MealCard(
    meal: MealType,
    entries: List<FoodEntry>,
    onAdd: () -> Unit,
    onEntryClick: (FoodEntry) -> Unit,
) {
    val total = Macros.sum(entries.map { it.macros })
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(meal.label, style = MaterialTheme.typography.titleMedium)
                if (entries.isNotEmpty()) {
                    Text(
                        "${formatAmount(total.calories)} kcal · ${macroLine(total)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Agregar a ${meal.label}") }
        }
        if (entries.isEmpty()) {
            Text(
                "Sin registros",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
            )
        } else {
            entries.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                EntryRow(entry, onClick = { onEntryClick(entry) })
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun EntryRow(entry: FoodEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entry.quantity.isNotBlank()) {
                Text(
                    entry.quantity,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("${formatAmount(entry.calories)} kcal", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                macroLine(entry.macros),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
