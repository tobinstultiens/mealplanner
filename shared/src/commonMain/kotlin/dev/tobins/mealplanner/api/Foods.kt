package dev.tobins.mealplanner.api

import kotlinx.serialization.Serializable

/** Nutrient amounts; null means the source has no value, which is not the same as zero. */
@Serializable
data class Nutrition(
    val kcal: Double?,
    val proteinG: Double?,
    val fatG: Double?,
    val saturatedFatG: Double?,
    val carbsG: Double?,
    val sugarG: Double?,
    val fibreG: Double?,
    val sodiumMg: Double?,
)

/** What quantity of a food its nutrient values describe. NEVO lists most drinks per 100 ml. */
enum class FoodBasis { PER_100_G, PER_100_ML }

/** A reference food from the NEVO dataset. */
@Serializable
data class Food(
    val nevoCode: Int,
    val nameNl: String,
    val nameEn: String,
    val synonym: String?,
    val groupEn: String,
    val basis: FoodBasis,
    /** Nutrients in 100 g or 100 ml of the food, depending on [basis]. */
    val per100: Nutrition,
)

/** State of the imported nutrition dataset. */
@Serializable
data class FoodDataset(
    val foodCount: Int,
    /** Source reference that must be shown wherever the data is used; null when nothing is imported. */
    val reference: String?,
)
