package dev.tobins.mealplanner.server.generation

import dev.tobins.mealplanner.api.GenerationMode
import dev.tobins.mealplanner.api.MealSlot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecipeGenerationTest {
    private val context = GenerationContext(
        mode = GenerationMode.PREFERENCE,
        slot = MealSlot.DINNER,
        wish = "Korean, spicy",
        kcalPerServing = 775,
        proteinPerServing = 61,
        servings = 4,
        staples = listOf("Olive oil", "Salt"),
        onHand = listOf("Spinach"),
        onOffer = listOf("Chicken fillet", "Broccoli"),
        recent = listOf("Gyros", "Carnitas"),
        existing = listOf("Gyudon"),
    )

    @Test
    fun promptCarriesTheBudgetAndEveryList() {
        val prompt = RecipePrompt.user(context)
        assertTrue("Write a dinner recipe." in prompt)
        assertTrue("4 servings" in prompt && "about 775 kcal" in prompt && "at least 61 g protein" in prompt)
        assertTrue("next day's lunch" in prompt)
        assertTrue("Wish: Korean, spicy" in prompt)
        assertTrue("Olive oil, Salt" in prompt)
        assertTrue("Spinach" in prompt && "Chicken fillet, Broccoli" in prompt && "Gyros, Carnitas" in prompt && "Gyudon" in prompt)

        val breakfast = RecipePrompt.user(context.copy(slot = MealSlot.BREAKFAST, servings = 1, mode = GenerationMode.TASTE_BREAKER, wish = "", onHand = emptyList()))
        assertTrue("breaks the routine" in breakfast)
        assertTrue("1 serving;" in breakfast)
        assertFalse("lunch" in breakfast)
        assertFalse("Wish" in breakfast)
        assertFalse("used up" in breakfast, "empty lists are left out")
    }

    private val good = GeneratedRecipe(
        title = "Gochujang chicken",
        servings = 4,
        ingredients = listOf(
            GeneratedIngredient(600.0, "g", "chicken fillet"),
            GeneratedIngredient(2.0, "tbsp", "gochujang"),
            GeneratedIngredient(2.0, "clove", "garlic"),
            GeneratedIngredient(null, "", "salt"),
        ),
        steps = listOf("Cut the chicken.", "Fry 8 minutes."),
    )

    @Test
    fun ingredientsBecomeLinesOfTheIngredientsBlock() {
        assertEquals(
            listOf("600 | g | chicken fillet", "2 | tbsp | gochujang", "2 | clove | garlic", " |  | salt"),
            good.ingredients.map { it.asLine() },
        )
        assertEquals("1.5 | piece | egg / white", GeneratedIngredient(1.5, "piece", "egg | white").asLine())
    }

    @Test
    fun unusableRecipesAreRecognised() {
        assertEquals(emptyList(), good.problems())
        val bad = good.copy(
            servings = 0,
            steps = emptyList(),
            ingredients = listOf(GeneratedIngredient(-1.0, "g", "x"), GeneratedIngredient(1.0, "cups", "rice"), GeneratedIngredient(null, "g", "oil")),
        )
        assertEquals(5, bad.problems().size, bad.problems().toString())
    }

    @Test
    fun schemaDescribesTheRecipeAndAReplyParses() {
        val schema = ClaudeRecipeGenerator.schemaMap
        @Suppress("UNCHECKED_CAST")
        assertEquals((schema["properties"] as Map<String, Any>).keys.toList(), schema["required"])
        assertEquals(false, schema["additionalProperties"])

        val reply = """{"title":"Gochujang chicken","description":"Spicy.","servings":4,"tags":["Korean"],
            "ingredients":[{"amount":600,"unit":"g","item":"chicken fillet"},{"amount":null,"unit":"","item":"salt"}],
            "steps":["Fry."]}"""
        val parsed = Json.decodeFromString<GeneratedRecipe>(reply)
        assertEquals(600.0, parsed.ingredients[0].amount)
        assertEquals(listOf("Korean"), parsed.tags)
    }

    /** Calls the real API. Run with ANTHROPIC_API_KEY set and LIVE_CLAUDE=1; skipped otherwise. Costs a few cents. */
    @Test
    fun liveGeneration() = runBlocking {
        if (System.getenv("LIVE_CLAUDE") != "1") return@runBlocking
        val generator = checkNotNull(ClaudeRecipeGenerator.fromEnv()) { "ANTHROPIC_API_KEY is not set" }
        val recipe = generator.generate(context)
        println(Json { prettyPrint = true }.encodeToString(GeneratedRecipe.serializer(), recipe))
        assertEquals(emptyList(), recipe.problems())
        assertEquals("4", Json.parseToJsonElement(Json.encodeToString(GeneratedRecipe.serializer(), recipe)).jsonObject["servings"]!!.jsonPrimitive.content)
    }
}
