package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.DinnerSuggestion
import dev.tobins.mealplanner.api.ManyAddedInput
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.api.SuggestionGround
import dev.tobins.mealplanner.server.UserScope
import dev.tobins.mealplanner.server.accounts.Identity
import dev.tobins.mealplanner.server.accounts.UserRepository
import dev.tobins.mealplanner.server.ingredients.FoodRepository
import dev.tobins.mealplanner.server.ingredients.parseDelimited
import dev.tobins.mealplanner.server.openDatabase
import dev.tobins.mealplanner.server.prices.OfferRepository
import dev.tobins.mealplanner.server.prices.PriceRepository
import dev.tobins.mealplanner.server.recipes.CookingSiteReader
import java.io.File
import java.sql.DriverManager
import java.time.LocalDate
import java.util.Properties
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the planning screen suggests for a typical week of the owner's (issue 255): the first two screens of six, with
 * their reasons, before and after a pick, printed for reading. The recipes are the cooking repo's, the nutrition NEVO's,
 * the regular AH prices those of `prices/ah-regular-prices-2026-11-19.csv`, and the offers a night's fetch stored in a
 * database (`-PoffersDb=`, opened read only: point it at a copy). The four weeks before are planned by the planner, so
 * that some recipes were last eaten weeks ago, as in a household that uses the app. Skipped when any file is missing:
 * `./gradlew :server:test --tests '*PlanningSuggestionsMeasurementTest*' -i -PcookingRepo=... -PnevoCsv=... -PoffersDb=...`
 */
class PlanningSuggestionsMeasurementTest {
    private val cooking = File(System.getProperty("cookingRepo"))
    private val nevo = File(System.getProperty("nevoCsv"))
    private val offersDb = File(System.getProperty("offersDb").orEmpty())
    private val week = LocalDate.of(2026, 11, 16)

    @Test
    fun theFirstTwoScreensOfATypicalWeekMixTheGrounds() {
        if (!File(cooking, "content").isDirectory || !nevo.isFile || !offersDb.isFile) {
            println("Cooking repo, NEVO file or offers database not found; skipping")
            return
        }
        val db = openDatabase("jdbc:sqlite::memory:").second
        val foods = FoodRepository(db).also { it.importNevo(nevo) }
        val offers = OfferRepository(db, emptyList())
        val prices = PriceRepository(db, null, foods, offers)
        val account = UserScope(UserRepository(db).resolve(Identity("measure", "measure")).id, db, foods, offers, prices, null, createTempDirectory("pictures").toFile(), today = { week })
        account.ingredients.applySeed()
        loadRegularPrices(db)
        val copied = copyOffers(db)
        account.recipes.syncCookingSite(checkNotNull(CookingSiteReader(cooking).readAll()))
        account.recipes.setAdded(ManyAddedInput(account.recipes.bank().map { it.id }, added = true))
        account.profiles.save(Profile(75.0, 180.0, 35, Sex.MALE, ActivityLevel.MODERATE))
        for (weeksBefore in 4 downTo 1) account.plans.generate(week.minusWeeks(weeksBefore.toLong()).toString(), seed = weeksBefore.toLong())
        val stores = account.shops.inUse()
        println("$copied offers of the week, stores ${stores.joinToString()}; ${account.plans.candidates(week).size} recipes in the rotation")

        val pool = account.planning.suggestions(week.toString(), emptySet(), PlanningRepository.MAX_SUGGESTIONS).suggestions
        val meals = account.planning.suggestions(week.toString(), emptySet(), PlanningRepository.MAX_SUGGESTIONS, query = "").suggestions
        fun List<DinnerSuggestion>.byGround() = SuggestionGround.entries.joinToString { ground -> "$ground ${count { ground in it.grounds }}" }
        println("${pool.size} recipes are suggested; ${pool.byGround()}")
        println(
            "Of ${meals.size} meals of mine, ${meals.count { it.grounds.isNotEmpty() }} have a ground (${meals.byGround()}); weeks since eaten of the rest: " +
                meals.filter { it.grounds.isEmpty() }.groupingBy { it.weeksSinceEaten }.eachCount().toSortedMap(nullsLast()),
        )
        val first = account.planning.suggestions(week.toString(), emptySet(), 6).suggestions
        val second = account.planning.suggestions(week.toString(), first.map { it.recipeId }.toSet(), 6)
        print("Screen 1", first)
        print("Screen 2 (Show others)", second.suggestions)
        account.planning.pick(week.toString(), first.first().recipeId)
        print("Screen 1 after choosing \"${first.first().title}\"", account.planning.suggestions(week.toString(), emptySet(), 6).suggestions)
        val strip = account.planning.get(week.toString()).totals
        println("Strip: %d meal(s), €%.2f (without offers €%.2f), %.0f of %.0f kcal".format(strip.meals, strip.cost, strip.costWithoutOffers, strip.kcal, strip.targetKcal))

        assertTrue((first + second.suggestions).all { it.grounds.isNotEmpty() })
        assertTrue(first.map { it.grounds.first() }.distinct().size > 1 || first.size < 6, "the first screen mixes the grounds where the recipes allow it")
    }

    private fun print(heading: String, suggestions: List<DinnerSuggestion>) {
        println("== $heading")
        for (s in suggestions) {
            val reasons = s.grounds.joinToString("; ") { ground ->
                when (ground) {
                    SuggestionGround.ON_OFFER -> "on offer: " + s.offers.joinToString { offer ->
                        "${offer.ingredient} at ${offer.store} (\"${offer.title}\", ${offer.deal}, saves €" + "%.2f".format(offer.savingPerServing ?: 0.0) + " a serving)"
                    }
                    SuggestionGround.USES_WHAT_IS_THERE -> "uses what is there: " + (s.onHand + s.packRests.map { "the rest of the ${it.ingredient} from ${it.openedBy}" }).joinToString()
                    SuggestionGround.FAVOURITE -> "favourite: " + listOfNotNull(s.rating?.let { "rated $it" }, s.weeksSinceEaten?.let { "not eaten for $it weeks" }).joinToString()
                    SuggestionGround.NOT_TRIED_YET -> "not tried yet"
                }
            }
            println("- ${s.title} | €%.2f a serving%s | %.2f portions: %.0f kcal, %.0f g protein | %s".format(
                s.costPerServing ?: Double.NaN, s.savingPerServing?.takeIf { it > 0.005 }?.let { " (saves €%.2f)".format(it) }.orEmpty(),
                s.portions, s.kcal, s.proteinG, reasons,
            ))
        }
    }

    /** The regular AH prices of the day the packs were measured on, as [RealRecipeFixture] loads them. */
    private fun loadRegularPrices(db: dev.tobins.mealplanner.server.db.Database) {
        val rows = parseDelimited(checkNotNull(javaClass.getResource("/prices/ah-regular-prices-2026-11-19.csv")).readText(), ',').drop(1)
        for (row in rows) {
            val (name, packSize, packPrice, perKg) = row
            db.priceQueries.upsertPrice(
                name, "AH", name, packSize.ifBlank { null }, packPrice.toDoubleOrNull(), perKg.toDouble(), "2026-11-19T00:00:00Z",
                row[4].ifBlank { null }, row[5].ifBlank { null }, null,
            )
        }
    }

    /** Copies the offers valid during the week from [offersDb], opened read only; how many. */
    private fun copyOffers(db: dev.tobins.mealplanner.server.db.Database): Int {
        val readOnly = Properties().apply { put("open_mode", "1") }
        return DriverManager.getConnection("jdbc:sqlite:${offersDb.path}", readOnly).use { connection ->
            val rows = connection.prepareStatement(
                "SELECT store, title, deal, price, originalPrice, packSize, category, validFrom, validUntil, url, imageUrl FROM offer WHERE validFrom <= ? AND validUntil >= ?",
            ).apply { setString(1, week.plusDays(6).toString()); setString(2, week.toString()) }.executeQuery()
            var count = 0
            while (rows.next()) {
                fun double(column: String) = rows.getDouble(column).takeUnless { rows.wasNull() }
                db.planQueries.insertOffer(
                    rows.getString("store"), rows.getString("title"), rows.getString("deal"), double("price"), double("originalPrice"),
                    rows.getString("packSize"), rows.getString("category"), rows.getString("validFrom"), rows.getString("validUntil"),
                    rows.getString("url"), rows.getString("imageUrl"),
                )
                count++
            }
            count
        }
    }
}
