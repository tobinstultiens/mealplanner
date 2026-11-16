package dev.tobins.mealplanner.server.history

import dev.tobins.mealplanner.server.SERVER_ZONE
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

    /** Another offer of the product, dated over today too, is clearly cheaper right now. */
    CHEAPER_ANOTHER_WAY,

    /** The deal gives nothing to compare by: no share of the regular price, no price per kg, litre or piece. */
    NOT_COMPARABLE,

    /** Too little history to say; said rather than a guess. */
    NOT_ENOUGH_HISTORY,
}

/**
 * The [verdict] on one offer with what it rests on: the [measure] compared by and the offer's [value] in it
 * (its share measured against the price before a [raise]), the earlier offers' [typical] (median) value, the
 * [lowest] of them and the latest one at least as cheap as now ([lastAsCheap]), how many of them had the
 * measure ([compared]), the [earlier] offers themselves (oldest first), the other offers of the product dated
 * over today too ([alongside]) and the clearly cheaper one of them ([cheaperNow]), and how many days the store was
 * read since [firstRead], on how many of them the store dated the product on offer at all, and on offer in today's
 * pack and deal ([daysThisDeal]).
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
    val alongside: List<OfferPeriod> = emptyList(),
    val cheaperNow: OfferPeriod? = null,
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
 * The verdict on [today]'s offer, from its product's [periods] at its store (the history's rows, every pack and
 * deal), the store's [recordings] over the same time, and the product's regular prices of the same pack
 * looked up ([regular]), on [day]. The rules, in order (docs/data-sources.md, "Is it a good deal?"):
 * 1. A regular price raised in the [REFERENCE_DAYS] before the offer began is the price the share is
 *    measured against; a deal that then pays [NO_SAVING_SHARE] or more of it is [Verdict.NOT_A_DEAL].
 * 2. Another offer of the product dated over today too (alongside, below) that is clearly cheaper, by [TOLERANCE],
 *    in today's measure: [Verdict.CHEAPER_ANOTHER_WAY]. Like rule 1, a fact that needs no history.
 * 3. Fewer than [MIN_DAYS_READ] days read: [Verdict.NOT_ENOUGH_HISTORY].
 * 4. Dated on offer in today's pack and deal on [ALWAYS_SHARE] of those days: [Verdict.ALWAYS_ON_OFFER].
 * 5. No measure at all: [Verdict.NOT_COMPARABLE]; fewer than [MIN_EARLIER_OFFERS] earlier offers with today's
 *    measure: [Verdict.NOT_ENOUGH_HISTORY].
 * 6. Against those earlier offers, [TOLERANCE] apart being the same: [Verdict.REAL_DEAL],
 *    [Verdict.WORSE_THAN_USUAL] or else [Verdict.USUAL_DEAL].
 *
 * Earlier offers are the [joinOffers] offers whose store dates ended before [day], other than today's offer itself
 * ([CurrentOffer.isItself]). Offers alongside are the others dated over [day]: not earlier, as they have not ended,
 * but on offer now. Next week's offer listed early is neither. All of it goes by the store's dates alone, never by
 * the nights a product was seen, and so do the days on offer (rule 4).
 *
 * Today's offer is measured as the store shows it today ([today]: its own price and price before), never at a
 * price of an earlier row it was joined with. The one exception is rule 1: against a raised regular price the
 * share is measured against the price before the raise, and the raise is reported with the verdict.
 */
fun judge(today: CurrentOffer, periods: List<OfferPeriod>, recordings: List<Instant>, regular: List<RegularPrice>, day: LocalDate): DealVerdict {
    val runs = joinOffers(periods)
    val itself = runs.filter(today::isItself)
    val earlier = runs.filter { it !in itself && it.validUntil < day }
    val alongside = runs.filter { it !in itself && it.dated(day) }
    val days = recordings.mapTo(sortedSetOf(), ::serverDay)
    fun daysWith(runs: List<OfferPeriod>) = days.count { d -> runs.any { it.dated(d) } }
    val raise = regularRaise(today, regular + pricesBefore(periods, today))
    // Against a raised price, the share is what a pack costs with the deal against what it cost before.
    val share = raise?.let { raised -> (today.measures.perPack ?: today.measures.paidShare?.let { it * raised.now })?.let { it / raised.before } }
        ?: today.measures.paidShare
    val now = today.measures.copy(paidShare = share)
    val pack = today.identity.product.pack
    val measure = measureOf(now, earlier, pack)
    val cheaperNow = measure?.let { cheaperAlongside(now, it, alongside, pack) }
    val facts = DealVerdict(
        verdict = Verdict.NOT_ENOUGH_HISTORY, measure = null, value = null, typical = null, lowest = null, lastAsCheap = null, compared = 0,
        earlier = earlier, daysRead = days.size, daysOnOffer = daysWith(runs), daysThisDeal = daysWith(runs.filter { it.offerKey == today.offerKey }),
        firstRead = days.firstOrNull(), raise = raise, alongside = alongside, cheaperNow = cheaperNow?.first,
    )
    return when {
        raise != null && share != null && share >= NO_SAVING_SHARE -> facts.copy(verdict = Verdict.NOT_A_DEAL, measure = Measure.PAID_SHARE, value = share)
        cheaperNow != null -> facts.copy(verdict = Verdict.CHEAPER_ANOTHER_WAY, measure = cheaperNow.second, value = now[cheaperNow.second])
        facts.daysRead < MIN_DAYS_READ -> facts
        facts.daysThisDeal >= ALWAYS_SHARE * facts.daysRead -> facts.copy(verdict = Verdict.ALWAYS_ON_OFFER)
        measure == null -> facts.copy(verdict = Verdict.NOT_COMPARABLE)
        else -> compare(facts, now, measure, pack)
    }
}

/**
 * The store's prices before in the product's rows of the same pack as [offer], each as the regular price over the
 * row's store dates, whether or not the row was joined into today's offer: last week's "1+1 gratis" at 1,59 and this
 * week's at 3,19 are one offer, and still a raise. By the rows, not the joined offers, which keep only the lowest
 * price before; by the store's dates, not the nights the row was seen, which say nothing about the price.
 */
private fun pricesBefore(rows: List<OfferPeriod>, offer: CurrentOffer): List<RegularPrice> =
    rows.filter { it.identity.product.pack == offer.identity.product.pack }.mapNotNull { row ->
        row.identity.originalPrice?.takeIf { it > 0 }?.let {
            RegularPrice(it, row.validFrom.atStartOfDay(SERVER_ZONE).toInstant(), row.validUntil.atStartOfDay(SERVER_ZONE).toInstant())
        }
    }

/** [offer]'s value in [measure], or null when it has none; a price per pack only of today's [pack]. */
private fun valueOf(offer: OfferPeriod, measure: Measure, pack: String): Double? =
    offer.measures[measure]?.takeIf { measure != Measure.PER_PACK || offer.identity.product.pack == pack }

/** The measure today's offer is compared by: of those it has ([now]), the one most [earlier] offers have, ties in [Measure]'s order. */
private fun measureOf(now: Measures, earlier: List<OfferPeriod>, pack: String): Measure? =
    Measure.entries.filter { now[it] != null }.maxByOrNull { m -> earlier.count { valueOf(it, m, pack) != null } }

/**
 * The offer of [alongside] most clearly cheaper than today's ([now]), with the measure that says so, or null when
 * none is: each compared in today's [measure] where it has it, else in the first measure both have.
 */
private fun cheaperAlongside(now: Measures, measure: Measure, alongside: List<OfferPeriod>, pack: String): Pair<OfferPeriod, Measure>? =
    alongside.mapNotNull { other ->
        val m = measure.takeIf { valueOf(other, it, pack) != null } ?: Measure.entries.firstOrNull { now[it] != null && valueOf(other, it, pack) != null }
        m?.let { Triple(other, it, now[it]!! / valueOf(other, it, pack)!!) }
    }.filter { it.third > 1 + TOLERANCE }
        .maxWithOrNull(compareBy({ it.third }, { it.first.validFrom }))?.let { it.first to it.second }

private fun compare(facts: DealVerdict, now: Measures, measure: Measure, pack: String): DealVerdict {
    val value = now[measure]!!
    val compared = facts.earlier.mapNotNull { run -> valueOf(run, measure, pack)?.let { run to it } }
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
    // The latest offer at least as cheap, of the earlier ones and those on offer now: "less than ever" must hold for both.
    val asCheap = (compared + facts.alongside.mapNotNull { run -> valueOf(run, measure, pack)?.let { run to it } }).filter { it.second <= value * (1 + TOLERANCE) }
    return measured.copy(
        verdict = verdict,
        typical = median(compared.map { it.second }),
        lowest = lowest.first,
        lastAsCheap = asCheap.maxWithOrNull(compareBy({ it.first.validUntil }, { it.first.validFrom }))?.first,
    )
}

private fun median(values: List<Double>): Double = values.sorted().let { sorted ->
    val middle = sorted.size / 2
    if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}
