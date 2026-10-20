package io.github.thupapuha.macrotracker.ui.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thupapuha.macrotracker.core.update.AvailableUpdate
import io.github.thupapuha.macrotracker.data.AppUpdater
import io.github.thupapuha.macrotracker.data.UpdateState
import kotlin.math.roundToInt

/** Card that offers, downloads and installs a new version. Renders nothing when there is none. */
@Composable
fun UpdateBanner(updater: AppUpdater, state: UpdateState, modifier: Modifier = Modifier) {
    var waitingForPermission by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val update = (state as? UpdateState.Available)?.update ?: (state as? UpdateState.Failed)?.update
        if (waitingForPermission && update != null && updater.canInstall()) updater.download(update)
        waitingForPermission = false
    }

    fun start(update: AvailableUpdate) {
        if (updater.canInstall()) {
            updater.download(update)
        } else {
            waitingForPermission = true
            permissionLauncher.launch(updater.permissionIntent())
        }
    }

    if (!state.showsBanner) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is UpdateState.Available -> {
                    Text("Hay una versión nueva (${state.update.versionName})", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (updater.canInstall()) {
                            "Se descarga aquí mismo y Android te pedirá confirmar la instalación. Tus datos se conservan."
                        } else {
                            "La primera vez, Android te pedirá permitir que Macro Tracker instale actualizaciones."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { start(state.update) }) { Text("Actualizar") }
                        TextButton(onClick = updater::dismiss) { Text("Después") }
                    }
                }
                is UpdateState.Downloading -> {
                    Text(
                        "Descargando ${state.update.versionName}… ${(state.progress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                }
                is UpdateState.ReadyToInstall -> {
                    Text("Versión ${state.update.versionName} lista para instalar", style = MaterialTheme.typography.titleSmall)
                    Button(onClick = { updater.install(state.file) }) { Text("Instalar") }
                }
                is UpdateState.Failed -> {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.update?.let { update -> Button(onClick = { start(update) }) { Text("Reintentar") } }
                        TextButton(onClick = updater::dismiss) { Text("Cerrar") }
                    }
                }
                else -> Unit
            }
        }
    }
}
