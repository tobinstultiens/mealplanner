package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.OfferExclusionRequest
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.VerdictKind
import dev.tobins.mealplanner.api.VerdictMeasure
import dev.tobins.mealplanner.server.history.Measure
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

    private fun recordTenWeeks(store: Store = Store.AH, offersOn: (Int) -> List<Offer> = ::offersOn) {
        val source = object : OfferSource {
            override val store = store
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

    /**
     * "Not this ingredient" (issue 316): an offer the account excluded gets no verdict for that ingredient, in any pack,
     * and its history is read with the same exclusions: an offer that is another of the account's ingredients then is
     * judged by its whole history as that one. Another account's verdicts stay as they were.
     */
    @Test
    fun anOfferTheAccountSaidIsNotItsIngredientIsJudgedForNoneOrForTheOneItIsInstead() {
        val alice = users.resolve(Identity("sub-alice", "alice")).id
        val bob = users.resolve(Identity("sub-bob", "bob")).id
        val catalogue = IngredientRepository(db, foods, alice)
        val samphire = catalogue.create(IngredientInput("Zeekraal")).id
        // The longer term wins: before the exclusion the offer is this one's.
        val seaVegetables = catalogue.create(IngredientInput("Sea vegetables")).id.also { catalogue.map(MappingRequest("ah zeekraal", ingredientId = it)) }
        IngredientRepository(db, foods, bob).create(IngredientInput("Zeekraal"))
        recordTenWeeks()
        val exclusions = OfferExclusionRepository(db, alice, catalogue)
        assertEquals(listOf("Sea vegetables"), verdicts(alice).map { it.ingredient })

        exclusions.exclude(OfferExclusionRequest(Store.AH, "AH Zeekraal 200 gram", seaVegetables))

        val verdict = verdicts(alice).single()
        assertEquals("Zeekraal" to Verdict.WORSE_THAN_USUAL, verdict.ingredient to verdict.verdict, verdict.summary)
        assertEquals(3, verdict.earlier.size, "its history came along: ${verdict.earlier}")
        exclusions.exclude(OfferExclusionRequest(Store.AH, "AH Zeekraal", samphire))
        assertEquals(emptyList(), verdicts(alice))
        assertEquals(listOf("Zeekraal" to Verdict.WORSE_THAN_USUAL), verdicts(bob).map { it.ingredient to it.verdict })
    }

    /** Issue 197: "now" is what the store shows today, not what its dates say. */
    @Test
    fun aPriceTheStoreReplacedMidWeekIsNoLongerOnOffer() {
        val alice = users.resolve(Identity("sub-alice", "alice")).id
        IngredientRepository(db, foods, alice).create(IngredientInput("Kipfilet"))
        // Lidl: chicken for 2,29 on Thursday and Friday, then 2,49 on the same dates (nights 66 to 72).
        recordTenWeeks(Store.LIDL) { n ->
            val chicken = when (n) {
                66, 67 -> 2.29
                in 68..72 -> 2.49
                else -> null
            }?.let { Offer(Store.LIDL, "Kipfilet", null, it, packSize = "500 g", validFrom = serverDay(night(66)).toString(), validUntil = serverDay(night(72)).toString()) }
            listOfNotNull(chicken, Offer(Store.LIDL, "Jong belegen kaas", null, 4.99, validFrom = serverDay(night(n)).toString(), validUntil = serverDay(night(n)).toString()))
        }

        val verdict = verdicts(alice).single()
        // Today's 2,49 for 500 g, €4,98 a kg; 2,29 is not on offer now, and ended early.
        assertEquals(4.98 to emptyList(), verdict.value to verdict.alongside)
        assertEquals(listOf(Triple(2.29, "2026-11-19", true)), verdict.earlier.map { Triple(it.price, it.from, it.endedEarly) })
        assertEquals(Verdict.NOT_ENOUGH_HISTORY, verdict.verdict, verdict.summary)
        assertEquals(false, "Also on offer now" in verdict.summary, verdict.summary)
    }

    /**
     * Jumbo's chicken on offer in weeks 2, 5 and 8 for 5,99 and now for 4,99, each time with its pack written another
     * way: weighed a little differently, or with a footnote. Read as written, each text would be a pack of its own with
     * no earlier offer, and the verdict "not enough history" (issue 118, step c).
     */
    private fun recordChickenWeighedEachWeek() = recordTenWeeks(Store.JUMBO) { n ->
        val (pack, price) = when (n / 7) {
            1 -> "Bak ca. 460 gram" to 5.99
            4 -> "Bak ca. 455 gram<br />M.u.v. Biologisch" to 5.99
            7 -> "Bak ca. 445 gram<br />M.u.v. Biologisch<br /><br />" to 5.99
            TODAY / 7 -> "Bak ca. 450 gram" to 4.99
            else -> null to null
        }
        val monday = serverDay(night(n - n % 7)).toString()
        val sunday = serverDay(night(n - n % 7 + 6)).toString()
        val chicken = pack?.let { Offer(Store.JUMBO, "Jumbo Kipfilet", "voor ${price.toString().replace('.', ',')}", price, packSize = it, validFrom = monday, validUntil = sunday) }
        listOfNotNull(chicken, Offer(Store.JUMBO, "Jumbo Jong belegen kaas", "25% korting", validFrom = monday, validUntil = sunday))
    }

    @Test
    fun anOfferRecordedWithMarkupInItsTitleIsHistoryOfTheCleanedOffer() {
        // issue 320: weeks 2, 5 and 8 were recorded before the titles were cleaned; tonight's offer is read clean.
        val alice = users.resolve(Identity("sub-alice", "alice")).id
        IngredientRepository(db, foods, alice).create(IngredientInput("Roomijs"))
        recordTenWeeks(Store.AH) { n ->
            val monday = serverDay(night(n - n % 7)).toString()
            val sunday = serverDay(night(n - n % 7 + 6)).toString()
            val (title, price) = when (n / 7) {
                1, 4, 7 -> "AH Roomijs &amp;<br />vanille" to 5.99
                TODAY / 7 -> "AH Roomijs & · vanille" to 4.99
                else -> null to null
            }
            val ice = title?.let { Offer(Store.AH, it, "voor ${price.toString().replace('.', ',')}", price, packSize = "500 ml", validFrom = monday, validUntil = sunday) }
            listOfNotNull(ice, Offer(Store.AH, "AH Jong belegen kaas", "25% korting", validFrom = monday, validUntil = sunday))
        }

        val verdict = verdicts(alice).single()
        assertEquals(Verdict.REAL_DEAL, verdict.verdict, verdict.summary)
        assertEquals(listOf("2026-09-21", "2026-10-12", "2026-11-02"), verdict.earlier.map { it.from }, "the periods recorded with markup are read")
    }

    @Test
    fun aPackWrittenAnotherWayEachWeekIsStillOnePack() {
        val alice = users.resolve(Identity("sub-alice", "alice")).id
        IngredientRepository(db, foods, alice).create(IngredientInput("Kipfilet"))
        recordChickenWeighedEachWeek()

        val verdict = verdicts(alice).single()
        assertEquals(Verdict.REAL_DEAL, verdict.verdict, verdict.summary)
        assertEquals(listOf("2026-09-21", "2026-10-12", "2026-11-02"), verdict.earlier.map { it.from })
        assertEquals(setOf("bak ca. 445 gram"), (verdict.earlier.map { it.pack } + verdict.lowest!!.pack).toSet(), "known by the lightest of them")
        assertEquals(false, "in this pack" in verdict.summary, "no other pack: " + verdict.summary)
    }

    @Test
    fun theAppGetsTheVerdictsWithTheOffersAndTheShoppingList() {
        val alice = users.resolve(Identity("sub-alice", "alice")).id
        val ingredients = IngredientRepository(db, foods, alice)
        ingredients.create(IngredientInput("Kipfilet"))
        recordChickenWeighedEachWeek()
        val repository = DealVerdictRepository(db, OfferRepository(db, emptyList()), ingredients) { Store.entries.toSet() }
        val state = OfferRepository(db, emptyList()).state(serverDay(night(TODAY)))
        val chicken = state.offers.single { it.title == "Jumbo Kipfilet" }

        val judged = repository.on(state, night(TODAY)).offers.associate { it.title to it.verdict }
        val verdict = judged.getValue("Jumbo Kipfilet")!!
        assertEquals(VerdictKind.REAL_DEAL to VerdictMeasure.PER_KG, verdict.verdict to verdict.measure)
        assertEquals(listOf("2026-11-02", "2026-10-12", "2026-09-21"), verdict.earlier.map { it.from }, "newest first")
        assertEquals(null, verdict.lastAsCheap, "the lowest so far")
        assertEquals(TODAY + 1 to 56, verdict.daysRead to verdict.daysNeeded)
        assertEquals(null, judged.getValue("Jumbo Jong belegen kaas"), "no ingredient of hers: no verdict")

        // The shopping list's offers are matched by what the store published, whatever the server added to them.
        val row = ShoppingItem("i1", "Kipfilet", 450.0, null, "Meat", emptyList(), onHand = false, checked = false, offers = listOf(chicken.copy(paidShare = 0.83, ingredientId = null)))
        val list = repository.on(ShoppingList("2026-11-16", listOf(row), emptyList(), stockUp = listOf(row.copy(key = "stock:i1"))), night(TODAY))
        assertEquals(listOf(VerdictKind.REAL_DEAL, VerdictKind.REAL_DEAL), (list.items + list.stockUp).map { it.offers.single().verdict?.verdict })
        assertEquals(0.83, list.items.single().offers.single().paidShare)
    }

    /** The app's verdicts are the server's by name: a value added to one and not the other would fail every answer judged. */
    @Test
    fun theAppsVerdictsAndMeasuresAreTheServersOwn() {
        assertEquals(Verdict.entries.map { it.name }, VerdictKind.entries.map { it.name })
        assertEquals(Measure.entries.map { it.name }, VerdictMeasure.entries.map { it.name })
    }

    @Test
    fun aVerdictThatCannotBeWorkedOutLeavesTheAnswerAsItWas() {
        val alice = users.resolve(Identity("sub-alice", "alice")).id
        val ingredients = IngredientRepository(db, foods, alice)
        ingredients.create(IngredientInput("Kipfilet"))
        recordChickenWeighedEachWeek()
        val broken = DealVerdictRepository(db, OfferRepository(db, emptyList()), ingredients) { error("the stores could not be read") }
        val state = OfferRepository(db, emptyList()).state(serverDay(night(TODAY)))

        assertEquals(state, broken.on(state, night(TODAY)))
    }

    private companion object {
        const val TODAY = 70
    }
}
