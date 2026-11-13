package dev.tobins.mealplanner.api

import kotlinx.serialization.Serializable

enum class Sex { MALE, FEMALE }

/** How active a typical day is; [factor] multiplies the resting energy use. */
enum class ActivityLevel(val factor: Double) {
    SEDENTARY(1.2),
    LIGHT(1.375),
    MODERATE(1.55),
    ACTIVE(1.725),
}

/** What the daily targets are derived from. */
@Serializable
data class Profile(
    val weightKg: Double,
    val heightCm: Double,
    val ageYears: Int,
    val sex: Sex,
    val activity: ActivityLevel,
    /** Intended weight loss per week; 0 maintains weight. */
    val goalKgPerWeek: Double = 0.5,
    /** Set to use a fixed calorie target instead of the suggested one. */
    val kcalOverride: Int? = null,
    /** Set to use a fixed protein target instead of the suggested one. */
    val proteinOverrideG: Int? = null,
    /** Cuisines, flavours or dishes to lean towards when writing recipes, e.g. "Korean", "spicy". */
    val likes: List<String> = emptyList(),
    /** Ingredients never to plan or use, e.g. "mushroom"; matched as words in ingredient names. */
    val dislikes: List<String> = emptyList(),
)

@Serializable
data class DailyTargets(val kcal: Int, val proteinG: Int)

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
