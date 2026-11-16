package dev.tobins.mealplanner.server.offers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/*
 * A study of reading Sligro's public folders (issue 182). Nothing calls this outside its tests: there is
 * no Sligro store value, no source and no schema yet.
 *
 * Each folder is a Publitas viewer on folder.sligro.nl. Its `spreads.json` holds the text of every
 * page (the same text as in the folder's PDF, at 0.4 MB instead of 60 to 80 MB, so no PDF library is
 * needed), but no hotspots and no product links: the text is all there is. It comes in reading order,
 * not as laid out, so a big price printed beside an entry arrives in pieces ("5." and "99") somewhere
 * near it. The reader therefore takes only what it can pair with certainty and counts the rest as
 * unread instead of guessing.
 */

/** One entry of a folder: a product with its pack and price, prices without BTW. */
data class FolderOffer(
    /** Sligro's article number, a stable key (`sligro.nl/p.<number>.html`). */
    val articleNumber: String,
    /** The heading of the entry, brand and product as printed. A second pack of the same product has the first one's. */
    val name: String,
    /** The pack as printed: "Pot 200 gram", "Tray 12 blikken x 50 cl", "Per kilo". */
    val pack: String,
    /** The price before the offer, when the folder prints one. Null for "scherp geprijsd" entries with one price. */
    val regularPrice: Double?,
    /** The price to pay (the offer price, or the only price). Without BTW. */
    val price: Double,
    /** The folder page (1-based) the entry is on. */
    val page: Int,
    /** The folder's section the page is in ("FOOD", "WIJN", "NON-FOOD", "SPAAR"): a hint for the BTW rate. */
    val section: String?,
)

/** An article number the reader saw but would not guess a price for, and why. */
data class UnreadEntry(val articleNumber: String, val page: Int, val reason: String)

/** The period a folder is valid for, from its cover ("NR 15 Geldig van donderdag 29 oktober t/m maandag 16 november 2026"). */
data class FolderPeriod(val number: Int, val from: LocalDate, val until: LocalDate)

/** What one folder gave: [period] is null when no cover could be read, which makes the whole reading unusable. */
data class FolderReading(val period: FolderPeriod?, val offers: List<FolderOffer>, val unread: List<UnreadEntry>)

object SligroFolderReader {
    private val json = Json { ignoreUnknownKeys = true }

    /** The folders `acties.html` links to, by their address name ("foodvers-15-2026"). */
    fun folderNames(actiesHtml: String): List<String> =
        Regex("""folder\.sligro\.nl/([A-Za-z0-9_-]+)""").findAll(actiesHtml).map { it.groupValues[1] }.distinct().toList()

    /** Where a folder's pages come from; the viewer itself fetches this address. */
    fun spreadsUrl(folderName: String): String = "https://folder.sligro.nl/$folderName/spreads.json"

    /** The text of each page, in order, from a folder's `spreads.json`. */
    fun pageTexts(spreads: String): List<String> =
        json.parseToJsonElement(spreads).jsonArray
            .flatMap { (it as? JsonObject)?.get("pages") as? JsonArray ?: JsonArray(emptyList()) }
            .map { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty() }

    /** All entries of a folder, from its `spreads.json`. */
    fun read(spreads: String): FolderReading = readPages(pageTexts(spreads))

    fun readPages(pages: List<String>): FolderReading {
        val offers = mutableListOf<FolderOffer>()
        val unread = mutableListOf<UnreadEntry>()
        var section: String? = null
        pages.forEachIndexed { index, text ->
            // A section starts on a page that carries the period line; its first line names it.
            if (PERIOD.containsMatchIn(text)) section = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            val (found, missed) = page(text, index + 1, section)
            offers += found
            unread += missed
        }
        return FolderReading(period(pages), offers, unread)
    }

    /** The validity period, from the first cover that prints one. */
    fun period(pages: List<String>): FolderPeriod? {
        val match = pages.firstNotNullOfOrNull { PERIOD.find(it) } ?: return null
        val (number, fromDay, fromMonth, untilDay, untilMonth, year) = match.destructured
        val untilMonthNumber = MONTHS[untilMonth.lowercase()] ?: return null
        val fromMonthNumber = if (fromMonth.isEmpty()) untilMonthNumber else MONTHS[fromMonth.lowercase()] ?: return null
        val untilYear = year.toInt()
        // A folder over New Year starts in the year before the one printed.
        val fromYear = if (fromMonthNumber > untilMonthNumber) untilYear - 1 else untilYear
        return FolderPeriod(number.toInt(), LocalDate.of(fromYear, fromMonthNumber, fromDay.toInt()), LocalDate.of(untilYear, untilMonthNumber, untilDay.toInt()))
    }

    /**
     * The entries of one page. An article number is read when the text after it holds a pack and its price,
     * or a pack and a big price printed in two pieces right behind it; every other one comes back unread.
     */
    fun page(text: String, number: Int, section: String?): Pair<List<FolderOffer>, List<UnreadEntry>> {
        val lines = text.lines().map { it.trim() }
        val offers = mutableListOf<FolderOffer>()
        val unread = mutableListOf<UnreadEntry>()
        var previousName: String? = null
        var previousArticleLine = -1
        // The pieces of a big price can be far apart in the text, so a lone "129." is enough to doubt a single printed price.
        val pageHasBigPrice = lines.any { it.matches(BIG_EUROS) }
        // Variants of one product (flavours, colours) are listed one article number after the other with one
        // pack and price below the last: they wait here for it.
        val waiting = mutableListOf<Labelled>()
        for ((i, line) in lines.withIndex()) {
            val article = ARTICLE.find(line) ?: continue
            val labelled = labels(lines, i, article)
            // Only a second pack right behind the first one's price shares its heading.
            val sharedName = previousName.takeIf { lines.subList(previousArticleLine + 1, i).filter { it.isNotEmpty() }.all(::isPriceOrPack) }
            previousName = null
            previousArticleLine = i
            when (val read = price(lines, i, pageHasBigPrice)) {
                is Unreadable -> if (isFollowedByArticle(lines, i)) waiting += labelled else {
                    (waiting + labelled).forEach { unread += UnreadEntry(it.articleNumber, number, read.reason) }
                    waiting.clear()
                }
                is Priced -> {
                    val members = waiting + labelled
                    // Columns of variants come in tangled: article numbers with no label of their own do not say whose price follows.
                    // Several pack and price lines in a row mean columns with a price for each, not one price for all.
                    val tangled = members.size > 1 && (members.any { it.prefix == null && it.above.isEmpty() } || packLinesAfter(lines, i) > 1)
                    if (tangled) {
                        members.forEach { unread += UnreadEntry(it.articleNumber, number, "variants whose prices cannot be tied to them") }
                        waiting.clear()
                        continue
                    }
                    // The heading of variants is the text above the first; what is left of the first one's own label is not part of it.
                    val first = members.first()
                    val heading = if (members.size == 1 || first.prefix != null) first.above else first.above.dropLast(1)
                    members.forEachIndexed { index, member ->
                        val label = when {
                            members.size == 1 -> member.prefix
                            member.prefix != null -> member.prefix
                            index == 0 -> first.above.lastOrNull()
                            else -> member.above.joinToString(" ")
                        }
                        // A second pack of a product has no heading of its own: it follows the first one's price.
                        val name = dedupe(heading + listOfNotNull(label?.takeIf { it.isNotEmpty() })).joinToString(" ").ifEmpty { sharedName }
                        when {
                            name == null -> unread += UnreadEntry(member.articleNumber, number, "no heading")
                            isProse(name) -> unread += UnreadEntry(member.articleNumber, number, "the heading is a description, not a name")
                            else -> {
                                offers += FolderOffer(member.articleNumber, name, read.pack, read.regular, read.price, number, section)
                                if (members.size == 1) previousName = name
                            }
                        }
                    }
                    waiting.clear()
                }
            }
        }
        waiting.forEach { unread += UnreadEntry(it.articleNumber, number, "no pack and price after the variants") }
        return offers to unread
    }

    /** An article number with the text around it: [prefix] before it on its line, [above] the lines of the same block above. */
    private class Labelled(val articleNumber: String, val prefix: String?, val above: List<String>)

    /** "Medusa Ansjovisfilet" above "Medusa Ansjovisfilet op olie" is the same heading twice. */
    private fun dedupe(parts: List<String>): List<String> =
        if (parts.size >= 2 && parts[1].startsWith(parts[0])) parts.drop(1) else parts

    private fun labels(lines: List<String>, articleLine: Int, article: MatchResult): Labelled =
        Labelled(article.groupValues[1], lines[articleLine].substring(0, article.range.first).trim().ifEmpty { null }, above(lines, articleLine))

    /** True when the next text after [articleLine] is a short label and then another article number, with no price in between. */
    private fun isFollowedByArticle(lines: List<String>, articleLine: Int): Boolean {
        for (k in articleLine + 1 until minOf(lines.size, articleLine + 1 + LABEL_LINES)) {
            val line = lines[k]
            if (ARTICLE.containsMatchIn(line)) return true
            if (ANY_PRICE.containsMatchIn(line) || PACK_START.containsMatchIn(line)) return false
        }
        return false
    }

    private sealed interface PriceRead
    private class Priced(val pack: String, val regular: Double?, val price: Double) : PriceRead
    private class Unreadable(val reason: String) : PriceRead

    /** The pack and prices that follow the article number on [articleLine], or why they cannot be told. */
    private fun price(lines: List<String>, articleLine: Int, pageHasBigPrice: Boolean): PriceRead {
        val first = nextText(lines, articleLine + 1)
        if (first == null || ARTICLE.containsMatchIn(lines[first])) return Unreadable("no pack and price after the article number")
        var last: Int = first
        var text = lines[first]
        // "Ca. 440 gram," continues on the next line ("per kilo 24.99").
        if (text.endsWith(",")) {
            val continued = nextText(lines, last + 1)
            if (continued != null) {
                last = continued
                text = "$text ${lines[continued]}"
            }
        }
        if (RANGE.containsMatchIn(text)) return Unreadable("a price range (vanaf, or several packs)")

        TWO_PRICES.find(text)?.let { return both(clean(it.groupValues[1]), amount(it.groupValues[2]), amount(it.groupValues[3])) }
        ONE_PRICE.find(text)?.let { return withBigPrice(lines, clean(it.groupValues[1]), amount(it.groupValues[2]), last + 1, pageHasBigPrice) }

        // A pack without a price: the price follows on a line of its own, or large in two pieces.
        if (!PACK_START.containsMatchIn(text) || text.length > MAX_PACK) return Unreadable("no pack line")
        val pack = clean(text)
        val next = nextText(lines, last + 1)
        if (next != null) {
            TWO_PRICES_ONLY.matchEntire(lines[next])?.let { return both(pack, amount(it.groupValues[1]), amount(it.groupValues[2])) }
            ONE_PRICE_ONLY.matchEntire(lines[next])?.let { return withBigPrice(lines, pack, amount(it.groupValues[1]), next + 1, pageHasBigPrice) }
        }
        // A big price alone, with no printed price next to it, is not taken: it may as well belong to the entry beside this one.
        interleavedPrices(lines, last + 1)?.let { (regular, price) -> return both(pack, regular, price) }
        return Unreadable("a pack without a price")
    }

    /**
     * The wine pages print the struck-through regular price between the two pieces of the big one:
     * "5." "7.99" "99" is 5.99 now and 7.99 before. Null when the text does not follow that order exactly.
     */
    private fun interleavedPrices(lines: List<String>, from: Int): Pair<Double, Double>? {
        val euros = nextText(lines, from)?.takeIf { lines[it].matches(BIG_EUROS) } ?: return null
        val regular = nextText(lines, euros + 1)?.takeIf { ONE_PRICE_ONLY.matches(lines[it]) } ?: return null
        val cents = (regular + 1 until minOf(lines.size, regular + 1 + LOOKAHEAD)).firstOrNull { lines[it].isNotEmpty() }?.takeIf { lines[it].matches(BIG_CENTS) } ?: return null
        return amount(lines[regular]) to (lines[euros] + lines[cents]).toDouble()
    }

    /** Two printed prices are the regular one and the offer one, in that order; anything else is not understood. */
    private fun both(pack: String, regular: Double, price: Double): PriceRead =
        if (regular > price) Priced(pack, regular, price) else Unreadable("the first price is not above the second")

    /**
     * A pack with one printed price. When a large price in two pieces follows, the printed one is the
     * regular price and the large one the offer. Without one the printed price is all there is, but only on a
     * page that prints no big prices: elsewhere the printed price may be a struck-through regular price whose
     * big offer price is somewhere else in the text, and it would be taken for the price to pay.
     */
    private fun withBigPrice(lines: List<String>, pack: String, printed: Double, from: Int, pageHasBigPrice: Boolean): PriceRead {
        val big = bigPrice(lines, from)
        return when {
            big == null && pageHasBigPrice -> Unreadable("one printed price on a page with big prices that may belong to it")
            big == null -> Priced(pack, null, printed)
            big < printed -> Priced(pack, printed, big)
            else -> Unreadable("a big price that is not below the printed one")
        }
    }

    /** How many pack and price lines ("Literfles 14.79 11.99") follow [articleLine] before the next article number. */
    private fun packLinesAfter(lines: List<String>, articleLine: Int): Int =
        lines.subList(articleLine + 1, minOf(lines.size, articleLine + 1 + 2 * LOOKAHEAD)).takeWhile { !ARTICLE.containsMatchIn(it) }
            .count { PACK_START.containsMatchIn(it) && ANY_PRICE.containsMatchIn(it) }

    /** True for a line that is part of an entry's pack and price (so no new heading), not a name. */
    private fun isPriceOrPack(line: String): Boolean =
        BADGE.matches(line) || ANY_PRICE.containsMatchIn(line) || PACK_START.containsMatchIn(line) || line.matches(BIG_EUROS) || line.matches(BIG_CENTS)

    /**
     * The up to [HEADING_LINES] lines above an article number that belong to it (no blank line, price, pack
     * line or other article number between), top to bottom.
     */
    private fun above(lines: List<String>, articleLine: Int): List<String> {
        val above = mutableListOf<String>()
        var k = articleLine - 1
        while (k >= 0 && lines[k].isNotEmpty() && above.size < HEADING_LINES) {
            val line = lines[k]
            if (ARTICLE.containsMatchIn(line) || ANY_PRICE.containsMatchIn(line) || BADGE.matches(line) || PACK_START.containsMatchIn(line)) break
            above.add(0, line)
            k--
        }
        return above
    }

    /** A sentence of a description printed next to an entry is not its name. */
    private fun isProse(name: String): Boolean = name.endsWith(".") || name.split(' ').size > MAX_NAME_WORDS

    /** The first line from [from] on that is text: not blank and not a badge ("SCHERP", "VANAF", a stamp count). */
    private fun nextText(lines: List<String>, from: Int): Int? =
        (from until minOf(lines.size, from + LOOKAHEAD)).firstOrNull { lines[it].isNotEmpty() && !BADGE.matches(lines[it]) }

    /**
     * A price printed large in two pieces ("6." then "99") as the next thing from [from] on. A "vanaf" in
     * between means the big price is the lowest of several packs', which belongs to no one entry.
     */
    private fun bigPrice(lines: List<String>, from: Int): Double? {
        val euros = nextText(lines, from) ?: return null
        if (!lines[euros].matches(BIG_EUROS) || (from until euros).any { lines[it].equals("vanaf", ignoreCase = true) }) return null
        val cents = (euros + 1 until minOf(lines.size, euros + 1 + LOOKAHEAD)).firstOrNull { lines[it].isNotEmpty() } ?: return null
        return if (lines[cents].matches(BIG_CENTS)) (lines[euros] + lines[cents]).toDouble() else null
    }

    private fun amount(text: String): Double = text.replace(',', '.').toDouble()

    private fun clean(pack: String): String = pack.trim().trimEnd(',').trim()

    private const val LOOKAHEAD = 8
    private const val LABEL_LINES = 4
    private const val MAX_PACK = 60
    private const val MAX_NAME_WORDS = 10
    private const val HEADING_LINES = 4

    private const val PRICE = """(\d{1,3}[.,]\d{2})"""
    private val ARTICLE = Regex("""Art\. ?nr\. ?(\d{5,7})""")
    private val ANY_PRICE = Regex("""\b$PRICE\b""")
    private val TWO_PRICES = Regex("""^(.*?)\s*$PRICE\s+$PRICE$""")
    private val TWO_PRICES_ONLY = Regex("""^$PRICE\s+$PRICE$""")
    private val ONE_PRICE = Regex("""^(.*?[A-Za-z)])\s+$PRICE$""")
    private val ONE_PRICE_ONLY = Regex("""^(?:per \w+\s+)?$PRICE$""")
    private val RANGE = Regex("""$PRICE\s+-\s+$PRICE""")
    private val PACK_START = Regex("""^(Per|Pot|Zak|Doos|Fles|Krat|Tray|Emmer|Net|Bak|Pak|Stuk|Ca\.|Can|Krimp|Blik|Voordeelbak|Fust|Literfles|Rol|Mand|Bus|Set|Doosje|Display|Pallet|Tube|Pakket|\d+ )\b""")
    private val BIG_EUROS = Regex("""\d{1,3}\.""")
    private val BIG_CENTS = Regex("""\d{2}""")
    private val BADGE = Regex("""^(SCHERP|GEPRIJSD|VANAF|vanaf|scan mij|SPAARZEGELS?.*|BIJ AANKOOP|VAN \d .*|\d{1,2}|\d{1,2}%|KORTING|COMBINEREN MOGELIJK|1\+1|Groots in genieten)$""")
    private val PERIOD = Regex("""NR (\d+) Geldig van \w+ (\d{1,2})(?: (\w+))? t/m \w+ (\d{1,2}) (\w+) (\d{4})""")

    private val MONTHS = listOf("januari", "februari", "maart", "april", "mei", "juni", "juli", "augustus", "september", "oktober", "november", "december")
        .withIndex().associate { (i, m) -> m to i + 1 }
}
