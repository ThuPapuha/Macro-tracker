package io.github.thupapuha.macrotracker.core.backup

import io.github.thupapuha.macrotracker.core.model.MacroTargets
import io.github.thupapuha.macrotracker.core.net.AppJson
import io.github.thupapuha.macrotracker.core.supabase.AuthSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable
data class BackupEntry(
    val date: String,
    val meal: String,
    val name: String,
    val quantity: String = "",
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    val source: String = "manual",
    val createdAt: Long = 0,
)

/**
 * Everything needed to rebuild the app after a reinstall: the food log, goals and, if the user was
 * in groups, the anonymous session so they come back as the same member.
 */
@Serializable
data class BackupFile(
    val app: String = APP_ID,
    val format: Int = 1,
    val exportedAt: String,
    val entries: List<BackupEntry> = emptyList(),
    val targets: MacroTargets? = null,
    val displayName: String = "",
    val lastGroupId: String = "",
    val hadGroups: Boolean = false,
    val session: AuthSession? = null,
) {
    companion object {
        const val APP_ID = "macro-tracker"
    }
}

object Backups {
    fun encode(file: BackupFile): String = AppJson.encodeToString(BackupFile.serializer(), file)

    fun decode(text: String): BackupFile {
        val file = try {
            AppJson.decodeFromString(BackupFile.serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Este archivo no es un respaldo de Macro Tracker", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Este archivo no es un respaldo de Macro Tracker", e)
        }
        require(file.app == BackupFile.APP_ID) { "Este archivo no es un respaldo de Macro Tracker" }
        return file
    }

    /** Entries of [incoming] not already in [existing] (same day, meal, food and creation time). */
    fun missingEntries(existing: List<BackupEntry>, incoming: List<BackupEntry>): List<BackupEntry> {
        val known = existing.mapTo(HashSet()) { it.key() }
        return incoming.filter { known.add(it.key()) }
    }

    private fun BackupEntry.key() = listOf(date, meal, name.trim().lowercase(), createdAt.toString()).joinToString("|")
}
