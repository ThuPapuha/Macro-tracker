package io.github.thupapuha.macrotracker.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.thupapuha.macrotracker.core.backup.BackupEntry
import io.github.thupapuha.macrotracker.core.backup.BackupFile
import io.github.thupapuha.macrotracker.core.backup.Backups
import io.github.thupapuha.macrotracker.core.supabase.AuthSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

data class ImportResult(
    val added: Int,
    val alreadyPresent: Int,
    val restoredGroups: Boolean,
    val addedWorkouts: Int = 0,
    val addedWeights: Int = 0,
)

/**
 * The food log only lives on the phone, so uninstalling would lose it. This keeps a copy in
 * Downloads/MacroTracker (which survives uninstalling) and can export/import backup files.
 */
class BackupManager(
    context: Context,
    private val repository: FoodRepository,
    private val progress: ProgressRepository,
    private val settings: SettingsStore,
    private val restoreSession: suspend (AuthSession) -> Unit,
    private val scope: CoroutineScope,
) {
    private val context = context.applicationContext
    private val mutex = Mutex()

    @Volatile
    private var dirty = true

    /** Writing to Downloads without a storage permission needs Android 10+. */
    val autoBackupSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun start() {
        scope.launch { repository.changedDays.collect { dirty = true } }
        scope.launch { progress.changed.collect { dirty = true } }
    }

    /** Called when the app goes to the background. */
    fun backupIfChanged() {
        if (!dirty || !autoBackupSupported) return
        scope.launch {
            try {
                backupNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Automatic backup failed", e)
            }
        }
    }

    /** Writes Downloads/MacroTracker/macrotracker-respaldo.json right away. */
    suspend fun backupNow() {
        if (!autoBackupSupported) return
        mutex.withLock {
            val json = Backups.encode(snapshot())
            withContext(Dispatchers.IO) { writeToDownloads(json) }
            dirty = false
            settings.setLastBackup(System.currentTimeMillis())
        }
    }

    suspend fun exportTo(uri: Uri) {
        val json = Backups.encode(snapshot())
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
                ?: throw IOException("No se pudo escribir el archivo")
        }
    }

    suspend fun importFrom(uri: Uri): ImportResult {
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?: throw IOException("No se pudo abrir el archivo")
        }
        val backup = Backups.decode(text)
        val existing = repository.allEntries().map { it.toBackup() }
        val missing = Backups.missingEntries(existing, backup.entries)
        repository.add(missing.map { it.toEntry() })

        val (addedWorkouts, addedWeights) = progress.importBackup(backup.sections, backup.routines, backup.workouts, backup.weights)

        val prefs = settings.current()
        if (prefs.targets == null) backup.targets?.let { settings.setTargets(it) }
        if (prefs.weightGoal == null && backup.weightGoal != null) settings.setWeightGoal(backup.weightGoal, backup.shareWeight)

        // Coming back as the same group member only makes sense on an install without groups.
        val session = backup.session
        val restoreGroups = !prefs.hasGroups && backup.hadGroups && session != null
        if (restoreGroups && session != null) {
            restoreSession(session)
            settings.setHasGroups(true)
            if (prefs.lastGroupId.isBlank()) settings.setLastGroup(backup.lastGroupId)
        }
        if (prefs.displayName.isBlank() && backup.displayName.isNotBlank()) {
            settings.setDisplayName(backup.displayName, synced = restoreGroups)
        }
        dirty = true
        return ImportResult(missing.size, backup.entries.size - missing.size, restoreGroups, addedWorkouts, addedWeights)
    }

    private suspend fun snapshot(): BackupFile {
        val prefs = settings.current()
        return BackupFile(
            exportedAt = Instant.now().toString(),
            entries = repository.allEntries().map { it.toBackup() },
            targets = prefs.targets,
            displayName = prefs.displayName,
            lastGroupId = prefs.lastGroupId,
            hadGroups = prefs.hasGroups,
            session = if (prefs.hasGroups) settings.sessionStore.load() else null,
            sections = progress.backupSections(),
            routines = progress.backupRoutines(),
            workouts = progress.backupWorkouts(),
            weights = progress.backupWeights(),
            weightGoal = prefs.weightGoal,
            shareWeight = prefs.shareWeight,
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToDownloads(json: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // Without storage permission the query only sees files this install created.
        val existing = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(AUTO_FILE_NAME),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null }
        val uri = existing ?: resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, AUTO_FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/MacroTracker")
            },
        ) ?: throw IOException("No se pudo crear el respaldo en Descargas")
        resolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
            ?: throw IOException("No se pudo escribir el respaldo")
    }

    companion object {
        private const val TAG = "BackupManager"
        const val AUTO_FILE_NAME = "macrotracker-respaldo.json"
    }
}

private fun FoodEntry.toBackup() = BackupEntry(
    date = date,
    meal = meal,
    name = name,
    quantity = quantity,
    calories = calories,
    protein = protein,
    carbs = carbs,
    fat = fat,
    source = source,
    createdAt = createdAt,
)

private fun BackupEntry.toEntry() = FoodEntry(
    date = date,
    meal = meal,
    name = name,
    quantity = quantity,
    calories = calories,
    protein = protein,
    carbs = carbs,
    fat = fat,
    source = source,
    createdAt = createdAt,
)
