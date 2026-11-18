package dev.tobins.mealplanner.targets

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.Sex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TargetCalculatorTest {
    private val profile = Profile(
        weightKg = 90.0, heightCm = 180.0, ageYears = 30, sex = Sex.MALE,
        activity = ActivityLevel.LIGHT, goalKgPerWeek = 0.5,
    )

    @Test
    fun restingEnergyFollowsMifflinStJeor() {
        // 10*90 + 6.25*180 - 5*30 + 5
        assertEquals(1880.0, TargetCalculator.restingKcal(profile))
        assertEquals(1880.0 - 166, TargetCalculator.restingKcal(profile.copy(sex = Sex.FEMALE)))
        assertEquals(2585, TargetCalculator.maintenanceKcal(profile))
    }

    @Test
    fun suggestionSubtractsTheDeficitAndSetsProteinByWeight() {
        // 2585 - 0.5*7700/7 = 2035, rounded to tens; 90 kg * 1.8 = 162, rounded to fives; 2585 * 14 / 1000 = 36.2
        assertEquals(DailyTargets(kcal = 2040, proteinG = 160, fibreG = 36), TargetCalculator.suggest(profile))
        assertEquals(2590, TargetCalculator.suggest(profile.copy(goalKgPerWeek = 0.0)).kcal)
    }

    @Test
    fun fibreFollowsWhatTheBodyNeedsNotTheDeficit() {
        // The same 36 g whatever the goal: it follows the 2585 kcal that keep the weight.
        assertEquals(36, TargetCalculator.suggest(profile.copy(goalKgPerWeek = 1.0)).fibreG)
        assertEquals(36, TargetCalculator.targets(profile.copy(kcalOverride = 1500)).fibreG)
        // A woman sitting most of the day: 1714 kcal resting * 1.2 = 2057 kcal, 28.8 g.
        assertEquals(29, TargetCalculator.suggest(profile.copy(sex = Sex.FEMALE, activity = ActivityLevel.SEDENTARY)).fibreG)
    }

    @Test
    fun calorieTargetHasAFloor() {
        val small = Profile(50.0, 155.0, 60, Sex.FEMALE, ActivityLevel.SEDENTARY, goalKgPerWeek = 1.0)
        assertEquals(1200, TargetCalculator.suggest(small).kcal)
        assertEquals(1500, TargetCalculator.suggest(small.copy(sex = Sex.MALE)).kcal)
    }

    @Test
    fun overridesReplaceTheSuggestionIndividually() {
        assertEquals(DailyTargets(1900, 160, 36), TargetCalculator.targets(profile.copy(kcalOverride = 1900)))
        assertEquals(DailyTargets(2040, 180, 36), TargetCalculator.targets(profile.copy(proteinOverrideG = 180)))
        assertEquals(DailyTargets(2040, 160, 45), TargetCalculator.targets(profile.copy(fibreOverrideG = 45)))
    }

    @Test
    fun implausibleProfilesAreRejected() {
        assertEquals(emptyList(), TargetCalculator.problems(profile))
        assertEquals(3, TargetCalculator.problems(profile.copy(weightKg = 5.0, ageYears = 12, goalKgPerWeek = 2.0)).size)
        assertTrue(TargetCalculator.problems(profile.copy(kcalOverride = 500)).single().startsWith("kcalOverride"))
        assertEquals(listOf("fibreOverrideG must be between 10 and 100"), TargetCalculator.problems(profile.copy(fibreOverrideG = 5)))
        assertEquals(emptyList(), TargetCalculator.problems(profile.copy(fibreOverrideG = 100)))
    }
}
