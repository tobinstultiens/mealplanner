package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.history.Verdict
import dev.tobins.mealplanner.server.offers.OfferSource
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verdicts from a history recorded the way the server records it, refresh by refresh: ten weeks of nights from
 * Monday 14 September 2026, in which AH offers samphire 1+1 three times and now 2+1.
 */
class DealVerdictRepositoryTest {
    private val db = openDatabase("jdbc:sqlite::memory:").second
    private val foods = FoodRepository(db)
    private val users = UserRepository(db)

    private fun night(n: Int): Instant = Instant.parse("2026-09-14T03:00:00Z").plus(Duration.ofDays(n.toLong()))

    /** AH's offers on night [n], each valid for its week (Monday to Sunday). */
    private fun offersOn(n: Int): List<Offer> {
        val monday = serverDay(night(n - n % 7)).toString()
        val sunday = serverDay(night(n - n % 7 + 6)).toString()
        fun offer(deal: String, category: String = "Groente, aardappelen") =
            Offer(Store.AH, "AH Zeekraal 100 gram", deal, category = category, validFrom = monday, validUntil = sunday)
        val samphire = when (n) {
            in 7..13, in 28..34, in 49..55 -> offer("1+1 gratis")
            // Filed as cold cuts that week: no account's samphire, whatever the title says.
            in 17..20 -> offer("1+1 gratis", category = "Vleeswaren")
            in 63..70 -> offer("2+1 gratis")
            else -> null
        }
        // Something on offer every night, so that every night is a night the store was read.
        return listOfNotNull(samphire, Offer(Store.AH, "AH Jong belegen kaas", "25% korting", category = "Kaas", validFrom = monday, validUntil = sunday))
    }

    private fun recordTenWeeks() {
        val source = object : OfferSource {
            override val store = Store.AH
            var night = 0
            override suspend fun fetch() = offersOn(night)
        }
        val offers = OfferRepository(db, listOf(source))
        for (n in 0..TODAY) {
            source.night = n
            runBlocking { offers.refresh(now = night(n)) }
        }
    }

    private fun verdicts(accountId: Long, shops: Set<Store> = Store.entries.toSet()) =
        DealVerdictRepository(db, OfferRepository(db, emptyList()), IngredientRepository(db, foods, accountId), { shops }).current(night(TODAY)).verdicts

    @Test
    fun anOfferIsJudgedByItsProductsRecordedHistory() {
        val alice = users.resolve(Identity("sub-alice", "alice")).id
        IngredientRepository(db, foods, alice).create(IngredientInput("Zeekraal"))
        recordTenWeeks()

        val verdict = verdicts(alice).single()
        assertEquals("AH Zeekraal 100 gram" to "Zeekraal", verdict.title to verdict.ingredient)
        assertEquals(Verdict.WORSE_THAN_USUAL, verdict.verdict, verdict.summary)
        assertEquals(TODAY + 1, verdict.daysRead)
        assertEquals(
            listOf("2026-09-21" to "2026-09-27", "2026-10-12" to "2026-10-18", "2026-11-02" to "2026-11-08"),
            verdict.earlier.map { it.from to it.until },
            "the week filed as cold cuts is not samphire",
        )
        assertEquals(listOf(0.5, 0.5, 0.5), verdict.earlier.map { it.paidShare })
        assertEquals("2026-11-02" to "2026-09-21", verdict.lastAsCheap?.from to verdict.lowest?.from)
        assertEquals(3 * 7 + 8 to 8, verdict.daysOnOffer to verdict.daysThisDeal, "three weeks of 1+1 and eight nights of 2+1")
    }

    @Test
    fun eachAccountGoesByItsOwnNamesAndStoresNotTheOnesStoredWithTheHistory() {
        val alice = users.resolve(Identity("sub-alice", "alice")).id
        val bob = users.resolve(Identity("sub-bob", "bob")).id
        IngredientRepository(db, foods, alice).create(IngredientInput("Zeekraal"))
        recordTenWeeks()
        val samphire = db.priceHistoryQueries.selectOfferHistory().executeAsList().filter { it.title.contains("Zeekraal") && it.category != "Vleeswaren" }
        assertEquals(listOf("Zeekraal"), samphire.map { it.ingredient }.distinct())

        // The history's rows name Alice's ingredient; Bob has none like it, so he gets no verdict on it.
        assertEquals(emptyList(), verdicts(bob))
        // His own name for it finds it, and the verdict is his.
        IngredientRepository(db, foods, bob).run { map(MappingRequest("zeekraal", ingredientId = create(IngredientInput("Samphire")).id)) }
        assertEquals(listOf("Samphire" to Verdict.WORSE_THAN_USUAL), verdicts(bob).map { it.ingredient to it.verdict })
        // Only at the stores he shops at.
        assertEquals(emptyList(), verdicts(bob, shops = setOf(Store.JUMBO, Store.LIDL)))
        assertEquals(listOf("Zeekraal"), verdicts(alice).map { it.ingredient })
    }

    private companion object {
        const val TODAY = 70
    }
}
