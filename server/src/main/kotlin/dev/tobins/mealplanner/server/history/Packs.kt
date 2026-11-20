package dev.tobins.mealplanner.server.history

import java.util.Locale

// The packs of a product as the verdicts compare them and as the app names them (issue 118, step c). Each pack is
// judged against itself (issue 197), so a pack written another way one week would start a history of its own and
// stay "not enough history" for longer. These rules say which texts are one pack; they are applied when the history
// is read, never when it is written, so the recorded rows keep the store's text as it was.

/**
 * A pack text ([ProductKey.pack], normalised) without what is not the pack: markup, and the footnotes Jumbo adds
 * to a subtitle on lines of their own ("Alle flesjes à 500 ml, per flesje<br />M.u.v. Gekoeld", sometimes with
 * empty lines after it). A footnote says what the deal leaves out ("M.u.v."), that the price already has the
 * discount, or how many one order may have; never what the pack is. Other lines stay: "Land van herkomst" tells
 * two wines under one title apart, and a list of flavours may come before the size.
 */
fun packText(pack: String): String =
    lines(pack).filterNot { footnote.containsMatchIn(it) }.joinToString(" ").replace(spaces, " ").trim()

/** The lines of a pack text, split at its line breaks, with any other markup taken out. */
private fun lines(text: String): List<String> =
    text.split(lineBreak).map { it.replace(markup, " ").replace(spaces, " ").trim() }.filter { it.isNotEmpty() }

private val lineBreak = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val markup = Regex("""<[^>]*>""")
private val spaces = Regex("""\s+""")
private val footnote = Regex("""^(m\.u\.v\.|de korting is al verwerkt|maximaal \d+ .* per bestelling)""", RegexOption.IGNORE_CASE)

/**
 * Approximate weights this close (5 %) are one pack: fresh meat and fish are weighed by the pack, and a store's
 * "circa 700 gram" one week may be "circa 720 gram" the next. A pack a tenth bigger is another pack.
 */
const val SAME_PACK_SHARE = 0.05

/**
 * Which of one product's pack texts ([ProductKey.pack]) are one pack: those equal as [packText], and approximate
 * weights ("ca. 450 g", "circa 700 gram") that differ only in a weight within [SAME_PACK_SHARE] of the smallest of
 * them. Each pack is then known by one text, the [packText] of the lightest, whatever order the texts come in.
 * Built per product from every text the verdict reads (the history's rows and what the store shows today), so that a
 * pack seen once more is read as the same one everywhere.
 */
class SamePacks(packs: Collection<String>) {
    private val same: Map<String, String>

    init {
        val texts = packs.distinct().associateWith(::packText)
        val approximate = texts.values.distinct().mapNotNull { text -> Approximate.of(text)?.let { text to it } }
        val byLightest = HashMap<String, String>()
        for (group in approximate.groupBy { it.second.around }.values) {
            var first: Pair<String, Approximate>? = null
            for (pack in group.sortedWith(compareBy({ it.second.grams }, { it.first }))) {
                if (first == null || pack.second.grams > first.second.grams * (1 + SAME_PACK_SHARE)) first = pack
                byLightest[pack.first] = first.first
            }
        }
        same = texts.mapValues { (_, text) -> byLightest[text] ?: text }
    }

    /** The text [pack] is known by: that of the pack it is the same as. */
    operator fun get(pack: String): String = same[pack] ?: packText(pack)

    /** An approximate weight in grams, and the text [around] it, which must be the same for two packs to be one. */
    private class Approximate(val grams: Double, val around: Pair<String, String>) {
        companion object {
            private val pattern = Regex("""^(.*?)\b(?:ca\.?|circa)\s*(\d+(?:\.\d+)?)\s*(kilogram|kilo|kg|gram|gr|g)\b(.*)$""")

            fun of(text: String): Approximate? = pattern.find(text)?.destructured?.let { (before, number, unit, after) ->
                val grams = number.toDoubleOrNull()?.times(if (unit.startsWith("k")) 1000 else 1) ?: return null
                Approximate(grams, before.trim() to after.trim())
            }
        }
    }
}

/**
 * The pack in a few characters, for the app's lines that name another pack ("1,5 l", "24 × 250 ml", "ca. 700 g",
 * "14 cm"), from the store's [packSize] as written ("Alle flessen à 1,5 liter", "Tray 24x250ml", "Schaal circa 700
 * gram, per 100 gram", "1 stuk, doorsnede van 14 cm"). Sizes only, in the units' symbols and with the stores'
 * decimal comma, so the name reads the same in English and Dutch. Null when the text gives no size, or several
 * ("Zak 500 gram of 1 kilo"), or only a count of pieces ("Pak 10 stuks"): the app then names the offer by its deal.
 * Footnotes and markup are left out first ([packText]); a price basis ("per 100 gram") is no pack.
 */
fun packName(packSize: String?): String? {
    val text = lines(packSize.orEmpty()).filterNot { footnote.containsMatchIn(it) }
    // "Naturel of knoflook, verpakking 190 of 350 gram": a number after "of" is another size to choose from.
    if (text.isEmpty() || text.any { several.containsMatchIn(it) }) return null
    val all = text.joinToString(" ")
    multiple.find(all)?.destructured?.let { (count, size, unit) -> return "$count × ${number(size)} ${symbol(unit)}" }
    range.find(all)?.destructured?.let { (low, high, unit) -> return "${number(low)}–${number(high)} ${symbol(unit)}" }
    return single.find(all)?.destructured?.let { (about, size, unit) -> (if (about.isEmpty()) "" else "ca. ") + "${number(size)} ${symbol(unit)}" }
}

private const val NUMBER = """\d+(?:[.,]\d+)?"""
private const val UNIT = """(kilogram|kilo|kg|gram|gr|g|liter|litre|ml|cl|l|cm)\b"""
private val options = setOf(RegexOption.IGNORE_CASE)
private val several = Regex("""\bof\b.*\d""", options)

/** "6 x 1,5 L", "Tray 24x250ml", "4-pack à 1 liter", "2 verpakkingen à 200 gram", "Tray met 24 blikken à 33cl". */
private val multiple = Regex("""(?<![\d.,])(\d+)(?:\s*[x×]\s*|(?:-pack)?\s+(?:\p{L}+\s+){0,2}à\s*)($NUMBER)\s*$UNIT""", options)

/** "Alle bakjes à 170-200 gram", "3 verpakkingen à 133 - 300 gram". */
private val range = Regex("""(?<![\d.,])($NUMBER)\s*-\s*($NUMBER)\s*$UNIT""", options)

/** "Alle flessen à 1,5 liter", "Net 1 kilo", "Schaal circa 700 gram", "Doorsnede van 14 cm"; never "per 100 gram". */
private val single = Regex("""(?<!per\s)(?<![\d.,])((?:ca\.?|circa)\s*)?($NUMBER)\s*$UNIT""", options)

private fun number(text: String) = text.replace('.', ',').removeSuffix(",0")

private fun symbol(unit: String) = when (unit.lowercase(Locale.ROOT)) {
    "kilogram", "kilo", "kg" -> "kg"
    "gram", "gr", "g" -> "g"
    "liter", "litre", "l" -> "l"
    else -> unit.lowercase(Locale.ROOT)
}
