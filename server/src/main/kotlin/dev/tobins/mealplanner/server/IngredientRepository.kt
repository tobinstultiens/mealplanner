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
            input.category, input.pricePerKg, if (input.isStaple) 1 else 0, if (input.negligible) 1 else 0, id,
        )
        q.selectIngredient(id).executeAsOne().toIngredient()
    }

    /**
     * Looks up the ingredient each line's text means. In order: a link for the exact
     * text, a link for its core text ("garlic, minced" -> "garlic"), then an ingredient
     * whose name is the core text, ignoring a plural s; the last two are then retried
     * without leading preparation words ("chopped green onion" -> "green onion").
     */
    fun resolver(): (IngredientLine) -> Ingredient? {
        val all = list()
        val byId = all.associateBy { it.id }
        val byName = all.associateBy { singular(it.name.lowercase()) }
        val aliases = q.selectAliases().executeAsList().associate { it.text to it.ingredientId }
        return { line ->
            val core = IngredientLine.coreText(line.ingredientText)
            val plain = IngredientLine.withoutDescriptors(core)
            aliases[normalise(line.ingredientText)]?.let(byId::get)
                ?: listOf(core, plain).distinct().firstNotNullOfOrNull { text ->
                    (aliases[text] ?: aliases[singular(text)])?.let(byId::get) ?: byName[singular(text)]
                }
        }
    }

    /**
     * Adds the default catalogue (`catalogue-seed.csv`): common ingredients with the NEVO
     * food they mean. Existing ingredients and links are left alone, so it is safe to
     * run on every start. Does nothing until NEVO data has been imported.
     * @return the number of ingredients added.
     */
    fun applySeed(): Int = db.transactionWithResult {
        if (foods.dataset().foodCount == 0) return@transactionWithResult 0
        val text = checkNotNull(javaClass.getResource("/catalogue-seed.csv")) { "catalogue-seed.csv is missing" }.readText()
        var added = 0
        for (row in parseDelimited(text.lineSequence().filterNot { it.startsWith("#") }.joinToString("\n"), '|')) {
            val (name, code, gramsPerPiece, gramsPerMl, aliases) = row
            val existing = q.selectIngredientByName(name).executeAsOneOrNull()
            val id = existing?.id ?: run {
                val nevoCode = code.toIntOrNull()
                if (nevoCode != null && foods.find(nevoCode) == null) return@run null // not in this NEVO release
                added++
                insert(
                    IngredientInput(name, nevoCode, gramsPerPiece.toDoubleOrNull(), gramsPerMl.toDoubleOrNull(), negligible = nevoCode == null),
                ).id
            } ?: continue
            aliases.split(';').map(::normalise).filter { it.isNotEmpty() }.forEach { q.insertAliasIfAbsent(it, id) }
        }
        added
    }

    /**
     * Links every unlinked core text that is exactly the name of one NEVO food:
     * the same words in any order, ignoring a plural s and NEVO's neutral qualifiers
     * ("raw", "av"). Texts that match several foods are left for the user.
     * @return the number of texts linked.
     */
    fun autoLink(): Int = db.transactionWithResult {
        val resolve = resolver()
        val cores = activeLines().filter { it.amount != null && resolve(it) == null }
            .mapTo(linkedSetOf()) { IngredientLine.withoutDescriptors(IngredientLine.coreText(it.ingredientText)) }
        if (cores.isEmpty()) return@transactionWithResult 0

        val foodsByWords = HashMap<Set<String>, MutableSet<Food>>()
        for (food in foods.all()) {
            for (name in listOfNotNull(food.nameEn, food.nameNl) + food.synonym.orEmpty().split(',')) {
                nameWords(name).takeIf { it.isNotEmpty() }?.let { foodsByWords.getOrPut(it) { linkedSetOf() } += food }
            }
        }
        var linked = 0
        for (core in cores) {
            val food = foodsByWords[nameWords(core)]?.singleOrNull() ?: continue
            val ingredient = q.selectIngredientByNevoCode(food.nevoCode.toLong()).executeAsOneOrNull()?.toIngredient()
                ?: insert(IngredientInput(name = uniqueName(food.nameEn), nevoCode = food.nevoCode))
            q.insertAliasIfAbsent(core, ingredient.id)
            linked++
        }
        linked
    }

    private fun uniqueName(name: String): String =
        generateSequence(name) { "$it (NEVO)" }.first { q.selectIngredientByName(it).executeAsOneOrNull() == null }

    private fun activeLines(): List<IngredientLine> = q.selectActiveLines().executeAsList().map {
        IngredientLine(it.amount, it.unit, it.dimension?.let(Dimension::valueOf), it.baseAmount, it.item, it.groupName)
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
                completed.category, completed.pricePerKg, if (completed.isStaple) 1 else 0,
                if (completed.negligible) 1 else 0, completed.id,
            )
        }
        q.upsertAlias(text, completed.id)
        completed
    }

    fun unmap(text: String) = q.deleteAlias(normalise(text))

    /**
     * Line texts of active recipes that need attention, most-used first: texts with an
     * amount but no ingredient (with suggestions from the catalogue and from NEVO), and
     * linked texts that are counted in pieces while the weight per piece is unknown.
     */
    fun unmapped(limit: Int = 50): List<UnmappedText> {
        val resolve = resolver()
        val catalogue = list()
        data class Use(val text: String, val recipes: MutableSet<String> = linkedSetOf(), var counted: Boolean = false, var ingredient: Ingredient? = null)

        val uses = linkedMapOf<String, Use>()
        for (row in q.selectActiveLines().executeAsList()) {
            val line = IngredientLine(row.amount, row.unit, row.dimension?.let(Dimension::valueOf), row.baseAmount, row.item, row.groupName)
            if (line.amount == null) continue
            val ingredient = resolve(line)
            if (ingredient?.negligible == true) continue
            val needsWeight = ingredient != null && line.dimension == Dimension.COUNT && ingredient.gramsPerPiece == null
            if (ingredient != null && !needsWeight) continue
            // Unlinked variants of one ingredient ("garlic, minced", "garlic, crushed") are one entry.
            val text = IngredientLine.withoutDescriptors(IngredientLine.coreText(line.ingredientText))
            // Keyed without the plural s, so "chicken thigh" and "chicken thighs" are one entry.
            val use = uses.getOrPut(singular(text)) { Use(text) }
            use.recipes += row.title
            use.ingredient = ingredient
            if (line.dimension == Dimension.COUNT) use.counted = true
        }
        return uses.entries
            .sortedWith(compareByDescending<Map.Entry<String, Use>> { it.value.recipes.size }.thenBy { it.key })
            .take(limit)
            .map { (_, use) ->
                val text = use.text
                val terms = if (use.ingredient == null) searchTerms(text) else emptyList()
                UnmappedText(
                    text = text,
                    recipeCount = use.recipes.size,
                    exampleRecipe = use.recipes.first(),
                    countedInPieces = use.counted,
                    linkedIngredient = use.ingredient,
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
            input.category, input.pricePerKg, if (input.isStaple) 1 else 0, if (input.negligible) 1 else 0,
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
        negligible = negligible != 0L,
    )

    private fun Ingredient.toInput() =
        IngredientInput(name, food?.nevoCode, gramsPerPiece, gramsPerMl, category, pricePerKg, isStaple, negligible)

    companion object {
        private fun singular(word: String): String = if (word.length > 3 && word.endsWith("s")) word.dropLast(1) else word

        private val neutralWords = setOf("raw", "rauw", "av", "gem", "fresh", "vers", "verse")

        /** The words of a food or ingredient name that carry meaning, singular, as a set. */
        internal fun nameWords(name: String): Set<String> =
            name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it !in neutralWords }.mapTo(HashSet(), ::singular)

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
