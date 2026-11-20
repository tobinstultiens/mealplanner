package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.OffersState
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.pricing.DealTerms
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.history.LOOKBACK_WEEKS
import dev.tobins.mealplanner.server.history.normalised
import dev.tobins.mealplanner.server.offers.OfferSource
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.io.path.createTempDirectory
import kotlin.system.measureTimeMillis
import kotlin.test.Test

/**
 * What the verdicts say on the offers of a real database, printed for reading. Runs only when PRICE_HISTORY_DB
 * names a database file, which is copied twice and never opened itself:
 * `PRICE_HISTORY_DB=/path/to/dev.db ./gradlew :server:test --tests '*DealVerdictRealDataTest*' --rerun -i`
 *
 * 1. The first copy as it is: its stored offers are replayed through refreshes (as [PriceHistoryRealDataTest]
 *    does) so that it has a history, and the verdicts on the offers of the account with the most ingredients
 *    are printed, by verdict, with how long reading them took. A young history says "not enough history".
 *    When PRICE_HISTORY_NEXT_DB names a later database of the same stores (also only copied), its stored offers
 *    are replayed after that as one more night, at the time it fetched them: two real nights, so that an offer
 *    the store stopped showing in between is not "on offer now".
 * 2. The second copy gets twelve weeks of made-up history first: every store read every night, and a few of
 *    the account's real offers given earlier offers chosen to show each verdict's wording. Its verdicts are
 *    printed too; they say what the rules do, not what the stores did.
 * 3. The third copy gets a made-up year: every store read every night, and each stored offer one week in four
 *    with a row and half of them a second (a change mid-week), other products the other weeks, about as many
 *    rows as a year of the real stores leaves. What a call costs is printed, and what reading the stores' whole
 *    year (as the first version of this did) cost.
 */
class DealVerdictRealDataTest {
    @Test
    fun theVerdictsOnARealDatabasesOffers() {
        val original = System.getenv("PRICE_HISTORY_DB")?.let(::File) ?: return
        println("== As recorded")
        val real = open(original)
        report(real.db, real.account, real.now, real.verdicts())
        real.forTheApp()
        System.getenv("PRICE_HISTORY_NEXT_DB")?.let(::File)?.let { next ->
            println("\n== As recorded, and the next database's offers as one more night")
            val twoNights = open(original, next = next)
            report(twoNights.db, twoNights.account, twoNights.now, twoNights.verdicts())
            twoNights.forTheApp()
        }

        println("\n== With twelve weeks of made-up history before it")
        var products = emptyList<Pair<String, Offer>>()
        val madeUp = open(original) { db, account, first -> products = madeUpHistory(db, account, first) }
        val shown = madeUp.verdicts()
        report(madeUp.db, madeUp.account, madeUp.now, shown)
        println("\nThe products given a made-up history:")
        val forTheApp = madeUp.forTheApp()
        for ((scenario, offer) in products) {
            val verdict = shown.verdicts.firstOrNull { it.store == offer.store && it.title == offer.title }
            println("- [$scenario] ${offer.store.label}, ${offer.title} (${offer.packSize ?: "no pack size"}), ${dealOf(offer.deal)}: ${verdict?.verdict}\n  ${verdict?.summary}")
            forTheApp.offers.firstOrNull { it.store == offer.store && it.title == offer.title && it.packSize == offer.packSize }?.verdict?.let { println("  the app's: $it") }
        }

        println("\n== A made-up year: what a call costs")
        val year = open(original) { db, _, first -> madeUpYear(db, first) }
        cost(year)
    }

    private fun cost(year: Opened) {
        val from = year.now.minus(Duration.ofDays(LOOKBACK_WEEKS * 7)).toString()
        val rows = year.db.priceHistoryQueries.selectOfferHistory().executeAsList().size
        val recordings = Store.entries.sumOf { PriceHistoryRepository(year.db).recordings(it, year.now.minus(Duration.ofDays(400)), year.now).size }
        println("History: $rows rows, $recordings recordings")
        val wholeYear = "SELECT * FROM offerHistory WHERE store IN ('AH', 'JUMBO', 'LIDL') AND lastSeen >= ? AND firstSeen <= ?"
        repeat(3) {
            var read = 0L
            val ms = measureTimeMillis {
                read = year.driver.executeQuery(null, wholeYear, { cursor ->
                    var n = 0L
                    while (cursor.next().value) {
                        for (column in 0 until OFFER_HISTORY_COLUMNS) cursor.getString(column)
                        n++
                    }
                    QueryResult.Value(n)
                }, 2) {
                    bindString(0, from)
                    bindString(1, year.now.toString())
                }.value
            }
            println("Reading the stores' whole year, as before: $read rows in $ms ms")
        }
        // The products of every offer valid today, more than any account's: what the verdicts read now, at most.
        val products = year.db.planQueries.selectOffersValidOn(serverDay(year.now).toString()).executeAsList()
            .groupBy({ Store.valueOf(it.store) }, { normalised(it.title) })
        repeat(3) {
            var read = 0
            val ms = measureTimeMillis {
                read = products.entries.sumOf { (store, titles) -> PriceHistoryRepository(year.db).offerPeriods(store, titles, Instant.parse(from), year.now).size }
            }
            println("Reading the rows of all ${products.values.sumOf { it.size }} products valid today: $read rows in $ms ms")
        }
        repeat(3) { year.verdicts() }
        // What every answer that carries the shopping list adds, ticks included: the verdicts on the account's offers.
        repeat(5) { year.forTheApp() }
    }

    private class Opened(val driver: SqlDriver, val db: Database, val account: Long, val now: Instant) {
        fun verdicts(): DealVerdicts {
            val ingredients = IngredientRepository(db, FoodRepository(db), account)
            val repository = DealVerdictRepository(db, OfferRepository(db, emptyList()), ingredients, ShopsRepository(db, account)::inUse)
            lateinit var verdicts: DealVerdicts
            val ms = measureTimeMillis { verdicts = repository.current(now) }
            println("Read ${verdicts.verdicts.size} verdicts in $ms ms")
            return verdicts
        }

        /** Today's offers as the app gets them (`/api/offers`), with what their verdicts would show and how long they took. */
        fun forTheApp(): OffersState {
            val ingredients = IngredientRepository(db, FoodRepository(db), account)
            val repository = DealVerdictRepository(db, OfferRepository(db, emptyList()), ingredients, ShopsRepository(db, account)::inUse)
            val state = OfferRepository(db, emptyList()).state(serverDay(now), ingredients.matchTerms(), ingredients.list().associate { it.id to it.name })
            lateinit var judged: OffersState
            val ms = measureTimeMillis { judged = repository.on(state, now) }
            val verdicts = judged.offers.mapNotNull { it.verdict }
            println("For the app: ${verdicts.size} of ${judged.offers.size} offers judged in $ms ms, by verdict " + verdicts.groupingBy { it.verdict }.eachCount())
            val named = verdicts.flatMap { v -> v.alongside + v.earlier + listOfNotNull(v.cheaperNow, v.lastAsCheap, v.cheaperPack?.offer) }.mapNotNull { it.packName }
            println("Packs named in them: " + named.distinct().sorted())
            return judged
        }
    }

    /**
     * A copy of [original], migrated, given [before] the replay (with the account and the instant of the first
     * refresh) and then its stored offers replayed through five refreshes ten minutes apart; then, with [next], the
     * stored offers of a copy of that one replayed once more, at the time it fetched them.
     */
    private fun open(original: File, next: File? = null, before: (Database, Long, Instant) -> Unit = { _, _, _ -> }): Opened {
        val (driver, db) = openCopy(original)
        val account = db.ingredientQueries.selectEveryIngredient().executeAsList().groupingBy { it.userId }.eachCount().maxBy { it.value }.key
        val fetched = db.planQueries.selectSources().executeAsList().mapNotNull { it.fetchedAt?.let(Instant::parse) }
        val first = (fetched + listOfNotNull(db.priceHistoryQueries.selectOfferHistory().executeAsList().maxOfOrNull { Instant.parse(it.lastSeen) }))
            .max().plusSeconds(60).truncatedTo(ChronoUnit.SECONDS)
        before(db, account, first)
        val stored = storedOffers(db)
        val offers = OfferRepository(db, stored.groupBy { it.store }.map { (store, offers) -> Replay(store, offers) })
        for (n in 0 until 5) runBlocking { offers.refresh(now = first.plus(Duration.ofMinutes(n * 10L))) }
        val now = next?.let { replayNext(db, it, after = first.plus(Duration.ofMinutes(40))) } ?: first
        return Opened(driver, db, account, now.plus(Duration.ofMinutes(50)))
    }

    private fun openCopy(original: File): Pair<SqlDriver, Database> {
        val copy = File(createTempDirectory("deal-verdicts").toFile(), "copy.db")
        original.copyTo(copy)
        return openDatabase("jdbc:sqlite:${copy.path}")
    }

    private fun storedOffers(db: Database) = db.planQueries.selectOffersOverlapping(until = "9999-12-31", from = "0000-01-01").executeAsList().map {
        Offer(Store.valueOf(it.store), it.title, it.deal, it.price, it.originalPrice, it.packSize, it.category, it.validFrom, it.validUntil, it.url, imageUrl = it.imageUrl)
    }

    /** The stored offers of a copy of [next] replayed into [db] as one refresh, when [next] fetched them (and after [after]); that time. */
    private fun replayNext(db: Database, next: File, after: Instant): Instant {
        val (driver, later) = openCopy(next)
        val offers = storedOffers(later)
        val fetched = later.planQueries.selectSources().executeAsList().mapNotNull { it.fetchedAt?.let(Instant::parse) }.max()
        driver.close()
        val at = maxOf(fetched, after.plusSeconds(60)).truncatedTo(ChronoUnit.SECONDS)
        runBlocking { OfferRepository(db, offers.groupBy { it.store }.map { (store, them) -> Replay(store, them) }).refresh(now = at) }
        println("Replayed ${offers.size} offers of ${next.name} as fetched at $at")
        return at
    }

    private class Replay(override val store: Store, val offers: List<Offer>) : OfferSource {
        override suspend fun fetch() = offers
    }

    private fun report(db: Database, account: Long, now: Instant, verdicts: DealVerdicts) {
        val valid = db.planQueries.selectOffersValidOn(serverDay(now).toString()).executeAsList()
        val all = valid.size
        // Offers the store dates over more than two weeks (Jumbo): what the rules make of them.
        val longDated = valid.filter { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(it.validFrom), LocalDate.parse(it.validUntil)) > 14 }
            .mapTo(HashSet()) { it.store to it.title }
        val theirs = verdicts.verdicts.filter { (it.store.name to it.title) in longDated }
        println("Long-dated offers valid today: ${longDated.size}, of them the account's: ${theirs.size}, by verdict " + theirs.groupingBy { it.verdict }.eachCount())
        theirs.forEach { println("- long-dated: ${it.store.label}, ${it.title}, ${dealOf(it.deal)}: ${it.summary}") }
        println("Account $account, $now: ${verdicts.verdicts.size} of the $all offers valid today are for its ingredients at its stores")
        println("By verdict: " + verdicts.verdicts.groupingBy { it.verdict }.eachCount())
        println("By store and verdict: " + verdicts.verdicts.groupingBy { "${it.store} ${it.verdict}" }.eachCount())
        println("Measures: " + verdicts.verdicts.groupingBy { it.measure }.eachCount())
        // What issue 197 changed: offers no longer shown, other packs, and another pack cheaper per unit.
        println("Verdicts naming an offer no longer shown: ${verdicts.verdicts.count { "no longer shown" in it.summary }}, naming another pack cheaper: " +
            "${verdicts.verdicts.count { "in another pack" in it.summary }}, judged in this pack: ${verdicts.verdicts.count { "this pack" in it.summary }}")
        verdicts.verdicts.filter { it.alongside.isNotEmpty() || "another pack" in it.summary }.forEach {
            println("- with another offer: ${it.store.label}, ${it.title} (${it.packSize}), ${dealOf(it.deal)}: ${it.verdict}: ${it.summary}")
        }
        for ((verdict, some) in verdicts.verdicts.groupBy { it.verdict }) {
            println("$verdict, for example:")
            some.sortedBy { it.title }.take(SAMPLE).forEach { println("- ${it.store.label}, ${it.title}, ${dealOf(it.deal)} (${it.ingredient}): ${it.summary}") }
        }
    }

    /**
     * Twelve weeks of nights before [first], every store read each night, and the earlier offers that show each
     * verdict on one real offer of the [account] (by title, for a result that does not change between runs).
     * Returns which offer shows which verdict.
     */
    private fun madeUpHistory(db: Database, account: Long, first: Instant): List<Pair<String, Offer>> {
        val ingredients = IngredientRepository(db, FoodRepository(db), account)
        val stores = ShopsRepository(db, account).inUse()
        val ours = OfferRepository(db, emptyList()).state(serverDay(first), ingredients.matchTerms(), ingredients.list().associate { it.id to it.name }).offers
            .filter { it.ingredientId != null && it.store in stores }.sortedWith(compareBy({ it.store }, { it.title })).distinctBy { it.store to normalised(it.title) }
        fun night(n: Int) = first.minus(Duration.ofDays(n.toLong()))
        val h = db.priceHistoryQueries
        for (store in Store.entries) for (n in NIGHTS downTo 1) h.insertOfferRecording(store.name, night(n).toString(), 100)

        /** An earlier offer of [offer]'s product with [deal], from [from] to [until] nights before the first refresh. */
        fun earlier(offer: Offer, deal: String?, from: Int, until: Int, price: Double? = null, originalPrice: Double? = null) =
            insertPeriod(db, offer.copy(deal = deal, price = price, originalPrice = originalPrice), night(from), night(until))
        fun percentOff(share: Double) = "${((1 - share) * 100).toInt()}% korting"
        fun euros(value: Double) = "voor " + String.format(Locale.ROOT, "%.2f", value).replace('.', ',')

        val terms = ours.associateWith { DealTerms.of(it) }
        val byShare = ours.filter { terms.getValue(it).paidShare != null && it.originalPrice == null && it.price == null }.sortedBy { terms.getValue(it).paidShare }
        val byPrice = ours.filter { it.price != null && it.originalPrice == null && it.deal.isNullOrBlank() && terms.getValue(it).let { t -> t.eurosPerKg ?: t.eurosPerPiece ?: t.eurosPerLitre } != null }
        val raised = ours.filter { it.price != null && it.originalPrice != null && it.originalPrice!! > it.price!! }
        val nothing = ours.filter { terms.getValue(it).let { t -> t.paidShare == null && t.eurosPerKg == null && t.eurosPerPiece == null && t.eurosPerLitre == null && t.eurosPerPack == null } }
        val byPack = ours.filter { terms.getValue(it).let { t -> t.eurosPerPack != null && t.paidShare == null && t.eurosPerKg == null && t.eurosPerPiece == null && t.eurosPerLitre == null } }
        val shown = mutableListOf<Pair<String, Offer>>()
        val used = HashSet<Offer>()
        fun pick(scenario: String, from: List<Offer>, history: (Offer) -> Unit) {
            val offer = from.firstOrNull { it !in used } ?: return println("No offer of the account to show $scenario with")
            used += offer
            history(offer)
            shown += scenario to offer
        }
        pick("real deal: dearer before", byShare) { o ->
            val share = terms.getValue(o).paidShare!!
            earlier(o, percentOff(minOf(0.9, share + 0.25)), 70, 64)
            earlier(o, percentOff(minOf(0.9, share + 0.25)), 49, 43)
            earlier(o, percentOff(minOf(0.9, share + 0.15)), 28, 22)
        }
        pick("usual deal: the same share, worded otherwise", byShare) { o ->
            for (weeks in listOf(9, 6, 3)) earlier(o, percentOff(terms.getValue(o).paidShare!!), weeks * 7, weeks * 7 - 6)
        }
        pick("worse than usual: cheaper before", byShare.reversed()) { o ->
            val share = terms.getValue(o).paidShare!!
            for (weeks in listOf(10, 7, 4)) earlier(o, percentOff(maxOf(0.3, share - 0.17)), weeks * 7, weeks * 7 - 6)
        }
        pick("always on offer this way", byShare + byPrice) { o -> earlier(o, o.deal, NIGHTS, 1, o.price, o.originalPrice) }
        pick("not enough history: on offer once before", byShare) { o -> earlier(o, percentOff(terms.getValue(o).paidShare!!), 35, 29) }
        pick("real deal by price per unit", byPrice) { o ->
            for (weeks in listOf(8, 5, 2)) earlier(o, euros(o.price!! * 1.25), weeks * 7, weeks * 7 - 6, price = o.price!! * 1.25)
        }
        pick("worse than usual by price per pack", byPack) { o ->
            val cheaper = terms.getValue(o).eurosPerPack!! * 0.85
            for (weeks in listOf(9, 6, 3)) earlier(o, euros(cheaper), weeks * 7, weeks * 7 - 6, price = cheaper)
        }
        pick("not a deal: regular price raised", raised) { o -> earlier(o, euros(o.price!! * 0.9), 20, 14, price = o.price!! * 0.9, originalPrice = o.price) }
        pick("group offer: earlier offers of the group with another price before", ours.filter { it.store == Store.AH && normalised(it.title).startsWith("alle ") && it.originalPrice == null }) { o ->
            earlier(o, o.deal, 63, 57, originalPrice = 3.99)
            earlier(o, o.deal, 35, 29, originalPrice = 5.49)
        }
        pick("not comparable", nothing) { o -> for (weeks in listOf(9, 5)) earlier(o, o.deal, weeks * 7, weeks * 7 - 6, o.price, o.originalPrice) }
        return shown
    }

    /**
     * Twelve months of made-up nights before [first]: every store read each night, and each stored offer on offer
     * one week in four (with a second row for half of them, a price changed mid-week), other products the other
     * weeks. About 1.5 rows per offer a week, as the real stores leave.
     */
    private fun madeUpYear(db: Database, first: Instant) {
        val stored = db.planQueries.selectOffersOverlapping(until = "9999-12-31", from = "0000-01-01").executeAsList().map {
            Offer(Store.valueOf(it.store), it.title, it.deal, it.price, it.originalPrice, it.packSize, it.category, it.validFrom, it.validUntil)
        }
        fun night(n: Int) = first.minus(Duration.ofDays(n.toLong()))
        db.transaction {
            for (store in Store.entries) for (n in 365 downTo 1) db.priceHistoryQueries.insertOfferRecording(store.name, night(n).toString(), 400)
            for (week in 52 downTo 1) for ((i, offer) in stored.withIndex()) {
                val product = if (week % 4 == 0) offer else offer.copy(title = "${offer.title} (another product, week ${week % 4})")
                val monday = week * 7
                if (i % 2 == 0) {
                    insertPeriod(db, product, night(monday), night(monday - 6))
                } else {
                    insertPeriod(db, product, night(monday), night(monday - 3), validUntil = night(monday - 6))
                    insertPeriod(db, product.copy(price = (product.price ?: 1.0) + 0.1), night(monday - 4), night(monday - 6), validFrom = night(monday))
                }
            }
        }
    }

    /** A row of the history for [offer], seen from [from] to [until] and dated by the store over the same days unless given. */
    private fun insertPeriod(db: Database, offer: Offer, from: Instant, until: Instant, validFrom: Instant = from, validUntil: Instant = until) {
        val terms = DealTerms.of(offer)
        db.priceHistoryQueries.insertOfferPeriod(
            offer.store.name, normalised(offer.title), normalised(offer.packSize), offer.title, offer.packSize, null, offer.deal, terms.kind::class.simpleName!!,
            terms.packs.toLong(), null, null, null, offer.price, offer.originalPrice, terms.paidShare, terms.eurosPerKg, terms.eurosPerPiece,
            serverDay(validFrom).toString(), serverDay(validUntil).toString(), from.toString(), until.toString(), offer.category, null, terms.eurosPerLitre,
        )
    }

    private fun dealOf(deal: String?) = deal?.let { "\"$it\"" } ?: "no deal text"

    private companion object {
        /** Twelve weeks of made-up nights. */
        const val NIGHTS = 84
        const val SAMPLE = 6

        /** The columns a row of offerHistory has, all read as a mapped row would read them. */
        const val OFFER_HISTORY_COLUMNS = 25
    }
}
