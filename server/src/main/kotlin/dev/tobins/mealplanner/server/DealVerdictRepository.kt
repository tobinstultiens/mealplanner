package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.CheaperPack
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.OffersState
import dev.tobins.mealplanner.api.PastOffer
import dev.tobins.mealplanner.api.PriceRaise
import dev.tobins.mealplanner.api.PriceVerdict
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.VerdictKind
import dev.tobins.mealplanner.api.VerdictMeasure
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.db.OfferHistory
import dev.tobins.mealplanner.server.db.RegularPriceHistory
import dev.tobins.mealplanner.server.history.CurrentOffer
import dev.tobins.mealplanner.server.history.DealVerdict
import dev.tobins.mealplanner.server.history.LOOKBACK_WEEKS
import dev.tobins.mealplanner.server.history.MIN_DAYS_READ
import dev.tobins.mealplanner.server.history.Measure
import dev.tobins.mealplanner.server.history.Measures
import dev.tobins.mealplanner.server.history.OfferIdentity
import dev.tobins.mealplanner.server.history.OfferPeriod
import dev.tobins.mealplanner.server.history.OfferSighting
import dev.tobins.mealplanner.server.history.ProductKey
import dev.tobins.mealplanner.server.history.RegularPrice
import dev.tobins.mealplanner.server.history.SamePacks
import dev.tobins.mealplanner.server.history.Verdict
import dev.tobins.mealplanner.server.history.isSameProduct
import dev.tobins.mealplanner.server.history.judge
import dev.tobins.mealplanner.server.history.normalised
import dev.tobins.mealplanner.server.history.packName
import dev.tobins.mealplanner.server.history.summary
import dev.tobins.mealplanner.server.offers.storeText
import dev.tobins.mealplanner.server.offers.OfferMatcher
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Whether each of one account's current offers is a good deal, compared with its product's own history at its
 * store (docs/data-sources.md, "Is it a good deal?"). The offers are those valid today at the stores the
 * account shops at that match one of its own ingredients. The history is shared, but its rows are matched
 * again with the account's own names, its exclusions ("Not this ingredient") and the store's category ([OfferMatcher]),
 * never by the `ingredient` stored with them, which was matched against every account's names together; so is the
 * regular price history, read by the account's own ingredient names. Reads only.
 */
class DealVerdictRepository(
    private val db: Database,
    private val offers: OfferRepository,
    private val ingredients: IngredientRepository,
    private val shops: () -> Set<Store>,
) {
    private val history = PriceHistoryRepository(db)

    /** The verdicts on the account's offers valid at [now], by store, verdict and title. */
    fun current(now: Instant): DealVerdicts =
        DealVerdicts(judged(now).map { (offer, verdict) -> view(offer, verdict) }.sortedWith(compareBy({ it.store }, { it.verdict }, { it.title })))

    /**
     * [state]'s offers with the verdicts on those of the account's offers valid at [now] ([Offer.verdict]), for the app
     * (issue 118, step c). The others keep none.
     */
    fun on(state: OffersState, now: Instant): OffersState = attached(state, now) { verdictOf -> state.copy(offers = state.offers.map(verdictOf)) }

    /**
     * [offers] with the verdicts on those of the account's offers valid at [now], as [on] the offers of an answer: for the
     * stock-up advice, which goes by them before the list is made (issue 118, step d).
     */
    fun onOffers(offers: List<Offer>, now: Instant): List<Offer> = attached(offers, now) { verdictOf -> offers.map(verdictOf) }

    /**
     * [list]'s offers with their verdicts, as [on] the offers: on every row's offers, the advice's and those of what is no
     * longer needed, so that the list the app keeps offline has them too.
     */
    fun on(list: ShoppingList, now: Instant): ShoppingList = attached(list, now) { verdictOf ->
        fun List<ShoppingItem>.judged() = map { item -> item.copy(offers = item.offers.map(verdictOf)) }
        list.copy(items = list.items.judged(), buyAhead = list.buyAhead.judged(), stockUp = list.stockUp.judged(), noLongerNeeded = list.noLongerNeeded.judged())
    }

    /**
     * [answer] with [attach] given each offer's verdict, or as it is when judging fails: a verdict is an extra, and a
     * fault in reading the history must never cost the app its offers or its shopping list.
     */
    private fun <T> attached(answer: T, now: Instant, attach: ((Offer) -> Offer) -> T): T {
        val verdicts = try {
            judged(now).associate { (offer, verdict) -> OfferKey(offer) to priceVerdict(verdict) }
        } catch (e: Exception) {
            log.warn("Could not judge the offers; answering without verdicts", e)
            return answer
        }
        return attach { offer -> verdicts[OfferKey(offer)]?.let { offer.copy(verdict = it) } ?: offer }
    }

    /**
     * The account's offers valid at [now] with their verdicts. The packs of each product are read as [SamePacks] has
     * them, in its history, on today's page and in the offer itself alike.
     */
    private fun judged(now: Instant): List<Pair<Offer, DealVerdict>> {
        val terms = ingredients.matchTerms()
        val names = ingredients.list().associate { it.id to it.name }
        val stores = shops()
        val valid = offers.state(serverDay(now), terms, names).offers.filter { OfferSighting.readable(it) }
        val onOffer = valid.filter { it.store in stores && it.ingredientId != null }
        if (onOffer.isEmpty()) return emptyList()
        // What each store shows of each product today: its stored offers, whichever ingredient they match.
        val page = valid.groupBy { it.store to normalised(it.title) }
        val read = read(onOffer, stores, now)
        val matcher = OfferMatcher(terms)
        val matched = HashMap<Triple<String, String, String?>, Long?>()
        val periods = read.periods.groupBy { it.store to storeText(it.productKey) }
        return onOffer.map { offer ->
            val own = periods[offer.store.name to normalised(offer.title)].orEmpty()
                .filter { row -> matched.getOrPut(Triple(row.store, row.title, row.category)) { matcher.match(row.title, row.category, store = Store.valueOf(row.store)) } == offer.ingredientId }
            val regular = read.regular.filter { it.isFor(offer) }.mapNotNull { row -> row.packPrice?.let { RegularPrice(it, Instant.parse(row.firstSeen), Instant.parse(row.lastSeen)) } }
            val shown = page[offer.store to normalised(offer.title)].orEmpty()
            val packs = SamePacks(own.map { it.packKey } + (shown + offer).map { ProductKey.of(it).pack })
            fun current(it: Offer) = CurrentOffer(it, packs[ProductKey.of(it).pack])
            offer to judge(current(offer), own.map { it.period(packs) }, read.recordings[offer.store].orEmpty(), regular, serverDay(now), shown.map(::current))
        }
    }

    private class Read(val periods: List<OfferHistory>, val recordings: Map<Store, List<Instant>>, val regular: List<RegularPriceHistory>)

    /**
     * What the verdicts on [onOffer] read, in one snapshot so that the recordings and the rows agree should a
     * refresh record in between: the rows of the products on offer (by product, not the stores' whole year) and
     * the stores' recordings over the last [LOOKBACK_WEEKS] weeks, and the regular prices of the ingredients on
     * offer. A few indexed reads per store, no write.
     */
    private fun read(onOffer: List<Offer>, stores: Set<Store>, now: Instant): Read {
        val from = now.minus(Duration.ofDays(LOOKBACK_WEEKS * 7))
        val products = onOffer.groupBy({ it.store }, { ProductKey.of(it).title })
        return db.transactionWithResult {
            Read(
                products.flatMap { (store, titles) -> history.offerPeriods(store, titles, from, now) },
                stores.associateWith { store -> history.recordings(store, from, now).map { Instant.parse(it.seenAt) } },
                history.regularPrices(onOffer.mapNotNull { it.ingredientName }, from),
            )
        }
    }

    /** Whether this regular price is of [offer]'s own product: looked up for its ingredient, at its store, the same product and pack. */
    private fun RegularPriceHistory.isFor(offer: Offer) =
        name.equals(offer.ingredientName, ignoreCase = true) && store == offer.store.name && isSameProduct(product, packSize, offer.title, offer.packSize)

    private fun OfferHistory.period(packs: SamePacks) = OfferPeriod(
        identity = OfferIdentity(ProductKey(Store.valueOf(store), storeText(productKey), packs[packKey]), price, originalPrice, normalised(deal)),
        deal = deal,
        measures = Measures.of(Offer(Store.valueOf(store), title, deal, price, originalPrice, packSize, category, validFrom, validUntil)),
        firstSeen = Instant.parse(firstSeen),
        lastSeen = Instant.parse(lastSeen),
        validFrom = LocalDate.parse(validFrom),
        validUntil = LocalDate.parse(validUntil),
    )

    private fun view(offer: Offer, verdict: DealVerdict) = OfferVerdict(
        store = offer.store,
        title = offer.title,
        packSize = offer.packSize,
        deal = offer.deal,
        ingredient = offer.ingredientName!!,
        verdict = verdict.verdict,
        summary = verdict.summary(offer.store.label),
        measure = verdict.measure,
        value = verdict.value,
        typical = verdict.typical,
        lowest = verdict.lowest?.let(::earlierOffer),
        lastAsCheap = verdict.lastAsCheap?.let(::earlierOffer),
        daysRead = verdict.daysRead,
        daysOnOffer = verdict.daysOnOffer,
        daysThisDeal = verdict.daysThisDeal,
        regularRaise = verdict.raise?.let { RaisedPrice(it.before, it.beforeSeen.toString(), it.now) },
        earlier = verdict.earlier.map { earlierOffer(it, endedEarly = verdict.endedEarly(it)) },
        alongside = verdict.alongside.map(::earlierOffer),
        cheaperNow = verdict.cheaperNow?.let(::earlierOffer),
        cheaperPack = verdict.cheaperPack?.let { CheaperPackView(earlierOffer(it.offer, endedEarly = !it.now && verdict.endedEarly(it.offer)), it.measure, it.value, it.now) },
    )

    private fun earlierOffer(run: OfferPeriod, endedEarly: Boolean = false) = EarlierOffer(
        run.validFrom.toString(), run.validUntil.toString(), run.deal,
        run.measures.paidShare, run.measures.perKg, run.measures.perLitre, run.measures.perPiece, run.measures.perPack,
        pack = run.identity.product.pack, price = run.identity.price, endedEarly = endedEarly, packName = packName(run.identity.product.pack),
    )

    /**
     * The verdict as the app gets it ([PriceVerdict]): the facts it words in English and Dutch, the offers it names
     * valued in the verdict's measure, a pack named only where it is another than today's. Offers named apart (the
     * one cheaper now, another pack shown now) are not repeated among those alongside; of the earlier offers the
     * latest [EARLIER_SHOWN].
     */
    private fun priceVerdict(verdict: DealVerdict): PriceVerdict {
        val measure = verdict.measure
        fun past(run: OfferPeriod, valueIn: Measure? = measure, endedEarly: Boolean = false) = PastOffer(
            from = run.validFrom.toString(),
            until = run.validUntil.toString(),
            deal = run.deal?.takeIf { it.isNotBlank() },
            price = run.identity.price,
            packName = run.identity.product.pack.takeIf { it != verdict.pack }?.let(::packName),
            value = valueIn?.let { run.measures[it] },
            endedEarly = endedEarly,
        )
        val cheaperNow = verdict.cheaperNow.takeIf { verdict.verdict == Verdict.CHEAPER_ANOTHER_WAY }
        val named = listOfNotNull(cheaperNow, verdict.cheaperPack?.takeIf { it.now }?.offer)
        return PriceVerdict(
            verdict = VerdictKind.valueOf(verdict.verdict.name),
            measure = measure?.let { VerdictMeasure.valueOf(it.name) },
            value = verdict.value,
            typical = verdict.typical,
            betterThanUsual = verdict.betterThanUsual,
            lastAsCheap = verdict.lastAsCheap?.let { past(it, endedEarly = it !in verdict.alongside && verdict.endedEarly(it)) },
            daysRead = verdict.daysRead,
            daysNeeded = MIN_DAYS_READ,
            thisPack = verdict.otherPacks,
            raise = verdict.raise?.let { PriceRaise(it.before, it.now) },
            cheaperNow = cheaperNow?.let { past(it) },
            cheaperPack = verdict.cheaperPack?.let { other ->
                CheaperPack(past(other.offer, other.measure, endedEarly = !other.now && verdict.endedEarly(other.offer)), VerdictMeasure.valueOf(other.measure.name), other.now)
            },
            alongside = verdict.alongside.filter { it !in named }.map { past(it) },
            earlier = verdict.earlier.sortedWith(compareBy({ it.validUntil }, { it.validFrom })).takeLast(EARLIER_SHOWN).reversed()
                .map { past(it, endedEarly = verdict.endedEarly(it)) },
        )
    }

    /** An offer as the verdicts and the answers that carry them both have it: what the store published, without what the server adds. */
    private data class OfferKey(
        val store: Store,
        val title: String,
        val packSize: String?,
        val deal: String?,
        val price: Double?,
        val originalPrice: Double?,
        val validFrom: String,
        val validUntil: String,
    ) {
        constructor(offer: Offer) : this(offer.store, offer.title, offer.packSize, offer.deal, offer.price, offer.originalPrice, offer.validFrom, offer.validUntil)
    }

    private companion object {
        /** The earlier offers the app's detail lists: the latest few say how it went lately; a year of them would not fit. */
        const val EARLIER_SHOWN = 5
        val log: Logger = LoggerFactory.getLogger(DealVerdictRepository::class.java)
    }
}

/**
 * `GET /api/offers/verdicts`: the verdicts on the calling account's current offers, for the owner to read
 * what they say on real offers before the app shows them (issue 118, step c). No app reads this route, so its
 * shape may still change; it is not one of the shared API types.
 */
@Serializable
data class DealVerdicts(val verdicts: List<OfferVerdict>)

/** The verdict on one offer: the offer, the account's ingredient it is for, the verdict with a summary in English, and what it rests on. */
@Serializable
data class OfferVerdict(
    val store: Store,
    val title: String,
    val packSize: String?,
    val deal: String?,
    val ingredient: String,
    val verdict: Verdict,
    val summary: String,
    val measure: Measure?,
    /** The offer's value in [measure]: euros per kg, litre, piece or pack, or the share of the regular price paid. */
    val value: Double?,
    /** The earlier offers' median value in [measure]. */
    val typical: Double?,
    val lowest: EarlierOffer?,
    /** The latest earlier offer at least as cheap as this one, in [measure]; null when none was. */
    val lastAsCheap: EarlierOffer?,
    /** Days the store was read in the last year, on how many of them the store dated the product on offer, and this same way. */
    val daysRead: Int,
    val daysOnOffer: Int,
    val daysThisDeal: Int,
    /** The product's regular price went up shortly before this offer began; null when it did not, or can't be told. */
    val regularRaise: RaisedPrice?,
    /** The earlier offers of this pack at the store, oldest first, from and until as the store dated them. */
    val earlier: List<EarlierOffer>,
    /** The other offers of the product the store shows today, of any pack, as it shows them; and the one of this pack clearly cheaper than this one, if any. */
    val alongside: List<EarlierOffer>,
    val cheaperNow: EarlierOffer?,
    /** An offer of another pack clearly cheaper per kg or per litre, shown now or else ended; null when none is. */
    val cheaperPack: CheaperPackView? = null,
)

/** A regular price raised from [before] (last seen on [beforeSeen], an ISO date) to [now], a pack. */
@Serializable
data class RaisedPrice(val before: Double, val beforeSeen: String, val now: Double)

@Serializable
data class EarlierOffer(
    val from: String,
    val until: String,
    val deal: String?,
    val paidShare: Double?,
    val perKg: Double?,
    val perLitre: Double?,
    val perPiece: Double?,
    val perPack: Double?,
    /** The pack, normalised; '' when the store gives none. */
    val pack: String = "",
    /** The price the store gave, which is the deal of an offer without deal text (Lidl). */
    val price: Double? = null,
    /** Dated over today, but the store no longer shows it: it ended before its dates did. */
    val endedEarly: Boolean = false,
    /** The pack in short, where the store's text gives a size ([packName]). */
    val packName: String? = null,
)

/** Another pack's [offer] clearly cheaper in [measure], in which this offer comes to [value]; shown [now], or ended. */
@Serializable
data class CheaperPackView(val offer: EarlierOffer, val measure: Measure, val value: Double, val now: Boolean)
