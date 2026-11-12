package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.UnmappedText
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.units.Dimension
import dev.tobins.mealplanner.server.db.Ingredient as IngredientRow

/** Thrown for requests that refer to something that does not exist; mapped to 404. */
class NotFoundException(message: String) : RuntimeException(message)

/** The ingredient catalogue and the remembered links from recipe line texts to it. */
class IngredientRepository(private val db: Database, private val foods: FoodRepository) {
    private val q = db.ingredientQueries

    fun list(): List<Ingredient> = q.selectAllIngredients().executeAsList().map { it.toIngredient() }

    fun find(id: Long): Ingredient? = q.selectIngredient(id).executeAsOneOrNull()?.toIngredient()

    fun create(input: IngredientInput): Ingredient = db.transactionWithResult { insert(input) }

    fun update(id: Long, input: IngredientInput): Ingredient = db.transactionWithResult {
        q.selectIngredient(id).executeAsOneOrNull() ?: throw NotFoundException("No ingredient with id $id")
        validate(input, selfId = id)
        q.updateIngredient(
            input.name.trim(), input.nevoCode?.toLong(), input.gramsPerPiece, input.gramsPerMl,
            input.category, input.pricePerKg, if (input.isStaple) 1 else 0, id,
        )
        q.selectIngredient(id).executeAsOne().toIngredient()
    }

    /** Looks up the ingredient each line's text is linked to. */
    fun resolver(): (IngredientLine) -> Ingredient? {
        val byId = list().associateBy { it.id }
        val aliases = q.selectAliases().executeAsList().associate { it.text to it.ingredientId }
        return { line -> aliases[normalise(line.ingredientText)]?.let(byId::get) }
    }

    /** Links a line text to an ingredient, creating the ingredient from a NEVO food if asked. */
    fun map(request: MappingRequest): Ingredient = db.transactionWithResult {
        val text = normalise(request.text)
        require(text.isNotEmpty()) { "text must not be blank" }
        val ingredient = when {
            request.ingredientId != null ->
                q.selectIngredient(request.ingredientId!!).executeAsOneOrNull()?.toIngredient()
                    ?: throw NotFoundException("No ingredient with id ${request.ingredientId}")
            request.nevoCode != null -> {
                val food = foods.find(request.nevoCode!!) ?: throw NotFoundException("No NEVO food ${request.nevoCode}")
                q.selectIngredientByNevoCode(food.nevoCode.toLong()).executeAsOneOrNull()?.toIngredient()
                    ?: insert(IngredientInput(name = request.name?.takeIf { it.isNotBlank() } ?: food.nameEn, nevoCode = food.nevoCode))
            }
            else -> throw IllegalArgumentException("Either ingredientId or nevoCode is required")
        }
        // Weights given with the mapping fill gaps; they never overwrite what is already known.
        val completed = ingredient.copy(
            gramsPerPiece = ingredient.gramsPerPiece ?: request.gramsPerPiece,
            gramsPerMl = ingredient.gramsPerMl ?: request.gramsPerMl,
        )
        if (completed != ingredient) {
            validate(completed.toInput(), selfId = completed.id)
            q.updateIngredient(
                completed.name, completed.food?.nevoCode?.toLong(), completed.gramsPerPiece, completed.gramsPerMl,
                completed.category, completed.pricePerKg, if (completed.isStaple) 1 else 0, completed.id,
            )
        }
        q.upsertAlias(text, completed.id)
        completed
    }

    fun unmap(text: String) = q.deleteAlias(normalise(text))

    /**
     * Line texts of active recipes that have an amount but no linked ingredient,
     * most-used first, each with suggestions from the catalogue and from NEVO.
     */
    fun unmapped(limit: Int = 50): List<UnmappedText> {
        val resolve = resolver()
        val catalogue = list()
        data class Use(val recipes: MutableSet<String> = linkedSetOf(), var counted: Boolean = false)

        val uses = linkedMapOf<String, Use>()
        for (row in q.selectActiveLines().executeAsList()) {
            val line = IngredientLine(row.amount, row.unit, row.dimension?.let(Dimension::valueOf), row.baseAmount, row.item, row.groupName)
            if (line.amount == null || resolve(line) != null) continue
            val use = uses.getOrPut(normalise(line.ingredientText)) { Use() }
            use.recipes += row.title
            if (line.dimension == Dimension.COUNT) use.counted = true
        }
        return uses.entries
            .sortedWith(compareByDescending<Map.Entry<String, Use>> { it.value.recipes.size }.thenBy { it.key })
            .take(limit)
            .map { (text, use) ->
                val terms = searchTerms(text)
                UnmappedText(
                    text = text,
                    recipeCount = use.recipes.size,
                    exampleRecipe = use.recipes.first(),
                    countedInPieces = use.counted,
                    suggestedIngredients = terms.firstNotNullOfOrNull { term ->
                        catalogue.filter { it.name.contains(term, ignoreCase = true) }.take(3).ifEmpty { null }
                    }.orEmpty(),
                    suggestedFoods = suggestFoods(terms),
                )
            }
    }

    private fun suggestFoods(terms: List<String>): List<Food> =
        terms.firstNotNullOfOrNull { foods.search(it, limit = 5).ifEmpty { null } }.orEmpty()

    private fun insert(input: IngredientInput): Ingredient {
        validate(input, selfId = null)
        q.insertIngredient(
            input.name.trim(), input.nevoCode?.toLong(), input.gramsPerPiece, input.gramsPerMl,
            input.category, input.pricePerKg, if (input.isStaple) 1 else 0,
        )
        return q.selectIngredient(q.lastIngredientId().executeAsOne()).executeAsOne().toIngredient()
    }

    private fun validate(input: IngredientInput, selfId: Long?) {
        require(input.name.isNotBlank()) { "name must not be blank" }
        require(listOfNotNull(input.gramsPerPiece, input.gramsPerMl, input.pricePerKg).all { it > 0 && it.isFinite() }) {
            "gramsPerPiece, gramsPerMl and pricePerKg must be positive"
        }
        input.nevoCode?.let { if (foods.find(it) == null) throw NotFoundException("No NEVO food $it") }
        q.selectIngredientByName(input.name.trim()).executeAsOneOrNull()?.let {
            require(it.id == selfId) { "An ingredient named '${it.name}' already exists" }
        }
    }

    private fun IngredientRow.toIngredient() = Ingredient(
        id = id,
        name = name,
        food = nevoCode?.let { foods.find(it.toInt()) },
        gramsPerPiece = gramsPerPiece,
        gramsPerMl = gramsPerMl,
        category = category,
        pricePerKg = pricePerKg,
        isStaple = isStaple != 0L,
    )

    private fun Ingredient.toInput() =
        IngredientInput(name, food?.nevoCode, gramsPerPiece, gramsPerMl, category, pricePerKg, isStaple)

    companion object {
        /** Alias key: lower case with single spaces. */
        fun normalise(text: String): String = text.trim().lowercase().replace(Regex("\\s+"), " ")

        /**
         * Search terms for a line text, most specific first. Preparation notes after a
         * comma or in brackets are dropped. Recipes qualify ingredients more than NEVO
         * does, so the full phrase is followed by shorter phrases without the leading
         * words ("light soy sauce" -> "soy sauce") and then by the single words in order
         * ("chicken thigh" -> "chicken").
         */
        fun searchTerms(text: String): List<String> {
            val core = text.substringBefore(',').replace(Regex("\\(.*?\\)"), " ")
            val words = core.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }
            val phrases = (0..words.size - 2).map { words.drop(it).joinToString(" ") }
            return (phrases + words).distinct()
        }
    }
}
