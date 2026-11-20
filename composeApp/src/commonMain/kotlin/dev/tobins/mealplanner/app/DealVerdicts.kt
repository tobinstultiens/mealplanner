package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.PastOffer
import dev.tobins.mealplanner.api.PriceVerdict
import dev.tobins.mealplanner.api.VerdictKind
import dev.tobins.mealplanner.api.VerdictMeasure
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

// How the app words the server's verdict on an offer (issue 118, step c): a few words for next to the deal, a line on
// tap, and what else the verdict knows below it. The texts follow the table the owner read in pull request #280.

/**
 * What a verdict asks of the shopper, which the chip's look follows: [GOOD] stands out, the others read as plain
 * text. The words always say it; the look only helps.
 */
enum class VerdictTone { GOOD, PLAIN }

/** The look of this verdict's chip. */
val PriceVerdict.tone: VerdictTone get() = if (verdict == VerdictKind.REAL_DEAL) VerdictTone.GOOD else VerdictTone.PLAIN

/**
 * The verdict in a few words, for next to the deal, only where it calls for action (the owner's choice, issue 118):
 * buy now (`Real deal`), buy another way (`Can be cheaper`), or think twice (`No real discount`, `Usually cheaper`).
 * Null for the rest, so that the screens do not fill with it: a usual deal, one better than usual or always on offer,
 * not enough history yet (most offers, for the first eight weeks) and nothing to compare by. [line] says every one of
 * them in the detail.
 */
fun PriceVerdict.chipText(t: Strings): String? = when (verdict) {
    VerdictKind.REAL_DEAL -> t(Msg.VERDICT_CHIP_REAL)
    VerdictKind.CHEAPER_ANOTHER_WAY -> t(Msg.VERDICT_CHIP_CHEAPER)
    VerdictKind.NOT_A_DEAL -> t(Msg.VERDICT_CHIP_NOT_A_DEAL)
    VerdictKind.WORSE_THAN_USUAL -> t(Msg.VERDICT_CHIP_WORSE)
    VerdictKind.USUAL_DEAL, VerdictKind.ALWAYS_ON_OFFER, VerdictKind.NOT_COMPARABLE, VerdictKind.NOT_ENOUGH_HISTORY -> null
}

/**
 * The verdict in one line, with the number it rests on: `Real deal: lowest so far, €13.30/kg`, `Usually cheaper:
 * €2.34, now €2.75 (last on 1 Nov)`, `Too new to judge (2 of 56 days)`. A line that holds today's offer against an
 * earlier price says both in the same terms (the chip next to it shows a pack's price, which a price per kg cannot be
 * compared with by eye). An earlier offer is named by its period (`19–25 Oct`); "last on" names the last day it ran.
 * A fact the line needs that the server left out gives the line without it rather than a wrong one.
 */
fun PriceVerdict.line(t: Strings): String {
    val now = value?.let { amount(it, measure, t) }
    val usually = typical?.let { amount(it, measure, t) }
    val last = lastAsCheap
    return when (verdict) {
        VerdictKind.REAL_DEAL -> when {
            last != null -> t(Msg.VERDICT_REAL_AS_LOW, period(last.from, last.until, t))
            now == null -> t(Msg.VERDICT_CHIP_REAL)
            else -> t(if (thisPack) Msg.VERDICT_REAL_LOWEST_PACK else Msg.VERDICT_REAL_LOWEST, now)
        }
        VerdictKind.USUAL_DEAL -> {
            val was = last?.let { earlier -> earlier.value?.let { amount(it, measure, t) to period(earlier.from, earlier.until, t) } }
            when {
                betterThanUsual && was != null && now != null -> t(Msg.VERDICT_BETTER, now, was.first, was.second)
                usually != null -> t(Msg.VERDICT_USUAL, usually)
                else -> t(Msg.VERDICT_USUAL_ONLY)
            }
        }
        VerdictKind.WORSE_THAN_USUAL ->
            if (usually != null && now != null && last != null) t(Msg.VERDICT_WORSE, usually, now, date(last.until, t)) else t(Msg.VERDICT_CHIP_WORSE)
        VerdictKind.ALWAYS_ON_OFFER -> t(Msg.VERDICT_ALWAYS)
        VerdictKind.NOT_A_DEAL -> raise?.let { t(Msg.VERDICT_NOT_A_DEAL, euros(it.before, t), euros(it.now, t)) } ?: t(Msg.VERDICT_CHIP_NOT_A_DEAL)
        VerdictKind.CHEAPER_ANOTHER_WAY -> {
            val deal = cheaperNow?.deal
            val price = cheaperNow?.price
            val worth = cheaperNow?.value?.let { amount(it, measure, t) }
            when {
                deal != null && worth != null -> t(Msg.VERDICT_CHEAPER_DEAL, "\"$deal\"", worth)
                deal != null -> t(Msg.VERDICT_CHEAPER_DEAL_ONLY, "\"$deal\"")
                price != null -> t(Msg.VERDICT_CHEAPER_PRICE, euros(price, t))
                else -> t(Msg.VERDICT_CHIP_CHEAPER)
            }
        }
        VerdictKind.NOT_COMPARABLE -> t(Msg.VERDICT_NOT_COMPARABLE)
        VerdictKind.NOT_ENOUGH_HISTORY ->
            if (daysRead < daysNeeded) t(Msg.VERDICT_TOO_NEW, daysRead, daysNeeded) else t(Msg.VERDICT_TOO_FEW)
    }
}

/**
 * What else the verdict knows, a line each, under [line]: another pack that is or was cheaper per kg or per litre,
 * a regular price raised just before (unless that is the verdict itself), and the other offers of the product shown
 * now.
 */
fun PriceVerdict.notes(t: Strings): List<String> {
    val pack = cheaperPack?.let { other ->
        val measure = other.measure ?: return@let null
        val value = other.offer.value ?: return@let null
        val unit = t(if (measure == VerdictMeasure.PER_LITRE) Msg.VERDICT_UNIT_LITRE else Msg.VERDICT_UNIT_KG)
        val price = amount(value, measure, t)
        val name = other.offer.packName
        when {
            other.now && name != null -> t(Msg.VERDICT_PACK_NOW, unit, name, price)
            other.now -> t(Msg.VERDICT_OTHER_PACK_NOW, unit, price)
            else -> t(Msg.VERDICT_PACK_BEFORE, unit, name?.let { t(Msg.VERDICT_PACK_NAMED, it) } ?: t(Msg.VERDICT_PACK_OTHER), price, period(other.offer.from, other.offer.until, t))
        }
    }
    val raised = raise?.takeIf { verdict != VerdictKind.NOT_A_DEAL }?.let { t(Msg.VERDICT_RAISED, euros(it.before, t), euros(it.now, t)) }
    val also = alongside.map { t(Msg.VERDICT_ALSO, it.named(t)) }
    return listOfNotNull(pack, raised) + also
}

/**
 * An earlier offer as the detail's history lists it: `19–25 Oct · "1+1 gratis" · €1.87/kg`, and `Ended early`
 * when the store stopped showing it before its dates ended. Its value is in the verdict's [measure].
 */
fun PastOffer.historyLine(measure: VerdictMeasure?, t: Strings): String =
    listOfNotNull(
        period(from, until, t),
        named(t),
        value?.let { amount(it, measure, t) },
        t(Msg.VERDICT_ENDED_EARLY).takeIf { endedEarly },
    ).joinToString(" · ")

/** An offer by its deal as the store wrote it (`"2 voor 5,50"`), or without one by its price, with its pack when it is another one. */
private fun PastOffer.named(t: Strings): String {
    val what = deal?.let { "\"$it\"" } ?: price?.let { euros(it, t) } ?: t(Msg.ON_OFFER)
    return packName?.let { "$what ($it)" } ?: what
}

/** [value] in [measure]: `€13.30/kg`, `€1.33/l`, `€1.50 each`, `€2.34` a pack, `67% of the regular price`. */
private fun amount(value: Double, measure: VerdictMeasure?, t: Strings): String = when (measure) {
    VerdictMeasure.PER_KG -> "${euros(value, t)}/kg"
    VerdictMeasure.PER_LITRE -> "${euros(value, t)}/l"
    VerdictMeasure.PER_PIECE -> t(Msg.VERDICT_EACH, euros(value, t))
    VerdictMeasure.PAID_SHARE -> t(Msg.VERDICT_SHARE, "${(value * 100).roundToInt()}%")
    VerdictMeasure.PER_PACK, null -> euros(value, t)
}

private fun euros(value: Double, t: Strings) = formatEuro(value, t.locale)

private fun date(iso: String, t: Strings) = dayMonthLabel(iso, t.locale)

/** The days an offer ran, from [from] to [until] (ISO): `19–25 Oct` in one month, `26 Oct – 1 Nov` across two, `19 Oct` for one day. */
private fun period(from: String, until: String, t: Strings): String {
    val first = LocalDate.parse(from)
    val last = LocalDate.parse(until)
    if (first == last) return date(from, t)
    if (first.year != last.year || first.month != last.month) return "${date(from, t)} – ${date(until, t)}"
    val month = t.locale.monthNames[first.month.ordinal]
    return if (t.locale.monthFirst) "$month ${first.day}–${last.day}" else "${first.day}–${last.day} $month"
}
