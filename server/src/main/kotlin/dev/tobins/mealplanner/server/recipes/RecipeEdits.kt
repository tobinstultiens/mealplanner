package dev.tobins.mealplanner.server.recipes

import dev.tobins.mealplanner.api.MappedLine
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeKeeps
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeLineInput
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.StandardSwitch
import dev.tobins.mealplanner.recipes.HugoRecipeParser
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.recipes.toIngredientLine
import dev.tobins.mealplanner.server.ConflictException
import dev.tobins.mealplanner.server.NotFoundException
import dev.tobins.mealplanner.server.db.Recipe
import dev.tobins.mealplanner.server.ingredients.IngredientRepository
import kotlinx.serialization.json.Json
import java.time.Instant

/** Section name given to recipes created in the app. */
private const val MANUAL_SECTION = "own"

/**
 * Writing a recipe: a new one, an edit of one of mine, the lines of one being edited as they would be saved, and its
 * rating, kind and slots, how long it keeps and whether it is a standard meal. Each answers the recipe as it is then.
 */
class RecipeEdits(
    private val store: RecipeStore,
    private val view: RecipeView,
    private val mine: MyRecipes,
    private val candidates: RecipeCandidates,
    private val ingredients: IngredientRepository,
) {
    /** Creates a recipe of the user's own, or one generated for them ([source] GENERATED): one of mine, in the rotation. */
    fun create(input: RecipeInput, source: RecipeSource = RecipeSource.MANUAL): RecipeDetail = store.writing {
        require(source != RecipeSource.COOKING_SITE) { "cooking site recipes come from the sync" }
        val lines = linesFrom(input, before = emptyList())
        val id = store.insert(
            source = source,
            sourcePath = null,
            contentHash = null,
            section = MANUAL_SECTION,
            title = input.title.trim(),
            tags = input.tags,
            servings = input.servings,
            instructions = input.instructions.trim(),
            hasIngredientsBlock = true,
            problems = emptyList(),
            kind = RecipeKind.MEAL,
            addedAt = Instant.now(),
            inRotation = true,
        )
        store.setSettings(id, RecipeKind.MEAL, input.mealSlots)
        store.replaceLines(id, lines)
        view.find(id)!!
    }

    /**
     * Replaces one of my recipes ([MyRecipes.requireMine]): one made in the app, or one taken from the cooking site, which
     * is the account's own copy (issue 340). Its kind, rating, picture and the like stay; a bank recipe is the site's (409).
     * A save that changes nothing ([unchanged]) writes nothing, so a taken recipe opened in the editor and saved as it
     * was is not changed in the app and can still go back to the bank (issue 342).
     */
    fun update(id: Long, input: RecipeInput): RecipeDetail = store.writing {
        val row = mine.requireMine(id)
        val before = store.linesOf(id)
        val lines = linesFrom(input, before)
        if (unchanged(row, before, input, lines)) return@writing view.find(id)!!
        store.updateMine(
            id = id,
            title = input.title.trim(),
            tags = input.tags,
            servings = input.servings,
            instructions = input.instructions.trim(),
            editedAt = Instant.now(),
        )
        store.setSettings(id, RecipeKind.valueOf(row.kind), input.mealSlots)
        store.replaceLines(id, lines)
        view.find(id)!!
    }

    /**
     * Whether [input], read as [lines], is recipe [row] as it is, with its lines [before]: its title, servings,
     * instructions (both trimmed, as saved), tags, slots and every line with its group and own ingredient. A recipe
     * with no number of servings (a cooking-site file without one) is read as 1, which is what the editor shows and sends
     * for it: saved untouched it stays without, instead of gaining a 1 and counting as edited.
     */
    private fun unchanged(row: Recipe, before: List<IngredientLine>, input: RecipeInput, lines: List<IngredientLine>): Boolean =
        input.title.trim() == row.title && input.servings.toLong() == (row.servings ?: 1L) && input.instructions.trim() == row.instructions &&
            input.tags == Json.decodeFromString<List<String>>(row.tagsJson) && input.mealSlots.toSet() == slotsOf(row) && lines == before

    /**
     * [lines] as a recipe saved with them would have them, without saving anything (`POST /api/recipes/lines`, issue 342):
     * each as stored, with the ingredient it means for the account now and its weight, as [RecipeView.find] gives them.
     * The app's editor looks pasted lines up with it, as only the server knows the account's links of texts. Refused as a
     * save would refuse the lines (400, and 404 for another account's ingredient).
     */
    fun readDraft(lines: List<RecipeLineInput>): List<MappedLine> {
        val read = readLines(lines).also(::requireOwnIngredients)
        val resolve = ingredients.resolver()
        return read.map { view.mapped(it, resolve) }
    }

    fun setRating(id: Long, rating: Int?): RecipeDetail {
        require(rating == null || rating in 1..5) { "rating must be 1 to 5, or null" }
        store.writing {
            mine.ownRecipe(id)
            store.setRating(id, rating)
        }
        return view.find(id)!!
    }

    /**
     * Sets how many days the recipe keeps once made, in a normal box and vacuum sealed (issue 234): what a prep
     * of it may span. Per account, as the recipe is; any recipe of the account's, also one only in the bank.
     */
    fun setKeeps(id: Long, keeps: RecipeKeeps): RecipeDetail {
        for (days in listOfNotNull(keeps.days, keeps.vacuumDays)) {
            require(days in 1..RecipeKeeps.MAX_DAYS) { "keeps must be 1 to ${RecipeKeeps.MAX_DAYS} days, or null" }
        }
        store.writing {
            mine.ownRecipe(id)
            store.setKeeps(id, keeps.days, keeps.vacuumDays)
        }
        return view.find(id)!!
    }

    /**
     * Makes one of my meals a standard meal (issue 265), or no longer one. Marking makes the ingredients it is made of
     * staples ([RecipeCandidates.standardIngredients]), as what is always at home, and says which were not yet;
     * unmarking leaves the staples as they are, as other meals may need them. Its own route, so that released apps saving
     * [RecipeSettings] leave it alone. Only a meal of mine with amounts is one: a bank recipe ([MyRecipes.requireMine]),
     * a building block and a recipe with nothing to size a portion by are a [ConflictException]; another account's is
     * not there (404).
     */
    fun setStandard(id: Long, standard: Boolean): StandardSwitch {
        val (added, unlinked) = store.writing {
            val row = mine.requireMine(id)
            if (!standard) {
                store.setStandardMeal(id, false)
                return@writing emptyList<String>() to emptyList()
            }
            if (row.kind == RecipeKind.COMPONENT.name) throw ConflictException("A building block is no standard meal: make it a meal first")
            candidates.candidate(id) ?: throw ConflictException("This recipe has no amounts to plan with")
            store.setStandardMeal(id, true)
            val (made, unlinked) = candidates.standardIngredients(id)
            val added = made.filterNot { it.isStaple }
            for (ingredient in added) ingredients.setStaple(ingredient.id, true)
            added.map { it.name } to unlinked
        }
        return StandardSwitch(view.find(id)!!, added, unlinked)
    }

    fun updateSettings(id: Long, settings: RecipeSettings): RecipeDetail {
        store.writing {
            mine.ownRecipe(id)
            store.setSettings(id, settings.kind, settings.mealSlots)
        }
        return view.find(id)!!
    }

    /**
     * The lines [input] saves, checked: its [RecipeInput.lines] when it has them, else those of its text, which keep the
     * ingredients of the recipe's lines [before] ([keepingIngredients]). An ingredient that is not the account's is a
     * [NotFoundException], as for toppings.
     */
    private fun linesFrom(input: RecipeInput, before: List<IngredientLine>): List<IngredientLine> {
        require(input.title.isNotBlank()) { "title must not be blank" }
        require(input.servings in 1..100) { "servings must be between 1 and 100" }
        val lines = input.lines?.let(::readLines) ?: keepingIngredients(readText(input.ingredients), before)
        require(lines.isNotEmpty()) { "at least one ingredient line is required" }
        requireOwnIngredients(lines)
        return lines
    }

    /** Refuses [lines] with an ingredient that is not the account's, as for toppings: as one that does not exist (404). */
    private fun requireOwnIngredients(lines: List<IngredientLine>) {
        for (id in lines.mapNotNullTo(LinkedHashSet()) { it.ingredientId }) {
            ingredients.find(id) ?: throw NotFoundException("No ingredient with id $id")
        }
    }

    /** The lines of the import format [text], read as the cooking site's are; a line that cannot be read is refused. */
    private fun readText(text: String): List<IngredientLine> {
        val parsed = HugoRecipeParser().parse("$MANUAL_SECTION/x.md", "```ingredients\n$text\n```")
        // One problem per line, so that an app can show each unreadable ingredient line on its own.
        require(parsed.problems.isEmpty()) { parsed.problems.joinToString("\n") }
        return parsed.ingredients
    }

    /**
     * [lines] as the same lines of the import format read, each with its ingredient ([toIngredientLine], which the app's
     * editor reads them with too), a line break in a text, unit or group as a space. A unit with a `|` is refused: the
     * import format, in which the installed app edits a recipe, would read it as part of the text (issue 342).
     */
    private fun readLines(lines: List<RecipeLineInput>): List<IngredientLine> {
        val parser = HugoRecipeParser()
        return lines.mapIndexed { index, line ->
            val amount = line.amount
            require(amount == null || (amount.isFinite() && amount > 0)) { "Line ${index + 1}: the amount must be above 0, or none" }
            require(line.unit?.contains('|') != true) { "Line ${index + 1}: a unit cannot hold |" }
            line.toIngredientLine(parser) ?: throw IllegalArgumentException("Line ${index + 1}: a line needs an amount, a unit or a text")
        }
    }

    /**
     * [lines] read from text, each with the ingredient of the first line [before] with the same text that had one. So a
     * recipe saved as text, as released apps save it, keeps the ingredients its lines were saved with where their text
     * stayed as it was (amount and unit may change); a line whose text changed is looked up by its new text.
     */
    private fun keepingIngredients(lines: List<IngredientLine>, before: List<IngredientLine>): List<IngredientLine> {
        val kept = HashMap<String, Long>()
        for (line in before) line.ingredientId?.let { kept.putIfAbsent(IngredientRepository.normalise(line.ingredientText), it) }
        return lines.map { it.copy(ingredientId = kept[IngredientRepository.normalise(it.ingredientText)]) }
    }
}
