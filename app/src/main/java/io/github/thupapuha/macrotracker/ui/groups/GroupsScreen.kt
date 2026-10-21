@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.thupapuha.macrotracker.ui.groups

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.thupapuha.macrotracker.core.supabase.GroupApi
import io.github.thupapuha.macrotracker.core.supabase.GroupInfo
import io.github.thupapuha.macrotracker.data.SettingsStore
import io.github.thupapuha.macrotracker.data.SummarySync
import io.github.thupapuha.macrotracker.data.UserSettings
import io.github.thupapuha.macrotracker.ui.common.ErrorText
import io.github.thupapuha.macrotracker.ui.common.TextInputDialog
import io.github.thupapuha.macrotracker.ui.common.appViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    /** True once the group list has been fetched at least once. */
    var loaded by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Null until settings are loaded. */
    val prefs: StateFlow<UserSettings?> = settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        refresh()
    }

    fun refresh() = launchWithApi { api ->
        groups = api.myGroups()
        loaded = true
        settings.setHasGroups(groups.isNotEmpty())
        // Forget a group we no longer belong to.
        val last = settings.current().lastGroupId
        if (last.isNotBlank() && groups.none { it.id == last }) settings.setLastGroup(groups.firstOrNull()?.id.orEmpty())
    }

    fun selectGroup(groupId: String) {
        viewModelScope.launch { settings.setLastGroup(groupId) }
    }

    fun saveName(name: String) = launchWithApi { api ->
        settings.setDisplayName(name, synced = false)
        api.setDisplayName(name)
        settings.setDisplayName(name, synced = true)
    }

    fun create(name: String) = launchWithApi { api ->
        ensureNameSynced(api)
        afterMembershipChange(api, api.createGroup(name))
    }

    fun join(code: String) = launchWithApi { api ->
        ensureNameSynced(api)
        afterMembershipChange(api, api.joinGroup(code))
    }

    fun markNotificationPermissionAsked() {
        viewModelScope.launch { settings.setNotificationPermissionAsked() }
    }

    private suspend fun ensureNameSynced(api: GroupApi) {
        val prefs = settings.current()
        if (!prefs.displayNameSynced && prefs.displayName.isNotBlank()) {
            api.setDisplayName(prefs.displayName)
            settings.setDisplayName(prefs.displayName, synced = true)
        }
    }

    private suspend fun afterMembershipChange(api: GroupApi, group: GroupInfo) {
        settings.setHasGroups(true)
        settings.setLastGroup(group.id)
        groups = api.myGroups()
        loaded = true
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

/**
 * The Grupos tab: opens straight into the last group viewed (or the only one); without groups it
 * offers to create or join one.
 */
@Composable
fun GroupsScreen() {
    val vm = appViewModel { GroupsViewModel(it.groupApi, it.settings, it.summarySync) }
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val settings = prefs ?: return

    val selectedId = settings.lastGroupId
        .takeIf { id -> id.isNotBlank() && (!vm.loaded || vm.groups.any { it.id == id }) }
        ?: vm.groups.firstOrNull()?.id

    NotificationPermissionRequest(
        active = selectedId != null && settings.notifyGroupActivity && !settings.notificationPermissionAsked,
        onAsked = vm::markNotificationPermissionAsked,
    )

    when {
        !vm.configured -> NotConfigured()
        selectedId != null -> GroupDetailScreen(
            groupId = selectedId,
            groups = vm.groups,
            onSelectGroup = vm::selectGroup,
            onCreate = { dialog = "create" },
            onJoin = { dialog = "join" },
            onRename = { dialog = "name" },
            onLeft = vm::refresh,
        )
        !vm.loaded && vm.error == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> NoGroups(
            displayName = settings.displayName,
            loading = vm.loading,
            error = vm.error,
            onRetry = vm::refresh,
            onRename = { dialog = "name" },
            onCreate = { dialog = "create" },
            onJoin = { dialog = "join" },
        )
    }

    when (dialog) {
        "name" -> TextInputDialog(
            title = "Tu nombre",
            label = "Nombre o apodo",
            message = "Así te verán tus amigos en los grupos.",
            initial = settings.displayName,
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
            onConfirm = {
                dialog = null
                vm.create(it.take(60))
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
            onConfirm = {
                dialog = null
                vm.join(it)
            },
        )
    }
}

/** Asks once (Android 13+) for permission to show friends' activity notifications. */
@Composable
private fun NotificationPermissionRequest(active: Boolean, onAsked: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onAsked() }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) onAsked() else launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun NotConfigured() {
    Scaffold(topBar = { TopAppBar(title = { Text("Grupos") }) }) { padding ->
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.padding(padding).padding(16.dp),
        ) {
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
}

@Composable
private fun NoGroups(
    displayName: String,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onRename: () -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Grupos") },
                actions = {
                    IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, contentDescription = "Actualizar") }
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
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Tu nombre en los grupos", style = MaterialTheme.typography.labelMedium)
                            Text(displayName.ifBlank { "Sin nombre" }, style = MaterialTheme.typography.titleMedium)
                        }
                        TextButton(onClick = onRename) { Text(if (displayName.isBlank()) "Elegir" else "Cambiar") }
                    }
                }
            }
            item {
                val hasName = displayName.isNotBlank()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCreate, enabled = hasName && !loading, modifier = Modifier.weight(1f)) { Text("Crear grupo") }
                    OutlinedButton(onClick = onJoin, enabled = hasName && !loading, modifier = Modifier.weight(1f)) { Text("Unirme") }
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message -> item { ErrorText(message) } }
            if (error == null) {
                item {
                    Text(
                        "Aún no estás en ningún grupo. Crea uno y comparte el código con tus amigos, " +
                            "o únete al de un amigo con su código.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
