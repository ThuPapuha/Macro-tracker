package io.github.thupapuha.macrotracker.core

import io.github.thupapuha.macrotracker.core.gym.Creatine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CreatineTest {
    private val today = LocalDate.of(2026, 11, 6)

    @Test fun dailyUseBuildsGraduallyAndStopsAtMaximum() {
        val days = (0L until 28L).mapTo(mutableSetOf()) { today.minusDays(it) }
        assertEquals(1.0, Creatine.estimatedLevel(days, today), 0.0001)
        assertTrue(Creatine.estimatedLevel(setOf(today), today) < 0.04)
    }

    @Test fun missedDaysReduceTheEstimateWithoutGoingBelowZero() {
        val days = (42L until 70L).mapTo(mutableSetOf()) { today.minusDays(it) }
        assertEquals(0.0, Creatine.estimatedLevel(days, today), 0.0001)
        val yesterday = today.minusDays(1)
        val earlier = (1L..28L).mapTo(mutableSetOf()) { today.minusDays(it) }
        assertTrue(Creatine.estimatedLevel(earlier, today) < Creatine.estimatedLevel(earlier, yesterday))
    }

    @Test fun configuredDoseChangesTheIllustrativeRate() {
        val days = (0L until 6L).mapTo(mutableSetOf()) { today.minusDays(it) }
        assertEquals(1.0, Creatine.estimatedLevel(days, today, days.associateWith { 20 }), 0.0001)
        assertTrue(Creatine.estimatedLevel(days, today, days.associateWith { 3 }) < Creatine.estimatedLevel(days, today))
        assertTrue(Creatine.estimatedLevel(days, today) < Creatine.estimatedLevel(days, today, days.associateWith { 10 }))
    }
}
