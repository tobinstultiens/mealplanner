// Frozen from v0.1.13 (bd9ebd8e6d679057d814bb48ea7186dbd3816907) by scripts/freeze-api.sh. Do not edit: freeze again after a release.
package dev.tobins.mealplanner.released.api

import dev.tobins.mealplanner.released.planner.PortionRange
import kotlinx.serialization.Serializable

enum class Sex { MALE, FEMALE }

/** How active a typical day is; [factor] multiplies the resting energy use. */
enum class ActivityLevel(val factor: Double) {
    SEDENTARY(1.2),
    LIGHT(1.375),
    MODERATE(1.55),
    ACTIVE(1.725),
}

/**
 * What the planner weighs most when it chooses between recipes that fit the day about equally well.
 * Each raises one part of the planner's score and lowers none: protein and fibre always count at least
 * as much as under [BALANCED], and calorie fit and variety are never changed by it.
 */
enum class PlanPriority { BALANCED, PROTEIN, FIBRE, PRICE }

/** What the daily targets are derived from. */
@Serializable
data class Profile(
    val weightKg: Double,
    val heightCm: Double,
    val ageYears: Int,
    val sex: Sex,
    val activity: ActivityLevel,
    /** Intended weight loss per week; 0 maintains weight. */
    val goalKgPerWeek: Double = DEFAULT_GOAL_KG_PER_WEEK,
    /** Set to use a fixed calorie target instead of the suggested one. */
    val kcalOverride: Int? = null,
    /** Set to use a fixed protein target instead of the suggested one. */
    val proteinOverrideG: Int? = null,
    /** Cuisines, flavours or dishes to lean towards when writing recipes, e.g. "Korean", "spicy". */
    val likes: List<String> = emptyList(),
    /** Ingredients never to plan or use, e.g. "mushroom"; matched as words in ingredient names. */
    val dislikes: List<String> = emptyList(),
    /**
     * The smallest and the largest meal the planner plans, in servings of the recipe, in quarter
     * servings ([PortionRange]). An app from before them saves a profile without them, which keeps
     * the stored range.
     */
    val minPortions: Double = PortionRange.DEFAULT_MIN,
    val maxPortions: Double = PortionRange.DEFAULT_MAX,
    /**
     * What the planner weighs most (issue 223). An app from before it saves a profile without it, which
     * keeps the stored priority. An app that does not know a later value reads it as the default and
     * would save that back, so a new value waits for an app release (README, "API compatibility").
     */
    val priority: PlanPriority = PlanPriority.BALANCED,
    /** Set to use a fixed fibre target instead of the suggested one; kept like [priority] by an older app. */
    val fibreOverrideG: Int? = null,
) {
    companion object {
        /** The weight loss a new profile starts from, which the profile form shows before anything is saved. */
        const val DEFAULT_GOAL_KG_PER_WEEK = 0.5
    }
}

@Serializable
data class DailyTargets(
    val kcal: Int,
    val proteinG: Int,
    /** Grams of fibre a day; null from a server before fibre counted, and then the planner leaves fibre out. */
    val fibreG: Int? = null,
)

/** Response of `GET /api/profile`. All fields are null until a profile is saved. */
@Serializable
data class ProfileState(
    val profile: Profile?,
    /** Computed from the profile, before overrides. */
    val suggested: DailyTargets?,
    /** What the planner uses: the suggestion with the profile's overrides applied. */
    val targets: DailyTargets?,
    /** Energy use at the current weight, i.e. the calorie level that maintains it. */
    val maintenanceKcal: Int?,
)
