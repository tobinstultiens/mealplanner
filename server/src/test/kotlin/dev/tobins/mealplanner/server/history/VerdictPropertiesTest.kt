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
 * as fixed cases. Four review rounds each found a new case of one of these; this guards the properties:
 * 1. The verdict and every number in it stay the same when nights are dropped from the sightings, as long as each
 *    store-dated row of a price keeps at least one sighting: the parsers skip items without a word.
 * 2. Today's value is what [DealTerms.of] gives for today's offer as the store shows it today. The one exception is
 *    the share against a reported raise (rule 1): it is then measured against the price before the raise.
 * 3. No offer whose store dates include today or begin after it is an earlier offer, today's own offer is none,
 *    and no earlier offer is counted twice (two earlier offers of one pack and deal never share or run on dates).
 *    The offers alongside are exactly the other offers dated over today.
 * 4. A verdict comparing with earlier offers (real, usual, worse than usual) rests on at least
 *    [MIN_EARLIER_OFFERS] of them, each ended before today. "Not a deal", "cheaper another way" and "always on
 *    offer" rest on no earlier offer by design (rule 1 needs two prices, rule 2 an offer running now, rule 4 days
 *    on offer), so they are not held to this.
 * 5. With a price before today, a raise is reported exactly when a price before of the same pack (in the history,
 *    or looked up), dated in the [REFERENCE_DAYS] before today's offer began, was lower by more than [TOLERANCE],
 *    whatever the deal then: "the same pack", as another pack's price is another product's; and not for a group
 *    offer ("Alle ..."), which the made-up histories never are.
 * 6. A real deal is never clearly dearer, in its measure, than any other offer of the product dated up to today,
 *    ended or running; and "less than ever" means clearly less than every one of them.
 * 7. A real, usual or worse-than-usual verdict never passes over an offer running now that is clearly cheaper in
 *    its measure; "cheaper another way" names one that is.
 * 8. "Always on offer" only when offers of today's pack and deal were dated over at least [ALWAYS_SHARE] of the days
 *    read.
 * 9. The summary says "no earlier offer of it ended" only when none did, names every other offer running now, speaks
 *    of a share against a raise only when it compares by the share, and the price before a raise was seen before
 *    today's offer began.
 */
class VerdictPropertiesTest {
    /** A deal as the store words it: its [text], a [price] it names or a share of the price before it states ([stated]). */
    private data class Deal(val text: String?, val price: Double? = null, val stated: Double? = null, val hasBefore: Boolean = true) {
        fun offer(pack: String, before: Double, from: Int, until: Int) = Offer(
            Store.AH, "AH Kipfilet", text, price ?: stated?.let { Math.round(before * it * 100) / 100.0 }, before.takeIf { hasBefore }, pack,
            validFrom = day(from).toString(), validUntil = day(until).toString(),
        )

        override fun toString() = text ?: "price $price"
    }

    /**
     * One store-dated offer: its pack and deal from night [from] to [until], with [prices] before, each from its
     * night on; seen on every night it ran, or on the nights of [seen] only (a long-dated offer seen now and then,
     * next week's listed early).
     */
    private data class Dated(val pack: String, val deal: Deal, val from: Int, val until: Int, val prices: List<Pair<Int, Double>>, val seen: List<Int>? = null) {
        /** Each price before as an offer, with the nights it was shown. */
        fun segments(): List<Pair<Offer, List<Int>>> = prices.mapIndexed { i, (start, before) ->
            val end = prices.getOrNull(i + 1)?.first?.minus(1) ?: until
            deal.offer(pack, before, from, until) to (seen?.filter { prices.size == 1 || it in start..end } ?: (start..end).toList())
        }
    }

    private data class Case(val name: String, val today: Int, val offers: List<Dated>, val unread: Set<Int> = emptySet(), val lookups: List<Pair<IntRange, Double>> = emptyList()) {
        val read = (0..today).filterNot { it in unread }

        /** Today's offer as the store shows it: the latest one dated over today, at its price before today. */
        val current: Offer = offers.filter { today in it.from..it.until }.maxBy { it.from }.let { dated ->
            dated.deal.offer(dated.pack, dated.prices.last { it.first <= today }.second, dated.from, dated.until)
        }

        /** Each price's sightings on nights the store was read, up to today; prices never seen are not in the history. */
        val sightings: List<Pair<Offer, List<Int>>> = offers.flatMap { it.segments() }
            .map { (offer, nights) -> offer to nights.filter { it in read && it in 0..today } }.filter { it.second.isNotEmpty() }

        /** The regular prices looked up for today's pack (`regularPriceHistory`): from and until which night, and the price. */
        val regular = lookups.map { (nights, price) -> RegularPrice(price, night(nights.first), night(nights.last)) }
    }

    private fun judged(case: Case, sightings: List<Pair<Offer, List<Int>>>): DealVerdict {
        val rows = sightings.flatMap { (offer, nights) -> runsOf(nights.sorted()).map { row(offer, it.first(), it.last()) } }
        return judge(CurrentOffer(case.current), rows, case.read.map(::night), case.regular, day(case.today))
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
        return listOf(
            verdict, measure, value, typical, lowest?.said(), lastAsCheap?.said(), compared, earlier.map { it.said() }, alongside.map { it.said() },
            cheaperNow?.said(), daysRead, daysOnOffer, daysThisDeal, firstRead, raise, summary("AH"),
        )
    }

    /** [offer]'s value in [measure] as the history reads it; a price per pack only of [pack]. */
    private fun valueOf(offer: Offer, measure: Measure, pack: String): Double? =
        CurrentOffer(offer).takeIf { measure != Measure.PER_PACK || it.identity.product.pack == pack }?.measures?.get(measure)

    private fun check(case: Case, random: Random, where: String): DealVerdict {
        val verdict = judged(case, case.sightings)
        fun fails(what: String): Nothing =
            fail("$where, ${case.name}: $what\nverdict ${verdict.said()}\nhistory ${case.offers}\nunread ${case.unread.sorted()}\nlookups ${case.lookups}")
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
        // 3. Earlier offers ended, are not today's and are not counted twice; the offers alongside are dated over today.
        val today = CurrentOffer(case.current)
        val day = day(case.today)
        for (earlier in verdict.earlier) {
            if (!earlier.validUntil.isBefore(day)) fails("$earlier is dated over today or later, yet an earlier offer")
            if (today.isItself(earlier)) fails("$earlier is today's own offer, yet an earlier offer")
        }
        for ((i, a) in verdict.earlier.withIndex()) for (b in verdict.earlier.drop(i + 1)) {
            if (a.offerKey == b.offerKey && a.touches(b.validFrom, b.validUntil)) fails("$a and $b are one offer counted twice")
        }
        /** The other offers of the product as the store showed them, each price apart: not today's own, dated by today. */
        val others = case.sightings.map { it.first }.filter { !today.isItself(row(it, 0, 0)) && !LocalDate.parse(it.validFrom).isAfter(day) }
        val running = others.filter { !LocalDate.parse(it.validUntil).isBefore(day) }
        for (offer in running) {
            if (verdict.alongside.none { it.offerKey == CurrentOffer(offer).offerKey && it.dated(day) }) fails("$offer runs today, yet is not among the offers alongside")
        }
        for (offer in verdict.alongside) if (!offer.dated(day) || today.isItself(offer)) fails("$offer is no other offer running today, yet alongside")
        // 4. A comparison rests on enough earlier offers.
        if (verdict.verdict in comparing && verdict.compared < MIN_EARLIER_OFFERS) fails("${verdict.verdict} on ${verdict.compared} earlier offers")
        // 5. A raise, exactly when a price before of the 30 days before was lower.
        case.current.originalPrice?.let { now ->
            val start = LocalDate.parse(case.current.validFrom)
            val fromHistory = case.sightings.map { it.first }.filter {
                it.packSize == case.current.packSize && it.originalPrice != null && LocalDate.parse(it.validFrom) < start &&
                    !LocalDate.parse(it.validUntil).isBefore(start.minusDays(REFERENCE_DAYS))
            }.mapNotNull { it.originalPrice }
            val looked = case.lookups.filter { (nights, _) -> day(nights.first) < start && !day(nights.last).isBefore(start.minusDays(REFERENCE_DAYS)) }.map { it.second }
            val seenBefore = fromHistory + looked
            val lowest = seenBefore.minOrNull()
            val raised = lowest != null && now > lowest * (1 + TOLERANCE)
            if (raised != (verdict.raise != null)) fails("prices before in the 30 days before: $seenBefore, today $now; raise ${verdict.raise}")
            if (raised && (verdict.raise!!.before != lowest || verdict.raise!!.now != now)) fails("raise ${verdict.raise}, expected from $lowest to $now")
        }
        val pack = today.identity.product.pack
        // 6. A real deal is no dearer than any other offer, and "less than ever" is less than every one.
        if (verdict.verdict == Verdict.REAL_DEAL) {
            val m = verdict.measure!!
            for (offer in others) valueOf(offer, m, pack)?.let { other ->
                if (verdict.value!! > other * (1 + TOLERANCE)) fails("a real deal at ${verdict.value}, where $offer gave $other")
                if (verdict.lastAsCheap == null && verdict.value!! * (1 + TOLERANCE) >= other) fails("less than ever at ${verdict.value}, where $offer gave $other")
            }
        }
        // 7. No comparing verdict passes over a cheaper offer running now; "cheaper another way" names one.
        if (verdict.verdict in comparing) for (offer in running) valueOf(offer, verdict.measure!!, pack)?.let { other ->
            if (verdict.value!! > other * (1 + TOLERANCE)) fails("${verdict.verdict} at ${verdict.value}, where $offer runs today at $other")
        }
        if (verdict.verdict == Verdict.CHEAPER_ANOTHER_WAY) {
            val cheaper = verdict.cheaperNow!!
            if (!cheaper.dated(day) || today.isItself(cheaper) || verdict.value!! <= cheaper.measures[verdict.measure!!]!! * (1 + TOLERANCE)) fails("$cheaper is not cheaper now")
        }
        // 8. Always on offer, by today's pack and deal.
        if (verdict.verdict == Verdict.ALWAYS_ON_OFFER) {
            val same = case.sightings.map { it.first }.filter { CurrentOffer(it).offerKey == today.offerKey }
            val dated = case.read.count { n -> same.any { day(n) in LocalDate.parse(it.validFrom)..LocalDate.parse(it.validUntil) } }
            if (dated < ALWAYS_SHARE * case.read.size) fails("always on offer, but today's pack and deal were dated on $dated of ${case.read.size} days")
        }
        // 9. The summary is true about earlier offers and names those running now.
        val summary = verdict.summary("AH")
        if ("no earlier offer of it ended" in summary && verdict.earlier.isNotEmpty()) fails("says no earlier offer ended")
        for (offer in verdict.alongside) if ("dated until ${dateText(offer.validUntil)}" !in summary) fails("does not name $offer, running now: $summary")
        if ("its share is measured against" in summary && verdict.measure != Measure.PAID_SHARE) fails("speaks of a share it does not compare by: $summary")
        verdict.raise?.let { if (it.beforeSeen >= LocalDate.parse(case.current.validFrom)) fails("the price before the raise was seen on ${it.beforeSeen}, not before the offer") }
        return verdict
    }

    /**
     * A history of six to thirteen weeks (some too short for a verdict): offers one after another that share days,
     * run on or lie apart, each with probability [often], of one to three deals, a price before that may move within
     * an offer and between offers, and a tenth of the nights unread. Sometimes too: offers dated from before the
     * first read, offers dated over months and seen now and then, another deal running alongside for weeks (of the
     * same pack or another), next week's offer listed early, and regular prices looked up.
     */
    private fun madeUp(seed: Long): Case {
        val random = Random(seed)
        val today = 40 + random.nextInt(50)
        val deals = DEALS.shuffled(random).take(1 + random.nextInt(3))
        val often = listOf(0.4, 0.8, 1.0).random(random)
        fun dated(from: Int, until: Int, deal: Deal = deals.random(random), pack: String = PACKS.random(random), seen: List<Int>? = null): Dated {
            val prices = listOf(from to BEFORE.random(random)) +
                (if (seen == null && random.nextInt(3) == 0 && until > from) listOf(from + 1 + random.nextInt(until - from) to BEFORE.random(random)) else emptyList())
            return Dated(pack, deal, from, until, prices, seen)
        }
        val offers = mutableListOf<Dated>()
        var from = -random.nextInt(8)
        while (from <= today) {
            val until = from + 2 + random.nextInt(8)
            if (random.nextDouble() < often) offers += dated(from, until)
            // Share a day or two, run on, or lie a few days apart.
            from = until + 1 + listOf(-2, -1, 0, 0, 0, 2, 4).random(random)
        }
        if (offers.none { today in it.from..it.until }) offers += (today - random.nextInt(5)).let { dated(it, it + 6) }
        // Dated over months, from before the first read, seen on a few nights; still running or ended.
        repeat(if (random.nextInt(3) == 0) 1 + random.nextInt(2) else 0) {
            val start = -random.nextInt(200)
            val end = if (random.nextBoolean()) today + random.nextInt(60) else today - 10 - random.nextInt(20)
            val nights = (0..minOf(end, today)).shuffled(random).take(1 + random.nextInt(4)).sorted()
            offers += dated(start, end, deal = DEALS.random(random), seen = nights)
        }
        // Another deal alongside for weeks, of the same pack or another.
        if (random.nextInt(3) == 0) {
            val start = random.nextInt(today)
            offers += dated(start, start + 14 + random.nextInt(30), deal = DEALS.random(random))
        }
        // Jumbo lists next week's offer this week.
        if (random.nextInt(3) == 0) offers += dated(today + 1, today + 7, seen = listOf(today - 2, today - 1, today))
        val unread = (0..today).filter { random.nextInt(10) == 0 }.toSet() - today
        val lookups = if (random.nextBoolean()) List(1 + random.nextInt(3)) { (today - random.nextInt(70)).let { at -> at..minOf(today, at + random.nextInt(10)) } to BEFORE.random(random) } else emptyList()
        return Case("made up from seed $seed", today, offers, unread, lookups)
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
    private fun onePlusOne(from: Int, until: Int, vararg prices: Pair<Int, Double>) = Dated("1 kg", ONE_PLUS_ONE, from, until, prices.toList())

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
        assertEquals(Verdict.NOT_A_DEAL, probe("B, another deal", Dated("1 kg", QUARTER_STATED, 60, 66, listOf(60 to 1.59)), onePlusOne(67, 73, 67 to 3.19)).verdict)
        assertEquals(Verdict.NOT_A_DEAL, probe("B, apart", onePlusOne(59, 65, 59 to 1.59), onePlusOne(67, 73, 67 to 3.19)).verdict)
    }

    @Test
    fun probeCARaiseBetweenWeeksOfTheSameDealIsReported() {
        val verdict = probe("C", Dated("1 kg", TWO_FOR, 60, 66, listOf(60 to 1.69)), Dated("1 kg", TWO_FOR, 67, 73, listOf(67 to 1.99)))
        assertEquals(RegularRaise(1.69, day(66), 1.99), verdict.raise)
        assertEquals(Verdict.NOT_ENOUGH_HISTORY, verdict.verdict)
        assertTrue(verdict.summary("AH").endsWith("Its regular price went up from €1.69 (seen 19 Nov 2026) to €1.99."), verdict.summary("AH"))
    }

    /**
     * The reviewer's "worth knowing": earlier offers each 2,24 (before 2,99) from Monday and 2,39 (before 3,19)
     * from Thursday, today's the same on a Thursday. Earlier offers count at their lowest, today's at today's
     * price, and the earlier weeks' 2,99 is a regular price of the 30 days before: worse than usual, with the
     * raise said. On the Monday, at 2,24 against 2,99, it is the usual deal.
     */
    @Test
    fun probeHAPriceThatMovesEveryWeekIsJudgedAtTodaysPrice() {
        fun week(from: Int) = Dated("1 kg", QUARTER_STATED, from, from + 6, listOf(from to 2.99, from + 3 to 3.19))
        val thursday = probe("H, Thursday", week(14), week(42), week(67))
        assertEquals(Verdict.WORSE_THAN_USUAL, thursday.verdict)
        assertEquals(2.39, thursday.value!!, 1e-9)
        assertEquals(RegularRaise(2.99, day(48), 3.19), thursday.raise)
        val monday = probe("H, Monday", week(14), week(42), week(70))
        assertEquals(Verdict.USUAL_DEAL, monday.verdict)
        assertEquals(2.24, monday.value!!, 1e-9)
    }

    /** Round 4: "2 voor 6,00" (€1,50 a piece) dated all year and still running; "voor 3,19" today is no real deal. */
    @Test
    fun probeL3ACheaperOfferStillRunningIsNoRealDeal() {
        val pieces = "Schaal à 2 stuks"
        val verdict = probe(
            "L3, hamburgers",
            Dated(pieces, Deal("2 voor 6,00", hasBefore = false), -300, 85, listOf(-300 to 1.0), seen = listOf(0, 40, 70)),
            Dated(pieces, Deal("voor 3,49", price = 3.49, hasBefore = false), 14, 20, listOf(14 to 1.0)),
            Dated(pieces, Deal("voor 3,49", price = 3.49, hasBefore = false), 42, 48, listOf(42 to 1.0)),
            Dated(pieces, Deal("voor 3,19", price = 3.19, hasBefore = false), 67, 73, listOf(67 to 1.0)),
        )
        assertEquals(Verdict.CHEAPER_ANOTHER_WAY to Measure.PER_PIECE, verdict.verdict to verdict.measure)
        assertEquals(
            "Cheaper another way right now: €1.60 per piece, where \"2 voor 6,00\" for schaal à 2 stuks, dated until 8 Dec 2026 gives €1.50 per piece.",
            verdict.summary("Jumbo"),
        )
        // The same pack: "2 voor 3,00" of the kilo all year, today 25 % off 2,49.
        val kilo = probe(
            "L3, same pack", Dated("1 kg", TWO_FOR, -300, 85, listOf(-300 to 1.99), seen = (0..70).toList()),
            onePlusOne(14, 20, 14 to 3.99), onePlusOne(42, 48, 42 to 3.99), Dated("1 kg", QUARTER, 67, 73, listOf(67 to 2.49)),
        )
        assertEquals(Verdict.CHEAPER_ANOTHER_WAY, kilo.verdict)
    }

    /** Round 4: the 500 g pack on "1+1 gratis" all year does not make a week of it on the kilo pack "always on offer". */
    @Test
    fun probeL2AnotherPacksDaysAreNotTodaysDays() {
        val verdict = probe("L2", Dated("500 g", ONE_PLUS_ONE, -300, 85, listOf(-300 to 3.00), seen = (0..70).toList()), onePlusOne(67, 73, 67 to 4.00))
        assertEquals(Verdict.NOT_ENOUGH_HISTORY to 4, verdict.verdict to verdict.daysThisDeal)
        assertTrue("Also on offer now: \"1+1 gratis\" for 500 g, dated until 8 Dec 2026." in verdict.summary("AH"), verdict.summary("AH"))
    }

    /** Round 4: a deeper deal on top of a long-dated ordinary one says the ordinary one runs, not "not on offer before". */
    @Test
    fun probeL1TheSummaryNamesTheOfferRunningAlongside() {
        val pack = "260 gram"
        val verdict = probe(
            "L1", Dated(pack, Deal("2 voor 5,50", hasBefore = false), -300, 85, listOf(-300 to 1.0), seen = listOf(0, 68, 69)),
            Dated(pack, Deal("2 voor 4,00", hasBefore = false), 64, 77, listOf(64 to 1.0)),
        )
        assertEquals(Verdict.NOT_ENOUGH_HISTORY, verdict.verdict)
        assertEquals(
            "Not enough history yet: no earlier offer of it ended since 14 Sep 2026; a verdict needs 2 earlier offers to compare with. " +
                "Also on offer now: \"2 voor 5,50\" for 260 gram, dated until 8 Dec 2026.",
            verdict.summary("Jumbo"),
        )
    }

    private companion object {
        const val CASES = 600L
        const val DROPS = 3
        val PACKS = listOf("1 kg", "500 g")
        val BEFORE = listOf(2.49, 2.99, 3.19)
        val ONE_PLUS_ONE = Deal("1+1 gratis")

        // AH states a price with some percentages, which follows the price before.
        val QUARTER_STATED = Deal("25% korting", stated = 0.75)
        val QUARTER = Deal("25% korting")
        val TWO_FOR = Deal("2 voor 3,00")
        val DEALS = listOf(
            ONE_PLUS_ONE, QUARTER_STATED, QUARTER, TWO_FOR, Deal("VOOR 1.99", price = 1.99),
            // Next to nothing off: after a raise, no deal.
            Deal("VOOR 2.99", price = 2.99),
            // Lidl: a price, no deal, no price before.
            Deal(null, price = 1.79, hasBefore = false),
            // A deal of no known form: nothing to compare by.
            Deal("2 stapelen tot 35%"),
        )
        val comparing = setOf(Verdict.REAL_DEAL, Verdict.USUAL_DEAL, Verdict.WORSE_THAN_USUAL)

        fun night(n: Int): Instant = Instant.parse("2026-09-14T03:00:00Z").plus(Duration.ofDays(n.toLong()))

        fun day(n: Int) = serverDay(night(n))

        fun dateText(day: LocalDate) = day.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH))
    }
}
