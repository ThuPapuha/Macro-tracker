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
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.thupapuha.macrotracker.MacroTrackerApp
import io.github.thupapuha.macrotracker.core.model.MacroTargets
import io.github.thupapuha.macrotracker.core.model.Macros
import io.github.thupapuha.macrotracker.core.model.MealType
import io.github.thupapuha.macrotracker.core.nutrition.WeekSummary
import io.github.thupapuha.macrotracker.core.nutrition.WeeklyBudget
import io.github.thupapuha.macrotracker.data.FoodEntry
import io.github.thupapuha.macrotracker.data.FoodRepository
import io.github.thupapuha.macrotracker.data.ProgressRepository
import io.github.thupapuha.macrotracker.data.SettingsStore
import io.github.thupapuha.macrotracker.data.WeightEntry
import io.github.thupapuha.macrotracker.data.macros
import io.github.thupapuha.macrotracker.data.mealType
import io.github.thupapuha.macrotracker.data.toIntake
import io.github.thupapuha.macrotracker.ui.common.Avatar
import io.github.thupapuha.macrotracker.ui.common.FoodDraft
import io.github.thupapuha.macrotracker.ui.common.FoodEditDialog
import io.github.thupapuha.macrotracker.ui.common.MacroSummaryCard
import io.github.thupapuha.macrotracker.ui.common.UpdateBanner
import io.github.thupapuha.macrotracker.ui.common.WeekCard
import io.github.thupapuha.macrotracker.ui.common.appViewModel
import io.github.thupapuha.macrotracker.ui.common.dayLabel
import io.github.thupapuha.macrotracker.ui.common.formatAmount
import io.github.thupapuha.macrotracker.ui.common.macroLine
import io.github.thupapuha.macrotracker.ui.progress.BodyLogDialog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

class TodayViewModel(
    private val repository: FoodRepository,
    private val progress: ProgressRepository,
    private val settings: SettingsStore,
    initialDate: LocalDate,
) : ViewModel() {
    val date = MutableStateFlow(initialDate)

    val displayName: StateFlow<String> = settings.settings
        .map { it.displayName }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Consecutive days with food logged, up to today (or yesterday if today is still empty). */
    val streak: StateFlow<Int> = repository.observeTotals(LocalDate.now().minusDays(365), LocalDate.now())
        .map { totals ->
            val logged = totals.filter { it.entries > 0 }.mapTo(HashSet()) { it.date }
            var day = LocalDate.now()
            if (day.toString() !in logged) day = day.minusDays(1)
            var count = 0
            while (day.toString() in logged) {
                count++
                day = day.minusDays(1)
            }
            count
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Same colors as in the groups: the avatar depends on the user id once there is one. */
    var avatarSeed by mutableStateOf("")
        private set

    init {
        viewModelScope.launch { avatarSeed = settings.sessionStore.load()?.userId.orEmpty() }
    }

    val weights: StateFlow<List<WeightEntry>> = progress.weights
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun logWeight(entry: WeightEntry) {
        viewModelScope.launch { progress.logWeight(entry) }
    }

    val entries: StateFlow<List<FoodEntry>> = date
        .flatMapLatest { repository.observeDay(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val targets: StateFlow<MacroTargets?> = settings.settings
        .map { it.targets }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Budget of the week of the day shown (null without calorie goals). */
    val week: StateFlow<WeekSummary?> = date
        .flatMapLatest { day ->
            val start = WeeklyBudget.weekStart(day)
            combine(repository.observeTotals(start, start.plusDays(6)), settings.settings.map { it.targets }) { totals, goals ->
                goals?.takeIf { it.calories > 0 }?.let {
                    WeeklyBudget.summarize(totals.map { t -> t.toIntake() }, it, day, LocalDate.now())
                }
            }
        }
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
    onTrain: () -> Unit = {},
) {
    val vm = appViewModel(key = "day-${initialDate ?: "today"}") {
        TodayViewModel(it.foodRepository, it.progressRepository, it.settings, initialDate ?: LocalDate.now())
    }
    val name by vm.displayName.collectAsStateWithLifecycle()
    val streak by vm.streak.collectAsStateWithLifecycle()
    var loggingWeight by remember { mutableStateOf(false) }
    val weights by vm.weights.collectAsStateWithLifecycle()
    val date by vm.date.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val targets by vm.targets.collectAsStateWithLifecycle()
    val week by vm.week.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<FoodEntry?>(null) }
    val isToday = date == LocalDate.now()
    val updater = (LocalContext.current.applicationContext as MacroTrackerApp).container.updater
    val updateState by updater.state.collectAsStateWithLifecycle()

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
            if (onBack == null) {
                item(key = "hero") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                greeting() + if (name.isNotBlank()) ", ${name.substringBefore(' ')}" else "",
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                if (streak > 0) "🔥 $streak ${if (streak == 1) "día" else "días"} seguidos registrando" else "Registra hoy y empieza tu racha 🔥",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Avatar(
                            name.ifBlank { "Yo" },
                            vm.avatarSeed.ifBlank { name },
                            Modifier.clickable(onClick = onOpenProfile),
                            diameter = 48.dp,
                        )
                    }
                }
                item(key = "actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuickAction("🍽️", "Comida", Modifier.weight(1f)) { onAddFood(date, MealType.forHour(LocalTime.now().hour)) }
                        QuickAction("🏋️", "Entrenar", Modifier.weight(1f), onClick = onTrain)
                        QuickAction("⚖️", "Peso", Modifier.weight(1f)) { loggingWeight = true }
                    }
                }
            }
            if (onBack == null && updateState.showsBanner) {
                item(key = "update") { UpdateBanner(updater, updateState) }
            }
            item(key = "summary") {
                MacroSummaryCard(Macros.sum(entries.map { it.macros }), targets)
            }
            week?.let { summary -> item(key = "week") { WeekCard(summary) } }
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

    if (loggingWeight) {
        BodyLogDialog(
            entries = weights,
            onDismiss = { loggingWeight = false },
            onSave = {
                vm.logWeight(it)
                loggingWeight = false
            },
        )
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

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Buenos días"
    in 12..19 -> "Buenas tardes"
    else -> "Buenas noches"
}

@Composable
private fun QuickAction(emoji: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(emoji, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
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
