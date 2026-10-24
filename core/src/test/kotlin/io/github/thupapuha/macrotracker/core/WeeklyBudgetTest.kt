package io.github.thupapuha.macrotracker.core

import io.github.thupapuha.macrotracker.core.model.MacroTargets
import io.github.thupapuha.macrotracker.core.model.Macros
import io.github.thupapuha.macrotracker.core.nutrition.DayIntake
import io.github.thupapuha.macrotracker.core.nutrition.DayStatus
import io.github.thupapuha.macrotracker.core.nutrition.WeeklyBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WeeklyBudgetTest {

    private val goal = MacroTargets(2000.0, 150.0, 200.0, 60.0)

    // Monday 19 to Sunday 25 of October 2026.
    private val monday = LocalDate.of(2026, 10, 19)

    private fun day(offset: Long, calories: Double, entries: Int = 3) =
        DayIntake(monday.plusDays(offset), Macros(calories, calories / 20, calories / 10, calories / 40), entries)

    @Test
    fun unusedCaloriesBecomeMarginForTheRestOfTheWeek() {
        val intakes = listOf(
            day(0, 1700.0), // 300 under
            day(1, 2100.0), // 100 over
            day(2, 500.0), // looks incomplete: not counted
            // Thursday not logged.
            day(4, 800.0), // today, still pending
        )
        val week = WeeklyBudget.summarize(intakes, goal, date = monday.plusDays(4), today = monday.plusDays(4))

        assertEquals(monday, week.start)
        assertEquals(listOf(DayStatus.COUNTED, DayStatus.COUNTED, DayStatus.INCOMPLETE, DayStatus.NOT_LOGGED), week.days.take(4).map { it.status })
        assertEquals(200.0, week.margin, 1e-9)
        assertEquals(3, week.daysLeft)
        assertEquals(2200.0, week.todayWithMargin, 1e-9)
        assertEquals(2000.0 + 200.0 / 3, week.paceForRest!!, 1e-9)
        assertEquals(800.0, week.todayCalories, 1e-9)
        assertEquals(1, week.skippedDays.size)
        assertEquals(14000.0, week.weekly.calories, 1e-9)
        assertEquals(1700.0 + 2100 + 500 + 800, week.consumed.calories, 1e-9)
        assertFalse(week.isOver)
    }

    @Test
    fun overeatingTakesFromTheMarginAndFinishedWeeksAreSettled() {
        val intakes = (0L..6L).map { day(it, 2300.0) }
        val week = WeeklyBudget.summarize(intakes, goal, date = monday, today = monday.plusDays(10))
        assertTrue(week.isOver)
        assertEquals(-2100.0, week.margin, 1e-9)
        assertEquals(0, week.daysLeft)
        assertNull(week.paceForRest)

        // A big debt never asks for negative calories.
        val early = WeeklyBudget.summarize(listOf(day(0, 9000.0)), goal, date = monday, today = monday.plusDays(1))
        assertEquals(0.0, early.todayWithMargin, 1e-9)
    }

    @Test
    fun weeksStartOnMonday() {
        assertEquals(monday, WeeklyBudget.weekStart(LocalDate.of(2026, 10, 25)))
        assertEquals(monday, WeeklyBudget.weekStart(monday))
        val weeks = WeeklyBudget.lastWeeks(listOf(day(-3, 1500.0)), goal, today = monday.plusDays(2), count = 2)
        assertEquals(listOf(monday, monday.minusWeeks(1)), weeks.map { it.start })
        assertEquals(500.0, weeks[1].margin, 1e-9)
    }
}
