package dev.tobins.mealplanner.server.history

import dev.tobins.mealplanner.pricing.PackSize
import dev.tobins.mealplanner.server.SERVER_ZONE
import dev.tobins.mealplanner.server.serverDay
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

/** A regular price of one pack of a product, and when it was seen: from a price lookup or an offer's price before. */
data class RegularPrice(val packPrice: Double, val firstSeen: Instant, val lastSeen: Instant)

/**
 * The regular price of an offer's product went up shortly before the offer began: from [before] (the
 * lowest in the [REFERENCE_DAYS] before it, last seen on [beforeSeen]) to [now].
 */
data class RegularRaise(val before: Double, val beforeSeen: LocalDate, val now: Double)

/**
 * Days before an offer begins in which its regular price is looked for, the lowest of them being the price
 * the deal is measured against. Thirty, as the EU asks of a shop announcing a reduction: the price before
 * must be the lowest it charged in at least the 30 days before (Directive 98/6/EC, article 6a). A price
 * raised within that time, and then "reduced", is measured against what it cost before the raise.
 */
const val REFERENCE_DAYS = 30L

/**
 * The raise of [offer]'s regular price in the [REFERENCE_DAYS] before it began, or null when it was not
 * raised by more than [TOLERANCE], the prices to tell are missing, or the offer is a [group offer][isGroupOffer].
 * [regular] are the product's regular prices, all of the same pack. The price now is the store's price before
 * where the offer gives one, else the last one seen.
 */
fun regularRaise(offer: CurrentOffer, regular: List<RegularPrice>): RegularRaise? {
    if (isGroupOffer(offer.offer.title)) return null
    val start = offer.validFrom.atStartOfDay(SERVER_ZONE).toInstant()
    val windowStart = offer.validFrom.minusDays(REFERENCE_DAYS).atStartOfDay(SERVER_ZONE).toInstant()
    // The lowest; of equal ones the one seen last, so that the answer does not hang on the order of the rows.
    val before = regular.filter { it.firstSeen < start && it.lastSeen >= windowStart }
        .minWithOrNull(compareBy<RegularPrice> { it.packPrice }.thenByDescending { it.lastSeen }) ?: return null
    val now = offer.originalPrice ?: regular.maxWithOrNull(compareBy({ it.lastSeen }, { it.packPrice }))?.packPrice ?: return null
    if (now <= before.packPrice * (1 + TOLERANCE)) return null
    // Last seen before the offer began: a price before dated on past that day was not the price "before" any more.
    return RegularRaise(before.packPrice, minOf(serverDay(before.lastSeen), offer.validFrom.minusDays(1)), now)
}

/**
 * Whether a regular price looked up for an ingredient ([product], [packSize]) is the price of an offer's own
 * product ([offerTitle], [offerPack]). The lookup finds a product per ingredient, which is often not the one
 * on offer, and only the same product's price says anything about a raise. AH writes the same product as
 * "AH Snoepgroente tomaat" with "500 g" in its search and as "AH Snoepgroente tomaat 500 gram" on offer, so
 * the offer's title may add a size to the product's name, and nothing else; the sizes must then be equal.
 */
fun isSameProduct(product: String, packSize: String?, offerTitle: String, offerPack: String?): Boolean {
    val name = normalised(product)
    val title = normalised(offerTitle)
    val rest = when {
        title == name -> ""
        title.startsWith("$name ") -> title.removePrefix("$name ")
        else -> return false
    }
    if (rest.split(' ').any { it.isNotEmpty() && !sizeWord.matches(it) }) return false
    val offered = sizeOf(offerPack) ?: sizeOf(rest) ?: return false
    val looked = sizeOf(packSize) ?: return false
    return offered.first == looked.first && abs(offered.second - looked.second) <= looked.second * SIZE_TOLERANCE
}

/**
 * Whether an offer is for a group of products under one title, as AH and Jumbo write it: "Alle AH Gerookte zalm",
 * "Alle Santa Maria, La Morena en Knorr wraps". AH gives such an offer the price before of whichever product of
 * the group it shows, so two of them a few weeks apart can differ without any price having been raised: their
 * prices before tell nothing about a raise. A title without a size is no group: AH writes most single products
 * so ("AH Ananas zonder kroon"), and their price before is the only sign of a raise they have.
 */
fun isGroupOffer(title: String): Boolean = normalised(title).startsWith("alle ")

/** Words that only state a size: numbers, units, "x" between them, "ca." for about. */
private val sizeWord = Regex("""\d+(\.\d+)?|x|ca\.?|g|gr|gram|kg|kilo|kilogram|ml|cl|l|lt|liter|st|stuk|stuks""")

/** A pack's size as a unit and an amount, by the reading the offers' prices per unit use, or null. */
private fun sizeOf(text: String?): Pair<String, Double>? {
    if (text.isNullOrBlank()) return null
    if (PackSize.isVolume(text)) return PackSize.millilitres(text)?.let { "ml" to it }
    PackSize.grams(text)?.let { return "g" to it }
    return PackSize.pieces(text)?.let { "pieces" to it.toDouble() }
}

/** Sizes as written are rounded ("ca. 450 g"); one per cent apart is the same pack. */
private const val SIZE_TOLERANCE = 0.01
