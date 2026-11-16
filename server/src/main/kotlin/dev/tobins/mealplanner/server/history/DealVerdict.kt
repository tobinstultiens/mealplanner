package dev.tobins.mealplanner.server.history

import dev.tobins.mealplanner.server.serverDay
import java.time.Instant
import java.time.LocalDate

/** Whether an offer is a good deal, compared with its own product's history at its store. */
enum class Verdict {
    /** At or below the lowest seen before, and clearly below at least half of the earlier offers. */
    REAL_DEAL,

    /** About what it usually goes for on offer: neither at the lowest, nor clearly dearer than most earlier offers. */
    USUAL_DEAL,

    /** Clearly dearer than more than half of the earlier offers: it usually goes for less. */
    WORSE_THAN_USUAL,

    /** On offer this same way on nearly every day the store was read: that is its usual price, not a deal. */
    ALWAYS_ON_OFFER,

    /** Its regular price went up just before, and with the deal it costs about what it cost before the raise. */
    NOT_A_DEAL,

    /** The deal gives nothing to compare by: no share of the regular price, no price per kg, litre or piece. */
    NOT_COMPARABLE,

    /** Too little history to say; said rather than a guess. */
    NOT_ENOUGH_HISTORY,
}

/**
 * The [verdict] on one offer with what it rests on: the [measure] compared by and the offer's [value] in it
 * (its share measured against the price before a [raise]), the earlier offers' [typical] (median) value, the
 * [lowest] of them and the latest one at least as cheap as now ([lastAsCheap]), how many of them had the
 * measure ([compared]), the [earlier] offers themselves (oldest first), and how many days the store was read
 * since [firstRead], on how many of them the product was on offer at all and in this same way.
 */
data class DealVerdict(
    val verdict: Verdict,
    val measure: Measure?,
    val value: Double?,
    val typical: Double?,
    val lowest: OfferPeriod?,
    val lastAsCheap: OfferPeriod?,
    val compared: Int,
    val earlier: List<OfferPeriod>,
    val daysRead: Int,
    val daysOnOffer: Int,
    val daysThisDeal: Int,
    val firstRead: LocalDate?,
    val raise: RegularRaise?,
)

/**
 * How far back the history is read: a year, so that a seasonal product's offers of last season count, while
 * prices from before that say little about today's.
 */
const val LOOKBACK_WEEKS = 52L

/**
 * Days the store must have been read before any verdict: 8 weeks. Stores run their offers by the week and
 * bring a product back on offer every few weeks (the issue's examples: every 3, every 4 weeks), so 8 weeks
 * see a usual deal come back at least once. Days, not calendar time, as a store that could not be read for a
 * while has not been watched then.
 */
const val MIN_DAYS_READ = 56

/**
 * Earlier offers of the product with the same measure, before it is compared with them. One could be the
 * odd one out; with two, "usual" and "lowest" begin to mean something.
 */
const val MIN_EARLIER_OFFERS = 2

/**
 * The share of the days read on which an offer must have run this same way to be the product's usual price:
 * nine in ten leaves room for a night it was missed and a pause between two weeks' offers.
 */
const val ALWAYS_SHARE = 0.9

/**
 * Prices this close (3 %) are the same: "2 voor 3,99" and "2 voor 4,00", or prices per kg of packs written
 * "ca. 450 g". Less than this is no reason to buy now or to wait.
 */
const val TOLERANCE = 0.03

/**
 * With a raised regular price, a deal paying at least this share of the price before the raise saves
 * nothing: the shopping list's own bound for a deal that saves something (`Discounts.factor`, at most 0.97).
 */
const val NO_SAVING_SHARE = 0.97

/**
 * The verdict on [offer], from its product's [periods] at its store (the history's rows, every pack and
 * deal), the store's [recordings] over the same time, and the product's regular prices of the same pack
 * looked up ([regular]). The rules, in order (docs/data-sources.md, "Is it a good deal?"):
 * 1. A regular price raised in the [REFERENCE_DAYS] before the offer began is the price the share is
 *    measured against; a deal that then pays [NO_SAVING_SHARE] or more of it is [Verdict.NOT_A_DEAL].
 * 2. Fewer than [MIN_DAYS_READ] days read: [Verdict.NOT_ENOUGH_HISTORY].
 * 3. On offer this same way on [ALWAYS_SHARE] of those days: [Verdict.ALWAYS_ON_OFFER].
 * 4. Compared by the measure that most earlier offers have too ([Measure], ties in its order); none at all:
 *    [Verdict.NOT_COMPARABLE]; fewer than [MIN_EARLIER_OFFERS] with it: [Verdict.NOT_ENOUGH_HISTORY].
 * 5. Against those earlier offers, [TOLERANCE] apart being the same: [Verdict.REAL_DEAL],
 *    [Verdict.WORSE_THAN_USUAL] or else [Verdict.USUAL_DEAL].
 *
 * Earlier offers are the [joinGaps] runs that ended before the store's last recording: the current one, and
 * another offer of the product running at the same time (Jumbo lists next week's), are not.
 */
fun judge(offer: CurrentOffer, periods: List<OfferPeriod>, recordings: List<Instant>, regular: List<RegularPrice>): DealVerdict {
    val runs = joinGaps(periods, recordings)
    val latest = recordings.maxOrNull()
    val earlier = runs.filter { latest == null || it.lastSeen < latest }
    val days = recordings.groupBy(::serverDay)
    fun daysWith(runs: List<OfferPeriod>) = days.values.count { seen -> seen.any { at -> runs.any { it.covers(at) } } }
    val raise = regularRaise(offer, regular + pricesBefore(runs, offer))
    // Against a raised price, the share is what a pack costs with the deal against what it cost before.
    val share = raise?.let { raised -> (offer.measures.perPack ?: offer.measures.paidShare?.let { it * raised.now })?.let { it / raised.before } }
        ?: offer.measures.paidShare
    val facts = DealVerdict(
        verdict = Verdict.NOT_ENOUGH_HISTORY, measure = null, value = null, typical = null, lowest = null, lastAsCheap = null, compared = 0,
        earlier = earlier, daysRead = days.size, daysOnOffer = daysWith(runs), daysThisDeal = daysWith(runs.filter { it.dealKey == offer.dealKey }),
        firstRead = days.keys.minOrNull(), raise = raise,
    )
    return when {
        raise != null && share != null && share >= NO_SAVING_SHARE -> facts.copy(verdict = Verdict.NOT_A_DEAL, measure = Measure.PAID_SHARE, value = share)
        facts.daysRead < MIN_DAYS_READ -> facts
        facts.daysThisDeal >= ALWAYS_SHARE * facts.daysRead -> facts.copy(verdict = Verdict.ALWAYS_ON_OFFER)
        else -> compare(facts, offer.measures.copy(paidShare = share), offer.identity.product.pack)
    }
}

/** The store's prices before in the product's offers of the same pack as [offer]: regular prices seen then. */
private fun pricesBefore(runs: List<OfferPeriod>, offer: CurrentOffer): List<RegularPrice> =
    runs.filter { it.identity.product.pack == offer.identity.product.pack }
        .mapNotNull { run -> run.identity.originalPrice?.takeIf { it > 0 }?.let { RegularPrice(it, run.firstSeen, run.lastSeen) } }

private fun compare(facts: DealVerdict, now: Measures, pack: String): DealVerdict {
    /** The earlier offers' values in [measure]; a price per pack only of the same pack. */
    fun earlier(measure: Measure) = facts.earlier.filter { measure != Measure.PER_PACK || it.identity.product.pack == pack }
        .mapNotNull { run -> run.measures[measure]?.let { run to it } }
    val measure = Measure.entries.filter { now[it] != null }.maxByOrNull { earlier(it).size }
        ?: return facts.copy(verdict = Verdict.NOT_COMPARABLE)
    val value = now[measure]!!
    val compared = earlier(measure)
    val measured = facts.copy(measure = measure, value = value, compared = compared.size)
    if (compared.size < MIN_EARLIER_OFFERS) return measured
    val lowest = compared.minBy { it.second }
    val cheaperThanNow = compared.count { (_, earlier) -> value > earlier * (1 + TOLERANCE) }
    val dearerThanNow = compared.count { (_, earlier) -> value < earlier * (1 - TOLERANCE) }
    val verdict = when {
        value <= lowest.second * (1 + TOLERANCE) && dearerThanNow * 2 >= compared.size -> Verdict.REAL_DEAL
        cheaperThanNow * 2 > compared.size -> Verdict.WORSE_THAN_USUAL
        else -> Verdict.USUAL_DEAL
    }
    return measured.copy(
        verdict = verdict,
        typical = median(compared.map { it.second }),
        lowest = lowest.first,
        lastAsCheap = compared.filter { it.second <= value * (1 + TOLERANCE) }.maxByOrNull { it.first.lastSeen }?.first,
    )
}

private fun median(values: List<Double>): Double = values.sorted().let { sorted ->
    val middle = sorted.size / 2
    if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}
