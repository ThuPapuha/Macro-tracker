package io.github.thupapuha.macrotracker.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import io.github.thupapuha.macrotracker.BuildConfig
import io.github.thupapuha.macrotracker.core.update.AvailableUpdate
import io.github.thupapuha.macrotracker.core.update.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class Downloading(val update: AvailableUpdate, val progress: Float) : UpdateState
    data class ReadyToInstall(val update: AvailableUpdate, val file: File) : UpdateState
    data class Failed(val message: String, val update: AvailableUpdate?) : UpdateState

    /** States worth a banner on the home screen. */
    val showsBanner: Boolean
        get() = this is Available || this is Downloading || this is ReadyToInstall || (this is Failed && update != null)
}

/**
 * Updates the app outside the Play Store: checks the repo's GitHub releases, downloads the newer
 * APK and hands it to the system installer (Android always asks the user to confirm).
 */
class AppUpdater(
    context: Context,
    http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val context = context.applicationContext
    private val checker = UpdateChecker(http, BuildConfig.UPDATE_REPO)
    private val downloads = http.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val dir get() = File(context.cacheDir, "updates")
    private var job: Job? = null
    private var lastAutoCheck = 0L

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** Debug builds have a different package name, so a release APK would not update them. */
    val enabled: Boolean = !BuildConfig.DEBUG

    /** [manual] reports errors and "up to date"; the automatic check at startup stays quiet. */
    fun check(manual: Boolean = false) {
        if (!enabled || job?.isActive == true) return
        job = scope.launch {
            if (manual) _state.value = UpdateState.Checking
            _state.value = try {
                val update = checker.check(BuildConfig.VERSION_CODE)
                if (update == null) dir.deleteRecursively()
                when {
                    update != null -> UpdateState.Available(update)
                    manual -> UpdateState.UpToDate
                    else -> UpdateState.Idle
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                if (manual) UpdateState.Failed(e.message ?: "No se pudo buscar actualizaciones", null) else UpdateState.Idle
            }
        }
    }

    /** Called whenever the app comes to the foreground; checks at most every few hours. */
    fun checkIfDue() {
        val now = System.currentTimeMillis()
        val busy = _state.value.showsBanner || _state.value == UpdateState.Checking
        if (now - lastAutoCheck < AUTO_CHECK_INTERVAL_MS || busy) return
        lastAutoCheck = now
        check()
    }

    fun dismiss() {
        if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Idle
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows this app to install updates. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun download(update: AvailableUpdate) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Downloading(update, 0f)
            try {
                val file = fetch(update)
                _state.value = UpdateState.ReadyToInstall(update, file)
                install(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update download failed", e)
                _state.value = UpdateState.Failed(e.message ?: "No se pudo descargar la actualización", update)
            }
        }
    }

    /** Opens the system installer. Fails silently if the app is in the background; the banner offers a retry. */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            _state.value = UpdateState.Failed("No se encontró el instalador de Android", null)
        }
    }

    private suspend fun fetch(update: AvailableUpdate): File = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        dir.mkdirs()
        val target = File(dir, "MacroTracker-${update.versionCode}.apk")
        val request = Request.Builder().url(update.apkUrl).build()
        downloads.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Error al descargar (${response.code})")
            val body = response.body ?: throw IOException("Descarga vacía")
            val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) _state.value = UpdateState.Downloading(update, (copied.toFloat() / total).coerceIn(0f, 1f))
                    }
                    if (update.sizeBytes > 0 && copied != update.sizeBytes) {
                        throw IOException("La descarga quedó incompleta")
                    }
                }
            }
        }
        target
    }

    private companion object {
        const val TAG = "AppUpdater"
        const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
