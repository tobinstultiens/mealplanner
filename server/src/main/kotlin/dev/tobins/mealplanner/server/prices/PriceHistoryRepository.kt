package dev.tobins.mealplanner.server.prices

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.pricing.DealKind
import dev.tobins.mealplanner.pricing.DealTerms
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.db.OfferHistory
import dev.tobins.mealplanner.server.db.OfferRecording
import dev.tobins.mealplanner.server.db.RegularPriceHistory
import dev.tobins.mealplanner.server.prices.history.OfferIdentity
import dev.tobins.mealplanner.server.prices.history.OfferSighting
import dev.tobins.mealplanner.server.prices.history.ProductKey
import dev.tobins.mealplanner.server.prices.history.RegularPriceSighting
import dev.tobins.mealplanner.server.prices.history.changes
import dev.tobins.mealplanner.server.prices.history.normalised
import dev.tobins.mealplanner.server.prices.offers.OfferMatcher
import dev.tobins.mealplanner.server.prices.offers.storeText
import dev.tobins.mealplanner.server.serverDay
import dev.tobins.mealplanner.server.writing
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * The price history: what the stores offered and what the regular prices were, over time, so that a
 * later step can tell a good deal from an ordinary one. Shared by every account, like the offers and
 * prices it is recorded from, and written only by the server's own refreshes. Only changes are written
 * (see [changes]); docs/data-sources.md, "Price history", says what that means.
 */
class PriceHistoryRepository(private val db: Database) {
    private val q = db.priceHistoryQueries
    private val log = LoggerFactory.getLogger(PriceHistoryRepository::class.java)

    /**
     * The catalogue ingredient each offer is for, by name: every account's ingredients, one per name, known
     * by the names and aliases any account gave it (negligible ones left out, as for an account's offers).
     * By name, like the regular prices, because the history is shared and ingredient ids are an account's.
     */
    fun ingredientNames(): (Offer) -> String? {
        val iq = db.ingredientQueries
        val aliases = iq.selectEveryAlias().executeAsList().groupBy({ it.ingredientId }, { it.text })
        val byName = iq.selectEveryIngredient().executeAsList().filter { it.negligible == 0L }.groupBy { it.name.lowercase() }.values.toList()
        val matcher = OfferMatcher(
            byName.withIndex().associate { (index, rows) -> index.toLong() to (listOf(rows.first().name) + rows.flatMap { aliases[it.id].orEmpty() }).distinct() },
        )
        return { offer -> matcher.match(offer)?.let { byName[it.toInt()].first().name } }
    }

    /**
     * Records one store's successful refresh at [now], once its offers are stored; the caller runs it in a
     * transaction of its own, so that a failure here leaves the offers stored and writes nothing here.
     *
     * The periods still open are the ones the last recording saw (`max(lastSeen)` of the store). A refresh
     * that saw nothing (an empty page that did not fail) writes nothing, so that stays the last recording
     * and the refresh ends no offer. A refresh no later than the last one recorded (two at once) records
     * nothing either, rather than put its older view on top. An offer whose dates are no ISO dates is left
     * out and logged ([OfferSighting.readable]); the rest of the store's offers are recorded. A refresh that
     * records anything also leaves an `offerRecording` row, so that a product missing from the history on a
     * night the store was read can be told from a night it was not.
     *
     * [ingredientOf] is asked only for a period that starts, or one still without an ingredient: what a
     * period is for stays as it was matched, and one that matched nothing gets the ingredient an account
     * adds later. The category, address and price per litre are not part of what a period is (see
     * [OfferIdentity]): a continued period gets them only where it has none yet, and keeps the ones it has.
     */
    fun recordOffers(store: Store, offers: List<Offer>, ingredientOf: (Offer) -> String?, now: Instant): Unit = db.writing {
        val seen = seen(now)
        val last = q.lastOfferRecording(store.name).executeAsOne().lastSeen
        if (last != null && last >= seen) return@writing
        offers.filterNot(OfferSighting::readable).takeIf { it.isNotEmpty() }?.let { unreadable ->
            val example = unreadable.first()
            log.warn(
                "Left ${unreadable.size} offers of ${store.label} out of the price history, as their dates are no ISO dates: " +
                    "such as ${example.title}, ${example.validFrom} to ${example.validUntil}",
            )
        }
        val saw = OfferSighting.of(offers, serverDay(now))
        if (saw.isEmpty()) return@writing
        val open = last?.let { q.selectOpenOffers(store.name, it).executeAsList() }.orEmpty().associateBy { it.id }
        val (continued, started) = changes(open.values.map { it.id to it.identity() }, saw) { it.identity }
        for ((id, sighting) in continued) {
            val offer = sighting.offer
            val ingredient = open.getValue(id).ingredient ?: ingredientOf(offer)
            q.continueOfferPeriod(
                lastSeen = seen,
                validFrom = offer.validFrom,
                validUntil = offer.validUntil,
                ingredient = ingredient,
                category = offer.category,
                url = httpsAddress(offer.url),
                eurosPerLitre = DealTerms.of(offer).eurosPerLitre,
                id = id,
            )
        }
        for (sighting in started) insert(sighting, ingredientOf(sighting.offer), seen)
        q.insertOfferRecording(store.name, seen, saw.size.toLong())
    }

    private fun insert(sighting: OfferSighting, ingredient: String?, seen: String) {
        val offer = sighting.offer
        val key = sighting.identity.product
        val terms = DealTerms.of(offer)
        val kind = terms.kind
        q.insertOfferPeriod(
            store = offer.store.name,
            productKey = key.title,
            packKey = key.pack,
            title = offer.title,
            packSize = offer.packSize,
            ingredient = ingredient,
            deal = offer.deal,
            dealKind = kindName(kind),
            dealPacks = terms.packs.toLong(),
            dealFree = (kind as? DealKind.FreeItems)?.free?.toLong(),
            dealPercent = (kind as? DealKind.PercentOff)?.percent,
            dealEuros = dealEuros(kind),
            price = offer.price,
            originalPrice = offer.originalPrice,
            paidShare = terms.paidShare,
            eurosPerKg = terms.eurosPerKg,
            eurosPerPiece = terms.eurosPerPiece,
            validFrom = offer.validFrom,
            validUntil = offer.validUntil,
            firstSeen = seen,
            lastSeen = seen,
            category = offer.category,
            url = httpsAddress(offer.url),
            eurosPerLitre = terms.eurosPerLitre,
        )
    }

    /**
     * The offer's address at the store, kept only when it is an https address: the parsers build them so,
     * and an app may open it one day, so whatever else a page might carry is not kept.
     */
    private fun httpsAddress(url: String?): String? =
        url?.takeIf { runCatching { URI(it) }.getOrNull()?.let { uri -> uri.scheme == "https" && !uri.host.isNullOrEmpty() } == true }

    private fun OfferHistory.identity() =
        OfferIdentity(ProductKey.recorded(Store.valueOf(store), productKey, packKey), price, originalPrice, normalised(deal))

    /**
     * Records the regular price a lookup at [now] found, in the transaction that stores it. [lastFound] is
     * when the previous lookup found this name's price (`ingredientPrice.fetchedAt`), null when it found none:
     * a period continues only from that lookup, so a name the lookup could not find for a while starts again.
     */
    fun recordRegularPrice(price: RegularPriceSighting, lastFound: Instant?, now: Instant): Unit = db.writing {
        val open = lastFound?.let { q.selectOpenRegularPrice(price.name, price.store.name, seen(it)).executeAsOneOrNull() }
        val (continued, started) = changes(listOfNotNull(open).map { it.id to it.identity() }, listOf(price)) { it.identity }
        val seen = seen(now)
        for ((id, _) in continued) q.continueRegularPrice(seen, id)
        for (p in started) q.insertRegularPrice(p.name, p.store.name, p.product, p.packSize, p.packPrice, p.eurosPerKg, seen, seen)
    }

    private fun RegularPriceHistory.identity() =
        RegularPriceSighting(name, Store.valueOf(store), product, packSize, packPrice, eurosPerKg).identity

    /**
     * The periods of [store]'s [products] (normalised titles, [ProductKey.title]) seen at any time from [from] to
     * [until] (both included), in no set order: those that began by [until] and were last seen from [from] on.
     * With [recordings], what judging a deal reads.
     */
    fun offerPeriods(store: Store, products: Collection<String>, from: Instant, until: Instant): List<OfferHistory> {
        if (products.isEmpty()) return emptyList()
        val wanted = products.toSet()
        // Rows recorded with the store's markup in the title are found by the title they have once cleaned (issue 320).
        val marked = q.selectMarkedOfferPeriods(store.name, seen(from), seen(until)).executeAsList().filter { it.productKey !in wanted && storeText(it.productKey) in wanted }
        return q.selectOfferPeriods(store.name, wanted.toList(), seen(from), seen(until)).executeAsList() + marked
    }

    /** When [store]'s offers were recorded from [from] to [until] (both included), oldest first. */
    fun recordings(store: Store, from: Instant, until: Instant): List<OfferRecording> =
        q.selectOfferRecordings(store.name, seen(from), seen(until)).executeAsList()

    /**
     * Whether [store] was recorded with an offer for the catalogue [ingredient] (by name, any case) that saves
     * something and was valid on some day from [from] to [until] (both included): what a late tick's saving
     * store is believed by (see [savingFor]).
     */
    fun offered(store: Store, ingredient: String, from: LocalDate, until: LocalDate): Boolean =
        q.selectOfferedBetween(store.name, ingredient, from.toString(), until.toString()).executeAsOne()

    /** The regular prices of the ingredient [names] (any case) at every store, last seen from [from] on. */
    fun regularPrices(names: Collection<String>, from: Instant): List<RegularPriceHistory> =
        if (names.isEmpty()) emptyList() else q.selectRegularPrices(names.distinct(), seen(from)).executeAsList()

    /** Removes the offers, and the recordings, last seen more than [KEEP_YEARS] years before [now]. */
    fun pruneOffers(now: Instant): Unit = db.writing {
        q.pruneOffers(cutoff(now))
        q.pruneOfferRecordings(cutoff(now))
    }

    /** Removes the regular prices last seen more than [KEEP_YEARS] years before [now]. */
    fun pruneRegularPrices(now: Instant): Unit = db.writing { q.pruneRegularPrices(cutoff(now)) }

    private fun cutoff(now: Instant) = seen(now.atZone(ZoneOffset.UTC).minusYears(KEEP_YEARS).toInstant())

    private fun kindName(kind: DealKind) = when (kind) {
        is DealKind.LowerPrice -> "LOWER_PRICE"
        is DealKind.PercentOff -> "PERCENT_OFF"
        is DealKind.AmountOff -> "AMOUNT_OFF"
        is DealKind.MultiPrice -> "N_FOR_PRICE"
        is DealKind.FreeItems -> "X_PLUS_Y_FREE"
        is DealKind.NthHalfPrice -> "NTH_HALF_PRICE"
        is DealKind.PricePerKg -> "PRICE_PER_KG"
        is DealKind.Unknown -> "UNKNOWN"
    }

    private fun dealEuros(kind: DealKind) = when (kind) {
        is DealKind.LowerPrice -> kind.euros
        is DealKind.MultiPrice -> kind.euros
        is DealKind.AmountOff -> kind.euros
        is DealKind.PricePerKg -> kind.euros
        else -> null
    }

    private companion object {
        const val KEEP_YEARS = 2L

        /**
         * An instant as the history stores it: to the second, so every value has the same width and text
         * order is time order (`max(lastSeen)`, the pruning cutoff).
         */
        fun seen(instant: Instant): String = instant.truncatedTo(ChronoUnit.SECONDS).toString()
    }
}
