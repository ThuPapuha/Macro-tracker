package io.github.thupapuha.macrotracker.core

import io.github.thupapuha.macrotracker.core.model.MacroTargets
import io.github.thupapuha.macrotracker.core.model.Macros
import io.github.thupapuha.macrotracker.core.model.MealType
import io.github.thupapuha.macrotracker.core.nutrition.ActivityLevel
import io.github.thupapuha.macrotracker.core.nutrition.BodyProfile
import io.github.thupapuha.macrotracker.core.nutrition.Goal
import io.github.thupapuha.macrotracker.core.nutrition.NutritionCalculator
import io.github.thupapuha.macrotracker.core.nutrition.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NutritionCalculatorTest {

    private val profile = BodyProfile(
        sex = Sex.MALE,
        age = 30,
        weightKg = 80.0,
        heightCm = 180.0,
        activity = ActivityLevel.MODERATE,
        goal = Goal.MAINTAIN,
    )

    @Test
    fun bmrMatchesMifflinStJeor() {
        // 10*80 + 6.25*180 - 5*30 + 5
        assertEquals(1780.0, NutritionCalculator.bmr(profile), 0.001)
        assertEquals(1780.0 - 166, NutritionCalculator.bmr(profile.copy(sex = Sex.FEMALE)), 0.001)
    }

    @Test
    fun macrosAddUpToCalories() {
        for (goal in Goal.entries) {
            val t = NutritionCalculator.targets(profile.copy(goal = goal))
            val fromMacros = Macros.caloriesFrom(t.protein, t.carbs, t.fat)
            assertTrue("$goal: ${t.calories} vs $fromMacros", abs(t.calories - fromMacros) <= 10)
        }
    }

    @Test
    fun losingFatHasFewerCaloriesAndMoreProtein() {
        val lose = NutritionCalculator.targets(profile.copy(goal = Goal.LOSE))
        val keep = NutritionCalculator.targets(profile)
        assertTrue(lose.calories < keep.calories)
        assertEquals(160.0, lose.protein, 0.001)
    }

    @Test
    fun onTargetWithinTenPercent() {
        val targets = MacroTargets(2000.0, 150.0, 200.0, 60.0)
        assertTrue(targets.isOnTarget(Macros(calories = 1850.0)))
        assertTrue(targets.isOnTarget(Macros(calories = 2200.0)))
        assertFalse(targets.isOnTarget(Macros(calories = 1700.0)))
        assertFalse(MacroTargets(0.0, 0.0, 0.0, 0.0).isOnTarget(Macros()))
    }

    @Test
    fun mealTypeForHour() {
        assertEquals(MealType.BREAKFAST, MealType.forHour(8))
        assertEquals(MealType.LUNCH, MealType.forHour(13))
        assertEquals(MealType.DINNER, MealType.forHour(21))
        assertEquals(MealType.SNACK, MealType.forHour(17))
        assertEquals(MealType.SNACK, MealType.fromName("nope"))
    }
}
