package io.github.thupapuha.macrotracker.core

import io.github.thupapuha.macrotracker.core.gym.BestLift
import io.github.thupapuha.macrotracker.core.gym.Body
import io.github.thupapuha.macrotracker.core.gym.BodyLog
import io.github.thupapuha.macrotracker.core.gym.BodyMetric
import io.github.thupapuha.macrotracker.core.gym.ExerciseLog
import io.github.thupapuha.macrotracker.core.gym.Gym
import io.github.thupapuha.macrotracker.core.gym.WeightGoal
import io.github.thupapuha.macrotracker.core.gym.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GymTest {

    private fun sets(vararg pairs: Pair<Int, Double>) = pairs.map { (reps, kg) -> WorkoutSet(reps, kg) }

    @Test
    fun bestSetIsHeaviestThenMostReps() {
        val best = Gym.best(sets(10 to 60.0, 6 to 70.0, 8 to 70.0, 12 to 50.0) + WorkoutSet(3, 90.0, done = false))
        assertEquals(BestLift(70.0, 8), best)
        assertEquals(70.0 * (1 + 8 / 30.0), best!!.estimatedMax, 1e-9)
        assertEquals(10 * 60.0 + 6 * 70 + 8 * 70 + 12 * 50, Gym.volume(sets(10 to 60.0, 6 to 70.0, 8 to 70.0, 12 to 50.0)), 1e-9)
    }

    @Test
    fun recordsBeatEveryPreviousWorkout() {
        val history = listOf(
            listOf(ExerciseLog("Sentadilla", sets(8 to 80.0, 8 to 80.0)), ExerciseLog("Dominadas", sets(8 to 0.0))),
            listOf(ExerciseLog("sentadilla ", sets(5 to 85.0))),
        )
        val today = listOf(
            // Same top weight as before but more reps -> record.
            ExerciseLog("Sentadilla", sets(6 to 85.0, 8 to 80.0)),
            // Bodyweight: more reps -> record.
            ExerciseLog("Dominadas", sets(10 to 0.0)),
            // First time -> not a record.
            ExerciseLog("Peso muerto", sets(5 to 100.0)),
            // Nothing done -> dropped.
            ExerciseLog("Curl", listOf(WorkoutSet(10, 12.0, done = false))),
            ExerciseLog("  ", sets(5 to 10.0)),
        )
        val finished = Gym.finish(today, history)
        assertEquals(listOf("Sentadilla", "Dominadas", "Peso muerto"), finished.exercises.map { it.name })
        assertEquals(listOf(true, true, false), finished.exercises.map { it.pr })
        assertEquals(2, finished.prCount)

        val lighter = Gym.finish(listOf(ExerciseLog("Sentadilla", sets(10 to 80.0))), history)
        assertFalse(lighter.exercises.single().pr)
    }

    @Test
    fun routineKeepsLastLiftedValues() {
        val routine = listOf(
            ExerciseLog("Press banca", sets(10 to 60.0, 10 to 60.0, 10 to 60.0)),
            ExerciseLog("Fondos", sets(12 to 0.0)),
        )
        val start = Gym.startFrom(routine)
        assertTrue(start.all { exercise -> exercise.sets.none { it.done } })

        val performed = listOf(ExerciseLog("press banca", sets(10 to 62.5, 9 to 62.5)))
        val updated = Gym.updateRoutine(routine, performed)
        assertEquals(sets(10 to 62.5, 9 to 62.5), updated[0].sets)
        assertEquals("Press banca", updated[0].name)
        // Skipped exercise keeps its targets.
        assertEquals(routine[1], updated[1])
    }

    @Test
    fun progressAndDescriptions() {
        val workouts = listOf(
            "2026-10-20" to listOf(ExerciseLog("Remo", sets(10 to 50.0))),
            "2026-10-10" to listOf(ExerciseLog("Remo", sets(10 to 45.0)), ExerciseLog("Otro", sets(1 to 1.0))),
            "2026-10-15" to listOf(ExerciseLog("Curl", sets(10 to 10.0))),
        )
        val points = Gym.progress("remo", workouts)
        assertEquals(listOf("2026-10-10", "2026-10-20"), points.map { it.day })
        assertEquals(listOf(45.0, 50.0), points.map { it.best.weight })

        assertEquals("3 × 10 · 40 kg", Gym.describe(sets(10 to 40.0, 10 to 40.0, 10 to 40.0)))
        assertEquals("2 × 8-10 · 20-22.5 kg", Gym.describe(sets(10 to 20.0, 8 to 22.5)))
        assertEquals("1 × 12", Gym.describe(sets(12 to 0.0)))
        assertEquals("82.5", Gym.formatKg(82.5))
        assertEquals("80", Gym.formatKg(80.0))
    }

    @Test
    fun weightGoalProgress() {
        val cut = WeightGoal(start = 90.0, goal = 80.0)
        assertEquals(0.5f, cut.progress(85.0), 1e-6f)
        assertEquals(0f, cut.progress(92.0), 1e-6f)
        assertEquals(1f, cut.progress(79.0), 1e-6f)
        assertEquals(5.0, cut.remaining(85.0), 1e-9)

        val bulk = WeightGoal(start = 60.0, goal = 66.0)
        assertFalse(bulk.losing)
        assertEquals(0.5f, bulk.progress(63.0), 1e-6f)
        assertEquals(0.0, bulk.remaining(67.0), 1e-9)
    }

    @Test
    fun bodyCompositionTrendsSkipDaysWithoutTheMeasurement() {
        val logs = listOf(
            BodyLog("2026-10-22", 80.0, fatPercent = 18.0, boneKg = 3.1),
            BodyLog("2026-10-10", 82.0, fatPercent = 20.0, musclePercent = 40.0),
            BodyLog("2026-10-15", 81.0),
        )
        val fat = Body.trend(logs, BodyMetric.FAT)!!
        assertEquals(18.0, fat.current, 1e-9)
        assertEquals(-2.0, fat.change!!, 1e-9)
        assertNull(Body.trend(logs, BodyMetric.MUSCLE)!!.change)
        assertEquals(listOf(82.0, 81.0, 80.0), Body.series(logs, BodyMetric.WEIGHT).map { it.second })
        assertEquals(listOf(BodyMetric.WEIGHT, BodyMetric.FAT), Body.chartable(logs))
        assertEquals(14.4, logs[0].fatMassKg!!, 1e-9)
        assertEquals(65.6, logs[0].leanMassKg!!, 1e-9)
        assertFalse(logs[2].hasComposition)
        assertTrue(BodyMetric.FAT.upIsGood == false && BodyMetric.MUSCLE.upIsGood == true)
    }
}
