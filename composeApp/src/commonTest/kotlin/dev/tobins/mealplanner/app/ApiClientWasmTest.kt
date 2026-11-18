package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.CookingRepoSetting
import dev.tobins.mealplanner.api.GenerateRequest
import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.ShoppingAdvice
import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.api.Store
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Sends every kind of request body of [ApiClient] and reads every kind of response. These run in the
 * browser too (`wasmJsBrowserTest`), where no serializer can be found by reflection at run time: a
 * body or response whose type is not known when compiled (a bare enum without `@Serializable`, a
 * generic helper that loses the type) fails there and not on the JVM or Android.
 */
class ApiClientWasmTest {
    private val week = """{"weekStart":"2026-11-16","targets":{"kcal":2000,"proteinG":100},"days":[],"emptySlots":[]}"""
    private val list = """{"weekStart":"2026-11-16","items":[],"staples":[]}"""
    private val summary = """{"id":1,"title":"Soup","section":"Dinner","tags":[],"servings":2,"source":"MANUAL","kind":"MEAL","plannable":true}"""
    private val recipe = """{"summary":$summary,"ingredients":[],"instructions":"","problems":[]}"""
    private val ingredient = """{"id":1,"name":"Onion","food":null,"gramsPerPiece":null,"gramsPerMl":null,"category":null,"pricePerKg":null,"isStaple":false}"""
    private val food = """{"nevoCode":1,"nameNl":"Ui","nameEn":"Onion","synonym":null,"groupEn":"Veg","basis":"PER_100_G","per100":{"kcal":1.0,"proteinG":1.0,"fatG":1.0,"saturatedFatG":1.0,"carbsG":1.0,"sugarG":1.0,"fibreG":1.0,"sodiumMg":1.0}}"""
    private val section = """{"ingredientId":1,"name":"Onion","section":"Veg","automatic":"Veg","source":"x","byHand":false}"""
    private val cooking = """{"syncedAt":null,"commit":null,"error":null,"everyHours":null}"""
    private val pantry = """{"staples":[$ingredient],"onHand":[]}"""
    private val weights = """{"entries":[{"date":"2026-11-16","kg":80.0}],"actualKgPerWeek":null,"suggestion":null}"""
    private val day = """{"date":"2026-11-16","weekStart":"2026-11-16","dayIndex":0,"targets":null,"meals":[],"extras":[],
        "eatenKcal":0.0,"eatenProteinG":0.0,"expectedKcal":0.0,"expectedProteinG":0.0}"""
    private val profile = """{"weightKg":80.0,"heightCm":180.0,"ageYears":30,"sex":"MALE","activity":"LIGHT"}"""

    /** What the server answers for a route: a body of the type that route answers. */
    private fun answer(method: String, path: String): String = when {
        method == "DELETE" && (path.startsWith("/api/log/") || path.matches(Regex("/api/recipes/\\d+"))) -> ""
        path == "/api/me" -> """{"username":"a","displayName":null}"""
        path == "/health" -> """{"status":"ok","version":"1"}"""
        path == "/api/recipes" && method == "GET" -> "[$summary]"
        path.startsWith("/api/recipes") -> recipe
        path == "/api/sync/cooking" && method == "POST" -> """{"added":0,"updated":0,"unchanged":0,"removed":0,"problems":{}}"""
        path.startsWith("/api/sync/cooking") -> cooking
        path.startsWith("/api/days/") || path == "/api/log" -> day
        path.startsWith("/api/weights") -> weights
        path == "/api/ingredients" -> "[$ingredient]"
        path == "/api/ingredients/sections" -> "[$section]"
        path.startsWith("/api/ingredients/") -> section
        path.startsWith("/api/pantry") -> pantry
        path == "/api/offers" || path == "/api/offers/refresh" -> """{"offers":[],"sources":[{"store":"AH","fetchedAt":null,"offerCount":0,"error":null}]}"""
        path.startsWith("/api/prices") -> """{"prices":[],"unpriced":[],"fetchedAt":null}"""
        path == "/api/shopping-advice" -> """{"buyAhead":true,"stockUp":false}"""
        path.contains("/shopping") -> list
        path == "/api/routines" -> """{"breakfast":3}"""
        path == "/api/mappings/unmapped" ->
            """[{"text":"ui","recipeCount":1,"exampleRecipe":"Soup","countedInPieces":false,"suggestedIngredients":[$ingredient],"suggestedFoods":[$food]}]"""
        path == "/api/mappings" -> ingredient
        path == "/api/foods" -> "[$food]"
        path == "/api/foods/dataset" -> """{"foodCount":1,"reference":null}"""
        path == "/api/profile" -> """{"profile":$profile,"suggested":null,"targets":null,"maintenanceKcal":null}"""
        path == "/api/shops" -> """{"stores":["AH"]}"""
        path.startsWith("/api/plans/") -> week
        else -> error("No answer for $method $path")
    }

    /** Runs [calls] against a client whose server answers by [answer]; returns "METHOD path body" per request. */
    private suspend fun sent(calls: suspend (ApiClient) -> Unit): List<String> {
        val requests = mutableListOf<String>()
        val api = ApiClient(
            ServerSettings("http://localhost"),
            engine = MockEngine { request ->
                val body = (request.body as? TextContent)?.text
                val path = request.url.encodedPath
                requests += listOfNotNull(request.method.value, path, body).joinToString(" ")
                respond(answer(request.method.value, path), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        calls(api)
        return requests
    }

    @Test
    fun aMealStatusIsSentAsItsNameInQuotes() = runTest {
        val requests = sent { api ->
            api.setMealStatus("2026-11-16", 2, MealSlot.DINNER, MealStatus.EATEN)
            api.setMealStatus("2026-11-16", 2, MealSlot.DINNER, MealStatus.SKIPPED)
            api.setMealStatus("2026-11-16", 2, MealSlot.DINNER, MealStatus.PLANNED)
        }
        assertEquals(
            listOf("EATEN", "SKIPPED", "PLANNED").map { """PUT /api/plans/2026-11-16/meals/2/DINNER/status "$it"""" },
            requests,
        )
    }

    @Test
    fun everyKindOfRequestBodyIsWrittenAsBefore() = runTest {
        val requests = sent { api ->
            api.setCookingRepo(CookingRepoSetting("https://example.org/r.git"))
            api.setCookingRepo(CookingRepoSetting(null, noSite = true))
            api.logFood(LogEntry(date = "2026-11-16", description = "Apple", kcal = 50.5, proteinG = 0.5))
            api.setWeight("2026-11-16", 80.5)
            api.setShoppingChecked("2026-11-16", "i1", true)
            api.tickShoppingAgain("2026-11-16", "i1")
            api.useUpShopping("2026-11-16", "i1")
            api.addShoppingExtra("2026-11-16", "Coffee")
            api.saveShoppingAdvice(ShoppingAdvice(buyAhead = true, stockUp = false))
            api.generateRecipe(GenerateRequest())
            api.generateIntoPlan("2026-11-16", 1, MealSlot.LUNCH, GenerateRequest(slot = MealSlot.LUNCH, wish = "soup"))
            api.createRecipe(RecipeInput("Soup", 2, "1 onion"))
            api.updateRecipe(1, RecipeInput("Soup", 2, "1 onion", mealSlots = listOf(MealSlot.DINNER)))
            api.updateRecipeSettings(1, RecipeSettings(RecipeKind.COMPONENT, listOf(MealSlot.LUNCH)))
            api.rateRecipe(1, 4)
            api.saveRoutines(Routines(breakfast = 3))
            api.map(MappingRequest("ui", ingredientId = 1))
            api.setSection(1, "Veg")
            api.setKeeps(1, true)
            api.saveProfile(Profile(80.0, 180.0, 30, Sex.MALE, ActivityLevel.LIGHT))
            api.saveShops(ShopsSetting(listOf(Store.AH)))
            api.chooseRecipe("2026-11-16", 1, MealSlot.DINNER, 12)
        }
        assertEquals(
            listOf(
                """PUT /api/sync/cooking/repo {"url":"https://example.org/r.git"}""",
                """PUT /api/sync/cooking/repo {"url":null,"noSite":true}""",
                """POST /api/log {"id":0,"date":"2026-11-16","description":"Apple","kcal":50.5,"proteinG":0.5}""",
                """PUT /api/weights/2026-11-16 80.5""",
                """PUT /api/plans/2026-11-16/shopping {"key":"i1","checked":true}""",
                """PUT /api/plans/2026-11-16/shopping/again {"key":"i1"}""",
                """POST /api/plans/2026-11-16/shopping/use-up {"key":"i1"}""",
                """POST /api/plans/2026-11-16/shopping/extras {"name":"Coffee"}""",
                """PUT /api/shopping-advice {"buyAhead":true,"stockUp":false}""",
                """POST /api/recipes/generate {"mode":"PREFERENCE","slot":"DINNER","wish":""}""",
                """POST /api/plans/2026-11-16/meals/1/LUNCH/generate {"mode":"PREFERENCE","slot":"LUNCH","wish":"soup"}""",
                """POST /api/recipes {"title":"Soup","servings":2,"ingredients":"1 onion","instructions":"","tags":[],"mealSlots":[]}""",
                """PUT /api/recipes/1 {"title":"Soup","servings":2,"ingredients":"1 onion","instructions":"","tags":[],"mealSlots":["DINNER"]}""",
                """PUT /api/recipes/1/settings {"kind":"COMPONENT","mealSlots":["LUNCH"]}""",
                """PUT /api/recipes/1/rating {"rating":4}""",
                """PUT /api/routines {"breakfast":3,"snack":null}""",
                """PUT /api/mappings {"text":"ui","ingredientId":1,"nevoCode":null,"name":null,"gramsPerPiece":null,"gramsPerMl":null}""",
                """PUT /api/ingredients/1/section {"section":"Veg"}""",
                """PUT /api/ingredients/1/keeps {"keeps":true}""",
                """PUT /api/profile {"weightKg":80.0,"heightCm":180.0,"ageYears":30,"sex":"MALE","activity":"LIGHT","goalKgPerWeek":0.5,"kcalOverride":null,"proteinOverrideG":null,"likes":[],"dislikes":[],"minPortions":0.75,"maxPortions":1.5,"priority":"BALANCED","fibreOverrideG":null}""",
                """PUT /api/shops {"stores":["AH"]}""",
                """PUT /api/plans/2026-11-16/meals/1/DINNER {"recipeId":12}""",
            ),
            requests,
        )
    }

    @Test
    fun everyKindOfResponseIsRead() = runTest {
        val requests = sent { api ->
            api.me(); api.health(); api.recipes(); api.recipe(1); api.syncCookingSite(); api.cookingSyncStatus()
            api.day("2026-11-16"); api.weights(); api.deleteWeight("2026-11-16"); api.ingredients()
            api.setStaple(1, true); api.setStaple(1, false); api.setOnHand(1, true); api.pantry()
            api.offers("2026-11-16"); api.refreshOffers(); api.prices(); api.refreshPrices()
            api.shoppingList("2026-11-16"); api.shoppingAdvice(); api.removeShoppingExtra("2026-11-16", 1)
            api.setShoppingDismissed("2026-11-16", ShoppingAdviceGroup.entries.first(), true)
            api.setShoppingDismissed("2026-11-16", ShoppingAdviceGroup.entries.first(), false)
            api.routines(); api.unmapped(); api.ingredientSections(); api.searchFoods("ui"); api.foodDataset()
            api.profile(); api.plan("2026-11-16"); api.shops(); api.generatePlan("2026-11-16")
            api.rerollMeal("2026-11-16", 1, MealSlot.DINNER); api.setMealLocked("2026-11-16", 1, MealSlot.DINNER, true)
            api.setMealLocked("2026-11-16", 1, MealSlot.DINNER, false)
            api.deleteLog(1); api.deleteRecipe(1)
        }
        assertEquals(37, requests.size)
    }
}
