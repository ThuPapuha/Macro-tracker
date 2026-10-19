@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.thupapuha.macrotracker.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.thupapuha.macrotracker.BuildConfig
import io.github.thupapuha.macrotracker.core.ai.AiService
import io.github.thupapuha.macrotracker.core.ai.DocumentPreparer
import io.github.thupapuha.macrotracker.core.model.MacroTargets
import io.github.thupapuha.macrotracker.core.model.Macros
import io.github.thupapuha.macrotracker.core.nutrition.ActivityLevel
import io.github.thupapuha.macrotracker.core.nutrition.BodyProfile
import io.github.thupapuha.macrotracker.core.nutrition.Goal
import io.github.thupapuha.macrotracker.core.nutrition.NutritionCalculator
import io.github.thupapuha.macrotracker.core.nutrition.Sex
import io.github.thupapuha.macrotracker.data.MediaReader
import io.github.thupapuha.macrotracker.data.SettingsStore
import io.github.thupapuha.macrotracker.data.UserSettings
import io.github.thupapuha.macrotracker.ui.common.ErrorText
import io.github.thupapuha.macrotracker.ui.common.NumberField
import io.github.thupapuha.macrotracker.ui.common.appViewModel
import io.github.thupapuha.macrotracker.ui.common.formatAmount
import io.github.thupapuha.macrotracker.ui.common.inputAmount
import io.github.thupapuha.macrotracker.ui.common.toAmount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val documentTypes = arrayOf(
    "application/pdf",
    "image/*",
    "text/*",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
)

class ProfileViewModel(
    private val settings: SettingsStore,
    private val ai: AiService,
    private val media: MediaReader,
    val serverConfigured: Boolean,
) : ViewModel() {
    val state: StateFlow<UserSettings?> = settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var analyzing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Targets proposed by the AI, waiting for the user to confirm them. */
    var suggestion by mutableStateOf<MacroTargets?>(null)
        private set

    fun analyzeDocument(uri: Uri, note: String) {
        viewModelScope.launch {
            analyzing = true
            error = null
            try {
                val document = media.document(uri)
                val parts = DocumentPreparer.prepare(document.bytes, document.mimeType, document.name)
                suggestion = ai.targetsFromDocument(parts, note).toTargets(document.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "No se pudo analizar el documento"
            } finally {
                analyzing = false
            }
        }
    }

    fun saveTargets(targets: MacroTargets) {
        suggestion = null
        viewModelScope.launch { settings.setTargets(targets) }
    }

    fun dismissSuggestion() {
        suggestion = null
    }

    fun applyCalculator(profile: BodyProfile, targets: MacroTargets) {
        viewModelScope.launch {
            settings.setBodyProfile(profile)
            settings.setTargets(targets)
        }
    }

    fun saveGemini(apiKey: String, model: String) {
        viewModelScope.launch { settings.setOwnGemini(apiKey, model) }
    }

    fun setShareMeals(value: Boolean) {
        viewModelScope.launch { settings.setShareMeals(value) }
    }
}

@Composable
fun ProfileScreen() {
    val vm = appViewModel { ProfileViewModel(it.settings, it.ai, it.media, it.serverConfigured) }
    val state by vm.state.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var documentNote by rememberSaveable { mutableStateOf("") }
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.analyzeDocument(uri, documentNote)
    }
    val settings = state ?: return

    Scaffold(topBar = { TopAppBar(title = { Text("Perfil y objetivos") }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TargetsCard(
                targets = settings.targets,
                onUpload = { dialog = "document" },
                onEdit = { dialog = "manual" },
                onCalculate = { dialog = "calculator" },
            )
            if (vm.analyzing) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Analizando tu documento con IA… puede tardar unos segundos.")
                    }
                }
            }
            vm.error?.let { ErrorText(it) }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ajustes", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Compartir mis comidas con mis grupos", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Si lo apagas, tus amigos solo ven tus totales del día.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = settings.shareMeals, onCheckedChange = vm::setShareMeals)
                    }
                    HorizontalDivider()
                    Text("Inteligencia artificial", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            settings.ownGeminiKey.isNotBlank() ->
                                "Usando tu propia clave de Gemini (${settings.geminiModel.ifBlank { BuildConfig.GEMINI_MODEL }})."
                            vm.serverConfigured ->
                                "Usando el servidor con la clave de Gemini guardada en GitHub (${BuildConfig.GEMINI_MODEL})."
                            else -> "No configurada: falta el servidor o una clave propia."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { dialog = "gemini" }) {
                        Text(if (settings.ownGeminiKey.isBlank()) "Usar mi propia clave (opcional)" else "Cambiar clave propia")
                    }
                    HorizontalDivider()
                    Text(
                        "Servidor de grupos: " + if (vm.serverConfigured) "configurado ✅" else "no configurado",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Versión ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    vm.suggestion?.let { suggestion ->
        TargetsDialog(
            title = "Objetivos sugeridos",
            initial = suggestion,
            message = suggestion.note,
            confirmLabel = "Usar estos objetivos",
            onDismiss = vm::dismissSuggestion,
            onConfirm = vm::saveTargets,
        )
    }

    when (dialog) {
        "document" -> DocumentDialog(
            note = documentNote,
            onNoteChange = { documentNote = it },
            onDismiss = { dialog = null },
            onPick = {
                dialog = null
                pickDocument.launch(documentTypes)
            },
        )
        "manual" -> TargetsDialog(
            title = "Editar objetivos",
            initial = settings.targets,
            message = null,
            confirmLabel = "Guardar",
            onDismiss = { dialog = null },
            onConfirm = {
                vm.saveTargets(it.copy(note = "Ingresado manualmente."))
                dialog = null
            },
        )
        "calculator" -> CalculatorDialog(
            initial = settings.bodyProfile,
            onDismiss = { dialog = null },
            onConfirm = { profile, targets ->
                vm.applyCalculator(profile, targets)
                dialog = null
            },
        )
        "gemini" -> GeminiDialog(
            initialKey = settings.ownGeminiKey,
            initialModel = settings.geminiModel,
            onDismiss = { dialog = null },
            onSave = { key, model ->
                vm.saveGemini(key, model)
                dialog = null
            },
        )
    }
}

@Composable
private fun TargetsCard(
    targets: MacroTargets?,
    onUpload: () -> Unit,
    onEdit: () -> Unit,
    onCalculate: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Objetivos diarios", style = MaterialTheme.typography.titleMedium)
            if (targets == null) {
                Text(
                    "Aún no tienes objetivos. Sube el plan de tu nutricionista, un análisis corporal o cualquier " +
                        "documento con tus datos y la IA los configura por ti.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    "${formatAmount(targets.calories)} kcal",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MacroTarget("Proteína", targets.protein, 4, targets.calories)
                    MacroTarget("Carbos", targets.carbs, 4, targets.calories)
                    MacroTarget("Grasas", targets.fat, 9, targets.calories)
                }
                if (targets.note.isNotBlank()) {
                    Text(
                        targets.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = onUpload, modifier = Modifier.fillMaxWidth()) { Text("📄 Subir documento (IA)") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCalculate, modifier = Modifier.weight(1f)) { Text("Calcular") }
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("Editar") }
            }
        }
    }
}

@Composable
private fun MacroTarget(label: String, grams: Double, kcalPerGram: Int, totalCalories: Double) {
    Column {
        Text("${formatAmount(grams)} g", style = MaterialTheme.typography.titleMedium)
        val percent = if (totalCalories > 0) (grams * kcalPerGram / totalCalories * 100).roundToInt() else 0
        Text("$label · $percent%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DocumentDialog(note: String, onNoteChange: (String) -> Unit, onDismiss: () -> Unit, onPick: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Objetivos desde un documento") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Elige un PDF, foto, Word o texto: plan de tu nutricionista, dieta, InBody, análisis médico… " +
                        "La IA extrae o calcula tus calorías y macros. Podrás revisarlos antes de guardarlos.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = onNoteChange,
                    label = { Text("Nota para la IA (opcional)") },
                    placeholder = { Text("Ej: quiero bajar grasa, entreno 4 veces por semana") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = onPick) { Text("Elegir archivo") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun TargetsDialog(
    title: String,
    initial: MacroTargets?,
    message: String?,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (MacroTargets) -> Unit,
) {
    var calories by remember { mutableStateOf(initial?.calories?.let(::inputAmount).orEmpty()) }
    var protein by remember { mutableStateOf(initial?.protein?.let(::inputAmount).orEmpty()) }
    var carbs by remember { mutableStateOf(initial?.carbs?.let(::inputAmount).orEmpty()) }
    var fat by remember { mutableStateOf(initial?.fat?.let(::inputAmount).orEmpty()) }
    val p = protein.toAmount() ?: 0.0
    val c = carbs.toAmount() ?: 0.0
    val f = fat.toAmount() ?: 0.0
    val implied = Macros.caloriesFrom(p, c, f)
    val kcal = calories.toAmount() ?: implied
    val valid = kcal in 500.0..10000.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!message.isNullOrBlank()) Text(message, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(calories, { calories = it }, "Calorías", Modifier.weight(1f))
                    NumberField(protein, { protein = it }, "Proteína g", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(carbs, { carbs = it }, "Carbos g", Modifier.weight(1f))
                    NumberField(fat, { fat = it }, "Grasas g", Modifier.weight(1f))
                }
                Text(
                    "Los macros suman ${formatAmount(implied)} kcal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(MacroTargets(kcal.roundToInt().toDouble(), p, c, f, initial?.note.orEmpty())) },
                enabled = valid,
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun CalculatorDialog(
    initial: BodyProfile?,
    onDismiss: () -> Unit,
    onConfirm: (BodyProfile, MacroTargets) -> Unit,
) {
    var sex by remember { mutableStateOf(initial?.sex ?: Sex.MALE) }
    var age by remember { mutableStateOf(initial?.age?.toString().orEmpty()) }
    var weight by remember { mutableStateOf(initial?.weightKg?.let(::inputAmount).orEmpty()) }
    var height by remember { mutableStateOf(initial?.heightCm?.let(::inputAmount).orEmpty()) }
    var activity by remember { mutableStateOf(initial?.activity ?: ActivityLevel.MODERATE) }
    var goal by remember { mutableStateOf(initial?.goal ?: Goal.MAINTAIN) }

    val profile = run {
        val a = age.trim().toIntOrNull() ?: return@run null
        val w = weight.toAmount() ?: return@run null
        val h = height.toAmount() ?: return@run null
        BodyProfile(sex, a, w, h, activity, goal)
    }
    val result = profile?.let { runCatching { NutritionCalculator.targets(it) }.getOrNull() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calcular objetivos") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Sex.entries.forEach { option ->
                        FilterChip(selected = sex == option, onClick = { sex = option }, label = { Text(option.label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = age,
                        onValueChange = { new -> if (new.length <= 3 && new.all(Char::isDigit)) age = new },
                        label = { Text("Edad") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(weight, { weight = it }, "Peso kg", Modifier.weight(1f))
                }
                NumberField(height, { height = it }, "Altura cm", Modifier.fillMaxWidth())
                Text("Actividad", style = MaterialTheme.typography.labelLarge)
                ActivityLevel.entries.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = activity == option, onClick = { activity = option }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = activity == option, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("Objetivo", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Goal.entries.forEach { option ->
                        FilterChip(selected = goal == option, onClick = { goal = option }, label = { Text(option.label) })
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    result?.let {
                        "Resultado: ${formatAmount(it.calories)} kcal · P ${formatAmount(it.protein)} g · " +
                            "C ${formatAmount(it.carbs)} g · G ${formatAmount(it.fat)} g"
                    } ?: "Completa edad, peso y altura.",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (profile != null && result != null) onConfirm(profile, result) },
                enabled = result != null,
            ) { Text("Usar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun GeminiDialog(
    initialKey: String,
    initialModel: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var key by remember { mutableStateOf(initialKey) }
    var model by remember { mutableStateOf(initialModel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clave propia de Gemini") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Opcional. Normalmente la app usa la clave guardada en GitHub a través del servidor. " +
                        "Si pones una clave aquí, se guarda solo en este teléfono y se usa directamente.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim() },
                    label = { Text("API key de Gemini") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it.trim() },
                    label = { Text("Modelo") },
                    placeholder = { Text(BuildConfig.GEMINI_MODEL) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key, model) }) { Text("Guardar") } },
        dismissButton = {
            Row {
                if (initialKey.isNotBlank()) TextButton(onClick = { onSave("", "") }) { Text("Quitar") }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}
