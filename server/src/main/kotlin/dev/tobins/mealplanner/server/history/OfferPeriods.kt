package dev.tobins.mealplanner.server.history

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.pricing.DealTerms
import dev.tobins.mealplanner.server.serverDay
import java.time.Instant
import java.time.LocalDate

/** What a deal is compared by, in order of preference when two of them have as much history. */
enum class Measure {
    PER_KG,
    PER_LITRE,
    PER_PIECE,

    /** The price of one pack: only between offers of the same pack, for a product whose size can't be read. */
    PER_PACK,

    /** The share of the regular price paid: comparable for one product however its deal is worded. */
    PAID_SHARE,
}

/**
 * What an offer comes to when its deal is used in full ([DealTerms]): the price per kg, litre, piece or pack,
 * and the share of the regular price paid. Lower is better in each. Two offers are compared by one of these,
 * never by their deal text, so a "2+1 gratis" and a "1+1 gratis" of the same product compare by what is paid.
 */
data class Measures(val perKg: Double?, val perLitre: Double?, val perPiece: Double?, val perPack: Double?, val paidShare: Double?) {
    operator fun get(measure: Measure): Double? = when (measure) {
        Measure.PER_KG -> perKg
        Measure.PER_LITRE -> perLitre
        Measure.PER_PIECE -> perPiece
        Measure.PER_PACK -> perPack
        Measure.PAID_SHARE -> paidShare
    }

    companion object {
        /**
         * The measures of [offer], read now. An earlier offer is read again from what the history kept of it
         * (title, pack size, deal, prices), not taken from the readings stored with it: so the offer of today
         * and the earlier ones are read by the same reader, also after a change to how deals are read.
         */
        fun of(offer: Offer) = DealTerms.of(offer).let { Measures(it.eurosPerKg, it.eurosPerLitre, it.eurosPerPiece, it.eurosPerPack, it.paidShare) }
    }
}

/**
 * A run of refreshes in which a product was offered one way ([identity]), with the [deal] as the store
 * wrote it, when it was first and last seen, and the dates the store gave. A row of the history is one;
 * [joinGaps] joins rows of one run that a short gap split.
 */
data class OfferPeriod(
    val identity: OfferIdentity,
    val deal: String?,
    val measures: Measures,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val validFrom: LocalDate,
    val validUntil: LocalDate,
) {
    /** The way of offering that "always the same deal" goes by: the deal and its price, whatever the price before. */
    val dealKey get() = identity.deal to identity.price

    /** Whether the product was offered this way at the refresh at [instant]. */
    fun covers(instant: Instant) = instant in firstSeen..lastSeen

    /** This run and a [later] one of the same offer as one: from the first sighting to the last, over both's dates. */
    fun joinedWith(later: OfferPeriod) = copy(
        firstSeen = minOf(firstSeen, later.firstSeen),
        lastSeen = maxOf(lastSeen, later.lastSeen),
        validFrom = minOf(validFrom, later.validFrom),
        validUntil = maxOf(validUntil, later.validUntil),
    )
}

/** An offer of today, as a verdict compares it with its history. */
data class CurrentOffer(val identity: OfferIdentity, val measures: Measures, val validFrom: LocalDate, val originalPrice: Double?) {
    val dealKey get() = identity.deal to identity.price

    companion object {
        fun of(offer: Offer) = CurrentOffer(
            OfferSighting(offer).identity,
            Measures.of(offer),
            LocalDate.parse(offer.validFrom),
            offer.originalPrice?.takeIf { it > 0 },
        )
    }
}

/**
 * Most server days with a refresh that did not see a product, between two rows of one offer, for the rows
 * still to be one run. The store parsers skip an item with a missing field without a word, so a product can
 * miss one night and be back the next unchanged; that is no new offer. Nights the store was not read at all
 * are no nights without the offer, so they do not count.
 */
const val MAX_MISSED_DAYS = 1

/**
 * Joins the periods of one product that are one run of the same offer split by a short gap: the same
 * [OfferPeriod.identity], at most [MAX_MISSED_DAYS] days between them on which the store was read
 * ([recordings]) without the product, and dates that run on (the later one starts by the day after the
 * earlier one ends). A gap only of nights the store was not read joins as well: nothing says the offer
 * stopped. Dates that do not run on mean the store itself ended the offer and began it again.
 */
fun joinGaps(periods: List<OfferPeriod>, recordings: List<Instant>): List<OfferPeriod> =
    periods.groupBy { it.identity }.values.flatMap { same ->
        same.sortedBy { it.firstSeen }.fold(mutableListOf<OfferPeriod>()) { joined, next ->
            val last = joined.lastOrNull()
            if (last != null && runsOn(last, next, recordings)) joined[joined.lastIndex] = last.joinedWith(next) else joined += next
            joined
        }
    }.sortedBy { it.firstSeen }

private fun runsOn(earlier: OfferPeriod, later: OfferPeriod, recordings: List<Instant>): Boolean {
    val missed = recordings.filter { it > earlier.lastSeen && it < later.firstSeen }.mapTo(HashSet(), ::serverDay)
    return missed.size <= MAX_MISSED_DAYS && !later.validFrom.isAfter(earlier.validUntil.plusDays(1))
}
