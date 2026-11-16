package dev.tobins.mealplanner.server.history

import dev.tobins.mealplanner.server.serverDay
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
    val before = if (earlier.isEmpty()) "not on offer before$since" else "on offer ${times(earlier.size)} before$since"
    val text = when (verdict) {
        Verdict.NOT_A_DEAL -> raise!!.let { "Not a deal: its regular price went up from ${euros(it.before)} (seen ${date(it.beforeSeen)}) to ${euros(it.now)}, " +
            "and with this deal you pay ${share(value!!)} of the price before." }
        Verdict.NOT_ENOUGH_HISTORY -> if (daysRead < MIN_DAYS_READ) {
            "Not enough history yet: $store was read on ${days(daysRead)}$since; a verdict needs $MIN_DAYS_READ."
        } else {
            "Not enough history yet: $before" + (if (earlier.isEmpty()) "" else ", $compared of them with a ${name(measure!!)}") +
                "; a verdict needs $MIN_EARLIER_OFFERS earlier offers to compare with."
        }
        Verdict.ALWAYS_ON_OFFER -> "The usual price, not a deal: on offer this way on $daysThisDeal of the ${days(daysRead)} $store was read$since."
        Verdict.NOT_COMPARABLE -> "Can't be compared: the deal gives no share of the regular price and no price per kg, litre, piece or pack; $before."
        Verdict.REAL_DEAL -> "A real deal: ${amount(value!!)} " +
            (if (lastAsCheap == null) "is less than ever$since (lowest before: ${was(lowest!!)})" else "is as low as it has been (${was(lastAsCheap)})") +
            "; $before, usually at ${amount(typical!!)}."
        Verdict.USUAL_DEAL -> "The usual deal: ${amount(value!!)}; $before, usually at ${amount(typical!!)}, lowest ${was(lowest!!)}."
        Verdict.WORSE_THAN_USUAL -> "Worse than usual: ${amount(value!!)}, where it usually goes for ${amount(typical!!)}; " +
            (lastAsCheap?.let { "last this cheap ${was(it)}" } ?: "never this dear before") + ", lowest ${was(lowest!!)}; $before."
    }
    val raised = raise?.takeIf { verdict != Verdict.NOT_A_DEAL }?.let {
        " Its regular price went up from ${euros(it.before)} (seen ${date(it.beforeSeen)}) to ${euros(it.now)}; its share is measured against ${euros(it.before)}."
    }
    return text + raised.orEmpty()
}

/** An earlier offer: its value in the verdict's measure, its deal and when it ran. */
private fun DealVerdict.was(run: OfferPeriod): String {
    val from = serverDay(run.firstSeen)
    val until = serverDay(run.lastSeen)
    val dates = if (from == until) "on ${date(from)}" else "from ${date(from)} to ${date(until)}"
    val deal = run.deal?.takeIf { it.isNotBlank() }?.let { " with \"$it\"" }.orEmpty()
    return "${amount(run.measures[measure!!]!!)}$deal $dates"
}

private fun DealVerdict.amount(value: Double): String = when (measure!!) {
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
