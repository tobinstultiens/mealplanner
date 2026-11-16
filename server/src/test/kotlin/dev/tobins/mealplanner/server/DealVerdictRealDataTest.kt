package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.pricing.DealTerms
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.history.normalised
import dev.tobins.mealplanner.server.offers.OfferSource
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Duration
import java.time.Instant
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
 * 2. The second copy gets twelve weeks of made-up history first: every store read every night, and a few of
 *    the account's real offers given earlier offers chosen to show each verdict's wording. Its verdicts are
 *    printed too; they say what the rules do, not what the stores did.
 */
class DealVerdictRealDataTest {
    @Test
    fun theVerdictsOnARealDatabasesOffers() {
        val original = System.getenv("PRICE_HISTORY_DB")?.let(::File) ?: return
        println("== As recorded")
        val real = open(original)
        report(real.db, real.account, real.now, real.verdicts())

        println("\n== With twelve weeks of made-up history before it")
        var products = emptyList<Pair<String, Offer>>()
        val madeUp = open(original) { db, account, first -> products = madeUpHistory(db, account, first) }
        val shown = madeUp.verdicts()
        report(madeUp.db, madeUp.account, madeUp.now, shown)
        println("\nThe products given a made-up history:")
        for ((scenario, offer) in products) {
            val verdict = shown.verdicts.firstOrNull { it.store == offer.store && it.title == offer.title }
            println("- [$scenario] ${offer.store.label}, ${offer.title} (${offer.packSize ?: "no pack size"}), ${dealOf(offer.deal)}: ${verdict?.verdict}\n  ${verdict?.summary}")
        }
    }

    private class Opened(val db: Database, val account: Long, val now: Instant) {
        fun verdicts(): DealVerdicts {
            val ingredients = IngredientRepository(db, FoodRepository(db), account)
            val repository = DealVerdictRepository(db, OfferRepository(db, emptyList()), ingredients, ShopsRepository(db, account)::inUse)
            lateinit var verdicts: DealVerdicts
            val ms = measureTimeMillis { verdicts = repository.current(now) }
            println("Read ${verdicts.verdicts.size} verdicts in $ms ms")
            return verdicts
        }
    }

    /**
     * A copy of [original], migrated, given [before] the replay (with the account and the instant of the first
     * refresh) and then its stored offers replayed through five refreshes ten minutes apart.
     */
    private fun open(original: File, before: (Database, Long, Instant) -> Unit = { _, _, _ -> }): Opened {
        val copy = File(createTempDirectory("deal-verdicts").toFile(), "copy.db")
        original.copyTo(copy)
        val (_, db) = openDatabase("jdbc:sqlite:${copy.path}")
        val account = db.ingredientQueries.selectEveryIngredient().executeAsList().groupingBy { it.userId }.eachCount().maxBy { it.value }.key
        val stored = db.planQueries.selectOffersOverlapping(until = "9999-12-31", from = "0000-01-01").executeAsList().map {
            Offer(Store.valueOf(it.store), it.title, it.deal, it.price, it.originalPrice, it.packSize, it.category, it.validFrom, it.validUntil, it.url, imageUrl = it.imageUrl)
        }
        val fetched = db.planQueries.selectSources().executeAsList().mapNotNull { it.fetchedAt?.let(Instant::parse) }
        val first = (fetched + listOfNotNull(db.priceHistoryQueries.selectOfferHistory().executeAsList().maxOfOrNull { Instant.parse(it.lastSeen) }))
            .max().plusSeconds(60).truncatedTo(ChronoUnit.SECONDS)
        before(db, account, first)
        val offers = OfferRepository(db, stored.groupBy { it.store }.map { (store, offers) -> Replay(store, offers) })
        for (n in 0 until 5) runBlocking { offers.refresh(now = first.plus(Duration.ofMinutes(n * 10L))) }
        return Opened(db, account, first.plus(Duration.ofMinutes(50)))
    }

    private class Replay(override val store: Store, val offers: List<Offer>) : OfferSource {
        override suspend fun fetch() = offers
    }

    private fun report(db: Database, account: Long, now: Instant, verdicts: DealVerdicts) {
        val all = db.planQueries.selectOffersValidOn(serverDay(now).toString()).executeAsList().size
        println("Account $account, $now: ${verdicts.verdicts.size} of the $all offers valid today are for its ingredients at its stores")
        println("By verdict: " + verdicts.verdicts.groupingBy { it.verdict }.eachCount())
        println("By store and verdict: " + verdicts.verdicts.groupingBy { "${it.store} ${it.verdict}" }.eachCount())
        println("Measures: " + verdicts.verdicts.groupingBy { it.measure }.eachCount())
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

        /** An earlier offer of [offer]'s product with [deal], from [from] to [until] nights before the first refresh, read as the history reads every offer. */
        fun earlier(offer: Offer, deal: String?, from: Int, until: Int, price: Double? = null, originalPrice: Double? = null) {
            val past = offer.copy(deal = deal, price = price, originalPrice = originalPrice)
            val terms = DealTerms.of(past)
            h.insertOfferPeriod(
                past.store.name, normalised(past.title), normalised(past.packSize), past.title, past.packSize, null, deal, terms.kind::class.simpleName!!, terms.packs.toLong(),
                null, null, null, price, originalPrice, terms.paidShare, terms.eurosPerKg, terms.eurosPerPiece,
                serverDay(night(from)).toString(), serverDay(night(until)).toString(), night(from).toString(), night(until).toString(), past.category, null, terms.eurosPerLitre,
            )
        }
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
        pick("not comparable", nothing) { o -> for (weeks in listOf(9, 5)) earlier(o, o.deal, weeks * 7, weeks * 7 - 6, o.price, o.originalPrice) }
        return shown
    }

    private fun dealOf(deal: String?) = deal?.let { "\"$it\"" } ?: "no deal text"

    private companion object {
        /** Twelve weeks of made-up nights. */
        const val NIGHTS = 84
        const val SAMPLE = 6
    }
}
