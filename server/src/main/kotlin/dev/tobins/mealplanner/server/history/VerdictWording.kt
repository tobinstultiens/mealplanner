package dev.tobins.mealplanner.server.history

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The verdict in a sentence or two of plain English, for reading the verdicts on real offers before the app
 * shows them (step c of issue 118 words its own, in English and Dutch). [store] is the store's name.
 */
fun DealVerdict.summary(store: String): String {
    val since = firstRead?.let { " since ${date(it)}" }.orEmpty()
    // The verdict is on today's pack only; say so when the product came in other packs too.
    val inPack = if (otherPacks) " in this pack" else ""
    // Earlier offers are those that ended; offers shown alongside are said apart, below.
    val before = when {
        earlier.isNotEmpty() -> "on offer ${times(earlier.size)} before$inPack$since"
        otherPacks -> "no earlier offer of this pack ended$since"
        else -> "no earlier offer of it ended$since"
    }
    val text = when (verdict) {
        Verdict.NOT_A_DEAL -> raise!!.let { "Not a deal: its regular price went up from ${euros(it.before)} (seen ${date(it.beforeSeen)}) to ${euros(it.now)}, " +
            "and with this deal you pay ${share(value!!)} of the price before." }
        Verdict.NOT_ENOUGH_HISTORY -> if (daysRead < MIN_DAYS_READ) {
            "Not enough history yet: $store was read on ${days(daysRead)}$since; a verdict needs $MIN_DAYS_READ."
        } else {
            "Not enough history yet: $before" + (if (earlier.isEmpty()) "" else ", $compared of them with a ${name(measure!!)}") +
                "; a verdict needs $MIN_EARLIER_OFFERS earlier offers to compare with."
        }
        Verdict.CHEAPER_ANOTHER_WAY -> "Cheaper another way right now: ${amount(value!!)}, where ${offered(cheaperNow!!)} gives ${amount(cheaperNow.measures[measure!!]!!)}."
        Verdict.ALWAYS_ON_OFFER -> "The usual price, not a deal: $store dated it on offer this way on $daysThisDeal of the ${days(daysRead)} it was read$since."
        Verdict.NOT_COMPARABLE -> "Can't be compared: the deal gives no share of the regular price and no price per kg, litre, piece or pack; $before."
        Verdict.REAL_DEAL -> "A real deal: ${amount(value!!)} " +
            (if (lastAsCheap == null) "is less than ever$inPack$since (lowest before: ${was(lowest!!)})" else "is as low as it has been (${was(lastAsCheap)})") +
            "; $before, usually at ${amount(typical!!)}."
        Verdict.USUAL_DEAL -> if (value!! < typical!! * (1 - TOLERANCE)) {
            "Better than usual, but it has been cheaper: ${amount(value)}, where it usually goes for ${amount(typical)}; ${lastCheap()}, lowest ${was(lowest!!)}; $before."
        } else {
            "The usual deal: ${amount(value)}; $before, usually at ${amount(typical)}, lowest ${was(lowest!!)}."
        }
        // More than half of the earlier offers were cheaper, so the last of them is never null here.
        Verdict.WORSE_THAN_USUAL -> "Worse than usual: ${amount(value!!)}, where it usually goes for ${amount(typical!!)}; " +
            "${lastCheap()}, lowest ${was(lowest!!)}; $before."
    }
    val raised = raise?.takeIf { verdict != Verdict.NOT_A_DEAL }?.let {
        " Its regular price went up from ${euros(it.before)} (seen ${date(it.beforeSeen)}) to ${euros(it.now)}" +
            (if (measure == Measure.PAID_SHARE) "; its share is measured against ${euros(it.before)}." else ".")
    }
    val named = listOfNotNull(cheaperNow.takeIf { verdict == Verdict.CHEAPER_ANOTHER_WAY }, cheaperPack?.takeIf { it.now }?.offer)
    val also = alongside.filter { it !in named }.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = " Also on offer now: ", postfix = ".") { offered(it) }
    return text + raised.orEmpty() + cheaperPackSaid().orEmpty() + also.orEmpty()
}

/**
 * Another pack that is or was cheaper per unit (issue 197, "each pack against itself"): one shown now is said as
 * such, one that ended as the cheapest of the other packs.
 */
private fun DealVerdict.cheaperPackSaid(): String? = cheaperPack?.let { (run, measure, _, now) ->
    val unit = name(measure).removePrefix("price ")
    val pack = run.identity.product.pack.ifEmpty { "no size given" }
    if (now) {
        " Cheaper $unit in another pack now ($pack): ${amount(run.measures[measure]!!, measure)}${dealOf(run)}, dated until ${date(run.validUntil)}."
    } else {
        " Cheapest $unit in another pack ($pack): ${was(run, measure)}."
    }
}

/**
 * Another offer of the product as the store dated it: its deal (or, without deal text, its price), its pack where it
 * gives one, and until when.
 */
private fun offered(run: OfferPeriod): String {
    val pack = run.identity.product.pack.takeIf { it.isNotEmpty() }?.let { " for $it" }.orEmpty()
    val deal = run.deal?.takeIf { it.isNotBlank() }?.let { "\"$it\"" } ?: run.identity.price?.let(::euros) ?: "an offer without a price"
    return "$deal$pack, dated until ${date(run.validUntil)}"
}

/** The latest earlier offer at least as cheap as today's: "this cheap" when about the same, "cheaper" when clearly less. */
private fun DealVerdict.lastCheap(): String {
    val last = lastAsCheap!!
    val cheaper = last.measures[measure!!]!! < value!! * (1 - TOLERANCE)
    return (if (cheaper) "last cheaper " else "last this cheap ") + was(last)
}

/**
 * An earlier offer: its value in [measure], its deal and when it ran, as the store dated it; one that ended before
 * those dates did ([DealVerdict.endedEarly]) is said to be no longer shown.
 */
private fun DealVerdict.was(run: OfferPeriod, measure: Measure = this.measure!!): String {
    val from = run.validFrom
    val until = run.validUntil
    val dates = if (from == until) "on ${date(from)}" else "from ${date(from)} to ${date(until)}"
    val gone = if (run !in alongside && endedEarly(run)) " (no longer shown)" else ""
    return "${amount(run.measures[measure]!!, measure)}${dealOf(run)} $dates$gone"
}

private fun dealOf(run: OfferPeriod) = run.deal?.takeIf { it.isNotBlank() }?.let { " with \"$it\"" }.orEmpty()

private fun DealVerdict.amount(value: Double, measure: Measure = this.measure!!): String = when (measure) {
    Measure.PER_KG -> "${euros(value)} per kg"
    Measure.PER_LITRE -> "${euros(value)} per litre"
    Measure.PER_PIECE -> "${euros(value)} per piece"
    Measure.PER_PACK -> "${euros(value)} a pack"
    Measure.PAID_SHARE -> "${share(value)} of the regular price"
}

private fun name(measure: Measure) = when (measure) {
    Measure.PER_KG -> "price per kg"
    Measure.PER_LITRE -> "price per litre"
    Measure.PER_PIECE -> "price per piece"
    Measure.PER_PACK -> "price per pack"
    Measure.PAID_SHARE -> "share of the regular price"
}

private fun times(n: Int) = when (n) {
    1 -> "once"
    2 -> "twice"
    else -> "$n times"
}

private fun days(n: Int) = if (n == 1) "1 day" else "$n days"

private fun euros(value: Double) = "€" + String.format(Locale.ROOT, "%.2f", value)

private fun share(value: Double) = "${(value * 100).roundToInt()}%"

private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

private fun date(day: LocalDate) = day.format(dateFormat)
