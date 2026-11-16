package dev.tobins.mealplanner.server.history

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.pricing.DealTerms
import dev.tobins.mealplanner.server.serverDay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What must hold for every history of one product, checked on histories made up from a small alphabet (fixed
 * seeds, so a failure names the seed and the case and can be run again), and on the reviewers' probes of PR #178
 * as fixed cases. Three review rounds each found a new case of one of these; this guards the properties:
 * 1. The verdict and every number in it stay the same when nights are dropped from the sightings, as long as each
 *    store-dated row of a price keeps at least one sighting: the parsers skip items without a word.
 * 2. Today's value is what [DealTerms.of] gives for today's offer as the store shows it today. The one exception is
 *    the share against a reported raise (rule 1): it is then measured against the price before the raise.
 * 3. No offer whose store dates include today or begin after it is an earlier offer, today's own offer is none,
 *    and no earlier offer is counted twice (two earlier offers of one pack and deal never share or run on dates).
 * 4. A verdict comparing with earlier offers (real, usual, worse than usual) rests on at least
 *    [MIN_EARLIER_OFFERS] of them, each ended before today. "Not a deal" and "always on offer" rest on no earlier
 *    offer by design (rule 1 needs two prices, rule 3 days on offer), so they are not held to this.
 * 5. With a price before today, a raise is reported exactly when a price before of the same pack, dated in the
 *    [REFERENCE_DAYS] before today's offer began, was lower by more than [TOLERANCE], whatever the deal then:
 *    "the same pack", as another pack's price is another product's; and not for a group offer ("Alle ..."),
 *    which the made-up histories never are.
 */
class VerdictPropertiesTest {
    private enum class Deal {
        ONE_PLUS_ONE, QUARTER_STATED, QUARTER, VOOR, VOOR_DEAR, TWO_FOR, PLAIN, UNKNOWN;

        /** The offer as the store shows it, at price before [before]. */
        fun offer(pack: String, before: Double, from: Int, until: Int): Offer {
            val (text, price, priceBefore) = when (this) {
                ONE_PLUS_ONE -> Triple("1+1 gratis", null, before)
                // AH states a price with some percentages, which follows the price before.
                QUARTER_STATED -> Triple("25% korting", Math.round(before * 75) / 100.0, before)
                QUARTER -> Triple("25% korting", null, before)
                VOOR -> Triple("VOOR 1.99", 1.99, before)
                // Next to nothing off: after a raise, no deal.
                VOOR_DEAR -> Triple("VOOR 2.99", 2.99, before)
                TWO_FOR -> Triple("2 voor 3,00", null, before)
                // Lidl: a price, no deal, no price before.
                PLAIN -> Triple(null, 1.79, null)
                // A deal of no known form: nothing to compare by.
                UNKNOWN -> Triple("2 stapelen tot 35%", null, before)
            }
            return Offer(Store.AH, "AH Kipfilet", text, price, priceBefore, pack, validFrom = day(from).toString(), validUntil = day(until).toString())
        }
    }

    /**
     * One store-dated offer: its pack and deal from night [from] to [until], with [prices] before, each from its
     * night on; seen on the nights of [seen] (every night it ran, unless it is listed before it starts).
     */
    private data class Dated(val pack: String, val deal: Deal, val from: Int, val until: Int, val prices: List<Pair<Int, Double>>, val seen: IntRange = from..until) {
        /** Each price before as an offer, with the nights it was shown. */
        fun segments(): List<Pair<Offer, IntRange>> = prices.mapIndexed { i, (start, before) ->
            val end = prices.getOrNull(i + 1)?.first?.minus(1) ?: until
            deal.offer(pack, before, from, until) to (if (seen.first < from) seen else maxOf(start, seen.first)..minOf(end, seen.last))
        }
    }

    private data class Case(val name: String, val today: Int, val offers: List<Dated>, val unread: Set<Int> = emptySet()) {
        val read = (0..today).filterNot { it in unread }

        /** Today's offer as the store shows it: the latest one dated over today, at its price before today. */
        val current: Offer = offers.filter { today in it.from..it.until }.maxBy { it.from }.segments().last { (_, nights) -> nights.first <= today }.first

        /** Each price's sightings on nights the store was read, up to today; prices never seen are not in the history. */
        val sightings: List<Pair<Offer, List<Int>>> = offers.flatMap { it.segments() }
            .map { (offer, nights) -> offer to nights.filter { it in read && it <= today } }.filter { it.second.isNotEmpty() }
    }

    private fun judged(case: Case, sightings: List<Pair<Offer, List<Int>>>): DealVerdict {
        val rows = sightings.flatMap { (offer, nights) -> runsOf(nights.sorted()).map { row(offer, it.first(), it.last()) } }
        return judge(CurrentOffer(case.current), rows, case.read.map(::night), emptyList(), day(case.today))
    }

    /** Nights in runs of consecutive ones: each run is a row of the history, as the recorder writes it. */
    private fun runsOf(nights: List<Int>): List<List<Int>> = nights.fold(mutableListOf<MutableList<Int>>()) { runs, n ->
        if (runs.lastOrNull()?.last() == n - 1) runs.last() += n else runs += mutableListOf(n)
        runs
    }

    private fun row(offer: Offer, from: Int, until: Int): OfferPeriod {
        val read = CurrentOffer(offer)
        return OfferPeriod(read.identity, offer.deal, read.measures, night(from), night(until), read.validFrom, read.validUntil)
    }

    /** What a verdict says, without the instants a row was first and last seen, which are not part of it. */
    private fun DealVerdict.said(): List<Any?> {
        fun OfferPeriod.said() = listOf(offerKey, validFrom, validUntil, measures, deal)
        return listOf(verdict, measure, value, typical, lowest?.said(), lastAsCheap?.said(), compared, earlier.map { it.said() }, daysRead, daysOnOffer, daysThisDeal, firstRead, raise, summary("AH"))
    }

    private fun check(case: Case, random: Random, where: String): DealVerdict {
        val verdict = judged(case, case.sightings)
        fun fails(what: String): Nothing = fail("$where, ${case.name}: $what\nverdict ${verdict.said()}\nhistory ${case.offers}\nunread ${case.unread.sorted()}")
        // 1. Nights dropped, each price keeping one.
        repeat(DROPS) {
            val fewer = case.sightings.map { (offer, nights) -> offer to nights.filter { random.nextBoolean() }.ifEmpty { listOf(nights.random(random)) } }
            val again = judged(case, fewer)
            if (again.said() != verdict.said()) fails("with nights dropped it says ${again.said()}\nsightings $fewer")
        }
        // 2. Today's value as the store shows it today.
        val terms = DealTerms.of(case.current)
        val expected = when (verdict.measure) {
            null -> null
            Measure.PER_KG -> terms.eurosPerKg
            Measure.PER_LITRE -> terms.eurosPerLitre
            Measure.PER_PIECE -> terms.eurosPerPiece
            Measure.PER_PACK -> terms.eurosPerPack
            Measure.PAID_SHARE -> verdict.raise?.let { (terms.eurosPerPack ?: terms.paidShare!! * it.now) / it.before } ?: terms.paidShare
        }
        if (expected != null && abs(expected - verdict.value!!) > 1e-9) fails("today's value is ${verdict.value}, the store shows $expected")
        // 3. Earlier offers ended, are not today's and are not counted twice.
        val today = CurrentOffer(case.current)
        for (earlier in verdict.earlier) {
            if (!earlier.validUntil.isBefore(day(case.today))) fails("$earlier is dated over today or later, yet an earlier offer")
            if (today.isItself(earlier)) fails("$earlier is today's own offer, yet an earlier offer")
        }
        for ((i, a) in verdict.earlier.withIndex()) for (b in verdict.earlier.drop(i + 1)) {
            if (a.offerKey == b.offerKey && a.touches(b.validFrom, b.validUntil)) fails("$a and $b are one offer counted twice")
        }
        // 4. A comparison rests on enough earlier offers.
        if (verdict.verdict in comparing && verdict.compared < MIN_EARLIER_OFFERS) fails("${verdict.verdict} on ${verdict.compared} earlier offers")
        // 5. A raise, exactly when a price before of the 30 days before was lower.
        case.current.originalPrice?.let { now ->
            val start = LocalDate.parse(case.current.validFrom)
            val seenBefore = case.sightings.map { it.first }.filter {
                it.packSize == case.current.packSize && it.originalPrice != null && it.validFrom < case.current.validFrom &&
                    !LocalDate.parse(it.validUntil).isBefore(start.minusDays(REFERENCE_DAYS))
            }.mapNotNull { it.originalPrice }
            val lowest = seenBefore.minOrNull()
            val raised = lowest != null && now > lowest * (1 + TOLERANCE)
            if (raised != (verdict.raise != null)) fails("prices before in the 30 days before: $seenBefore, today $now; raise ${verdict.raise}")
            if (raised && (verdict.raise!!.before != lowest || verdict.raise!!.now != now)) fails("raise ${verdict.raise}, expected from $lowest to $now")
        }
        return verdict
    }

    /**
     * A history of six to thirteen weeks (some too short for a verdict): offers one after another that share days,
     * run on or lie apart, each with probability [often], of one to three deals (one deal often enough is always on
     * offer), a price before that may move within an offer and between offers, and a tenth of the nights unread.
     */
    private fun madeUp(seed: Long): Case {
        val random = Random(seed)
        val today = 40 + random.nextInt(50)
        val deals = Deal.entries.shuffled(random).take(1 + random.nextInt(3))
        val often = listOf(0.4, 0.8, 1.0).random(random)
        fun dated(from: Int, until: Int, seen: IntRange = from..until): Dated {
            val prices = listOf(from to BEFORE.random(random)) +
                (if (random.nextInt(3) == 0 && until > from) listOf(from + 1 + random.nextInt(until - from) to BEFORE.random(random)) else emptyList())
            return Dated(PACKS.random(random), deals.random(random), from, until, prices, seen)
        }
        val offers = mutableListOf<Dated>()
        var from = random.nextInt(4)
        while (from <= today) {
            val until = from + 2 + random.nextInt(8)
            if (random.nextDouble() < often) offers += dated(from, until)
            // Share a day or two, run on, or lie a few days apart.
            from = until + 1 + listOf(-2, -1, 0, 0, 0, 2, 4).random(random)
        }
        if (offers.none { today in it.from..it.until }) offers += (today - random.nextInt(5)).let { dated(it, it + 6) }
        // Jumbo lists next week's offer this week.
        if (random.nextInt(3) == 0) offers += dated(today + 1, today + 7, seen = today - 2..today)
        val unread = (0..today).filter { random.nextInt(10) == 0 }.toSet() - today
        return Case("made up from seed $seed", today, offers, unread)
    }

    @Test
    fun everyMadeUpHistoryKeepsTheProperties() {
        val verdicts = (1L..CASES).map { seed -> check(madeUp(seed), Random(seed * 31), "seed $seed").verdict }
        println("Verdicts on $CASES made-up histories: " + verdicts.groupingBy { it }.eachCount())
        // The cases reach every verdict, not only "not enough history".
        assertEquals(Verdict.entries.toSet(), verdicts.toSet())
    }

    private fun probe(name: String, vararg offers: Dated, today: Int = 70) = check(Case(name, today, offers.toList()), Random(7), "probe $name")

    /** "1+1 gratis" of 1 kg, at price before [before]. */
    private fun onePlusOne(from: Int, until: Int, vararg prices: Pair<Int, Double>) = Dated("1 kg", Deal.ONE_PLUS_ONE, from, until, prices.toList())

    @Test
    fun probeAAPriceBeforeMovedWithinTodaysDatesIsNoCheaperDeal() {
        // Two earlier offers at 3,19; today's at 2,99 for three days, 3,19 since: €1,60 a kg is paid today, as before.
        val verdict = probe("A", onePlusOne(14, 20, 14 to 3.19), onePlusOne(42, 48, 42 to 3.19), onePlusOne(67, 73, 67 to 2.99, 70 to 3.19))
        assertEquals(Verdict.USUAL_DEAL, verdict.verdict)
        assertEquals(1.595, verdict.value!!, 1e-9)
        assertEquals(null, verdict.raise)
    }

    @Test
    fun probeA2LastWeeksLowerPriceBeforeRunningOnIsARaiseNotACheaperDeal() {
        val verdict = probe("A2", onePlusOne(14, 20, 14 to 3.19), onePlusOne(42, 48, 42 to 3.19), onePlusOne(60, 66, 60 to 2.99), onePlusOne(67, 73, 67 to 3.19))
        assertEquals(Verdict.USUAL_DEAL, verdict.verdict)
        assertEquals(1.595, verdict.value!!, 1e-9)
        assertEquals(RegularRaise(2.99, day(66), 3.19), verdict.raise)
    }

    @Test
    fun probeBTheSameDealOnADoubledPriceBeforeIsNoDealThoughTheWeeksRunOn() {
        val verdict = probe("B", onePlusOne(60, 66, 60 to 1.59), onePlusOne(67, 73, 67 to 3.19))
        assertEquals(Verdict.NOT_A_DEAL, verdict.verdict)
        assertEquals(RegularRaise(1.59, day(66), 3.19), verdict.raise)
        // The same when last week had another deal, or lay a day apart.
        assertEquals(Verdict.NOT_A_DEAL, probe("B, another deal", Dated("1 kg", Deal.QUARTER_STATED, 60, 66, listOf(60 to 1.59)), onePlusOne(67, 73, 67 to 3.19)).verdict)
        assertEquals(Verdict.NOT_A_DEAL, probe("B, apart", onePlusOne(59, 65, 59 to 1.59), onePlusOne(67, 73, 67 to 3.19)).verdict)
    }

    @Test
    fun probeCARaiseBetweenWeeksOfTheSameDealIsReported() {
        val verdict = probe("C", Dated("1 kg", Deal.TWO_FOR, 60, 66, listOf(60 to 1.69)), Dated("1 kg", Deal.TWO_FOR, 67, 73, listOf(67 to 1.99)))
        assertEquals(RegularRaise(1.69, day(66), 1.99), verdict.raise)
        assertEquals(Verdict.NOT_ENOUGH_HISTORY, verdict.verdict)
        assertTrue(verdict.summary("AH").endsWith("Its regular price went up from €1.69 (seen 19 Nov 2026) to €1.99; its share is measured against €1.69."), verdict.summary("AH"))
    }

    /**
     * The reviewer's "worth knowing": earlier offers each 2,24 (before 2,99) from Monday and 2,39 (before 3,19)
     * from Thursday, today's the same on a Thursday. Earlier offers count at their lowest, today's at today's
     * price, and the earlier weeks' 2,99 is a regular price of the 30 days before: worse than usual, with the
     * raise said. On the Monday, at 2,24 against 2,99, it is the usual deal.
     */
    @Test
    fun probeHAPriceThatMovesEveryWeekIsJudgedAtTodaysPrice() {
        fun week(from: Int) = Dated("1 kg", Deal.QUARTER_STATED, from, from + 6, listOf(from to 2.99, from + 3 to 3.19))
        val thursday = probe("H, Thursday", week(14), week(42), week(67))
        assertEquals(Verdict.WORSE_THAN_USUAL, thursday.verdict)
        assertEquals(2.39, thursday.value!!, 1e-9)
        assertEquals(RegularRaise(2.99, day(48), 3.19), thursday.raise)
        val monday = probe("H, Monday", week(14), week(42), week(70))
        assertEquals(Verdict.USUAL_DEAL, monday.verdict)
        assertEquals(2.24, monday.value!!, 1e-9)
    }

    private companion object {
        const val CASES = 400L
        const val DROPS = 3
        val PACKS = listOf("1 kg", "500 g")
        val BEFORE = listOf(2.49, 2.99, 3.19)
        val comparing = setOf(Verdict.REAL_DEAL, Verdict.USUAL_DEAL, Verdict.WORSE_THAN_USUAL)

        fun night(n: Int): Instant = Instant.parse("2026-09-14T03:00:00Z").plus(Duration.ofDays(n.toLong()))

        fun day(n: Int) = serverDay(night(n))
    }
}
