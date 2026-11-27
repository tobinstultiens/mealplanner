package dev.tobins.mealplanner.app.ingredients

import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.FoundProduct
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.LookupProblem
import dev.tobins.mealplanner.api.MappedLine
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.PackSize
import dev.tobins.mealplanner.api.Pantry
import dev.tobins.mealplanner.api.ProductHit
import dev.tobins.mealplanner.api.ProductLookup
import dev.tobins.mealplanner.api.ProductSearch
import dev.tobins.mealplanner.app.FakeBackend
import dev.tobins.mealplanner.app.FakeBackend.Companion.AH_WITHOUT_TABLE
import dev.tobins.mealplanner.app.FakeBackend.Companion.OFF_WITHOUT_BASIS
import dev.tobins.mealplanner.app.FakeBackend.Companion.OFF_WITHOUT_VALUES
import dev.tobins.mealplanner.nutrition.NutritionCalculator
import dev.tobins.mealplanner.nutrition.Salt
import dev.tobins.mealplanner.recipes.IngredientLine
import kotlinx.serialization.decodeFromString

/* The ingredients' part of [FakeBackend]: the catalogue's ingredients and links, the pantry, foods and products. */

internal fun FakeBackend.ingredient(id: Long, name: String, staple: Boolean = false) = Ingredient(id, name, null, null, null, null, null, isStaple = staple)

internal fun FakeBackend.nevo(code: Int, en: String, nl: String, kcal: Double, protein: Double, basis: FoodBasis = FoodBasis.PER_100_G) =
    Food(code, nl, en, null, "Food", basis, Nutrition(kcal, protein, 5.0, 1.0, 3.0, 1.0, 1.0, 50.0))

internal fun FakeBackend.foodsMatching(query: String): List<Food> =
    foods.filter { query.lowercase() in it.nameEn.lowercase() || query.lowercase() in it.nameNl.lowercase() }

/** AH's drink yoghurts as the product search found them on 26 November 2026, a multi-pack among them. */
internal val FakeBackend.productHits get() = listOf(
    ProductHit(61758, "Optimel Drinkyoghurt framboos", "Optimel", "1 l"),
    ProductHit(141708, "AH Drinkyoghurt framboossmaak", "AH", "1 l"),
    ProductHit(585938, "Optimel Proteine extra framboos aardbei 4-pack", "Optimel", "4 stuks", packOf = 4),
)

/** The first of [productHits] as AH's table gives it, and by its barcode. */
internal val FakeBackend.optimel get() = FoundProduct(
    "ah:61758", "Optimel Drinkyoghurt framboos", "Optimel", FoodBasis.PER_100_ML,
    Nutrition(31.0, 3.1, 0.0, 0.0, 3.8, 3.2, null, Salt.sodiumMg(0.08)), pack = PackSize(ml = 1000.0),
)

/** `GET /api/products/search`: the [productHits], as AH found them for "drinkyoghurt", for any text; nothing for blank. */
internal fun FakeBackend.productSearch(text: String): ProductSearch =
    if (!productSourceAnswers) ProductSearch(problems = listOf(LookupProblem.AH_FAILED))
    else ProductSearch(productHits.takeIf { text.isNotBlank() }.orEmpty())

/**
 * `GET /api/products/ah/{id}` and `GET /api/products/barcode/{code}`: [optimel] by its id or barcode; by made-up
 * barcodes, a product of AH without a table, one of Open Food Facts without values (per portion only) and one of Open
 * Food Facts whose values do not say per what; nothing else.
 */
internal fun FakeBackend.productLookup(by: String, key: String): ProductLookup = when {
    !productSourceAnswers -> ProductLookup(problems = listOf(LookupProblem.AH_FAILED))
    (by == "ah" && key == "61758") || (by == "barcode" && key == "8712800035626") -> ProductLookup(optimel)
    by == "barcode" && key == AH_WITHOUT_TABLE ->
        ProductLookup(FoundProduct("ah:609144", "Optimel Proteine extra 4-pack", "Optimel", pack = PackSize(ml = 330.0, single = true), packOf = 4), listOf(LookupProblem.AH_NO_TABLE))
    by == "barcode" && key == OFF_WITHOUT_VALUES -> ProductLookup(FoundProduct("off:$key", "Cola zero", "Brand", pack = PackSize(ml = 330.0)))
    by == "barcode" && key == OFF_WITHOUT_BASIS ->
        ProductLookup(FoundProduct("off:$key", "Snack bar", "Brand", per100 = Nutrition(380.0, 20.0, 15.0, 6.0, 40.0, 20.0, null, Salt.sodiumMg(0.3))))
    else -> ProductLookup()
}

/** `POST /api/ingredients`: one more of the catalogue, for the NEVO food it names, or a product with its label and portions. */
internal fun FakeBackend.createIngredient(sent: String): Ingredient {
    changes += "POST /api/ingredients $sent"
    val made = ingredientOf(catalogue.maxOf { it.id } + 1, json.decodeFromString<IngredientInput>(sent))
    catalogue += made
    return made
}

/**
 * `PUT /api/ingredients/{id}`: the ingredient as sent, its portions as they were when it sends none; one it no longer
 * has is a removed one, as the server keeps it.
 */
internal fun FakeBackend.changeIngredient(id: Long, sent: String): Ingredient {
    changes += "PUT /api/ingredients/$id $sent"
    val before = catalogue.single { it.id == id }
    val input = json.decodeFromString<IngredientInput>(sent)
    val portions = input.portions ?: before.portions
    val names = portions.map { it.name.lowercase() }
    val removed = (before.removedPortions + before.portions.map { it.name }).filter { it.lowercase() !in names }.distinctBy { it.lowercase() }
    val changed = ingredientOf(id, input).copy(portions = portions, removedPortions = removed)
    catalogue.replaceAll { if (it.id == id) changed else it }
    return changed
}

internal fun FakeBackend.ingredientOf(id: Long, input: IngredientInput) = Ingredient(
    id, input.name, input.nevoCode?.let { code -> foods.single { it.nevoCode == code } }, input.gramsPerPiece, input.gramsPerMl,
    input.category, input.pricePerKg, input.isStaple, input.negligible, label = input.label, portions = input.portions.orEmpty(),
)

/** The account's links of texts (`/api/mappings`): the Dutch names it gave its ingredients, and salt in any form. */
internal val FakeBackend.links get() = mapOf("kipdijfilet" to 1L, "olijfolie" to 5L, "spinazie" to 8L, "kokosmelk" to 10L, "salt and pepper to taste" to 7L, "zout" to 7L)

/** The ingredient [line] means: the one it was saved with, else its text's link, else the one of that name. */
internal fun FakeBackend.resolve(line: IngredientLine): Ingredient? {
    val text = IngredientLine.coreText(line.ingredientText)
    val id = line.ingredientId ?: links[text] ?: catalogue.firstOrNull { it.name.equals(text, ignoreCase = true) }?.id
    return catalogue.firstOrNull { it.id == id }
}

internal fun FakeBackend.mapped(line: IngredientLine): MappedLine {
    val ingredient = resolve(line)
    val grams = ingredient?.takeUnless { it.negligible }?.let { NutritionCalculator.quantityOf(line, it)?.grams }
    return MappedLine(line, ingredient?.id, ingredient?.name, grams, ingredient?.negligible == true)
}

internal val FakeBackend.ingredients get() = listOf(
    ingredient(1, "Chicken thigh fillet"), ingredient(2, "Feta"), ingredient(3, "Greek yoghurt"),
    ingredient(4, "Minced beef"), ingredient(5, "Olive oil", staple = true), ingredient(6, "Rolled oats"),
    ingredient(7, "Salt", staple = true), ingredient(8, "Spinach"),
)

/**
 * Feta on hand by hand, how much not known; what is left of the chicken bought ahead last week and
 * of the yoghurt and oats stocked up; the minced beef bought ahead for next week, untouched. The
 * yoghurt was stocked up in two weeks.
 */
internal val FakeBackend.pantry get() = Pantry(
    staples = ingredients.filter { it.isStaple },
    onHand = ingredients.filter { it.id in setOf(1L, 2L, 3L, 4L, 6L) },
    onHandGrams = mapOf(1L to 300.0, 3L to 650.0, 4L to 1000.0, 6L to 200.0),
    onHandFrom = mapOf(1L to "2026-11-09", 3L to "2026-11-16", 4L to "2026-11-23", 6L to "2026-11-16"),
    onHandWeeks = mapOf(1L to listOf("2026-11-09"), 3L to listOf("2026-11-16", "2026-11-23"), 4L to listOf("2026-11-23"), 6L to listOf("2026-11-16")),
)

/** The catalogue's ingredients by id, with an egg the screens' catalogue does not list (so the pantry's picture stays as it is). */
internal val FakeBackend.names get() = (ingredients + Ingredient(9, "Egg", null, 60.0, null, null, null, isStaple = false)).associateBy { it.id }
