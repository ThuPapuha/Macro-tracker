package io.github.thupapuha.macrotracker.data

import io.github.thupapuha.macrotracker.core.backup.BackupRoutine
import io.github.thupapuha.macrotracker.core.backup.BackupWeight
import io.github.thupapuha.macrotracker.core.backup.BackupWorkout
import io.github.thupapuha.macrotracker.core.gym.ExerciseLog
import io.github.thupapuha.macrotracker.core.gym.Gym
import io.github.thupapuha.macrotracker.core.net.AppJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalDate
import java.util.UUID

data class Routine(
    val id: String,
    val name: String,
    val exercises: List<ExerciseLog>,
    val position: Int = 0,
)

data class Workout(
    val id: String,
    val date: LocalDate,
    val title: String,
    val routineId: String?,
    val exercises: List<ExerciseLog>,
    val prCount: Int,
    val durationMin: Int?,
    val finishedAt: Long,
)

data class WeightEntry(val date: LocalDate, val kg: Double)

private val exercisesSerializer = ListSerializer(ExerciseLog.serializer())

fun encodeExercises(exercises: List<ExerciseLog>): String = AppJson.encodeToString(exercisesSerializer, exercises)

fun decodeExercises(json: String): List<ExerciseLog> =
    runCatching { AppJson.decodeFromString(exercisesSerializer, json) }.getOrDefault(emptyList())

private fun RoutineEntity.toRoutine() = Routine(id, name, decodeExercises(exercisesJson), position)

private fun WorkoutEntity.toWorkout() = Workout(
    id = id,
    date = LocalDate.parse(date),
    title = title,
    routineId = routineId,
    exercises = decodeExercises(exercisesJson),
    prCount = prCount,
    durationMin = durationMin,
    finishedAt = finishedAt,
)

class ProgressRepository(private val dao: ProgressDao) {

    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    /** Emits after workouts, weights or routines change (for syncing and backups). */
    val changed: SharedFlow<Unit> = changes

    val routines: Flow<List<Routine>> = dao.observeRoutines().map { list -> list.map { it.toRoutine() } }

    /** Newest first. */
    val workouts: Flow<List<Workout>> = dao.observeWorkouts().map { list -> list.map { it.toWorkout() } }

    /** Oldest first. */
    val weights: Flow<List<WeightEntry>> = dao.observeWeights().map { list ->
        list.map { WeightEntry(LocalDate.parse(it.date), it.kg) }
    }

    suspend fun routine(id: String): Routine? = dao.routine(id)?.toRoutine()

    /** Exercises of every saved workout, oldest first (to find personal records). */
    suspend fun history(): List<List<ExerciseLog>> = dao.allWorkouts().map { decodeExercises(it.exercisesJson) }

    suspend fun saveRoutine(routine: Routine) {
        val position = if (dao.routine(routine.id) == null) dao.allRoutines().size else routine.position
        dao.upsertRoutine(
            RoutineEntity(routine.id, routine.name.trim(), encodeExercises(routine.exercises), position),
        )
        changes.emit(Unit)
    }

    suspend fun deleteRoutine(id: String) {
        dao.deleteRoutine(id)
        changes.emit(Unit)
    }

    /**
     * Saves a finished workout: marks personal records against every earlier workout and, if it
     * came from a routine, stores the reps and weights just lifted as the routine's new targets.
     * Returns null if nothing was done.
     */
    suspend fun finishWorkout(
        title: String,
        routineId: String?,
        exercises: List<ExerciseLog>,
        date: LocalDate,
        startedAt: Long,
    ): Workout? {
        val history = dao.allWorkouts().map { decodeExercises(it.exercisesJson) }
        val finished = Gym.finish(exercises, history)
        if (finished.exercises.isEmpty()) return null
        val now = System.currentTimeMillis()
        val minutes = ((now - startedAt) / 60_000).toInt().takeIf { startedAt > 0 && it in 1..600 }
        val entity = WorkoutEntity(
            id = UUID.randomUUID().toString(),
            date = date.toString(),
            title = title.trim().ifBlank { "Entrenamiento" },
            routineId = routineId,
            exercisesJson = encodeExercises(finished.exercises),
            prCount = finished.prCount,
            durationMin = minutes,
            finishedAt = now,
            updatedAt = now,
        )
        dao.upsertWorkout(entity)
        routineId?.let { dao.routine(it) }?.let { routine ->
            val updated = Gym.updateRoutine(decodeExercises(routine.exercisesJson), finished.exercises)
            dao.upsertRoutine(routine.copy(exercisesJson = encodeExercises(updated), updatedAt = now))
        }
        changes.emit(Unit)
        return entity.toWorkout()
    }

    suspend fun deleteWorkout(id: String) {
        val workout = dao.workout(id) ?: return
        dao.upsertWorkout(workout.copy(deleted = true, synced = false, updatedAt = System.currentTimeMillis()))
        changes.emit(Unit)
    }

    suspend fun logWeight(date: LocalDate, kg: Double) {
        dao.upsertWeight(WeightEntity(date.toString(), kg))
        changes.emit(Unit)
    }

    suspend fun deleteWeight(date: LocalDate) {
        val existing = dao.allWeights().firstOrNull { it.date == date.toString() } ?: return
        dao.upsertWeight(existing.copy(deleted = true, synced = false, updatedAt = System.currentTimeMillis()))
        changes.emit(Unit)
    }

    suspend fun latestWeight(): WeightEntry? =
        dao.allWeights().lastOrNull()?.let { WeightEntry(LocalDate.parse(it.date), it.kg) }

    // Syncing with the groups' server.

    suspend fun pendingWorkouts(): List<WorkoutEntity> = dao.pendingWorkouts()

    suspend fun markWorkoutSynced(workout: WorkoutEntity) {
        if (workout.deleted) dao.purgeWorkout(workout.id) else dao.markWorkoutSynced(workout.id, workout.updatedAt)
    }

    suspend fun pendingWeights(): List<WeightEntity> = dao.pendingWeights()

    suspend fun markWeightSynced(weight: WeightEntity) {
        if (weight.deleted) dao.purgeWeight(weight.date) else dao.markWeightSynced(weight.date, weight.updatedAt)
    }

    // Backups.

    suspend fun backupRoutines(): List<BackupRoutine> =
        dao.allRoutines().map { BackupRoutine(it.id, it.name, decodeExercises(it.exercisesJson), it.position) }

    suspend fun backupWorkouts(): List<BackupWorkout> = dao.allWorkouts().map {
        BackupWorkout(it.id, it.date, it.title, it.routineId, decodeExercises(it.exercisesJson), it.prCount, it.durationMin, it.finishedAt)
    }

    suspend fun backupWeights(): List<BackupWeight> = dao.allWeights().map { BackupWeight(it.date, it.kg) }

    /** Adds what is missing; returns how many workouts and weigh-ins were added. */
    suspend fun importBackup(
        routines: List<BackupRoutine>,
        workouts: List<BackupWorkout>,
        weights: List<BackupWeight>,
    ): Pair<Int, Int> {
        for (routine in routines) {
            if (dao.routine(routine.id) == null) {
                dao.upsertRoutine(RoutineEntity(routine.id, routine.name, encodeExercises(routine.exercises), routine.position))
            }
        }
        var addedWorkouts = 0
        for (workout in workouts) {
            if (dao.workout(workout.id) != null) continue
            dao.upsertWorkout(
                WorkoutEntity(
                    id = workout.id,
                    date = workout.date,
                    title = workout.title,
                    routineId = workout.routineId,
                    exercisesJson = encodeExercises(workout.exercises),
                    prCount = workout.prCount,
                    durationMin = workout.durationMin,
                    finishedAt = workout.finishedAt,
                ),
            )
            addedWorkouts++
        }
        val knownDays = dao.allWeights().mapTo(HashSet()) { it.date }
        val newWeights = weights.filter { knownDays.add(it.date) }
        newWeights.forEach { dao.upsertWeight(WeightEntity(it.date, it.kg)) }
        if (routines.isNotEmpty() || addedWorkouts > 0 || newWeights.isNotEmpty()) changes.emit(Unit)
        return addedWorkouts to newWeights.size
    }
}
