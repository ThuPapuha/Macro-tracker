@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.thupapuha.macrotracker.ui.add

import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.thupapuha.macrotracker.core.ai.AiService
import io.github.thupapuha.macrotracker.core.ai.Attachment
import io.github.thupapuha.macrotracker.core.model.Macros
import io.github.thupapuha.macrotracker.core.model.MealType
import io.github.thupapuha.macrotracker.data.FoodEntry
import io.github.thupapuha.macrotracker.data.FoodRepository
import io.github.thupapuha.macrotracker.data.MediaReader
import io.github.thupapuha.macrotracker.data.macros
import io.github.thupapuha.macrotracker.ui.common.ErrorText
import io.github.thupapuha.macrotracker.ui.common.FoodDraft
import io.github.thupapuha.macrotracker.ui.common.FoodEditDialog
import io.github.thupapuha.macrotracker.ui.common.FoodFields
import io.github.thupapuha.macrotracker.ui.common.MealSelector
import io.github.thupapuha.macrotracker.ui.common.appViewModel
import io.github.thupapuha.macrotracker.ui.common.dayLabel
import io.github.thupapuha.macrotracker.ui.common.formatAmount
import io.github.thupapuha.macrotracker.ui.common.macroLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class AddFoodViewModel(
    private val repository: FoodRepository,
    private val ai: AiService,
    private val media: MediaReader,
    val date: LocalDate,
    initialMeal: MealType,
) : ViewModel() {
    var meal by mutableStateOf(initialMeal)
    var description by mutableStateOf("")
    var image by mutableStateOf<Attachment?>(null)
        private set
    var preview by mutableStateOf<ImageBitmap?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var notes by mutableStateOf("")
        private set
    val results = mutableStateListOf<FoodDraft>()
    var manual by mutableStateOf(FoodDraft())

    val recent: StateFlow<List<FoodEntry>> = repository.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun newPhotoUri(): Uri = media.newPhotoUri()

    fun setImage(uri: Uri) {
        viewModelScope.launch {
            busy = true
            error = null
            try {
                val attachment = media.image(uri)
                preview = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(attachment.bytes, 0, attachment.bytes.size)?.asImageBitmap()
                }
                image = attachment
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "No se pudo cargar la foto"
            } finally {
                busy = false
            }
        }
    }

    fun clearImage() {
        image = null
        preview = null
    }

    fun analyze() {
        viewModelScope.launch {
            busy = true
            error = null
            try {
                val estimate = ai.estimateMeal(description, image)
                results.clear()
                results.addAll(estimate.items.map(FoodDraft::from))
                notes = estimate.notes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Error al consultar la IA"
            } finally {
                busy = false
            }
        }
    }

    fun save(drafts: List<FoodDraft>, source: String, onSaved: () -> Unit) {
        val entries = drafts.filter { it.isValid }.map { it.toEntry(date, meal, source) }
        if (entries.isEmpty()) return
        viewModelScope.launch {
            repository.add(entries)
            onSaved()
        }
    }
}

@Composable
fun AddFoodScreen(date: LocalDate, initialMeal: MealType, onDone: () -> Unit) {
    val vm = appViewModel { AddFoodViewModel(it.foodRepository, it.ai, it.media, date, initialMeal) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agregar · ${dayLabel(date)}") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            MealSelector(vm.meal, { vm.meal = it }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            TabRow(selectedTabIndex = tab) {
                listOf("Con IA", "Manual", "Recientes").forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> AiTab(vm, onDone)
                    1 -> ManualTab(vm, onDone)
                    else -> RecentTab(vm, snackbar)
                }
            }
        }
    }
}

@Composable
private fun AiTab(vm: AddFoodViewModel, onDone: () -> Unit) {
    var photoUri by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = photoUri
        if (saved && uri != null) vm.setImage(Uri.parse(uri))
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setImage(uri)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = vm.description,
            onValueChange = { vm.description = it },
            label = { Text("¿Qué comiste?") },
            placeholder = { Text("Ej: 2 huevos revueltos, 1 pan integral y un café con leche") },
            minLines = 2,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val uri = vm.newPhotoUri()
                    photoUri = uri.toString()
                    try {
                        takePicture.launch(uri)
                    } catch (e: ActivityNotFoundException) {
                        vm.error = "No se encontró una app de cámara"
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("📷 Cámara") }
            OutlinedButton(
                onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.weight(1f),
            ) { Text("🖼️ Galería") }
        }
        vm.preview?.let { bitmap ->
            Box {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Foto de la comida",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)),
                )
                TextButton(onClick = vm::clearImage, modifier = Modifier.align(Alignment.TopEnd)) { Text("Quitar") }
            }
        }
        Text(
            "Tip: también puedes fotografiar la etiqueta nutricional de un producto.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = vm::analyze,
            enabled = !vm.busy && (vm.description.isNotBlank() || vm.image != null),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (vm.busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Analizando…")
            } else {
                Text("✨ Calcular macros con IA")
            }
        }
        vm.error?.let { ErrorText(it) }

        if (vm.results.isNotEmpty()) {
            if (vm.notes.isNotBlank()) {
                Text(vm.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            vm.results.forEachIndexed { index, draft ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(end = 12.dp, top = 8.dp, bottom = 12.dp)) {
                        Checkbox(checked = draft.selected, onCheckedChange = { vm.results[index] = draft.copy(selected = it) })
                        FoodFields(draft, { vm.results[index] = it }, Modifier.weight(1f))
                    }
                }
            }
            val selected = vm.results.filter { it.selected && it.isValid }
            val total = Macros.sum(selected.map { it.macros })
            Text(
                "Total: ${formatAmount(total.calories)} kcal · ${macroLine(total)}",
                style = MaterialTheme.typography.titleSmall,
            )
            Button(
                onClick = { vm.save(selected, source = "ai", onSaved = onDone) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Agregar ${selected.size} a ${vm.meal.label.lowercase()}") }
        }
    }
}

@Composable
private fun ManualTab(vm: AddFoodViewModel, onDone: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FoodFields(vm.manual, { vm.manual = it })
        Text(
            "Si dejas las kcal vacías se calculan con los macros (4/4/9 kcal por gramo).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { vm.save(listOf(vm.manual), source = "manual", onSaved = onDone) },
            enabled = vm.manual.isValid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Agregar") }
    }
}

@Composable
private fun RecentTab(vm: AddFoodViewModel, snackbar: SnackbarHostState) {
    val recent by vm.recent.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var chosen by remember { mutableStateOf<FoodEntry?>(null) }
    val scope = rememberCoroutineScope()
    val filtered = recent.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }

    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Buscar en lo que ya comiste") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
        if (filtered.isEmpty()) {
            item {
                Text(
                    if (recent.isEmpty()) "Aún no tienes alimentos registrados." else "Sin resultados.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        items(filtered, key = { it.id }) { entry ->
            ListItem(
                headlineContent = { Text(entry.name) },
                supportingContent = {
                    val quantity = if (entry.quantity.isBlank()) "" else "${entry.quantity} · "
                    Text("$quantity${formatAmount(entry.calories)} kcal · ${macroLine(entry.macros)}")
                },
                trailingContent = { Icon(Icons.Filled.Add, contentDescription = "Agregar") },
                modifier = Modifier.clickable { chosen = entry },
            )
        }
    }

    chosen?.let { entry ->
        FoodEditDialog(
            title = "Agregar alimento",
            initial = FoodDraft.from(entry),
            initialMeal = vm.meal,
            confirmLabel = "Agregar",
            onDismiss = { chosen = null },
            onConfirm = { draft, meal ->
                vm.meal = meal
                vm.save(listOf(draft), source = "recent") {
                    scope.launch { snackbar.showSnackbar("Agregado: ${draft.name}") }
                }
                chosen = null
            },
        )
    }
}
