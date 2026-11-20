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
 * and issue 197 as fixed cases. Each pack is judged against itself, and "now" is what the store shows today (its
 * page: the offers seen on the last night, which every case reads):
 * 1. The verdict and every number in it stay the same when nights are dropped from the sightings, as long as each
 *    store-dated row of a price keeps at least one sighting: the parsers skip items without a word. Today's page is
 *    the store's, and stays.
 * 2. Today's value is what [DealTerms.of] gives for today's offer as the store shows it today. The one exception is
 *    the share against a reported raise (rule 1): it is then measured against the price before the raise.
 * 3. The earlier offers are exactly the other offers of today's pack that ended: by their store dates, or early (dated
 *    over today, but not on today's page). Today's own offer is none, and none is counted twice. The offers alongside
 *    are exactly the other offers, of any pack, dated over today and on today's page, valued as the page shows them.
 * 4. A verdict comparing with earlier offers (real, usual, worse than usual) rests on at least
 *    [MIN_EARLIER_OFFERS] of them, each ended. "Not a deal", "cheaper another way" and "always on offer" rest on no
 *    earlier offer by design (rule 1 needs two prices, rule 2 an offer shown now, rule 4 days on offer), so they are
 *    not held to this.
 * 5. With a price before today, a raise is reported exactly when a price before of the same pack (in the history,
 *    or looked up), dated in the [REFERENCE_DAYS] before today's offer began, was lower by more than [TOLERANCE],
 *    whatever the deal then: "the same pack", as another pack's price is another product's; and not for a group
 *    offer ("Alle ..."), which the made-up histories never are.
 * 6. A real deal is never clearly dearer, in its measure, than any other offer of its pack dated up to today, ended
 *    or shown today; and "less than ever" means clearly less than every one of them.
 * 7. No verdict but "not a deal" passes over an offer of today's pack shown today that is clearly cheaper in what is
 *    paid: by the first price both have (per kg, litre, piece or pack), else by the share; "cheaper another way" names
 *    one that is, of today's pack and shown today, by that measure. Worked out here from [DealTerms.of] of the two
 *    offers, not from the measure the verdict compares earlier offers by.
 * 8. "Always on offer" only when offers of today's pack and deal were dated over at least [ALWAYS_SHARE] of the days
 *    read.
 * 9. The summary says "no earlier offer ... ended" only when none did, names every other offer shown now and no
 *    other as on offer now, names an offer without deal text by its price, speaks of a share against a raise only
 *    when it compares by the share, and the price before a raise was seen before today's offer began.
 * 10. Another pack is said to be cheaper ([DealVerdict.cheaperPack]) exactly when an offer of another pack, shown today
 *    or ended, is clearly cheaper per kg or per litre (the first both have), never per piece (the owner: two products
 *    under one title, a pan in two sizes, are no cheaper by the piece); one shown today when any of those is, and then
 *    no other of them clearly cheaper in that measure.
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
     * next week's listed early, an offer the store stopped showing).
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

        /** Each price's sightings on nights the store was read, up to today; prices never seen are not in the history. */
        val sightings: List<Pair<Offer, List<Int>>> = offers.flatMap { it.segments() }
            .map { (offer, nights) -> offer to nights.filter { it in read && it in 0..today } }.filter { it.second.isNotEmpty() }

        /** Today's page: the offers the store shows on the last night (always read). */
        val page: List<Offer> = sightings.filter { today in it.second }.map { it.first }

        /** Today's offer as the store shows it: the latest one on today's page dated over today, at its price before today. */
        val current: Offer = offers.filter { d -> today in d.from..d.until && d.segments().any { today in it.second } }.maxBy { it.from }.let { dated ->
            dated.deal.offer(dated.pack, dated.prices.last { it.first <= today }.second, dated.from, dated.until)
        }

        /** The regular prices looked up for today's pack (`regularPriceHistory`): from and until which night, and the price. */
        val regular = lookups.map { (nights, price) -> RegularPrice(price, night(nights.first), night(nights.last)) }
    }

    private fun judged(case: Case, sightings: List<Pair<Offer, List<Int>>>): DealVerdict {
        val rows = sightings.flatMap { (offer, nights) -> runsOf(nights.sorted()).map { row(offer, it.first(), it.last()) } }
        return judge(CurrentOffer(case.current), rows, case.read.map(::night), case.regular, day(case.today), case.page.map(::CurrentOffer))
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
            cheaperNow?.said(), daysRead, daysOnOffer, daysThisDeal, firstRead, raise, otherPacks,
            cheaperPack?.let { listOf(it.offer.said(), it.measure, it.value, it.now) }, summary("AH"),
        )
    }

    /**
     * Another offer of the product, worked out here and not by [joinOffers]: its [lowest] price, as an ended offer is
     * valued, over all its store dates, and [shown], as today's page shows it, or null when the page does not.
     */
    private data class Other(val lowest: Offer, val shown: Offer?) {
        val key = CurrentOffer(lowest).offerKey
        val pack = key.first
        val from: LocalDate = LocalDate.parse(lowest.validFrom)
        val until: LocalDate = LocalDate.parse(lowest.validUntil)
    }

    /**
     * The offers of [case]: prices of one pack and deal (its text, or its price without one) whose store dates share a
     * day or run on are one offer, at the price with the lowest price before (then the lowest price), over all their
     * dates. For a price this is its lowest; for a share it is the share against the lowest price before, which is how a
     * reduction is measured: "VOOR 1.99" against 3,19 on Thursday is no deeper deal than against 2,49 on Monday. As
     * shown today, it is the cheapest of its prices on today's page.
     */
    private fun offersOf(case: Case): List<Other> {
        val cheapestFirst = compareBy<Offer>({ it.originalPrice ?: Double.MAX_VALUE }, { it.price ?: Double.MAX_VALUE })
        return case.sightings.map { it.first }.groupBy { CurrentOffer(it).offerKey }.values.flatMap { same ->
            same.sortedBy { it.validFrom }.fold(mutableListOf<MutableList<Offer>>()) { offers, next ->
                val last = offers.lastOrNull()
                if (last != null && LocalDate.parse(next.validFrom) <= LocalDate.parse(last.maxOf { it.validUntil }).plusDays(1)) last += next else offers += mutableListOf(next)
                offers
            }.map { parts ->
                Other(
                    parts.minWith(cheapestFirst).copy(validFrom = parts.minOf { it.validFrom }, validUntil = parts.maxOf { it.validUntil }),
                    parts.filter { it in case.page }.minWithOrNull(cheapestFirst),
                )
            }
        }
    }

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
        // 3. Earlier offers: today's pack, ended by their dates or early; the offers alongside: shown today, as shown.
        val today = CurrentOffer(case.current)
        val pack = today.identity.product.pack
        val day = day(case.today)
        /** The other offers of the product: not today's own, dated by today. */
        val others = offersOf(case).filter { !today.isItself(row(it.lowest, 0, 0)) && !it.from.isAfter(day) }
        val running = others.filter { !it.until.isBefore(day) && it.shown != null }
        val ended = others.filter { it.until.isBefore(day) || it.shown == null }
        fun OfferPeriod.isOf(other: Other) = offerKey == other.key && touches(other.from, other.until)
        for (earlier in verdict.earlier) {
            if (today.isItself(earlier)) fails("$earlier is today's own offer, yet an earlier offer")
            if (earlier.identity.product.pack != pack) fails("$earlier is of another pack, yet an earlier offer")
            if (ended.none { earlier.isOf(it) }) fails("$earlier has not ended, yet is an earlier offer")
        }
        for (offer in ended.filter { it.pack == pack }) if (verdict.earlier.none { it.isOf(offer) }) fails("${offer.lowest} ended, yet is not among the earlier offers")
        for ((i, a) in verdict.earlier.withIndex()) for (b in verdict.earlier.drop(i + 1)) {
            if (a.offerKey == b.offerKey && a.touches(b.validFrom, b.validUntil)) fails("$a and $b are one offer counted twice")
        }
        for (offer in running) {
            val shown = CurrentOffer(offer.shown!!).measures
            if (verdict.alongside.none { it.isOf(offer) && it.measures == shown }) fails("${offer.shown} is shown today, yet not among the offers alongside as shown")
        }
        for (offer in verdict.alongside) if (running.none { offer.isOf(it) }) fails("$offer is not shown today, yet alongside")
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
        /** The offers of today's pack as the rules value them: ended ones at their lowest, those shown today as shown. */
        val samePack = ended.filter { it.pack == pack }.map { it.lowest } + running.filter { it.pack == pack }.map { it.shown!! }
        // 6. A real deal is no dearer than any other offer of its pack, and "less than ever" is less than every one.
        if (verdict.verdict == Verdict.REAL_DEAL) {
            val m = verdict.measure!!
            for (offer in samePack) CurrentOffer(offer).measures[m]?.let { other ->
                if (verdict.value!! > other * (1 + TOLERANCE)) fails("a real deal at ${verdict.value}, where $offer gave $other")
                if (verdict.lastAsCheap == null && verdict.value!! * (1 + TOLERANCE) >= other) fails("less than ever at ${verdict.value}, where $offer gave $other")
            }
        }
        // 7. No verdict passes over an offer of its pack shown now that is clearly cheaper in what is paid; "cheaper another way" names one.
        val todayShare = verdict.raise?.let { (terms.eurosPerPack ?: terms.paidShare?.times(it.now))?.div(it.before) } ?: terms.paidShare
        val todays = Measures(terms.eurosPerKg, terms.eurosPerLitre, terms.eurosPerPiece, terms.eurosPerPack, todayShare)
        /** How [other], of today's pack, compares with today's offer in what is paid: the measure and its value, or null. */
        fun paid(other: Measures): Pair<Measure, Double>? =
            listOf(Measure.PER_KG, Measure.PER_LITRE, Measure.PER_PIECE, Measure.PER_PACK, Measure.PAID_SHARE)
                .firstOrNull { todays[it] != null && other[it] != null }?.let { it to other[it]!! }
        if (verdict.verdict != Verdict.NOT_A_DEAL && verdict.verdict != Verdict.CHEAPER_ANOTHER_WAY) for (offer in running.filter { it.pack == pack }) {
            paid(CurrentOffer(offer.shown!!).measures)?.let { (m, other) ->
                if (todays[m]!! > other * (1 + TOLERANCE)) fails("${verdict.verdict}, where ${offer.shown} runs today at $other $m against ${todays[m]}")
            }
        }
        if (verdict.verdict == Verdict.CHEAPER_ANOTHER_WAY) {
            val cheaper = verdict.cheaperNow!!
            if (cheaper.identity.product.pack != pack || running.none { cheaper.isOf(it) }) fails("$cheaper is not an offer of today's pack shown today")
            val (m, other) = paid(cheaper.measures) ?: fails("$cheaper can't be compared with today's in what is paid")
            if (today.isItself(cheaper) || m != verdict.measure || todays[m]!! <= other * (1 + TOLERANCE)) fails("$cheaper is not cheaper now")
        }
        // 8. Always on offer, by today's pack and deal.
        if (verdict.verdict == Verdict.ALWAYS_ON_OFFER) {
            val same = case.sightings.map { it.first }.filter { CurrentOffer(it).offerKey == today.offerKey }
            val dated = case.read.count { n -> same.any { day(n) in LocalDate.parse(it.validFrom)..LocalDate.parse(it.validUntil) } }
            if (dated < ALWAYS_SHARE * case.read.size) fails("always on offer, but today's pack and deal were dated on $dated of ${case.read.size} days")
        }
        // 9. The summary is true about earlier offers and names those shown now, and only those, as on offer now.
        val summary = verdict.summary("AH")
        if (Regex("no earlier offer of (it|this pack) ended") in summary && verdict.earlier.isNotEmpty()) fails("says no earlier offer ended")
        for (offer in verdict.alongside) if ("dated until ${dateText(offer.validUntil)}" !in summary) fails("does not name $offer, shown now: $summary")
        if ("dated until" in summary && verdict.alongside.isEmpty()) fails("names an offer as on offer now, where none is shown: $summary")
        if ("a lower price" in summary) fails("names an offer without deal text by no price: $summary")
        if ("its share is measured against" in summary && verdict.measure != Measure.PAID_SHARE) fails("speaks of a share it does not compare by: $summary")
        verdict.raise?.let { if (it.beforeSeen >= LocalDate.parse(case.current.validFrom)) fails("the price before the raise was seen on ${it.beforeSeen}, not before the offer") }
        // 10. Another pack, cheaper by weight or volume: shown now first, else ended; none when none is.
        /** The first price of a weight or volume [other] and today's offer both have, and [other]'s value in it. */
        fun unit(other: Offer): Pair<Measure, Double>? = CurrentOffer(other).measures.let { m ->
            listOf(Measure.PER_KG, Measure.PER_LITRE).firstOrNull { todays[it] != null && m[it] != null }?.let { it to m[it]!! }
        }
        fun List<Offer>.clearlyCheaper() = filter { o -> unit(o)?.let { (m, v) -> todays[m]!! > v * (1 + TOLERANCE) } == true }
        val packsNow = running.filter { it.pack != pack }.map { it.shown!! }.clearlyCheaper()
        val packsEnded = ended.filter { it.pack != pack }.map { it.lowest }.clearlyCheaper()
        val cheaperPack = verdict.cheaperPack
        val expectedPacks = packsNow.ifEmpty { packsEnded }
        if ((cheaperPack == null) != expectedPacks.isEmpty()) fails("cheaper pack ${cheaperPack?.offer}, where other packs clearly cheaper are $expectedPacks")
        if (cheaperPack != null) {
            val (offer, m, value, now) = cheaperPack
            val other = offer.measures[m]!!
            if (now != packsNow.isNotEmpty() || offer.identity.product.pack == pack || value != todays[m] || value <= other * (1 + TOLERANCE)) fails("$cheaperPack is no cheaper pack")
            if (expectedPacks.any { o -> unit(o)?.let { (om, v) -> om == m && v < other * (1 - TOLERANCE) } == true }) fails("$cheaperPack is not the cheapest of $expectedPacks")
        }
        return verdict
    }

    /**
     * A history of six to thirteen weeks (some too short for a verdict): offers one after another that share days,
     * run on or lie apart, each with probability [often], of one to three deals, a price before that may move within
     * an offer and between offers, and a tenth of the nights unread. Sometimes too: offers dated from before the
     * first read, offers dated over months and seen now and then, another deal running alongside for weeks (of the
     * same pack or another), next week's offer listed early, regular prices looked up, two prices without deal text on
     * the same dates (Lidl changing a price mid-week), an offer the store stopped showing before its dates ended, and
     * another deal of today's pack paying what today's does, shown alongside.
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
        // Lidl changes a price mid-week: two prices without deal text on the same dates, the first seen until the change.
        if (random.nextInt(4) == 0) {
            val start = today - random.nextInt(10)
            val change = start + 1 + random.nextInt(5)
            val (first, second) = LIDL_PRICES.shuffled(random).take(2)
            val pack = PACKS.random(random)
            offers += Dated(pack, Deal(null, price = first, hasBefore = false), start, start + 6, listOf(start to 1.0), seen = (start until change).toList())
            offers += Dated(pack, Deal(null, price = second, hasBefore = false), start, start + 6, listOf(start to 1.0), seen = (change..start + 6).toList())
        }
        // An offer the store stopped showing before its dates ended: withdrawn, or the long-dated one of last month.
        if (random.nextInt(4) == 0) {
            val start = today - 3 - random.nextInt(30)
            offers += dated(start, today + 1 + random.nextInt(30), deal = DEALS.random(random), seen = (start..start + random.nextInt(today - start)).toList())
        }
        if (offers.none { d -> today in d.from..d.until && d.segments().any { today in it.second } }) offers += dated(today - random.nextInt(5), today + 2)
        val unread = (0..today).filter { random.nextInt(10) == 0 }.toSet() - today
        val lookups = if (random.nextBoolean()) List(1 + random.nextInt(3)) { (today - random.nextInt(70)).let { at -> at..minOf(today, at + random.nextInt(10)) } to BEFORE.random(random) } else emptyList()
        val case = Case("made up from seed $seed", today, offers, unread, lookups)
        return sameValueAlongside(case, random) ?: case
    }

    /**
     * The shape a reviewer of PR #178 found no generated case of: another deal of today's pack, shown today, that pays
     * what today's does ("1+1 gratis" and "50% korting" of one price before), so that "less than ever" must not be
     * said. Added to half the cases whose offer today is one of them; it begins before today's, which stays today's.
     */
    private fun sameValueAlongside(case: Case, random: Random): Case? {
        val current = case.current
        val twin = mapOf(ONE_PLUS_ONE.text to HALF_OFF, HALF_OFF.text to ONE_PLUS_ONE)[current.deal]?.takeIf { random.nextBoolean() } ?: return null
        val start = case.offers.filter { it.deal.text == current.deal && day(it.from).toString() == current.validFrom }.minOf { it.from } - 1 - random.nextInt(7)
        val twinOffer = Dated(current.packSize!!, twin, start, case.today + random.nextInt(10), listOf(start to current.originalPrice!!))
        return case.copy(offers = case.offers + twinOffer)
    }

    @Test
    fun everyMadeUpHistoryKeepsTheProperties() {
        val shapes = mutableMapOf<String, Int>()
        val verdicts = (1L..CASES).map { seed ->
            val case = madeUp(seed)
            val verdict = check(case, Random(seed * 31), "seed $seed")
            for ((shape, there) in shapesOf(case, verdict)) if (there) shapes.merge(shape, 1, Int::plus)
            verdict.verdict
        }
        println("Verdicts on $CASES made-up histories: " + verdicts.groupingBy { it }.eachCount())
        println("Shapes reached: $shapes")
        // The cases reach every verdict, not only "not enough history", and every shape the properties are about.
        assertEquals(Verdict.entries.toSet(), verdicts.toSet())
        assertEquals(SHAPES, shapes.keys, "shapes reached: $shapes")
    }

    /** Which of [SHAPES] the verdict on [case] shows. */
    private fun shapesOf(case: Case, verdict: DealVerdict): Map<String, Boolean> {
        val pack = CurrentOffer(case.current).identity.product.pack
        // Lidl's two prices on the same dates: the one replaced is an earlier offer that ended early.
        val replaced = case.offers.filter { it.deal.text == null && it.seen != null }.groupBy { it.pack to (it.from to it.until) }.values.any { it.size == 2 }
        return mapOf(
            "as low as an offer shown alongside" to (verdict.verdict == Verdict.REAL_DEAL && verdict.lastAsCheap in verdict.alongside),
            "an earlier offer that ended early" to verdict.earlier.any { verdict.endedEarly(it) },
            "a price without deal text replaced on the same dates" to (replaced && verdict.earlier.any { verdict.endedEarly(it) && it.deal == null }),
            "another pack cheaper now" to (verdict.cheaperPack?.now == true),
            "another pack cheaper before" to (verdict.cheaperPack?.now == false),
            "another pack shown now, not cheaper" to (verdict.cheaperPack == null && verdict.alongside.any { it.identity.product.pack != pack }),
            "another pack cheaper only per piece, not said" to (verdict.cheaperPack == null && verdict.alongside.any { other ->
                val today = CurrentOffer(case.current).measures
                other.identity.product.pack != pack && listOf(Measure.PER_KG, Measure.PER_LITRE).none { today[it] != null && other.measures[it] != null } &&
                    today.perPiece != null && other.measures.perPiece != null && today.perPiece > other.measures.perPiece * (1 + TOLERANCE)
            }),
        )
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

    /** Round 4: "2 voor 6,00" (€1,50 a piece) dated all year and still shown; "voor 3,19" today is no real deal. */
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

    /** Round 5: a deeper share on another pack is not cheaper; only what is paid decides, and only within one pack. */
    @Test
    fun probeC3AShareAcrossPacksIsNotCheaper() {
        // 25 % off 4,00 for 500 g (€6,00 a kg), alongside 35 % off 6,00 for 4 pieces: no price both have.
        val c3 = probe("c3", Dated("500 g", QUARTER, 67, 73, listOf(67 to 4.00)), Dated("4 stuks", Deal("35% korting"), 60, 80, listOf(60 to 6.00)))
        assertTrue(c3.verdict != Verdict.CHEAPER_ANOTHER_WAY && "Also on offer now: \"35% korting\" for 4 stuks" in c3.summary("AH"), c3.summary("AH"))
        // Earlier offers without a size; the 500 g at 35 % off 5,00 is €6,50 a kg against today's kilo at €6,00.
        val c3b = probe(
            "c3b", Dated("", QUARTER, 14, 20, listOf(14 to 4.00)), Dated("", QUARTER, 42, 48, listOf(42 to 4.00)),
            Dated("500 g", Deal("35% korting"), 60, 80, listOf(60 to 5.00)), Dated("1 kg", QUARTER, 67, 73, listOf(67 to 8.00)),
        )
        assertTrue(c3b.verdict != Verdict.CHEAPER_ANOTHER_WAY && "Also on offer now: \"35% korting\" for 500 g" in c3b.summary("AH"), c3b.summary("AH"))
        assertEquals(null, c3b.cheaperPack, "€6,50 a kg is no cheaper than €6,00")
    }

    /** Round 5 and issue 197: "less than ever" must hold against an offer of its pack shown alongside at about the same price. */
    @Test
    fun probeR8LessThanEverHoldsAgainstOffersAlongside() {
        // Today 1+1 on the kilo at 2,99 (€1,50 a kg); earlier 25 % off (€2,24); "50% korting" on the kilo at 2,99 is shown alongside.
        val earlier = arrayOf(Dated("1 kg", QUARTER, 14, 20, listOf(14 to 2.99)), Dated("1 kg", QUARTER, 42, 48, listOf(42 to 2.99)))
        val verdict = probe("R8", *earlier, Dated("1 kg", HALF_OFF, 60, 80, listOf(60 to 2.99)), onePlusOne(67, 73, 67 to 2.99))
        assertEquals(Verdict.REAL_DEAL, verdict.verdict)
        assertTrue("is as low as it has been (€1.50 per kg with \"50% korting\"" in verdict.summary("AH"), verdict.summary("AH"))
        // The 500 g pack at the same price per kg is another pack: the kilo's is less than ever in its pack, and the 500 g is no cheaper.
        val otherPack = probe("R8, another pack", *earlier, Dated("500 g", ONE_PLUS_ONE, 60, 80, listOf(60 to 1.50)), onePlusOne(67, 73, 67 to 2.99))
        assertEquals(Verdict.REAL_DEAL to null, otherPack.verdict to otherPack.cheaperPack)
        assertTrue("is less than ever in this pack since 14 Sep 2026" in otherPack.summary("AH"), otherPack.summary("AH"))
    }

    /** Round 4: a deeper deal on top of a long-dated ordinary one says the ordinary one runs, not "not on offer before". */
    @Test
    fun probeL1TheSummaryNamesTheOfferRunningAlongside() {
        val pack = "260 gram"
        val deeper = Dated(pack, Deal("2 voor 4,00", hasBefore = false), 64, 77, listOf(64 to 1.0))
        val verdict = probe("L1", Dated(pack, Deal("2 voor 5,50", hasBefore = false), -300, 85, listOf(-300 to 1.0), seen = listOf(0, 68, 70)), deeper)
        assertEquals(Verdict.NOT_ENOUGH_HISTORY, verdict.verdict)
        assertEquals(
            "Not enough history yet: no earlier offer of it ended since 14 Sep 2026; a verdict needs 2 earlier offers to compare with. " +
                "Also on offer now: \"2 voor 5,50\" for 260 gram, dated until 8 Dec 2026.",
            verdict.summary("Jumbo"),
        )
        // Not on today's page (last seen yesterday): it ended early, an earlier offer, and not on offer now.
        val gone = probe("L1, not shown today", Dated(pack, Deal("2 voor 5,50", hasBefore = false), -300, 85, listOf(-300 to 1.0), seen = listOf(0, 68, 69)), deeper)
        assertEquals(emptyList(), gone.alongside)
        assertEquals(listOf("2 voor 5,50"), gone.earlier.map { it.deal })
        assertEquals(
            "Not enough history yet: on offer once before since 14 Sep 2026, 1 of them with a price per kg; a verdict needs 2 earlier offers to compare with.",
            gone.summary("Jumbo"),
        )
    }

    /** Issue 197 F1, probe e1: Lidl raises a price mid-week on the same dates; the old price is gone, not cheaper right now. */
    @Test
    fun probeE1APriceReplacedOnTheSameDatesIsNoLongerOnOffer() {
        fun lidl(price: Double, seen: IntRange) = Dated("500 g", Deal(null, price = price, hasBefore = false), 68, 74, listOf(68 to 1.0), seen = seen.toList())
        val raised = probe("e1", lidl(2.29, 68..69), lidl(2.49, 70..74))
        assertTrue(raised.verdict != Verdict.CHEAPER_ANOTHER_WAY && raised.alongside.isEmpty(), raised.summary("Lidl"))
        assertEquals(listOf(2.29), raised.earlier.map { it.identity.price })
        assertTrue(raised.endedEarly(raised.earlier.single()))
        // e1b, lowered: the old, dearer price is not "also on offer now".
        val lowered = probe("e1b", lidl(2.49, 68..69), lidl(2.29, 70..74))
        assertTrue("Also on offer now" !in lowered.summary("Lidl") && lowered.alongside.isEmpty(), lowered.summary("Lidl"))
        // Shown at once (two prices on today's page), the lower is cheaper right now, named by its price.
        val both = probe("e1, both shown", lidl(2.49, 70..74), lidl(2.29, 68..70))
        assertEquals(Verdict.CHEAPER_ANOTHER_WAY, both.verdict)
        assertTrue("where €2.29 for 500 g, dated until 27 Nov 2026 gives €4.58 per kg." in both.summary("Lidl"), both.summary("Lidl"))
    }

    /** Issue 197 F1, probe e2: a long-dated cheaper deal last seen fifty nights ago is not cheaper right now. */
    @Test
    fun probeE2ALongDatedDealLastSeenLongAgoIsNotCheaperNow() {
        val verdict = probe("e2", Dated("1 kg", TWO_FOR, -300, 85, listOf(-300 to 1.99), seen = listOf(10, 20)), Dated("1 kg", QUARTER, 67, 73, listOf(67 to 2.49)))
        assertTrue(verdict.verdict != Verdict.CHEAPER_ANOTHER_WAY && verdict.alongside.isEmpty(), verdict.summary("AH"))
        assertEquals(listOf("2 voor 3,00"), verdict.earlier.map { it.deal })
    }

    /** Issue 197 F2, probes d and e4: each pack against itself; another pack's lower price per kg is said apart. */
    @Test
    fun probeDEachPackIsJudgedAgainstItself() {
        fun half(from: Int) = Dated("500 g", ONE_PLUS_ONE, from, from + 6, listOf(from to 3.74))
        fun kilo(from: Int) = onePlusOne(from, from + 6, from to 3.74)
        // d: the kilo's ordinary deal (€1,87 a kg, as twice before) is the usual deal, not a real one against the 500 g's €3,74.
        val d = probe("d", half(7), half(21), kilo(28), half(35), kilo(49), kilo(67))
        assertEquals(Verdict.USUAL_DEAL to null, d.verdict to d.cheaperPack)
        assertEquals(listOf("1 kg"), d.earlier.map { it.identity.product.pack }.distinct())
        // e4: the 500 g's usual deal is usual for the 500 g; that the kilo goes for less per kg is said apart.
        val e4 = probe("e4", half(7), kilo(14), half(21), kilo(35), half(49), half(67))
        assertEquals(Verdict.USUAL_DEAL, e4.verdict)
        assertEquals("1 kg" to false, e4.cheaperPack?.offer?.identity?.product?.pack to e4.cheaperPack?.now)
        assertTrue(
            e4.summary("AH").endsWith(" Cheapest per kg in another pack (1 kg): €1.87 per kg with \"1+1 gratis\" from 19 Oct 2026 to 25 Oct 2026."),
            e4.summary("AH"),
        )
        // The kilo shown now: said as cheaper now, and not again under "also on offer now".
        val now = probe("e4, kilo now", half(7), half(21), half(49), kilo(64), half(67))
        assertEquals(Verdict.USUAL_DEAL to true, now.verdict to now.cheaperPack?.now)
        assertTrue(
            now.summary("AH").endsWith(" Cheaper per kg in another pack now (1 kg): €1.87 per kg with \"1+1 gratis\", dated until 23 Nov 2026."),
            now.summary("AH"),
        )
    }

    private companion object {
        const val CASES = 600L
        const val DROPS = 3
        // Weights, pieces and no size at all, so that offers of two packs can share no price and only a share; two
        // packs of pieces, so that another pack can be cheaper by the piece, which is never said (issue 197).
        val PACKS = listOf("1 kg", "500 g", "4 stuks", "2 stuks", "")
        val BEFORE = listOf(2.49, 2.99, 3.19)
        val LIDL_PRICES = listOf(1.59, 1.79, 1.99)
        val ONE_PLUS_ONE = Deal("1+1 gratis")
        val HALF_OFF = Deal("50% korting")

        // AH states a price with some percentages, which follows the price before.
        val QUARTER_STATED = Deal("25% korting", stated = 0.75)
        val QUARTER = Deal("25% korting")
        val TWO_FOR = Deal("2 voor 3,00")
        val DEALS = listOf(
            ONE_PLUS_ONE, HALF_OFF, QUARTER_STATED, QUARTER, TWO_FOR, Deal("VOOR 1.99", price = 1.99),
            // Next to nothing off: after a raise, no deal.
            Deal("VOOR 2.99", price = 2.99),
            // Lidl: a price, no deal, no price before.
            Deal(null, price = 1.79, hasBefore = false),
            // A deal of no known form: nothing to compare by.
            Deal("2 stapelen tot 35%"),
        )
        val comparing = setOf(Verdict.REAL_DEAL, Verdict.USUAL_DEAL, Verdict.WORSE_THAN_USUAL)
        val SHAPES = setOf(
            "as low as an offer shown alongside", "an earlier offer that ended early", "a price without deal text replaced on the same dates",
            "another pack cheaper now", "another pack cheaper before", "another pack shown now, not cheaper",
            "another pack cheaper only per piece, not said",
        )

        fun night(n: Int): Instant = Instant.parse("2026-09-14T03:00:00Z").plus(Duration.ofDays(n.toLong()))

        fun day(n: Int) = serverDay(night(n))

        fun dateText(day: LocalDate) = day.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH))
    }
}
