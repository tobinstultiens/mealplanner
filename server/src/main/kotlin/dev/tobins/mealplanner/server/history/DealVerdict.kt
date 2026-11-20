package dev.tobins.mealplanner.server.history

import dev.tobins.mealplanner.server.SERVER_ZONE
import dev.tobins.mealplanner.server.serverDay
import java.time.Instant
import java.time.LocalDate

/**
 * Whether an offer is a good deal, compared with its own pack's history at its store: each pack against itself (the
 * owner's choice in issue 197). That another pack is cheaper per kg or per litre is said apart
 * ([DealVerdict.cheaperPack]), never as the verdict.
 */
enum class Verdict {
    /** At or below the lowest of its pack seen, before or shown now, and clearly below at least half of its pack's earlier offers. */
    REAL_DEAL,

    /** About what its pack usually goes for on offer: neither at the lowest, nor clearly dearer than most earlier offers. */
    USUAL_DEAL,

    /** Clearly dearer than more than half of its pack's earlier offers: it usually goes for less. */
    WORSE_THAN_USUAL,

    /** On offer this same way on nearly every day the store was read: that is its usual price, not a deal. */
    ALWAYS_ON_OFFER,

    /** Its regular price went up just before, and with the deal it costs about what it cost before the raise. */
    NOT_A_DEAL,

    /** Another offer of the same pack, which the store shows today too, is clearly cheaper right now. */
    CHEAPER_ANOTHER_WAY,

    /** The deal gives nothing to compare by: no share of the regular price, no price per kg, litre or piece. */
    NOT_COMPARABLE,

    /** Too little history to say; said rather than a guess. */
    NOT_ENOUGH_HISTORY,
}

/**
 * The [verdict] on one offer, judged on [day], with what it rests on: the [measure] compared by and the offer's [value]
 * in it (its share measured against the price before a [raise]), the earlier offers' [typical] (median) value, the
 * [lowest] of them and the latest one at least as cheap as now ([lastAsCheap]), how many of them had the measure
 * ([compared]), the [earlier] offers of today's pack themselves (oldest first), the other offers of the product the
 * store shows today, of any pack ([alongside], as shown today), the clearly cheaper one of today's pack among them
 * ([cheaperNow]), and how many days the store was read since [firstRead], on how many of them the store dated the
 * product on offer at all, and on offer in today's pack and deal ([daysThisDeal]). [otherPacks] says the product was
 * offered in other packs too, which the verdict leaves out; [cheaperPack] is one of those that is or was cheaper.
 * [pack] is today's pack, as its product's packs are known ([SamePacks]).
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
    val day: LocalDate,
    val alongside: List<OfferPeriod> = emptyList(),
    val cheaperNow: OfferPeriod? = null,
    val otherPacks: Boolean = false,
    val cheaperPack: CheaperPack? = null,
    val pack: String = "",
) {
    /** Whether [run], an ended offer, ended before the store's dates did: dated over [day], but no longer shown. */
    fun endedEarly(run: OfferPeriod) = !run.validUntil.isBefore(day)

    /** A usual deal clearly below what its pack usually goes for, though not at the lowest: "better than usual". */
    val betterThanUsual: Boolean get() = verdict == Verdict.USUAL_DEAL && value != null && typical != null && value < typical * (1 - TOLERANCE)
}

/**
 * An [offer] of another pack of the product, clearly cheaper than today's in [measure] (per kg or per litre), in
 * which today's offer comes to [value]: the owner's "cheapest per kg in the 1 kg pack" (issue 197). One the store shows
 * today ([now]) is preferred, as it can be bought instead; else the cheapest that ended.
 */
data class CheaperPack(val offer: OfferPeriod, val measure: Measure, val value: Double, val now: Boolean)

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
 * deal), the store's [recordings] over the same time, the product's regular prices of the same pack looked up
 * ([regular]), and the store's offers of the product stored now ([page]), on [day]. The rules, in order
 * (docs/data-sources.md, "Is it a good deal?"):
 * 1. A regular price raised in the [REFERENCE_DAYS] before the offer began is the price the share is
 *    measured against; a deal that then pays [NO_SAVING_SHARE] or more of it is [Verdict.NOT_A_DEAL].
 * 2. Another offer of today's pack shown today (alongside, below) that is clearly cheaper, by [TOLERANCE], in a price
 *    both have, else by the share: [Verdict.CHEAPER_ANOTHER_WAY]. Like rule 1, a fact that needs no history.
 * 3. Fewer than [MIN_DAYS_READ] days read: [Verdict.NOT_ENOUGH_HISTORY].
 * 4. Dated on offer in today's pack and deal on [ALWAYS_SHARE] of those days: [Verdict.ALWAYS_ON_OFFER].
 * 5. No measure at all: [Verdict.NOT_COMPARABLE]; fewer than [MIN_EARLIER_OFFERS] earlier offers of today's pack
 *    with today's measure: [Verdict.NOT_ENOUGH_HISTORY].
 * 6. Against those earlier offers, [TOLERANCE] apart being the same: [Verdict.REAL_DEAL],
 *    [Verdict.WORSE_THAN_USUAL] or else [Verdict.USUAL_DEAL].
 *
 * Each pack is judged against itself: only offers of today's pack count in rules 2 to 6. An offer of another pack
 * that is clearly cheaper per kg or per litre is said apart ([CheaperPack]); per piece it is not.
 *
 * Offers alongside are the [joinOffers] offers other than today's itself ([CurrentOffer.isItself]) that are dated over
 * [day] and that the store shows today ([TodaysPage]), as it shows them. Earlier offers are the others whose store
 * dates ended before [day], or that are dated over [day] but no longer shown: they ended early. Next week's offer
 * listed early is neither. Which rows are one offer, and the days on offer (rule 4), go by the store's dates alone,
 * never by the nights a product was seen; only "shown today" goes by today's page.
 *
 * Today's offer is measured as the store shows it today ([today]: its own price and price before), never at a
 * price of an earlier row it was joined with. The one exception is rule 1: against a raised regular price the
 * share is measured against the price before the raise, and the raise is reported with the verdict.
 */
fun judge(
    today: CurrentOffer,
    periods: List<OfferPeriod>,
    recordings: List<Instant>,
    regular: List<RegularPrice>,
    day: LocalDate,
    page: List<CurrentOffer> = emptyList(),
): DealVerdict {
    val runs = joinOffers(periods)
    val others = runs.filter { !today.isItself(it) && !it.validFrom.isAfter(day) }
    val shown = TodaysPage(periods, recordings.maxOrNull(), page).let { page -> others.filter { it.dated(day) }.associateWith(page::asShown) }
    val alongside = shown.values.filterNotNull()
    val gone = shown.filterValues { it == null }.keys
    val ended = others.filter { it.validUntil < day || it in gone }
    val pack = today.identity.product.pack
    val (earlier, endedOtherPacks) = ended.partition { it.identity.product.pack == pack }
    val (running, runningOtherPacks) = alongside.partition { it.identity.product.pack == pack }
    val days = recordings.mapTo(sortedSetOf(), ::serverDay)
    fun daysWith(runs: List<OfferPeriod>) = days.count { d -> runs.any { it.dated(d) } }
    val raise = regularRaise(today, regular + pricesBefore(periods, today))
    // Against a raised price, the share is what a pack costs with the deal against what it cost before.
    val share = raise?.let { raised -> (today.measures.perPack ?: today.measures.paidShare?.let { it * raised.now })?.let { it / raised.before } }
        ?: today.measures.paidShare
    val now = today.measures.copy(paidShare = share)
    val measure = measureOf(now, earlier)
    val cheaperNow = cheaperAlongside(now, running)
    val facts = DealVerdict(
        verdict = Verdict.NOT_ENOUGH_HISTORY, measure = null, value = null, typical = null, lowest = null, lastAsCheap = null, compared = 0,
        earlier = earlier, daysRead = days.size, daysOnOffer = daysWith(runs), daysThisDeal = daysWith(runs.filter { it.offerKey == today.offerKey }),
        firstRead = days.firstOrNull(), raise = raise, day = day, alongside = alongside, cheaperNow = cheaperNow?.first,
        otherPacks = runs.any { it.identity.product.pack != pack },
        cheaperPack = cheaperPack(today.measures, runningOtherPacks, now = true) ?: cheaperPack(today.measures, endedOtherPacks, now = false),
        pack = pack,
    )
    return when {
        raise != null && share != null && share >= NO_SAVING_SHARE -> facts.copy(verdict = Verdict.NOT_A_DEAL, measure = Measure.PAID_SHARE, value = share)
        cheaperNow != null -> facts.copy(verdict = Verdict.CHEAPER_ANOTHER_WAY, measure = cheaperNow.second, value = now[cheaperNow.second])
        facts.daysRead < MIN_DAYS_READ -> facts
        facts.daysThisDeal >= ALWAYS_SHARE * facts.daysRead -> facts.copy(verdict = Verdict.ALWAYS_ON_OFFER)
        measure == null -> facts.copy(verdict = Verdict.NOT_COMPARABLE)
        else -> compare(facts, now, measure, running)
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

/** The measure today's offer is compared by: of those it has ([now]), the one most [earlier] offers have, ties in [Measure]'s order. */
private fun measureOf(now: Measures, earlier: List<OfferPeriod>): Measure? =
    Measure.entries.filter { now[it] != null }.maxByOrNull { m -> earlier.count { it.measures[m] != null } }

/**
 * The offer of today's pack [running] alongside most clearly cheaper than today's ([now]), with the measure that says
 * so, or null when none is. "Cheaper" is what is paid: the first price both have (per kg, litre, piece or pack), else
 * the share of the regular price, which within one pack says the same. Another pack's offer is never "cheaper another
 * way": each pack is judged against itself, and a cheaper pack is said apart ([cheaperPack]).
 */
private fun cheaperAlongside(now: Measures, running: List<OfferPeriod>): Pair<OfferPeriod, Measure>? =
    running.mapNotNull { other ->
        Measure.entries.firstOrNull { now[it] != null && other.measures[it] != null }?.let { m -> Triple(other, m, now[m]!! / other.measures[m]!!) }
    }.filter { it.third > 1 + TOLERANCE }
        .maxWithOrNull(compareBy({ it.third }, { it.first.validFrom }))?.let { it.first to it.second }

/**
 * Of [offers] of other packs, the one most clearly cheaper than today's ([today]) per kg or per litre (the first both
 * have), by more than [TOLERANCE]; null when none is. Only the price of an amount of the same stuff tells which of two
 * packs costs less: not the price of a pack, not the shares of two packs' regular prices, and not the price of a
 * piece, which the owner ruled out (issue 197): a pan in two sizes is two pieces of different worth. Of the most
 * clearly cheaper, the latest.
 */
private fun cheaperPack(today: Measures, offers: List<OfferPeriod>, now: Boolean): CheaperPack? =
    offers.mapNotNull { other ->
        AMOUNT_PRICES.firstOrNull { today[it] != null && other.measures[it] != null }?.let { m -> CheaperPack(other, m, today[m]!!, now) to today[m]!! / other.measures[m]!! }
    }.filter { it.second > 1 + TOLERANCE }
        .maxWithOrNull(compareBy({ it.second }, { it.first.offer.validFrom }))?.first

/** The prices another pack is compared by: of a weight or a volume, never of a piece. */
private val AMOUNT_PRICES = listOf(Measure.PER_KG, Measure.PER_LITRE)

/** Rule 6: today's offer ([now]) in [measure] against the earlier offers of its pack, and the offers of its pack [running] now. */
private fun compare(facts: DealVerdict, now: Measures, measure: Measure, running: List<OfferPeriod>): DealVerdict {
    val value = now[measure]!!
    val compared = facts.earlier.mapNotNull { run -> run.measures[measure]?.let { run to it } }
    val measured = facts.copy(measure = measure, value = value, compared = compared.size)
    if (compared.size < MIN_EARLIER_OFFERS) return measured
    val lowest = compared.minBy { it.second }
    val cheaperThanNow = compared.count { (_, earlier) -> value > earlier * (1 + TOLERANCE) }
    val dearerThanNow = compared.count { (_, earlier) -> value < earlier * (1 - TOLERANCE) }
    val alongside = running.mapNotNull { run -> run.measures[measure]?.let { run to it } }
    // At the lowest seen of its pack: of the earlier offers and of those shown now, in this measure.
    val lowestOfAll = (compared + alongside).minOf { it.second }
    val verdict = when {
        value <= lowestOfAll * (1 + TOLERANCE) && dearerThanNow * 2 >= compared.size -> Verdict.REAL_DEAL
        cheaperThanNow * 2 > compared.size -> Verdict.WORSE_THAN_USUAL
        else -> Verdict.USUAL_DEAL
    }
    // The latest offer at least as cheap, of the earlier ones and those on offer now: "less than ever" must hold for both.
    val asCheap = (compared + alongside).filter { it.second <= value * (1 + TOLERANCE) }
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
