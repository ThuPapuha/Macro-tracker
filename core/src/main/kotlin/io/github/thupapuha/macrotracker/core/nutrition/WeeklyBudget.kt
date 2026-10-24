package io.github.thupapuha.macrotracker.core.nutrition

import io.github.thupapuha.macrotracker.core.model.MacroTargets
import io.github.thupapuha.macrotracker.core.model.Macros
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** What was eaten on a day; [entries] is how many foods were logged (0 = nothing logged). */
data class DayIntake(val date: LocalDate, val totals: Macros, val entries: Int)

/** How a past day counts for the weekly margin. */
enum class DayStatus {
    /** Logged: its difference with the goal goes to the margin. */
    COUNTED,

    /** Nothing logged: counts as if the goal was met. */
    NOT_LOGGED,

    /** So few calories that the log is probably incomplete: counts as if the goal was met. */
    INCOMPLETE,

    /** Today or later: not settled yet. */
    PENDING,
}

data class WeekDay(val date: LocalDate, val totals: Macros, val status: DayStatus)

/**
 * The week's budget (the daily goal × 7, Monday to Sunday). Calories not eaten on past days build a
 * margin that can be used on the days left; eating more than the goal takes from it.
 */
data class WeekSummary(
    val start: LocalDate,
    val today: LocalDate,
    /** Daily goals. */
    val daily: MacroTargets,
    val days: List<WeekDay>,
) {
    val end: LocalDate get() = start.plusDays(6)

    /** The daily goals × 7. */
    val weekly: MacroTargets
        get() = MacroTargets(daily.calories * 7, daily.protein * 7, daily.carbs * 7, daily.fat * 7)

    /** Everything eaten this week so far, today included. */
    val consumed: Macros get() = Macros.sum(days.map { it.totals })

    /**
     * Calories left unused on past days (positive) or eaten over the goal (negative). Days not logged
     * or barely logged do not count, so a forgotten dinner does not turn into "free" calories.
     */
    val margin: Double
        get() = days.filter { it.status == DayStatus.COUNTED }.sumOf { daily.calories - it.totals.calories }

    /** Days from today to Sunday (0 once the week is over). */
    val daysLeft: Int get() = days.count { it.status == DayStatus.PENDING }

    val skippedDays: List<WeekDay> get() = days.filter { it.status == DayStatus.INCOMPLETE }

    val isOver: Boolean get() = today.isAfter(end)

    /** Today's goal if all the margin is used today (never below zero). */
    val todayWithMargin: Double get() = (daily.calories + margin).coerceAtLeast(0.0)

    /** Daily calories for the days left that would meet the weekly budget exactly. */
    val paceForRest: Double?
        get() = if (daysLeft == 0) null else (daily.calories + margin / daysLeft).coerceAtLeast(0.0)

    /** Calories eaten on [today]. */
    val todayCalories: Double get() = days.firstOrNull { it.date == today }?.totals?.calories ?: 0.0
}

object WeeklyBudget {
    /** A logged day under this share of the goal is treated as an incomplete log. */
    const val INCOMPLETE_SHARE = 0.35

    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * Builds the summary of the week containing [date], from the [intakes] logged (any days; only the
     * ones in that week are used). [today] decides which days are already settled.
     */
    fun summarize(intakes: List<DayIntake>, daily: MacroTargets, date: LocalDate, today: LocalDate): WeekSummary {
        val start = weekStart(date)
        val byDate = intakes.associateBy { it.date }
        val days = (0L..6L).map { offset ->
            val day = start.plusDays(offset)
            val intake = byDate[day]
            val totals = intake?.totals ?: Macros.ZERO
            val status = when {
                !day.isBefore(today) -> DayStatus.PENDING
                intake == null || intake.entries == 0 -> DayStatus.NOT_LOGGED
                daily.calories > 0 && totals.calories < daily.calories * INCOMPLETE_SHARE -> DayStatus.INCOMPLETE
                else -> DayStatus.COUNTED
            }
            WeekDay(day, totals, status)
        }
        return WeekSummary(start, today, daily, days)
    }

    /** Summaries of the [count] weeks up to the one containing [today], newest first. */
    fun lastWeeks(intakes: List<DayIntake>, daily: MacroTargets, today: LocalDate, count: Int): List<WeekSummary> =
        (0 until count).map { summarize(intakes, daily, weekStart(today).minusWeeks(it.toLong()), today) }
}
