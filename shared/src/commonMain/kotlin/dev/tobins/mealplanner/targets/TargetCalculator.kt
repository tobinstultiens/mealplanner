package dev.tobins.mealplanner.targets

import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.Sex
import kotlin.math.roundToInt

object TargetCalculator {
    /** Energy in a kilogram of body fat, the usual rule of thumb. */
    private const val KCAL_PER_KG = 7700.0

    /** Protein per kilogram of body weight that helps keep muscle while losing weight. */
    private const val PROTEIN_G_PER_KG = 1.8

    /** Resting energy use by the Mifflin-St Jeor equation, in kcal per day. */
    fun restingKcal(profile: Profile): Double {
        val base = 10 * profile.weightKg + 6.25 * profile.heightCm - 5 * profile.ageYears
        return base + if (profile.sex == Sex.MALE) 5 else -161
    }

    /** Calories per day that keep the current weight. */
    fun maintenanceKcal(profile: Profile): Int = (restingKcal(profile) * profile.activity.factor).roundToInt()

    /**
     * Daily targets for the profile's goal, ignoring its overrides. The calorie target
     * never goes below [minimumKcal]: lower intakes should not be planned without
     * medical guidance.
     */
    fun suggest(profile: Profile): DailyTargets {
        val deficit = profile.goalKgPerWeek * KCAL_PER_KG / 7
        val kcal = (maintenanceKcal(profile) - deficit).roundToInt().coerceAtLeast(minimumKcal(profile.sex))
        return DailyTargets(kcal = roundTo(kcal, 10), proteinG = roundTo((profile.weightKg * PROTEIN_G_PER_KG).roundToInt(), 5))
    }

    /** The suggestion with the profile's overrides applied. */
    fun targets(profile: Profile): DailyTargets = suggest(profile).let {
        DailyTargets(kcal = profile.kcalOverride ?: it.kcal, proteinG = profile.proteinOverrideG ?: it.proteinG)
    }

    fun minimumKcal(sex: Sex): Int = if (sex == Sex.MALE) 1500 else 1200

    /** Reasons the profile cannot be used; empty when it is fine. */
    fun problems(profile: Profile): List<String> = buildList {
        if (profile.weightKg !in 30.0..300.0) add("weightKg must be between 30 and 300")
        if (profile.heightCm !in 120.0..230.0) add("heightCm must be between 120 and 230")
        if (profile.ageYears !in 18..100) add("ageYears must be between 18 and 100")
        if (profile.goalKgPerWeek !in 0.0..1.0) add("goalKgPerWeek must be between 0 and 1")
        profile.kcalOverride?.let { if (it !in 1000..6000) add("kcalOverride must be between 1000 and 6000") }
        profile.proteinOverrideG?.let { if (it !in 20..400) add("proteinOverrideG must be between 20 and 400") }
    }

    private fun roundTo(value: Int, step: Int): Int = (value.toDouble() / step).roundToInt() * step
}
