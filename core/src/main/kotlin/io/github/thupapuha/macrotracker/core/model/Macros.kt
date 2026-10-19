package io.github.thupapuha.macrotracker.core.model

import kotlinx.serialization.Serializable

/** Calories in kcal; protein, carbs and fat in grams. */
@Serializable
data class Macros(
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
) {
    operator fun plus(other: Macros) = Macros(
        calories = calories + other.calories,
        protein = protein + other.protein,
        carbs = carbs + other.carbs,
        fat = fat + other.fat,
    )

    operator fun times(factor: Double) = Macros(
        calories = calories * factor,
        protein = protein * factor,
        carbs = carbs * factor,
        fat = fat * factor,
    )

    companion object {
        val ZERO = Macros()

        fun sum(items: Iterable<Macros>): Macros = items.fold(ZERO, Macros::plus)

        /** Energy implied by the macros using the 4/4/9 kcal-per-gram rule. */
        fun caloriesFrom(protein: Double, carbs: Double, fat: Double): Double =
            protein * 4 + carbs * 4 + fat * 9
    }
}

/** Daily goals. [note] explains where they came from (document, calculator or manual). */
@Serializable
data class MacroTargets(
    val calories: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val note: String = "",
) {
    val macros: Macros get() = Macros(calories, protein, carbs, fat)

    /** A day counts as "on target" when calories land within ±10% of the goal. */
    fun isOnTarget(consumed: Macros): Boolean =
        calories > 0 && consumed.calories in (calories * 0.9)..(calories * 1.1)
}

enum class MealType(val label: String) {
    BREAKFAST("Desayuno"),
    LUNCH("Almuerzo"),
    DINNER("Cena"),
    SNACK("Snacks");

    companion object {
        fun fromName(name: String?): MealType = entries.firstOrNull { it.name == name } ?: SNACK

        fun forHour(hour: Int): MealType = when (hour) {
            in 5..10 -> BREAKFAST
            in 11..15 -> LUNCH
            in 19..23 -> DINNER
            else -> SNACK
        }
    }
}

/** Fraction of [target] reached, clamped to 0..1.5 so overshoots stay drawable. */
fun progressOf(consumed: Double, target: Double): Float =
    if (target <= 0) 0f else (consumed / target).coerceIn(0.0, 1.5).toFloat()
