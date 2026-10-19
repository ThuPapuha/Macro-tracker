@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.thupapuha.macrotracker.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.thupapuha.macrotracker.core.supabase.GroupApi
import io.github.thupapuha.macrotracker.core.supabase.GroupInfo
import io.github.thupapuha.macrotracker.data.SettingsStore
import io.github.thupapuha.macrotracker.data.SummarySync
import io.github.thupapuha.macrotracker.ui.common.ErrorText
import io.github.thupapuha.macrotracker.ui.common.TextInputDialog
import io.github.thupapuha.macrotracker.ui.common.appViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroupsViewModel(
    private val api: GroupApi?,
    private val settings: SettingsStore,
    private val sync: SummarySync,
) : ViewModel() {
    val configured = api != null
    var groups by mutableStateOf<List<GroupInfo>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Null until settings are loaded. */
    val displayName: StateFlow<String?> = settings.settings
        .map { it.displayName }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        refresh()
    }

    fun refresh() = launchWithApi { api ->
        groups = api.myGroups()
        settings.setHasGroups(groups.isNotEmpty())
    }

    fun saveName(name: String) = launchWithApi { api ->
        settings.setDisplayName(name, synced = false)
        api.setDisplayName(name)
        settings.setDisplayName(name, synced = true)
    }

    fun create(name: String, onCreated: (GroupInfo) -> Unit) = launchWithApi { api ->
        ensureNameSynced(api)
        val group = api.createGroup(name)
        afterMembershipChange(api)
        onCreated(group)
    }

    fun join(code: String, onJoined: (GroupInfo) -> Unit) = launchWithApi { api ->
        ensureNameSynced(api)
        val group = api.joinGroup(code)
        afterMembershipChange(api)
        onJoined(group)
    }

    private suspend fun ensureNameSynced(api: GroupApi) {
        val prefs = settings.current()
        if (!prefs.displayNameSynced && prefs.displayName.isNotBlank()) {
            api.setDisplayName(prefs.displayName)
            settings.setDisplayName(prefs.displayName, synced = true)
        }
    }

    private suspend fun afterMembershipChange(api: GroupApi) {
        settings.setHasGroups(true)
        groups = api.myGroups()
        sync.requestRecent()
    }

    private fun launchWithApi(block: suspend (GroupApi) -> Unit) {
        val api = api ?: return
        viewModelScope.launch {
            loading = true
            error = null
            try {
                block(api)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Algo salió mal"
            } finally {
                loading = false
            }
        }
    }
}

@Composable
fun GroupsScreen(onOpenGroup: (String) -> Unit) {
    val vm = appViewModel { GroupsViewModel(it.groupApi, it.settings, it.summarySync) }
    val displayName by vm.displayName.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Grupos") },
                actions = {
                    if (vm.configured) {
                        IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Actualizar") }
                    }
                },
            )
        },
    ) { padding ->
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
            if (!vm.configured) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Grupos no disponibles", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Esta versión se compiló sin servidor. Sigue los pasos del README del repositorio " +
                                    "(Supabase) y vuelve a generar el APK para activar los grupos y la IA compartida.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                return@LazyColumn
            }

            val name = displayName
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Tu nombre en los grupos", style = MaterialTheme.typography.labelMedium)
                            Text(
                                if (name.isNullOrBlank()) "Sin nombre" else name,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        TextButton(onClick = { dialog = "name" }) { Text(if (name.isNullOrBlank()) "Elegir" else "Cambiar") }
                    }
                }
            }
            item {
                val hasName = !name.isNullOrBlank()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { dialog = "create" }, enabled = hasName && !vm.loading, modifier = Modifier.weight(1f)) {
                        Text("Crear grupo")
                    }
                    OutlinedButton(onClick = { dialog = "join" }, enabled = hasName && !vm.loading, modifier = Modifier.weight(1f)) {
                        Text("Unirme")
                    }
                }
            }
            if (vm.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            vm.error?.let { message -> item { ErrorText(message) } }
            if (!vm.loading && vm.groups.isEmpty() && vm.error == null) {
                item {
                    Text(
                        "Aún no estás en ningún grupo. Crea uno y comparte el código con tus amigos, " +
                            "o únete al de un amigo con su código.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(vm.groups, key = { it.id }) { group ->
                ElevatedCard(onClick = { onOpenGroup(group.id) }, modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(group.name) },
                        supportingContent = {
                            val members = if (group.memberCount == 1) "1 miembro" else "${group.memberCount} miembros"
                            Text("$members · código ${group.inviteCode}")
                        },
                    )
                }
            }
        }
    }

    when (dialog) {
        "name" -> TextInputDialog(
            title = "Tu nombre",
            label = "Nombre o apodo",
            message = "Así te verán tus amigos en los grupos.",
            initial = displayName.orEmpty(),
            confirmLabel = "Guardar",
            onDismiss = { dialog = null },
            onConfirm = {
                vm.saveName(it.take(40))
                dialog = null
            },
        )
        "create" -> TextInputDialog(
            title = "Crear grupo",
            label = "Nombre del grupo",
            confirmLabel = "Crear",
            onDismiss = { dialog = null },
            onConfirm = { groupName ->
                dialog = null
                vm.create(groupName.take(60)) { onOpenGroup(it.id) }
            },
        )
        "join" -> TextInputDialog(
            title = "Unirme a un grupo",
            label = "Código de invitación",
            message = "Pídele el código de 6 caracteres a quien creó el grupo.",
            confirmLabel = "Unirme",
            capitalization = KeyboardCapitalization.Characters,
            transform = { it.uppercase().filter(Char::isLetterOrDigit).take(6) },
            onDismiss = { dialog = null },
            onConfirm = { code ->
                dialog = null
                vm.join(code) { onOpenGroup(it.id) }
            },
        )
    }
}
