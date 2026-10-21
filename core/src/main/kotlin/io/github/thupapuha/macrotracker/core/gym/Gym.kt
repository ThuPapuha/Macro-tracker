package io.github.thupapuha.macrotracker.core.gym

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** One set: [weight] in kg (0 for bodyweight exercises). */
@Serializable
data class WorkoutSet(
    val reps: Int = 0,
    val weight: Double = 0.0,
    val done: Boolean = true,
)

/**
 * An exercise as defined in a routine (its current target sets) or as performed in a workout.
 * [pr] is only meaningful in a finished workout.
 */
@Serializable
data class ExerciseLog(
    val name: String,
    val sets: List<WorkoutSet> = emptyList(),
    val pr: Boolean = false,
    val note: String = "",
)

/** Heaviest set of an exercise; at equal weight, the one with more reps. */
data class BestLift(val weight: Double, val reps: Int) {
    val estimatedMax: Double get() = Gym.estimatedMax(weight, reps)
}

/** One point of an exercise's progress chart. */
data class ExercisePoint(val day: String, val best: BestLift, val volume: Double)

data class FinishedWorkout(val exercises: List<ExerciseLog>, val prCount: Int)

object Gym {
    private const val EPSILON = 1e-6

    /** Epley's one-rep max estimate. */
    fun estimatedMax(weight: Double, reps: Int): Double = when {
        reps <= 0 || weight <= 0 -> 0.0
        reps == 1 -> weight
        else -> weight * (1 + reps / 30.0)
    }

    fun completedSets(sets: List<WorkoutSet>): List<WorkoutSet> = sets.filter { it.done && it.reps > 0 }

    fun best(sets: List<WorkoutSet>): BestLift? = completedSets(sets)
        .maxWithOrNull(compareBy<WorkoutSet> { it.weight }.thenBy { it.reps })
        ?.let { BestLift(it.weight, it.reps) }

    /** Kilos moved: reps × weight of the completed sets. */
    fun volume(sets: List<WorkoutSet>): Double = completedSets(sets).sumOf { it.reps * it.weight }

    fun totalVolume(exercises: List<ExerciseLog>): Double = exercises.sumOf { volume(it.sets) }

    fun completedSetCount(exercises: List<ExerciseLog>): Int = exercises.sumOf { completedSets(it.sets).size }

    /** More weight than ever, or the same top weight for more reps. */
    fun isRecord(current: BestLift?, previous: BestLift?): Boolean {
        if (current == null || previous == null) return false
        return current.weight > previous.weight + EPSILON ||
            (abs(current.weight - previous.weight) <= EPSILON && current.reps > previous.reps)
    }

    /** Exercises are matched by name, ignoring case and surrounding spaces. */
    fun key(name: String): String = name.trim().lowercase()

    /** Best lift ever per exercise in [history]. */
    fun bests(history: List<List<ExerciseLog>>): Map<String, BestLift> {
        val result = HashMap<String, BestLift>()
        for (workout in history) {
            for (exercise in workout) {
                val best = best(exercise.sets) ?: continue
                val key = key(exercise.name)
                val known = result[key]
                if (known == null || isRecord(best, known)) result[key] = best
            }
        }
        return result
    }

    /**
     * Drops empty exercises and sets that were not done, and marks the exercises that beat every
     * previous workout in [history]. The first time an exercise is done does not count as a record.
     */
    fun finish(exercises: List<ExerciseLog>, history: List<List<ExerciseLog>>): FinishedWorkout {
        val previous = bests(history)
        val cleaned = exercises
            .map { it.copy(name = it.name.trim(), sets = completedSets(it.sets)) }
            .filter { it.name.isNotEmpty() && it.sets.isNotEmpty() }
            .map { it.copy(pr = isRecord(best(it.sets), previous[key(it.name)])) }
        return FinishedWorkout(cleaned, cleaned.count { it.pr })
    }

    /**
     * After a workout, the routine keeps the reps and weights just lifted as its new targets, so the
     * next session starts from them. Exercises skipped in the workout keep their previous values.
     */
    fun updateRoutine(routine: List<ExerciseLog>, performed: List<ExerciseLog>): List<ExerciseLog> {
        val byName = performed.associateBy { key(it.name) }
        return routine.map { exercise ->
            val done = byName[key(exercise.name)]?.let { completedSets(it.sets) }.orEmpty()
            if (done.isEmpty()) exercise else exercise.copy(sets = done.map { it.copy(done = true) })
        }
    }

    /** Sets to start a workout from a routine: same targets, not done yet. */
    fun startFrom(routine: List<ExerciseLog>): List<ExerciseLog> =
        routine.map { exercise -> exercise.copy(pr = false, sets = exercise.sets.map { it.copy(done = false) }) }

    /** Progress of one exercise across workouts given as (day, exercises), oldest first. */
    fun progress(name: String, workouts: List<Pair<String, List<ExerciseLog>>>): List<ExercisePoint> {
        val key = key(name)
        return workouts
            .mapNotNull { (day, exercises) ->
                val sets = exercises.filter { key(it.name) == key }.flatMap { it.sets }
                best(sets)?.let { ExercisePoint(day, it, volume(sets)) }
            }
            .sortedBy { it.day }
    }

    /** "3 × 10 · 40 kg", "3 × 8-10 · 20-25 kg", "3 × 12" for bodyweight. */
    fun describe(sets: List<WorkoutSet>): String {
        if (sets.isEmpty()) return "Sin series"
        val reps = sets.map { it.reps }
        val repText = if (reps.distinct().size == 1) "${reps.first()}" else "${reps.min()}-${reps.max()}"
        val weights = sets.map { it.weight }.filter { it > 0 }
        val weightText = when {
            weights.isEmpty() -> ""
            weights.distinct().size == 1 -> " · ${formatKg(weights.first())} kg"
            else -> " · ${formatKg(weights.min())}-${formatKg(weights.max())} kg"
        }
        return "${sets.size} × $repText$weightText"
    }

    /** 80.0 -> "80", 82.5 -> "82.5", 1.25 -> "1.25". */
    fun formatKg(value: Double): String {
        val rounded = Math.round(value * 100) / 100.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString().trimEnd('0')
    }
}

/** Body weight goal: [start] and [goal] in kg. */
@Serializable
data class WeightGoal(val start: Double, val goal: Double) {
    val losing: Boolean get() = goal < start

    /** Fraction of the way from [start] to [goal] (0..1); moving away from the goal counts as 0. */
    fun progress(current: Double): Float {
        val total = goal - start
        if (abs(total) < 1e-6) return if (abs(current - goal) < 0.25) 1f else 0f
        return ((current - start) / total).coerceIn(0.0, 1.0).toFloat()
    }

    /** Kilos still to lose (or gain) to reach the goal; 0 once reached. */
    fun remaining(current: Double): Double =
        if (losing) (current - goal).coerceAtLeast(0.0) else (goal - current).coerceAtLeast(0.0)
}
