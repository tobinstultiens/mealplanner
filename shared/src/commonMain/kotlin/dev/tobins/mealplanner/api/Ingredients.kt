package dev.tobins.mealplanner.api

import dev.tobins.mealplanner.recipes.IngredientLine
import kotlinx.serialization.Serializable

/** An entry of the app's ingredient catalogue. */
@Serializable
data class Ingredient(
    val id: Long,
    val name: String,
    /** The NEVO food that supplies the nutrition; null when none is linked. */
    val food: Food?,
    /** Weight of one piece, clove, can and so on; needed for lines counted in pieces. */
    val gramsPerPiece: Double?,
    /** Density; needed for lines measured by volume. */
    val gramsPerMl: Double?,
    val category: String?,
    val pricePerKg: Double?,
    /** Always at home: never on the shopping list. */
    val isStaple: Boolean,
    /** Used in amounts that add next to nothing (herbs, spices, leaveners): left out of nutrition. */
    val negligible: Boolean = false,
)

/** Body for creating or replacing an ingredient. */
@Serializable
data class IngredientInput(
    val name: String,
    val nevoCode: Int? = null,
    val gramsPerPiece: Double? = null,
    val gramsPerMl: Double? = null,
    val category: String? = null,
    val pricePerKg: Double? = null,
    val isStaple: Boolean = false,
    val negligible: Boolean = false,
)

/**
 * A recipe line text that needs attention: either no ingredient is linked to it, or
 * one is ([linkedIngredient]) but the recipes count it in pieces and its weight per
 * piece is unknown.
 */
@Serializable
data class UnmappedText(
    /** The line's ingredient text in the normalised form aliases are keyed by (lower case, single spaces). */
    val text: String,
    /** How many active recipes use it. */
    val recipeCount: Int,
    val exampleRecipe: String,
    /** True when a line with this text is counted in pieces, so a weight per piece will be needed. */
    val countedInPieces: Boolean,
    /** Set when the text is already linked and only a weight per piece is missing. */
    val linkedIngredient: Ingredient? = null,
    val suggestedIngredients: List<Ingredient>,
    val suggestedFoods: List<Food>,
)

/**
 * Links [text] to an ingredient: an existing one ([ingredientId]), or the ingredient
 * for a NEVO food ([nevoCode]), created on the spot when the catalogue has none.
 */
@Serializable
data class MappingRequest(
    val text: String,
    val ingredientId: Long? = null,
    val nevoCode: Int? = null,
    /** Name for a newly created ingredient; defaults to the food's English name. */
    val name: String? = null,
    val gramsPerPiece: Double? = null,
    val gramsPerMl: Double? = null,
)

/** A recipe's ingredient line with what the catalogue knows about it. */
@Serializable
data class MappedLine(
    val line: IngredientLine,
    val ingredientId: Long?,
    val ingredientName: String?,
    /** Weight used for nutrition; null when the line is unmapped or cannot be converted. */
    val grams: Double?,
    /** True when the ingredient is deliberately left out of the nutrition. */
    val negligible: Boolean = false,
)

/** Nutrition of one serving, with how far it can be trusted. */
@Serializable
data class RecipeNutrition(
    val perServing: Nutrition,
    /** False when some lines with an amount could not be counted; see [uncounted]. */
    val complete: Boolean,
    /** True when a volume was converted with an assumed density of 1 g/ml. */
    val approximate: Boolean,
    /** Ingredient texts of the lines left out. */
    val uncounted: List<String>,
)

/** What is at home: [staples] are always there, [onHand] are perishables to use up. */
@Serializable
data class Pantry(val staples: List<Ingredient>, val onHand: List<Ingredient>)
