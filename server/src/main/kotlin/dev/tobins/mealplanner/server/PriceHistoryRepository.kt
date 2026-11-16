package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.pricing.DealKind
import dev.tobins.mealplanner.pricing.DealTerms
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.db.OfferHistory
import dev.tobins.mealplanner.server.db.RegularPriceHistory
import dev.tobins.mealplanner.server.history.OfferIdentity
import dev.tobins.mealplanner.server.history.OfferSighting
import dev.tobins.mealplanner.server.history.ProductKey
import dev.tobins.mealplanner.server.history.RegularPriceSighting
import dev.tobins.mealplanner.server.history.changes
import dev.tobins.mealplanner.server.history.normalised
import dev.tobins.mealplanner.server.offers.OfferMatcher
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
     * out and logged ([OfferSighting.readable]); the rest of the store's offers are recorded.
     *
     * [ingredientOf] is asked only for a period that starts, or one still without an ingredient: what a
     * period is for stays as it was matched, and one that matched nothing gets the ingredient an account
     * adds later.
     */
    fun recordOffers(store: Store, offers: List<Offer>, ingredientOf: (Offer) -> String?, now: Instant) {
        val seen = seen(now)
        val last = q.lastOfferRecording(store.name).executeAsOne().lastSeen
        if (last != null && last >= seen) return
        offers.filterNot(OfferSighting::readable).takeIf { it.isNotEmpty() }?.let { unreadable ->
            val example = unreadable.first()
            log.warn(
                "Left ${unreadable.size} offers of ${store.label} out of the price history, as their dates are no ISO dates: " +
                    "such as ${example.title}, ${example.validFrom} to ${example.validUntil}",
            )
        }
        val saw = OfferSighting.of(offers, LocalDate.ofInstant(now, ZONE))
        val open = last?.let { q.selectOpenOffers(store.name, it).executeAsList() }.orEmpty().associateBy { it.id }
        val (continued, started) = changes(open.values.map { it.id to it.identity() }, saw) { it.identity }
        for ((id, sighting) in continued) {
            val ingredient = open.getValue(id).ingredient ?: ingredientOf(sighting.offer)
            q.continueOfferPeriod(seen, sighting.offer.validFrom, sighting.offer.validUntil, ingredient, id)
        }
        for (sighting in started) insert(sighting, ingredientOf(sighting.offer), seen)
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
        )
    }

    private fun OfferHistory.identity() =
        OfferIdentity(ProductKey(Store.valueOf(store), productKey, packKey), price, originalPrice, normalised(deal))

    /**
     * Records the regular price a lookup at [now] found, in the transaction that stores it. [lastFound] is
     * when the previous lookup found this name's price (`ingredientPrice.fetchedAt`), null when it found none:
     * a period continues only from that lookup, so a name the lookup could not find for a while starts again.
     */
    fun recordRegularPrice(price: RegularPriceSighting, lastFound: Instant?, now: Instant) {
        val open = lastFound?.let { q.selectOpenRegularPrice(price.name, price.store.name, seen(it)).executeAsOneOrNull() }
        val (continued, started) = changes(listOfNotNull(open).map { it.id to it.identity() }, listOf(price)) { it.identity }
        val seen = seen(now)
        for ((id, _) in continued) q.continueRegularPrice(seen, id)
        for (p in started) q.insertRegularPrice(p.name, p.store.name, p.product, p.packSize, p.packPrice, p.eurosPerKg, seen, seen)
    }

    private fun RegularPriceHistory.identity() =
        RegularPriceSighting(name, Store.valueOf(store), product, packSize, packPrice, eurosPerKg).identity

    /** Removes the offers last seen more than [KEEP_YEARS] years before [now]. */
    fun pruneOffers(now: Instant) = q.pruneOffers(cutoff(now))

    /** Removes the regular prices last seen more than [KEEP_YEARS] years before [now]. */
    fun pruneRegularPrices(now: Instant) = q.pruneRegularPrices(cutoff(now))

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
        val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")

        /**
         * An instant as the history stores it: to the second, so every value has the same width and text
         * order is time order (`max(lastSeen)`, the pruning cutoff).
         */
        fun seen(instant: Instant): String = instant.truncatedTo(ChronoUnit.SECONDS).toString()
    }
}
