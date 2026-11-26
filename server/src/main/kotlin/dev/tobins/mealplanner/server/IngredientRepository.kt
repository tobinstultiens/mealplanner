package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.LabelNutrition
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.Portion
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.UnmappedText
import dev.tobins.mealplanner.nutrition.inPortionOf
import dev.tobins.mealplanner.nutrition.portionKey
import dev.tobins.mealplanner.nutrition.portionNameProblem
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.offers.ExcludedMatch
import dev.tobins.mealplanner.server.offers.OfferTerms
import dev.tobins.mealplanner.shopping.ShopSection
import dev.tobins.mealplanner.units.Dimension
import dev.tobins.mealplanner.server.db.Ingredient as IngredientRow
import dev.tobins.mealplanner.server.db.IngredientPortion as PortionRow

/** One account's ingredient catalogue and the remembered links from its recipe line texts to it. */
class IngredientRepository(private val db: Database, private val foods: FoodRepository, private val userId: Long) {
    private val q = db.ingredientQueries
    private val pantry = db.pantryQueries
    private val shopping = db.shoppingQueries

    /** The account's ingredients, each with the unit it was last saved in ([Ingredient.lastUnit]). */
    fun list(): List<Ingredient> {
        val lastUnits = lastUnits()
        val portions = q.selectPortions(userId).executeAsList().groupBy { it.ingredientId }
        return q.selectAllIngredients(userId).executeAsList().map { it.toIngredient(portions[it.id].orEmpty()).copy(lastUnit = lastUnits[it.id]) }
    }

    /**
     * The unit each ingredient has in the recipe of mine saved last (issue 346), or in the food of a slot of the plan
     * picked or changed last (issue 358), for the editor and the plan to start a picked ingredient in it. Only a line saved
     * with its ingredient counts, not one that means it through its text: that is the cooking site's wording, not something
     * the account picked. Several lines of one recipe: the last.
     */
    private fun lastUnits(): Map<Long, String> = buildMap {
        q.selectLinkedUnits(userId).executeAsList().forEach { line -> line.unit?.let { put(line.ingredientId!!, it) } }
    }

    fun find(id: Long): Ingredient? = q.selectIngredient(userId, id).executeAsOneOrNull()?.toIngredient()

    fun create(input: IngredientInput): Ingredient = linking { insert(input) }

    /**
     * Replaces ingredient [id] with [input]: its values of its own too ([IngredientInput.label], none when null), and its
     * portions when [IngredientInput.portions] gives them. Another account's is one that does not exist (404).
     */
    fun update(id: Long, input: IngredientInput): Ingredient = linking {
        q.selectIngredient(userId, id).executeAsOneOrNull() ?: throw NotFoundException("No ingredient with id $id")
        validate(input, selfId = id)
        q.updateIngredient(
            input.name.trim(), input.nevoCode?.toLong(), input.gramsPerPiece, input.gramsPerMl,
            input.category, input.pricePerKg, if (input.isStaple) 1 else 0, if (input.negligible) 1 else 0, userId, id,
        )
        saveLabel(id, input.label)
        input.portions?.let { savePortions(id, it) }
        q.selectIngredient(userId, id).executeAsOne().toIngredient()
    }

    private fun saveLabel(id: Long, label: LabelNutrition?) {
        val values = label?.per100
        q.setLabel(
            label?.basis?.name, values?.kcal, values?.proteinG, values?.fatG, values?.saturatedFatG, values?.carbsG,
            values?.sugarG, values?.fibreG, values?.sodiumMg, label?.source?.trim()?.ifEmpty { null }, userId, id,
        )
    }

    /**
     * Makes [portions] ingredient [id]'s, in their order. One it had that is not among them is kept as removed, so that a
     * line in it counts as nothing rather than as pieces ([Ingredient.removedPortions]); one given again is the same row.
     */
    private fun savePortions(id: Long, portions: List<Portion>) {
        q.removePortions(userId, id)
        portions.forEachIndexed { index, portion ->
            val name = portion.name.trim()
            q.insertPortion(portionKey(name), name, portion.grams, portion.ml, index.toLong(), userId, id)
            q.keepPortion(name, portion.grams, portion.ml, index.toLong(), userId, id, portionKey(name))
        }
    }

    /**
     * Runs [change], which may link line texts to ingredients (a link, an alias, a new or renamed
     * ingredient), in one transaction with moving the ticks of the text rows it linked (issue 116).
     * A linked text's row changes key, from `t:<text>` to the ingredient's `i<id>`; without this its
     * tick would stay behind under the old key, listed as bought and no longer needed, while the new
     * row starts un-ticked. Each such tick, in any week, moves to the ingredient's key with what it
     * remembers. Only ticks of texts that [change] linked move, and never onto a tick of the ingredient
     * that is there already: then both stay as they were, as before.
     */
    private fun <T> linking(change: () -> T): T = db.writing {
        val ticks = shopping.selectTextChecks(userId).executeAsList()
            .mapNotNull { row -> (ShoppingKey.parseOrNull(row.itemKey) as? ShoppingKey.Text)?.let { row.weekStart to it } }
        // The resolver reads the whole catalogue: only when there is a tick to move.
        if (ticks.isEmpty()) return@writing change()
        val before = resolver()
        val result = change()
        val after = resolver()
        for ((week, key) in ticks) {
            // The row's key is its text as the list keeps it, which resolves as the lines it stands for do.
            val text = IngredientLine(null, null, null, null, key.text, null)
            if (before(text) != null) continue
            val ingredient = after(text) ?: continue
            shopping.moveCheck(ShoppingKey.Ingredient(ingredient.id).toString(), userId, week, key.toString())
        }
        result
    }

    /**
     * What the account's store offers are matched with: per ingredient, the names it is known by, and the offers the
     * account said are not the ingredient ([OfferExclusionRepository]). Negligible ingredients (spices and the like) are
     * left out.
     */
    fun matchTerms(): OfferTerms {
        val ingredients = list().filterNot { it.negligible }.associateBy { it.id }
        val aliases = q.selectAliases(userId).executeAsList().groupBy({ it.ingredientId }, { it.text })
        val excluded = db.offerExclusionQueries.selectExcludedMatches(userId).executeAsList()
            .mapTo(HashSet()) { ExcludedMatch(Store.valueOf(it.store), it.titleKey, it.ingredientId) }
        return OfferTerms(ingredients.mapValues { (id, ingredient) -> listOf(ingredient.name) + aliases[id].orEmpty() }, excluded)
    }

    fun setStaple(id: Long, staple: Boolean) = db.writing { requireIngredient(id).also { pantry.setStaple(if (staple) 1 else 0, userId, id) } }

    /** Whether ingredients keep until next week, where the account set it by hand, by id. */
    fun keepsByHand(): Map<Long, Boolean> = q.selectKeepsByHand(userId).executeAsList().associate { it.id to (it.keeps != 0L) }

    /** Sets by hand whether an ingredient keeps until next week; null goes back to its shop section's answer. */
    fun setKeeps(id: Long, keeps: Boolean?): Unit = db.writing {
        requireIngredient(id)
        q.setKeeps(keeps?.let { if (it) 1L else 0L }, userId, id)
    }

    /** The ingredients my recipes use, by id; recipes only in the bank are not cooked from, so they do not count. */
    fun usedIds(): Set<Long> {
        val resolve = resolver()
        return myLines().mapNotNullTo(HashSet()) { resolve(it)?.id }
    }

    /** Shop sections the account set by hand, by ingredient id. Keys this version does not know are skipped. */
    fun sectionsByHand(): Map<Long, ShopSection> = q.selectSectionsByHand(userId).executeAsList()
        .mapNotNull { row -> ShopSection.fromKey(row.section)?.let { row.id to it } }.toMap()

    /** Sets an ingredient's shop section by hand; null goes back to the automatic one. */
    fun setSection(id: Long, section: ShopSection?): Unit = db.writing {
        requireIngredient(id)
        q.setSection(section?.name, userId, id)
    }

    private fun requireIngredient(id: Long) =
        q.selectIngredient(userId, id).executeAsOneOrNull() ?: throw NotFoundException("No ingredient with id $id")

    /**
     * Looks up the ingredient each line means: the one it was saved with ([IngredientLine.ingredientId], issue 340),
     * whatever its text and the aliases say. A line without one is looked up by its text, in order: a link for the
     * exact text, a link for its core text ("garlic, minced" -> "garlic"), then an ingredient
     * whose name is the core text, ignoring a plural s; the last two are then retried
     * without leading preparation words ("chopped green onion" -> "green onion") and
     * for each alternative of an "a or b" text. Every reader of a recipe's lines goes through this.
     */
    fun resolver(): (IngredientLine) -> Ingredient? {
        val all = list()
        val byId = all.associateBy { it.id }
        val byName = all.associateBy { singular(it.name.lowercase()) }
        val aliases = q.selectAliases(userId).executeAsList().associate { it.text to it.ingredientId }
        return { line ->
            line.ingredientId?.let(byId::get)
                ?: aliases[normalise(line.ingredientText)]?.let(byId::get)
                ?: IngredientLine.lookupTexts(line.ingredientText).firstNotNullOfOrNull { text ->
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
    fun applySeed(): Int = linking {
        if (foods.dataset().foodCount == 0) return@linking 0
        val text = checkNotNull(javaClass.getResource("/catalogue-seed.csv")) { "catalogue-seed.csv is missing" }.readText()
        var added = 0
        for (row in parseDelimited(text.lineSequence().filterNot { it.startsWith("#") }.joinToString("\n"), '|')) {
            val (name, code, gramsPerPiece, gramsPerMl, aliases) = row
            val existing = q.selectIngredientByName(userId, name).executeAsOneOrNull()
            val id = existing?.id ?: run {
                val nevoCode = code.toIntOrNull()
                if (nevoCode != null && foods.find(nevoCode) == null) return@run null // not in this NEVO release
                added++
                insert(
                    IngredientInput(name, nevoCode, gramsPerPiece.toDoubleOrNull(), gramsPerMl.toDoubleOrNull(), negligible = nevoCode == null),
                ).id
            } ?: continue
            aliases.split(';').map(::normalise).filter { it.isNotEmpty() }.forEach { q.insertAliasIfAbsent(userId, it, id) }
        }
        added
    }

    /**
     * Links every unlinked core text of my recipes that is exactly the name of one NEVO food:
     * the same words in any order, ignoring a plural s and NEVO's neutral qualifiers
     * ("raw", "av"). Texts that match several foods are left for the user. Texts only bank
     * recipes use are left alone, so the catalogue does not fill with what the account never
     * added; adding a recipe runs this again ([RecipeRepository.setAdded]).
     * @return the number of texts linked.
     */
    fun autoLink(): Int = linking {
        val resolve = resolver()
        val cores = myLines().filter { it.amount != null && resolve(it) == null }
            .mapTo(linkedSetOf()) { IngredientLine.withoutDescriptors(IngredientLine.coreText(it.ingredientText)) }
        if (cores.isEmpty()) return@linking 0

        val foodsByWords = HashMap<Set<String>, MutableSet<Food>>()
        for (food in foods.all()) {
            for (name in listOfNotNull(food.nameEn, food.nameNl) + food.synonym.orEmpty().split(',')) {
                nameWords(name).takeIf { it.isNotEmpty() }?.let { foodsByWords.getOrPut(it) { linkedSetOf() } += food }
            }
        }
        var linked = 0
        for (core in cores) {
            val food = foodsByWords[nameWords(core)]?.singleOrNull() ?: continue
            val ingredient = q.selectIngredientByNevoCode(userId, food.nevoCode.toLong()).executeAsOneOrNull()?.toIngredient()
                ?: insert(IngredientInput(name = uniqueName(food.nameEn), nevoCode = food.nevoCode))
            q.insertAliasIfAbsent(userId, core, ingredient.id)
            linked++
        }
        linked
    }

    private fun uniqueName(name: String): String =
        generateSequence(name) { "$it (NEVO)" }.first { q.selectIngredientByName(userId, it).executeAsOneOrNull() == null }

    private fun myLines(): List<IngredientLine> = q.selectMyLines(userId).executeAsList().map {
        IngredientLine(it.amount, it.unit, it.dimension?.let(Dimension::valueOf), it.baseAmount, it.item, it.groupName, it.ingredientId)
    }

    /** Links a line text to an ingredient, creating the ingredient from a NEVO food if asked. */
    fun map(request: MappingRequest): Ingredient = linking {
        val text = normalise(request.text)
        require(text.isNotEmpty()) { "text must not be blank" }
        val ingredient = when {
            request.ingredientId != null ->
                q.selectIngredient(userId, request.ingredientId!!).executeAsOneOrNull()?.toIngredient()
                    ?: throw NotFoundException("No ingredient with id ${request.ingredientId}")
            request.nevoCode != null -> {
                val food = foods.find(request.nevoCode!!) ?: throw NotFoundException("No NEVO food ${request.nevoCode}")
                q.selectIngredientByNevoCode(userId, food.nevoCode.toLong()).executeAsOneOrNull()?.toIngredient()
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
                if (completed.negligible) 1 else 0, userId, completed.id,
            )
        }
        q.upsertAlias(userId, text, completed.id)
        completed
    }

    fun unmap(text: String) = db.writing { q.deleteAlias(userId, normalise(text)) }

    /**
     * Line texts of my recipes that need attention, most-used first: texts with an
     * amount but no ingredient (with suggestions from the catalogue and from NEVO), and
     * linked texts that are counted in pieces while the weight per piece is unknown.
     */
    fun unmapped(limit: Int = 50): List<UnmappedText> {
        val resolve = resolver()
        val catalogue = list()
        data class Use(val text: String, val recipes: MutableSet<String> = linkedSetOf(), var counted: Boolean = false, var ingredient: Ingredient? = null)

        val uses = linkedMapOf<String, Use>()
        for (row in q.selectMyLines(userId).executeAsList()) {
            val line = IngredientLine(row.amount, row.unit, row.dimension?.let(Dimension::valueOf), row.baseAmount, row.item, row.groupName, row.ingredientId)
            if (line.amount == null) continue
            val ingredient = resolve(line)
            if (ingredient?.negligible == true) continue
            // A line in a portion is weighed by the portion, or not at all once it is gone: a weight per piece would not help.
            val needsWeight = ingredient != null && line.dimension == Dimension.COUNT && !line.inPortionOf(ingredient) && ingredient.gramsPerPiece == null
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
            userId,
            input.name.trim(), input.nevoCode?.toLong(), input.gramsPerPiece, input.gramsPerMl,
            input.category, input.pricePerKg, if (input.isStaple) 1 else 0, if (input.negligible) 1 else 0,
        )
        val id = q.lastIngredientId().executeAsOne()
        saveLabel(id, input.label)
        input.portions?.let { savePortions(id, it) }
        return q.selectIngredient(userId, id).executeAsOne().toIngredient()
    }

    private fun validate(input: IngredientInput, selfId: Long?) {
        require(input.name.isNotBlank()) { "name must not be blank" }
        require(listOfNotNull(input.gramsPerPiece, input.gramsPerMl, input.pricePerKg).all { it > 0 && it.isFinite() }) {
            "gramsPerPiece, gramsPerMl and pricePerKg must be positive"
        }
        input.nevoCode?.let { if (foods.find(it) == null) throw NotFoundException("No NEVO food $it") }
        q.selectIngredientByName(userId, input.name.trim()).executeAsOneOrNull()?.let {
            require(it.id == selfId) { "An ingredient named '${it.name}' already exists" }
        }
        input.label?.let(::validateLabel)
        input.portions?.let(::validatePortions)
    }

    /**
     * A label's values are each a number of 0 or more, or unknown; its energy must be known, since a product without it
     * would add nothing to a day's kcal and still say it was counted.
     */
    private fun validateLabel(label: LabelNutrition) {
        val values = with(label.per100) { listOf(kcal, proteinG, fatG, saturatedFatG, carbsG, sugarG, fibreG, sodiumMg) }
        require(label.per100.kcal != null) { "A label's energy (kcal) is needed" }
        require(values.filterNotNull().all { it >= 0 && it.isFinite() }) { "A label's values must be 0 or more" }
        require((label.source?.length ?: 0) <= MAX_SOURCE) { "A label's source is at most $MAX_SOURCE characters" }
    }

    /** Each portion has a name ([portionNameProblem]) of its own, without regard to case, and a weight or a volume above 0. */
    private fun validatePortions(portions: List<Portion>) {
        val seen = HashSet<String>()
        for (portion in portions) {
            portionNameProblem(portion.name)?.let { throw IllegalArgumentException(it) }
            require(seen.add(portionKey(portion.name))) { "Two portions are named '${portion.name.trim()}'" }
            val size = listOfNotNull(portion.grams, portion.ml)
            require(size.size == 1 && size.single() > 0 && size.single().isFinite()) {
                "The portion '${portion.name.trim()}' needs a weight in grams or a volume in ml above 0, not both"
            }
        }
    }

    private fun IngredientRow.toIngredient(): Ingredient = toIngredient(q.selectPortionsOf(userId, id).executeAsList())

    /** The ingredient of this row, with [portions], the rows of its portions (those removed too). */
    private fun IngredientRow.toIngredient(portions: List<PortionRow>) = Ingredient(
        id = id,
        name = name,
        food = nevoCode?.let { foods.find(it.toInt()) },
        gramsPerPiece = gramsPerPiece,
        gramsPerMl = gramsPerMl,
        category = category,
        pricePerKg = pricePerKg,
        isStaple = isStaple != 0L,
        negligible = negligible != 0L,
        label = labelBasis?.let { basis ->
            LabelNutrition(
                basis = FoodBasis.valueOf(basis),
                per100 = Nutrition(labelKcal, labelProteinG, labelFatG, labelSaturatedFatG, labelCarbsG, labelSugarG, labelFibreG, labelSodiumMg),
                source = labelSource,
            )
        },
        portions = portions.filter { it.removed == 0L }.map { Portion(it.name, it.grams, it.ml) },
        removedPortions = portions.filter { it.removed != 0L }.map { it.name },
    )

    private fun Ingredient.toInput() =
        IngredientInput(name, food?.nevoCode, gramsPerPiece, gramsPerMl, category, pricePerKg, isStaple, negligible)

    companion object {
        /** Less than this left of what was bought ahead is a scrap, not something to plan or shop around. */
        const val USED_UP_GRAMS = 1.0

        /** The longest [LabelNutrition.source]: a product's id or barcode, with room to spare. */
        private const val MAX_SOURCE = 200

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
