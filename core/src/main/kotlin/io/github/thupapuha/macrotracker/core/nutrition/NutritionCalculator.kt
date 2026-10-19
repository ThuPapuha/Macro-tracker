package io.github.thupapuha.macrotracker.core.nutrition

import io.github.thupapuha.macrotracker.core.model.MacroTargets
import kotlin.math.max
import kotlin.math.roundToInt

enum class Sex(val label: String) { MALE("Hombre"), FEMALE("Mujer") }

enum class ActivityLevel(val label: String, val factor: Double) {
    SEDENTARY("Sedentario (poco o nada de ejercicio)", 1.2),
    LIGHT("Ligero (1-3 días por semana)", 1.375),
    MODERATE("Moderado (3-5 días por semana)", 1.55),
    HIGH("Alto (6-7 días por semana)", 1.725),
    VERY_HIGH("Muy alto (doble sesión o trabajo físico)", 1.9),
}

enum class Goal(val label: String, val calorieFactor: Double, val proteinPerKg: Double) {
    LOSE("Perder grasa", 0.8, 2.0),
    MAINTAIN("Mantener peso", 1.0, 1.6),
    GAIN("Ganar músculo", 1.1, 1.8),
}

data class BodyProfile(
    val sex: Sex,
    val age: Int,
    val weightKg: Double,
    val heightCm: Double,
    val activity: ActivityLevel,
    val goal: Goal,
)

object NutritionCalculator {
    /** Basal metabolic rate (Mifflin-St Jeor), kcal/day. */
    fun bmr(p: BodyProfile): Double {
        val base = 10 * p.weightKg + 6.25 * p.heightCm - 5 * p.age
        return base + if (p.sex == Sex.MALE) 5 else -161
    }

    fun tdee(p: BodyProfile): Double = bmr(p) * p.activity.factor

    fun targets(p: BodyProfile): MacroTargets {
        require(p.age in 10..100) { "Edad fuera de rango" }
        require(p.weightKg in 25.0..350.0) { "Peso fuera de rango" }
        require(p.heightCm in 100.0..250.0) { "Altura fuera de rango" }

        val bmr = bmr(p)
        val tdee = tdee(p)
        val calories = (tdee * p.goal.calorieFactor).roundToInt()
        val protein = (p.weightKg * p.goal.proteinPerKg).roundToInt()
        // At least 25% of energy from fat, and never below 0.6 g/kg.
        val fat = max(calories * 0.25 / 9, p.weightKg * 0.6).roundToInt()
        val carbs = max(0, ((calories - protein * 4 - fat * 9) / 4.0).roundToInt())

        return MacroTargets(
            calories = calories.toDouble(),
            protein = protein.toDouble(),
            carbs = carbs.toDouble(),
            fat = fat.toDouble(),
            note = "Calculado con Mifflin-St Jeor: metabolismo basal ${bmr.roundToInt()} kcal, " +
                "gasto diario ${tdee.roundToInt()} kcal (${p.activity.label.lowercase()}). " +
                "Objetivo: ${p.goal.label.lowercase()}.",
        )
    }
}
