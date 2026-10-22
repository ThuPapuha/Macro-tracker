package io.github.thupapuha.macrotracker.data

import android.util.Log
import io.github.thupapuha.macrotracker.core.supabase.BackendException
import io.github.thupapuha.macrotracker.core.supabase.GroupApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Uploads workouts, weigh-ins and the weight goal so group members can see them. Like
 * [SummarySync], it only talks to the server once the user belongs to a group; until then
 * everything waits marked as not synced.
 */
class ProgressSync(
    private val scope: CoroutineScope,
    private val repository: ProgressRepository,
    private val settings: SettingsStore,
    private val api: GroupApi?,
) {
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val mutex = Mutex()

    fun request() {
        if (api != null) requests.tryEmit(Unit)
    }

    @OptIn(FlowPreview::class)
    fun start() {
        if (api == null) return
        scope.launch { repository.changed.collect { request() } }
        // Joining a group or changing the weight goal (also fires once at startup).
        scope.launch {
            settings.settings
                .map { Triple(it.hasGroups, it.weightGoalSynced, it.shareWeight) }
                .distinctUntilChanged()
                .collect { request() }
        }
        scope.launch { requests.debounce(1_500).collect { syncNow() } }
    }

    /** Returns false if something could not be uploaded (it is retried on the next change). */
    suspend fun syncNow(): Boolean {
        val api = api ?: return false
        if (!settings.current().hasGroups) return true
        return mutex.withLock {
            try {
                val prefs = settings.current()
                if (!prefs.displayNameSynced && prefs.displayName.isNotBlank()) {
                    api.setDisplayName(prefs.displayName)
                    settings.setDisplayName(prefs.displayName, synced = true)
                }
                if (!prefs.weightGoalSynced) {
                    api.setWeightGoal(prefs.weightGoal?.goal, prefs.weightGoal?.start, prefs.shareWeight)
                    settings.markWeightGoalSynced(prefs.weightGoal, prefs.shareWeight)
                }
                for (weight in repository.pendingWeights().sortedBy { it.date }) {
                    val day = LocalDate.parse(weight.date)
                    skipIfRejected {
                        if (weight.deleted) {
                            api.deleteWeight(day)
                        } else {
                            api.upsertWeight(day, weight.kg, weight.fatPercent, weight.musclePercent, weight.boneKg)
                        }
                    }
                    repository.markWeightSynced(weight)
                }
                for (workout in repository.pendingWorkouts().sortedBy { it.finishedAt }) {
                    skipIfRejected {
                        if (workout.deleted) {
                            api.deleteWorkout(workout.id)
                        } else {
                            api.upsertWorkout(
                                id = workout.id,
                                day = LocalDate.parse(workout.date),
                                title = workout.title,
                                exercises = decodeExercises(workout.exercisesJson),
                                prCount = workout.prCount,
                                durationMin = workout.durationMin,
                                performedAt = Instant.ofEpochMilli(workout.finishedAt).atOffset(ZoneOffset.UTC).toString(),
                                category = workout.section,
                            )
                        }
                    }
                    repository.markWorkoutSynced(workout)
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ProgressSync", "No se pudo sincronizar el progreso", e)
                false
            }
        }
    }

    /** The server refused this item (e.g. a date too old): retrying it forever would block the rest. */
    private inline fun skipIfRejected(upload: () -> Unit) {
        try {
            upload()
        } catch (e: BackendException) {
            if (e.status != 400) throw e
            Log.w("ProgressSync", "El servidor rechazó un dato, se omite", e)
        }
    }
}
