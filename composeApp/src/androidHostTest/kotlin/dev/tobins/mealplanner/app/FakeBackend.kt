package dev.tobins.mealplanner.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.tobins.mealplanner.api.CookingSyncStatus
import dev.tobins.mealplanner.api.DailyTargets
import dev.tobins.mealplanner.api.DayView
import dev.tobins.mealplanner.api.FoodDataset
import dev.tobins.mealplanner.api.IngredientPrice
import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.MappedLine
import dev.tobins.mealplanner.api.Me
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.OfferSourceStatus
import dev.tobins.mealplanner.api.OffersState
import dev.tobins.mealplanner.api.Pantry
import dev.tobins.mealplanner.api.PlanDay
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.PricesState
import dev.tobins.mealplanner.api.RecipeCost
import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeNutrition
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.TargetSuggestion
import dev.tobins.mealplanner.api.WeekPlan
import dev.tobins.mealplanner.api.WeightEntry
import dev.tobins.mealplanner.api.WeightLog
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.shopping.ShopSection
import dev.tobins.mealplanner.units.Dimension
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * A server on localhost that answers the calls of the screens with fixed sample data, so
 * the pictures of the screenshot tests never change on their own. The week is the one of
 * [TODAY]; [emptyWeek] leaves it without meals, and [emptyOffers], [emptyPrices] and [emptyRecipes]
 * leave those lists empty, for the empty states. The offers and prices are shared data that a
 * running server always has, so only a fake can show those states.
 */
class FakeBackend(
    private val emptyWeek: Boolean = false,
    private val emptyOffers: Boolean = false,
    private val emptyPrices: Boolean = false,
    private val emptyRecipes: Boolean = false,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange -> exchange.respond() }
        start()
    }
    private val json = Json { encodeDefaults = true }

    val settings = ServerSettings("http://127.0.0.1:${server.address.port}")

    override fun close() = server.stop(0)

    private fun HttpExchange.respond() {
        val body = try {
            route(requestURI.path)
        } catch (e: Exception) {
            null
        }
        if (body == null) {
            sendResponseHeaders(404, -1)
        } else {
            val bytes = body.toByteArray()
            responseHeaders.add("Content-Type", "application/json")
            sendResponseHeaders(200, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
        close()
    }

    private fun route(path: String): String? {
        val parts = path.trim('/').split('/')
        return when {
            path == "/api/me" -> json.encodeToString(Me("anna", "Anna"))
            path == "/api/foods/dataset" -> json.encodeToString(FoodDataset(2328, "NEVO online version 2025/9.0, RIVM, Bilthoven"))
            path == "/api/recipes" -> json.encodeToString(if (emptyRecipes) emptyList() else recipes)
            parts.size == 3 && parts[1] == "recipes" -> json.encodeToString(recipeDetail(parts[2].toLong()))
            path == "/api/routines" -> json.encodeToString(Routines(breakfast = 1))
            path == "/api/sync/cooking" -> json.encodeToString(CookingSyncStatus("2026-11-17T20:00:00Z", "a1b2c3d", null, 6))
            parts.size == 3 && parts[1] == "days" -> json.encodeToString(day(parts[2]))
            parts.size == 3 && parts[1] == "plans" -> json.encodeToString(plan(parts[2]))
            parts.size == 4 && parts[1] == "plans" && parts[3] == "shopping" -> json.encodeToString(shopping(parts[2]))
            path == "/api/offers" -> json.encodeToString(if (emptyOffers) OffersState(emptyList(), emptyList()) else offers)
            path == "/api/prices" -> json.encodeToString(if (emptyPrices) PricesState(emptyList(), emptyList(), null) else prices)
            path == "/api/weights" -> json.encodeToString(weights)
            path == "/api/pantry" -> json.encodeToString(Pantry(emptyList(), emptyList()))
            else -> null
        }
    }

    private val targets = DailyTargets(2100, 130)

    private fun meal(day: Int, slot: MealSlot, id: Long, title: String, kcal: Double, protein: Double, cost: Double = 2.4) =
        PlannedMeal(day, slot, id, title, 1.0, kcal, protein, cost = cost)

    /** Three meals a day; a title is long on purpose, to show how rows cope. */
    private fun planDay(index: Int): PlanDay {
        val meals = listOf(
            meal(index, MealSlot.BREAKFAST, 1, "Overnight oats with blueberries", 460.0, 24.0, cost = 1.1)
                .let { if (index == 2) it.copy(status = MealStatus.EATEN) else it },
            meal(index, MealSlot.LUNCH, 2L + index % 2, if (index % 2 == 0) "Chickpea salad with feta and roasted peppers" else "Tuna pasta salad", 640.0, 42.0),
            meal(index, MealSlot.DINNER, 4, "Slow-cooked chicken thighs with coconut, lemon and spinach", 810.0, 55.0, cost = 3.9),
        )
        return PlanDay(plusDays(WEEK_START, index), meals, meals.sumOf { it.kcal }, meals.sumOf { it.proteinG })
    }

    private fun plan(weekStart: String) = WeekPlan(
        weekStart = weekStart,
        targets = targets,
        days = (0..6).map { if (emptyWeek) PlanDay(plusDays(weekStart, it), emptyList(), 0.0, 0.0) else planDay(it) },
        emptySlots = if (emptyWeek) MealSlot.entries else emptyList(),
        cost = if (emptyWeek) null else 61.40,
        costWithoutOffers = if (emptyWeek) null else 68.95,
    )

    private fun day(date: String): DayView {
        val planned = plan(mondayOf(date)).days.first { it.date == date }
        val extras = listOf(LogEntry(1, date, "Apple and a handful of almonds", 210.0, 5.0))
        return DayView(
            date = date,
            weekStart = mondayOf(date),
            dayIndex = daysBetween(mondayOf(date), date),
            targets = targets,
            meals = planned.meals,
            extras = extras,
            eatenKcal = 460.0 + 210.0,
            eatenProteinG = 29.0,
            expectedKcal = planned.kcal + 210.0,
            expectedProteinG = planned.proteinG + 5.0,
        )
    }

    private fun shopping(weekStart: String): ShoppingList {
        if (emptyWeek) return ShoppingList(weekStart, emptyList(), emptyList())
        // In walking order, as the server sends it: by shop section, then by name.
        fun item(key: String, name: String, grams: Double?, pieces: Double?, section: ShopSection, recipe: String, checked: Boolean = false, offers: List<Offer> = emptyList(), imageUrl: String? = null) =
            ShoppingItem(key, name, grams, pieces, section.title, listOf(recipe), onHand = false, checked = checked, offers = offers, imageUrl = imageUrl)
        val chicken = "Slow-cooked chicken thighs with coconut, lemon and spinach"
        val chickpea = "Chickpea salad with feta and roasted peppers"
        return ShoppingList(
            weekStart = weekStart,
            items = listOf(
                item("pepper", "Red bell pepper", null, 4.0, ShopSection.FRUIT_VEG, chickpea, offers = listOf(offerList[0]), imageUrl = offerList[0].imageUrl),
                item("spinach", "Spinach", 600.0, null, ShopSection.FRUIT_VEG, chicken),
                item("chicken", "Chicken thigh fillet", 1400.0, null, ShopSection.MEAT, chicken, checked = true, imageUrl = offerList[1].imageUrl),
                item("feta", "Feta", 400.0, null, ShopSection.DAIRY, chickpea, imageUrl = offerList[2].imageUrl),
                item("oats", "Rolled oats", 350.0, null, ShopSection.BREAKFAST, "Overnight oats with blueberries"),
            ),
            staples = listOf("Olive oil", "Salt"),
            toTaste = listOf("Black pepper", "Fresh parsley"),
        )
    }

    private fun summary(id: Long, title: String, section: String, kcal: Double, protein: Double, rating: Int?, tags: List<String> = emptyList(), photo: Boolean = false) = RecipeSummary(
        id = id,
        title = title,
        section = section,
        tags = tags,
        servings = 2,
        source = RecipeSource.COOKING_SITE,
        kind = RecipeKind.MEAL,
        plannable = true,
        nutrition = RecipeNutrition(
            Nutrition(kcal, protein, 22.0, 6.0, 48.0, 7.0, 9.0, 620.0),
            complete = true, approximate = false, uncounted = emptyList(),
        ),
        mealSlots = listOf(MealSlot.LUNCH, MealSlot.DINNER),
        rating = rating,
        cost = RecipeCost(2.40, 2.10),
        imageUrl = if (photo) "/api/recipes/$id/image?v=0a1b2c" else null,
    )

    private val recipes = listOf(
        // Two recipes have a photo, like the real ones where a few do; the others show how the screens look without.
        summary(1, "Overnight oats with blueberries", "Breakfast", 460.0, 24.0, 4, listOf("quick", "vegetarian"), photo = true),
        summary(2, "Chickpea salad with feta and roasted peppers", "Lunch", 640.0, 42.0, 5, listOf("vegetarian")),
        summary(3, "Tuna pasta salad", "Lunch", 610.0, 38.0, null),
        summary(4, "Slow-cooked chicken thighs with coconut, lemon and spinach", "Dinner", 810.0, 55.0, 5, listOf("meal prep", "gluten free"), photo = true),
        summary(5, "Lentil soup", "Dinner", 520.0, 31.0, 3),
        summary(6, "Shakshuka", "Dinner", 560.0, 29.0, 4, listOf("vegetarian", "eggs")),
    )

    private fun recipeDetail(id: Long) = RecipeDetail(
        summary = recipes.first { it.id == id },
        ingredients = listOf(
            line(700.0, "g", Dimension.WEIGHT, "chicken thigh fillet"),
            line(400.0, "ml", Dimension.VOLUME, "coconut milk"),
            line(300.0, "g", Dimension.WEIGHT, "spinach"),
            line(1.0, "lemon", Dimension.COUNT, "juiced"),
            line(null, null, null, "salt and pepper to taste"),
        ),
        instructions = "Season the chicken and brown it in a heavy pan.\n\n" +
            "1. Add the coconut milk and the lemon juice.\n2. Cover and cook gently for 40 minutes.\n3. Stir in the spinach until it wilts.\n\n" +
            "Serve with rice or flatbread.",
        problems = emptyList(),
    )

    private fun line(amount: Double?, unit: String?, dimension: Dimension?, item: String) = MappedLine(
        IngredientLine(amount, unit, dimension, if (dimension == Dimension.COUNT) null else amount, item, null),
        ingredientId = 1,
        ingredientName = item.substringBefore(" to taste"),
        grams = amount.takeIf { dimension != Dimension.COUNT },
    )

    private val offerList = listOf(
        Offer(Store.AH, "Rode paprika 500 g", "25% korting", 2.24, 2.99, "500 g", "Groente", "2026-11-16", "2026-11-22", ingredientName = "Red bell pepper", imageUrl = "https://static.ah.nl/dam/product/paprika.webp"),
        Offer(Store.JUMBO, "Kipdijfilet 600 g", "2 voor 9,00", 4.50, 5.99, "600 g", "Vlees", "2026-11-16", "2026-11-22", ingredientName = "Chicken thigh fillet", imageUrl = "https://www.jumbo.com/INTERSHOP/static/kip.png"),
        Offer(Store.LIDL, "Griekse feta 200 g", "1+1 gratis", 1.79, null, "200 g", "Zuivel", "2026-11-16", "2026-11-22", ingredientName = "Feta", imageUrl = "https://www.lidl.nl/assets/feta.png"),
        Offer(Store.AH, "Bio havermout met een heel lange productnaam voor de test", "voor 1,49", 1.49, 1.89, "500 g", null, "2026-11-16", "2026-11-22"),
    )

    private val offers = OffersState(
        offers = offerList,
        sources = listOf(
            OfferSourceStatus(Store.AH, "2026-11-17T06:00:00Z", 2, null),
            OfferSourceStatus(Store.JUMBO, "2026-11-17T06:00:00Z", 1, null),
            OfferSourceStatus(
                Store.LIDL, "2026-11-17T06:00:00Z", 1, null,
                warning = "No Lidl offer runs into next week (the last ends 2026-11-22); the offers page may not show next week's offers.",
            ),
        ),
    )

    private val prices = PricesState(
        prices = listOf(
            IngredientPrice(1, "Chicken thigh fillet", Store.AH, "AH Kipdijfilet", "600 g", 5.99, 9.98),
            IngredientPrice(2, "Spinach", Store.AH, "AH Verse spinazie", "300 g", 1.99, 6.63),
            IngredientPrice(3, "Feta", Store.AH, "Set by hand", null, null, 11.50),
            IngredientPrice(4, "Rolled oats", Store.AH, "AH Havermout", "500 g", 1.09, 2.18),
        ),
        unpriced = listOf("Harissa", "Smoked paprika"),
        fetchedAt = "2026-11-17T06:00:00Z",
    )

    private val weights = WeightLog(
        entries = (0..9).map { WeightEntry(plusDays("2026-11-09", it), 82.4 - it * 0.08 + (it % 3) * 0.15, 82.4 - it * 0.09) },
        actualKgPerWeek = 0.2,
        suggestion = TargetSuggestion(2100, 1950, 0.2, 0.5),
    )

    companion object {
        /** The day all pictures are taken on: a Wednesday. */
        const val TODAY = "2026-11-18"
        val WEEK_START = mondayOf(TODAY)
    }
}
