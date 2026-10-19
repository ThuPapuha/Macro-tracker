package io.github.thupapuha.macrotracker.data

import android.util.Log
import io.github.thupapuha.macrotracker.core.model.Macros
import io.github.thupapuha.macrotracker.core.supabase.GroupApi
import io.github.thupapuha.macrotracker.core.supabase.SharedMeal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * Publishes each day's totals (and, if allowed, meal names) so group members can see them.
 * Only runs once the user belongs to a group, so solo users never create a server account.
 */
class SummarySync(
    private val scope: CoroutineScope,
    private val repository: FoodRepository,
    private val settings: SettingsStore,
    private val api: GroupApi?,
) {
    private val pending = MutableStateFlow<Set<LocalDate>>(emptySet())
    private val mutex = Mutex()

    fun request(days: Collection<LocalDate>) {
        if (api != null) pending.update { it + days }
    }

    fun requestRecent() = request(recentDays())

    @OptIn(FlowPreview::class)
    fun start() {
        if (api == null) return
        scope.launch { repository.changedDays.collect { request(listOf(it)) } }
        // Targets or sharing preferences changed (also fires once at startup).
        scope.launch {
            settings.settings
                .map { Triple(it.targets, it.shareMeals, it.hasGroups) }
                .distinctUntilChanged()
                .collect { requestRecent() }
        }
        scope.launch {
            pending.debounce(1_500).filter { it.isNotEmpty() }.collect { days ->
                pending.update { it - days }
                syncNow(days)
            }
        }
    }

    /** Uploads the given days right away. Returns false if the upload failed. */
    suspend fun syncNow(days: Collection<LocalDate>): Boolean {
        val api = api ?: return false
        val prefs = settings.current()
        if (!prefs.hasGroups) return true
        return mutex.withLock {
            try {
                if (!prefs.displayNameSynced && prefs.displayName.isNotBlank()) {
                    api.setDisplayName(prefs.displayName)
                    settings.setDisplayName(prefs.displayName, synced = true)
                }
                for (day in days.toSortedSet()) {
                    val entries = repository.entriesFor(day)
                    val meals = if (prefs.shareMeals) {
                        entries.map { SharedMeal(name = it.name, calories = it.calories, meal = it.meal) }
                    } else {
                        emptyList()
                    }
                    api.upsertSummary(day, Macros.sum(entries.map { it.macros }), prefs.targets, meals)
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SummarySync", "No se pudo sincronizar", e)
                false
            }
        }
    }

    private fun recentDays(): List<LocalDate> {
        val today = LocalDate.now()
        return (0L..6L).map { today.minusDays(it) }
    }
}
