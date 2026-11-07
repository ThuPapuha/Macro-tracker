package io.github.thupapuha.macrotracker.core.gym

import java.time.LocalDate

/** Visual estimate of the extra muscle creatine from logged daily use, not a measured body level.
 * Dose changes the illustrative time to the display maximum; without use the extra level fades
 * over about six weeks. Only the last 90 days matter to either transition.
 */
object Creatine {
    val doses = listOf(3, 5, 10, 20)

    fun daysToFull(doseGrams: Int): Double = when (doseGrams) {
        3 -> 35.0
        10 -> 18.0
        20 -> 6.0
        else -> 28.0
    }

    fun estimatedLevel(takenDays: Set<LocalDate>, today: LocalDate, dosesByDay: Map<LocalDate, Int> = emptyMap()): Double {
        var level = 0.0
        for (offset in 89L downTo 0L) {
            val day = today.minusDays(offset)
            level = if (day in takenDays) {
                (level + 1.0 / daysToFull(dosesByDay[day] ?: 5)).coerceAtMost(1.0)
            } else {
                (level - 1.0 / 42).coerceAtLeast(0.0)
            }
        }
        return level
    }
}
