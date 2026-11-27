package dev.tobins.mealplanner.app.plan

import dev.tobins.mealplanner.api.DinnerPick
import dev.tobins.mealplanner.api.DinnerSuggestion
import dev.tobins.mealplanner.api.DinnerSuggestions
import dev.tobins.mealplanner.api.HiddenRecipe
import dev.tobins.mealplanner.api.PackRestUse
import dev.tobins.mealplanner.api.PlanningTotals
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.SuggestionGround
import dev.tobins.mealplanner.api.SuggestionOffer
import dev.tobins.mealplanner.api.WeekPlanning
import dev.tobins.mealplanner.app.FakeBackend
import dev.tobins.mealplanner.app.FakeBackend.Refusal
import dev.tobins.mealplanner.app.recipes.mine
import dev.tobins.mealplanner.app.recipes.row
import kotlinx.serialization.encodeToString

/* The planning screen's part of [FakeBackend]: the dinners chosen and hidden, and what the server suggests. */

internal fun FakeBackend.suggestion(id: Long, title: String, vararg grounds: SuggestionGround, kcal: Double = 640.0, protein: Double = 42.0, price: Double = 2.35, saving: Double = 0.0) =
    DinnerSuggestion(id, title, grounds.toList(), 1.25, kcal * 1.25, protein * 1.25, 7.5, price, saving)

/** What the server suggests, in the mixed order it sends them: on offer, what is there, favourites and recipes not tried yet, in turn. */
internal val FakeBackend.suggestions get() = listOf(
    suggestion(21, "Gochujang chicken thighs with rice and quick-pickled cucumber", SuggestionGround.ON_OFFER, saving = 0.85)
        .copy(offers = listOf(SuggestionOffer("Chicken thigh fillet", Store.AH, "AH Kippendijfilet", "25% korting", 0.85))),
    suggestion(22, "Spinach and feta pie", SuggestionGround.USES_WHAT_IS_THERE).copy(onHand = listOf("Feta")),
    suggestion(2, "Chickpea salad with feta and roasted peppers", SuggestionGround.FAVOURITE).copy(rating = 5, weeksSinceEaten = 4),
    suggestion(24, "Miso salmon traybake", SuggestionGround.NOT_TRIED_YET, price = 4.10),
    suggestion(25, "Beef chilli", SuggestionGround.ON_OFFER, saving = 0.60)
        .copy(offers = listOf(SuggestionOffer("Minced beef", Store.JUMBO, "Jumbo Rundergehakt 500 g", "2 voor 7,50", 0.60))),
    suggestion(26, "Pasta with spinach and lemon", SuggestionGround.USES_WHAT_IS_THERE).copy(packRests = listOf(PackRestUse("Spinach", "Lentil soup"))),
    suggestion(27, "Thai green curry", SuggestionGround.FAVOURITE).copy(rating = 4),
    suggestion(28, "Fish tacos", SuggestionGround.NOT_TRIED_YET),
    suggestion(29, "Pork tenderloin with apples", SuggestionGround.ON_OFFER, SuggestionGround.NOT_TRIED_YET, saving = 1.10)
        .copy(offers = listOf(SuggestionOffer("Pork tenderloin", Store.LIDL, "Varkenshaas", null, 1.10))),
    suggestion(30, "Mushroom risotto", SuggestionGround.FAVOURITE).copy(weeksSinceEaten = 6),
)

internal fun FakeBackend.titleOf(id: Long) = suggestions.firstOrNull { it.recipeId == id }?.title ?: row(id).title

/**
 * The week's planning: the open days (Monday and Tuesday have passed, Thursday's dinner is skipped and Friday's locked,
 * so Wednesday, Saturday and Sunday are left), the choices and the strip.
 */
internal fun FakeBackend.planningOf(weekStart: String): WeekPlanning {
    val chosen = picks.toList().map { id -> DinnerPick(id, titleOf(id), 1.25, 812.5, 51.0, 9.0, 3.40, 3.90) }
    return WeekPlanning(
        weekStart, targets, openDays = listOf(2, 5, 6), picks = chosen,
        notThisWeek = hidden.toList().map { HiddenRecipe(it, titleOf(it)) },
        totals = PlanningTotals(
            meals = 3 + 2 * chosen.size, cost = 7.80 + chosen.sumOf { 2 * it.cost!! }, costWithoutOffers = 8.40 + chosen.sumOf { 2 * it.costWithoutOffers!! },
            kcal = 2080.0 + chosen.sumOf { 2 * it.kcal }, proteinG = 121.0 + chosen.sumOf { 2 * it.proteinG }, fibreG = 26.0 + chosen.sumOf { 2 * it.fibreG!! },
            targetKcal = 1890.0 + 1428.0 * chosen.size, targetProteinG = 117.0 + 88.4 * chosen.size, targetFibreG = 27.0 + 20.4 * chosen.size,
        ),
    )
}

/** The next suggestions, as the server pages them; a search finds every meal of mine whose title has each word. */
internal fun FakeBackend.suggestionsOf(query: Map<String, String>): DinnerSuggestions {
    val count = query["count"]?.toInt() ?: 6
    val seen = query["seen"].orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()
    val words = query["q"]?.lowercase()?.split(' ')?.filter { it.isNotEmpty() }
    val pool = if (words == null) {
        suggestions.filter { suggesting && it.recipeId !in picks && it.recipeId !in hidden }
    } else {
        val meals = mine().filter { it.kind == RecipeKind.MEAL && suggestions.none { s -> s.recipeId == it.id } }
            .map { DinnerSuggestion(it.id, it.title, emptyList(), 1.25, 760.0, 45.0, 6.0, 2.40, 0.30) }
        (suggestions + meals).filter { s -> words.all { it in s.title.lowercase() } }
    }.filter { it.recipeId !in seen }
    return DinnerSuggestions(pool.take(count), pool.size > count)
}

internal fun FakeBackend.planningRoute(method: String, path: String, sent: String, query: Map<String, String>): String? {
    if (!planning) return null
    val parts = path.trim('/').split('/')
    val week = parts[2]
    if (parts.last() == "move") {
        changes += "POST $path $sent"
        return json.encodeToString(plan(week))
    }
    return when {
        method == "GET" && parts.size == 4 -> json.encodeToString(planningOf(week))
        method == "GET" && parts.size == 5 && parts[4] == "suggestions" -> json.encodeToString(suggestionsOf(query))
        method == "POST" && parts.size == 5 && parts[4] == "done" -> {
            doneRefusal?.let { throw Refusal(409, it) }
            changes += "POST $path"
            picks.clear()
            json.encodeToString(plan(week))
        }
        parts.size == 6 && (parts[4] == "picks" || parts[4] == "not-this-week") -> {
            changes += "$method $path"
            val id = parts[5].toLong()
            val (list, other) = if (parts[4] == "picks") picks to hidden else hidden to picks
            list.remove(id)
            if (method == "PUT") {
                list += id
                other.remove(id)
            }
            json.encodeToString(planningOf(week))
        }
        else -> null
    }
}
