package dev.tobins.mealplanner.server.generation

import dev.tobins.mealplanner.api.GenerationMode
import dev.tobins.mealplanner.api.MealSlot

/** The instructions given to the model. Kept apart from the API call so it can be tested. */
object RecipePrompt {
    val system = """
        You write home-cooking recipes for one person in the Netherlands who is losing weight
        and wants cheap, filling, high-protein food. Ingredients must be easy to buy at Albert
        Heijn, Jumbo or Lidl.

        Write ingredient names as plain, common English supermarket names without brand names
        or preparation notes in the name ("chicken thigh", "onion", "low-fat quark", "white rice"),
        because they are matched against a food database. Put preparation in the steps.
        Give amounts for the whole recipe, in grams or millilitres where possible; use "piece" for
        countable items such as eggs, onions or tortillas, "clove" for garlic, and tsp or tbsp for
        small amounts of spices, sauces and oil. Salt, pepper and similar "to taste" items have no
        amount and no unit.

        Keep oil and sugar modest and say how much exactly. Prefer lean protein. Steps are short,
        one action each, with times and temperatures in Celsius.
    """.trimIndent()

    fun user(context: GenerationContext): String = buildString {
        val meal = context.slot.name.lowercase()
        appendLine(
            when (context.mode) {
                GenerationMode.PREFERENCE -> "Write a $meal recipe."
                GenerationMode.TASTE_BREAKER ->
                    "Write a $meal recipe that breaks the routine: a cuisine, flavour or technique unlike the recent meals below."
            },
        )
        appendLine(
            "It makes ${context.servings} ${if (context.servings == 1) "serving" else "servings"}; one serving should have about " +
                "${context.kcalPerServing} kcal and at least ${context.proteinPerServing} g protein.",
        )
        if (context.slot == MealSlot.DINNER && context.servings > 1) {
            appendLine("The servings not eaten at dinner are next day's lunch, so it should keep and reheat well.")
        }
        if (context.wish.isNotBlank()) appendLine("Wish: ${context.wish.trim()}")
        list("Always at home, use freely", context.staples)
        list("At home and should be used up soon; use some of these", context.onHand)
        list("On offer this week; prefer these when they fit", context.onOffer)
        list("Recently eaten; do not repeat these", context.recent)
        list("Already in the collection; write something else", context.existing)
    }.trim()

    private fun StringBuilder.list(label: String, items: List<String>) {
        if (items.isEmpty()) return
        appendLine("$label: ${items.distinct().take(MAX_ITEMS).joinToString(", ")}.")
    }

    private const val MAX_ITEMS = 60
}
