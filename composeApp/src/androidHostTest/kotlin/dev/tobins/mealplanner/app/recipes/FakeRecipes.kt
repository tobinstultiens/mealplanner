package dev.tobins.mealplanner.app.recipes

import dev.tobins.mealplanner.api.AddOn
import dev.tobins.mealplanner.api.AddedInput
import dev.tobins.mealplanner.api.ManyAddedInput
import dev.tobins.mealplanner.api.ManyRotationInput
import dev.tobins.mealplanner.api.MappedLine
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.RecipeCost
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeLineInput
import dev.tobins.mealplanner.api.RecipeNutrition
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.RecipeTopping
import dev.tobins.mealplanner.api.RotationInput
import dev.tobins.mealplanner.api.StandardInput
import dev.tobins.mealplanner.api.StandardSwitch
import dev.tobins.mealplanner.api.ToppingsInput
import dev.tobins.mealplanner.app.FakeBackend
import dev.tobins.mealplanner.app.FakeBackend.Refusal
import dev.tobins.mealplanner.app.ingredients.mapped
import dev.tobins.mealplanner.app.ingredients.names
import dev.tobins.mealplanner.app.ingredients.resolve
import dev.tobins.mealplanner.nutrition.NutritionCalculator
import dev.tobins.mealplanner.recipes.HugoRecipeParser
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.recipes.toIngredientLine
import dev.tobins.mealplanner.units.Dimension
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/* The recipes' part of [FakeBackend]: the recipes of the account and the bank, their detail, saving and changes. */

/** Sets how long recipe [id] keeps as another device would, without the app asking. */
internal fun FakeBackend.keepElsewhere(id: Long, keeps: RecipeKeeps) {
    rows.replaceAll { if (it.id == id) it.copy(keepsDays = keeps.days, keepsVacuumDays = keeps.vacuumDays) else it }
}

/**
 * With [writing], the chicken thighs as taken from the cooking site: two groups, tags, steps, slots and keeps, its lines
 * with no ingredient of their own but what their texts mean, one (the coriander) meaning nothing.
 */
internal fun FakeBackend.takenStew(): RecipeDetail {
    val text = "```ingredients\n# Stew\n700 | g | chicken thigh fillet\n400 | ml | coconut milk\n300 | g | spinach\n1 | lemon | juiced\n" +
        "1 | bunch | coriander\n# To serve\n| | salt and pepper to taste\n```"
    val lines = HugoRecipeParser().parse("x.md", text).ingredients
    val nutrition = NutritionCalculator.perServing(lines.map { it to resolve(it) }, 2)
    return recipeDetail(4).copy(summary = row(4).copy(nutrition = nutrition), ingredients = lines.map(::mapped))
}

/** A recipe's quick add-ons: its toppings, then Greek yoghurt, added to three of its meals before. */
internal fun FakeBackend.addOns(recipeId: Long): List<AddOn> =
    toppings[recipeId].orEmpty().map { AddOn(it.ingredientId, it.name, it.grams, it.gramsPerPiece, topping = true) } +
        AddOn(3, "Greek yoghurt", 50.0, times = 3)

internal fun FakeBackend.summary(
    id: Long,
    title: String,
    section: String,
    kcal: Double,
    protein: Double,
    rating: Int?,
    tags: List<String> = emptyList(),
    photo: Boolean = false,
    inRotation: Boolean = true,
    added: Boolean = true,
    removedFromSite: Boolean = false,
    /** Ingredients left out of the numbers: the recipe is incomplete until they are linked. */
    uncounted: List<String> = emptyList(),
    source: RecipeSource = RecipeSource.COOKING_SITE,
    kind: RecipeKind = RecipeKind.MEAL,
    /** Days it keeps once made, in a box and vacuum sealed (issue 234). */
    keeps: Pair<Int?, Int?> = null to null,
) = RecipeSummary(
    id = id,
    title = title,
    section = section,
    tags = tags,
    servings = 2,
    source = source,
    kind = kind,
    plannable = true,
    nutrition = RecipeNutrition(
        Nutrition(kcal, protein, 22.0, 6.0, 48.0, 7.0, 9.0, 620.0),
        complete = uncounted.isEmpty(), approximate = false, uncounted = uncounted,
    ),
    mealSlots = if (kind == RecipeKind.MEAL) listOf(MealSlot.LUNCH, MealSlot.DINNER) else emptyList(),
    rating = rating,
    cost = RecipeCost(2.40, 2.10),
    imageUrl = if (photo) "/api/recipes/$id/image?v=0a1b2c" else null,
    added = added,
    inRotation = inRotation,
    removedFromSite = removedFromSite,
    keepsDays = keeps.first,
    keepsVacuumDays = keeps.second,
)

internal fun FakeBackend.mine() = rows.filter { it.added }

internal fun FakeBackend.bank() = rows.filter { !it.added }

/** The recipes that are planned in the sample week: the server refuses to put them back. */
internal val FakeBackend.planned get() = setOf(2L, 4L, 5L)

/** The recipe pinned as the breakfast routine (see `/api/routines`): it can neither leave the rotation nor my recipes. */
internal val FakeBackend.routine get() = 1L

internal fun FakeBackend.row(id: Long) = rows.first { it.id == id }

private val flags = setOf("added", "inRotation", "removedFromSite")

/** [this] as an older server sends recipes: without the fields of mine, the rotation and the site. */
private fun JsonElement.withoutFlags(): JsonElement = when (this) {
    is JsonArray -> JsonArray(map { it.withoutFlags() })
    // A recipe's detail holds its summary; a summary is the object with the flags.
    is JsonObject -> when {
        "summary" in this -> JsonObject(mapValues { (key, value) -> if (key == "summary") value.withoutFlags() else value })
        else -> JsonObject(filterKeys { it !in flags })
    }
    else -> this
}

internal fun FakeBackend.recipesJson(list: List<RecipeSummary>): String =
    json.encodeToJsonElement(list).let { if (olderServer) it.withoutFlags() else it }.toString()

internal fun FakeBackend.detailJson(id: Long): String =
    json.encodeToJsonElement(detailOf(id)).let { if (olderServer) it.withoutFlags() else it }.toString()

/** Recipe [id] as it is now: as last saved by the editor, else the sample (the stew with its groups when [writing]). */
internal fun FakeBackend.detailOf(id: Long): RecipeDetail =
    saved[id]?.copy(summary = row(id)) ?: if (writing && id == 4L) takenStew() else recipeDetail(id)

/** Makes recipe [id] one with no number of servings, as a cooking-site file without a `servings:` line is. */
internal fun FakeBackend.withoutServings(id: Long) {
    rows.replaceAll { if (it.id == id) it.copy(servings = null) else it }
}

/**
 * Saves [input] as recipe [id], or as a new one of mine, as the server does: each line read as it reads them, with the
 * ingredient it means ([resolve]), and the nutrition worked out from them. A save that changes nothing does not count
 * as a change in the app (`edited`), as on the server.
 */
internal fun FakeBackend.save(id: Long?, input: RecipeInput): RecipeDetail {
    val before = id?.let(::detailOf)
    val unchanged = before != null && input == asInput(before).copy(ingredients = input.ingredients)
    // Writes nothing, so a recipe without servings stays without.
    if (unchanged) return before!!
    val lines = input.lines!!.mapNotNull { it.toIngredientLine() }
    val nutrition = NutritionCalculator.perServing(lines.map { it to resolve(it) }, input.servings).takeIf { lines.any { it.amount != null } }
    val newId = id ?: (rows.maxOf { it.id } + 1)
    val base = before?.summary ?: summary(newId, input.title, "", 0.0, 0.0, null, source = RecipeSource.MANUAL)
    val summary = base.copy(
        title = input.title, tags = input.tags, servings = input.servings, mealSlots = input.mealSlots, nutrition = nutrition,
        edited = base.edited || !unchanged,
    )
    if (id == null) rows += summary else rows.replaceAll { if (it.id == id) summary else it }
    // The unit each ingredient was last saved in, as the server lists it (issue 346).
    input.lines.orEmpty().filter { it.ingredientId != null }.forEach { line ->
        catalogue.replaceAll { if (it.id == line.ingredientId) it.copy(lastUnit = line.unit) else it }
    }
    return RecipeDetail(summary, lines.map(::mapped), input.instructions, emptyList()).also { saved[newId] = it }
}

/** [recipe] as the body that saves it again: each line's own fields, its own ingredient among them. */
internal fun FakeBackend.asInput(recipe: RecipeDetail) = RecipeInput(
    recipe.summary.title, recipe.summary.servings ?: 1, instructions = recipe.instructions, tags = recipe.summary.tags,
    mealSlots = recipe.summary.mealSlots,
    lines = recipe.ingredients.map { RecipeLineInput(it.line.amount, it.line.unit, it.line.item, it.line.ingredientId, it.line.group) },
)

/**
 * The changes of recipes the app can make, answering as the server does, refusals included: a recipe
 * that is planned or the routine cannot be put back (409), nor can one made in the app, which is
 * deleted instead. [refusing] makes every change fail with its sentence. Many at once is all or nothing.
 */
internal fun FakeBackend.changeRecipes(method: String, parts: List<String>, sent: String): String? {
    // Looking lines up saves nothing.
    if (method == "POST" && parts.getOrNull(2) == "lines") {
        // As the server: a line with no amount, unit and text refuses the whole request.
        val asked = json.decodeFromString<List<RecipeLineInput>>(sent).map { it.toIngredientLine() }
        val empty = asked.indexOf(null)
        if (empty >= 0) throw Refusal(400, "Line ${empty + 1}: has nothing in it")
        return json.encodeToString(asked.filterNotNull().map(::mapped))
    }
    changes += "$method /${parts.joinToString("/")} $sent"
    if (method == "DELETE" && parts.size == 3) {
        rows.removeAll { it.id == parts[2].toLong() }
        return ""
    }
    val editing = method == "PUT" && parts.size == 3 && parts[2].toLongOrNull() != null
    // Writing, a recipe is saved as the server saves it; else the answer is always the sample recipe, which the screen only passes on.
    if (writing && (method == "POST" && parts.size == 2 || editing)) return json.encodeToString(save(parts.getOrNull(2)?.toLong(), json.decodeFromString<RecipeInput>(sent)))
    if (method == "POST" || editing) return detailJson(parts.getOrNull(2)?.toLong() ?: 4)
    if (olderServer || method != "PUT") return null
    refusing?.let { throw Refusal(409, it) }
    val many = parts.size == 3
    return when (parts.last()) {
        "added" -> if (many) {
            val input = json.decodeFromString<ManyAddedInput>(sent)
            setAdded(input.ids, input.added, input.inRotation)
            recipesJson(mine())
        } else {
            val input = json.decodeFromString<AddedInput>(sent)
            val id = parts[2].toLong()
            setAdded(listOf(id), input.added, input.inRotation)
            detailJson(id)
        }
        "toppings" -> {
            val id = parts[2].toLong()
            toppings = toppings + (id to json.decodeFromString<ToppingsInput>(sent).toppings.map { input ->
                val ingredient = names.getValue(input.ingredientId)
                RecipeTopping(ingredient.id, ingredient.name, input.grams, ingredient.gramsPerPiece)
            })
            detailJson(id)
        }
        "keeps" -> {
            keepsAnswer?.await(10, java.util.concurrent.TimeUnit.SECONDS)
            val id = parts[2].toLong()
            keepElsewhere(id, json.decodeFromString<RecipeKeeps>(sent))
            detailJson(id)
        }
        // Marking a standard meal makes its ingredients staples; one line is linked to nothing.
        "standard" -> {
            val id = parts[2].toLong()
            val standard = json.decodeFromString<StandardInput>(sent).standard
            rows.replaceAll { if (it.id == id) it.copy(standardMeal = standard) else it }
            val switch = if (standard) StandardSwitch(recipeDetail(id), listOf("Chicken thigh fillet", "Spinach"), listOf("juiced")) else StandardSwitch(recipeDetail(id))
            json.encodeToString(switch)
        }
        "rotation" -> if (many) {
            val input = json.decodeFromString<ManyRotationInput>(sent)
            setRotation(input.ids, input.inRotation)
            recipesJson(mine())
        } else {
            val id = parts[2].toLong()
            setRotation(listOf(id), json.decodeFromString<RotationInput>(sent).inRotation)
            detailJson(id)
        }
        else -> null
    }
}

internal fun FakeBackend.setAdded(ids: List<Long>, added: Boolean, inRotation: Boolean) {
    val targets = ids.map(::row)
    if (!added) {
        targets.forEach { recipe ->
            when {
                recipe.source != RecipeSource.COOKING_SITE ->
                    throw Refusal(409, "“${recipe.title}” was made in the app, so it has no bank to go back to: delete it instead")
                recipe.edited -> throw Refusal(409, "“${recipe.title}” was changed in the app, so it is no longer the cooking site's: delete it instead")
                recipe.id == routine -> throw Refusal(409, "“${recipe.title}” is the breakfast routine, so it cannot go back to the bank")
                recipe.id in planned ->
                    throw Refusal(409, "“${recipe.title}” is planned on Fri 20 Nov, so it cannot go back to the bank yet")
            }
        }
    }
    rows.replaceAll { if (it.id !in ids) it else if (added) it.copy(added = true, inRotation = inRotation) else it.copy(added = false, inRotation = false) }
}

internal fun FakeBackend.setRotation(ids: List<Long>, inRotation: Boolean) {
    val targets = ids.map(::row)
    if (!inRotation) {
        targets.firstOrNull { it.id == routine }?.let { throw Refusal(409, "“${it.title}” is the breakfast routine, so it cannot leave the rotation") }
    }
    rows.replaceAll { if (it.id in ids) it.copy(inRotation = inRotation) else it }
}

internal fun FakeBackend.recipeDetail(id: Long) = RecipeDetail(
    summary = row(id),
    ingredients = listOf(
        line(700.0, "g", Dimension.WEIGHT, "chicken thigh fillet"),
        line(400.0, "ml", Dimension.VOLUME, "coconut milk"),
        line(300.0, "g", Dimension.WEIGHT, "spinach"),
        line(1.0, "lemon", Dimension.COUNT, "juiced"),
        line(null, null, null, "salt and pepper to taste"),
    ),
    toppings = toppings[id].orEmpty(),
    instructions = "Season the chicken and brown it in a heavy pan.\n\n" +
        "1. Add the coconut milk and the lemon juice.\n2. Cover and cook gently for 40 minutes.\n3. Stir in the spinach until it wilts.\n\n" +
        "Serve with rice or flatbread.",
    problems = emptyList(),
)

internal fun FakeBackend.line(amount: Double?, unit: String?, dimension: Dimension?, item: String) = MappedLine(
    IngredientLine(amount, unit, dimension, if (dimension == Dimension.COUNT) null else amount, item, null),
    ingredientId = 1,
    ingredientName = item.substringBefore(" to taste"),
    grams = amount.takeIf { dimension != Dimension.COUNT },
)
