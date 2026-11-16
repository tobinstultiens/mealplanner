package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.size
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.pricing.PackSize
import dev.tobins.mealplanner.pricing.PacksToBuy
import kotlin.math.ceil
import kotlin.math.roundToInt

/** `450 g`, `1.4 kg`: a weight the way the list writes it. */
fun weightText(grams: Double, locale: FormatLocale): String =
    if (grams >= 1000) "${formatAmount(grams / 1000, locale)} kg" else "${grams.roundToInt()} g"

/**
 * What to pick up for this item when bought as [offer]: [PackSize.toBuy] for the weight the recipes
 * need, or their pieces. What could not be weighed is not guessed at, so an item with other amounts
 * (or without a weight) gets nothing.
 */
fun ShoppingItem.packsToBuy(offer: Offer): PacksToBuy? = PackSize.toBuy(offer, grams, if (otherAmounts.isEmpty()) pieces else null)

/** `2 × 700 g`, `1 × 500 g · deal needs 2`, `2 × 3 pieces`: the packs to take and, when the deal wants more than the amount needs, how many it wants. */
fun PacksToBuy.text(strings: Strings): String {
    val size = packGrams?.let { weightText(it, strings.locale) } ?: strings.plural(Plural.PACK_PIECES, packPieces ?: 1)
    return if (dealFits) strings(Msg.PACKS_TO_TAKE, packs, size) else strings(Msg.PACKS_DEAL_NEEDS, packs, size, dealPacks)
}

/** `3 ×`: a count of pieces, rounded up, as nobody buys 1.5 onions. */
private fun piecesText(pieces: Double): String = "${ceil(pieces - 1e-9).toInt()} ×"

/**
 * `3 × · 330 g`, `450 g`, `2 ×`, `200 g + 2 × + 1 bag`, `250 ml`, or empty when the recipes give no
 * quantity. What could not be weighed ([ShoppingItem.otherAmounts]) follows the known weight, with
 * the count of the ingredient itself between ([ShoppingItem.ownPieces]); the piece count is left out
 * then, as it would count those amounts a second time.
 */
fun ShoppingItem.quantityText(locale: FormatLocale): String {
    val weight = (grams ?: knownGrams)?.let { weightText(it, locale) }
    if (otherAmounts.isNotEmpty()) return (listOfNotNull(weight, ownPieces?.let(::piecesText)) + otherAmounts).joinToString(" + ")
    return listOfNotNull(pieces?.let(::piecesText), weight).joinToString(" · ")
}

/** Less than a gram still to buy is nothing to buy, as the server drops a stock once less than a gram is left. */
internal const val SCRAP_GRAMS = 1.0

/** No meal still to cook needs this row, whatever its unit: the server says the meals to come need exactly 0 ([ShoppingItem.toComeGrams]). */
internal val ShoppingItem.mealsDone: Boolean get() = toComeGrams == 0.0

/** Some of the row's meals are done, so the meals to come need less weight than the week ([ShoppingItem.grams]). */
private val ShoppingItem.partlyDone: Boolean
    get() = toComeGrams.let { toCome -> toCome != null && grams.let { it != null && it - toCome >= SCRAP_GRAMS } }

/**
 * The weight still to buy of this row, ticked or not: what the meals to come need
 * ([ShoppingItem.toComeGrams]) less what is on hand ([ShoppingItem.onHandGrams]), at least 0; 0 when
 * no meal to come needs it. Null when either is not known. On a ticked row it doesn't count what
 * was bought, which only the tick knows: there it is only a ceiling ([moreNeeded]) and the saving's share.
 */
internal fun ShoppingItem.leftToBuyGrams(): Double? {
    val toCome = toComeGrams ?: return null
    if (toCome == 0.0) return 0.0
    val left = if (onHand) onHandGrams ?: return null else 0.0
    return (toCome - left).coerceAtLeast(0.0)
}

/**
 * Nothing of this row is left to buy, ticked or not: no meal to come needs it ([mealsDone]), or the
 * stock covers what they need to within [SCRAP_GRAMS]. The scrap only forgives what the stock leaves
 * over: a need of under a gram with nothing at home (a pinch of oregano) is still to buy.
 */
internal fun ShoppingItem.nothingToBuy(): Boolean {
    if (mealsDone) return true
    val toBuy = leftToBuyGrams() ?: return false
    return onHand && toBuy < SCRAP_GRAMS
}

/**
 * Not ticked, and nothing of it is left to buy ([nothingToBuy]). Such a row is no item to buy: the
 * progress line, the store groups and their counts and the savings leave it out, and shopping mode
 * puts it with the done ones. It stays on the list, so it can still be ticked.
 */
internal val ShoppingItem.covered: Boolean get() = !checked && nothingToBuy()

/**
 * The row's amount column: the week's need ([quantityText]), but on a row not ticked yet whose meals
 * are partly done what the meals to come need, so it adds up with the line under the name
 * ([toBuyText]), and nothing once no meal to come needs it. A ticked row keeps the week's need, which
 * its "more" line ([moreNeeded]) counts from. The pieces are those of the meals to come
 * ([ShoppingItem.toComePieces]), left out when the server does not say them or other amounts follow.
 */
internal fun ShoppingItem.amountText(locale: FormatLocale): String = when {
    checked -> quantityText(locale)
    mealsDone -> ""
    partlyDone -> listOfNotNull(toComePieces?.takeIf { otherAmounts.isEmpty() }?.let(::piecesText), weightText(toComeGrams ?: 0.0, locale)).joinToString(" · ")
    else -> quantityText(locale)
}

/** The meals to name as the row's source: those still to cook when the server says them, else the week's. */
internal val ShoppingItem.sourceMeals: List<String> get() = toComeRecipes ?: recipes

/**
 * What a row not ticked yet says under its name of what is at home and still to buy: `300 g on
 * hand, buy 200 g`, `600 g on hand: enough` when that covers it, or `300 g on hand` when the need's
 * weight is not known. Once meals are done, the amount column is what the meals to come need
 * ([amountText]), and the line says so: `176 g on hand, buy 504 g for the meals to come` (next to
 * 680 g), `For the meals to come` without a stock, and `Nothing to buy: the meals that need it are
 * done` only when none of them is still to come. Null when nothing lessens the need, and when on
 * hand in an amount nobody knows with no meal done (the name then asks "(on hand?)", [nameText],
 * never saying none is there). Null on a ticked row too: its buying is done, or its "more" line
 * ([moreNeeded]) says the one amount still to buy, which a second amount would only make the reader
 * subtract from again.
 */
internal fun ShoppingItem.toBuyText(strings: Strings): String? {
    if (checked) return null
    if (mealsDone) return strings(Msg.NOTHING_TO_COME)
    val toCome = partlyDone
    val left = onHandGrams?.takeIf { onHand } ?: return if (toCome) strings(Msg.FOR_MEALS_TO_COME) else null
    val amount = weightText(left, strings.locale)
    val toBuy = leftToBuyGrams() ?: return strings(Msg.ON_HAND_AMOUNT, amount)
    return if (toBuy < SCRAP_GRAMS) {
        strings(if (toCome) Msg.ON_HAND_ENOUGH_TO_COME else Msg.ON_HAND_ENOUGH, amount)
    } else {
        strings(if (toCome) Msg.ON_HAND_BUY_TO_COME else Msg.ON_HAND_BUY, amount, weightText(toBuy, strings.locale))
    }
}

/** The row's name, asking "(on hand?)" when it is on hand in an amount nobody knows, as before amounts were kept. */
internal fun ShoppingItem.nameText(strings: Strings): String =
    if (onHand && onHandGrams == null) strings(Msg.ON_HAND_MAYBE, name) else name

/**
 * [packsToBuy] for what is left to buy ([leftToBuyGrams]), ticked or not: none when nothing is
 * ([nothingToBuy]); for that weight alone when it is less than the whole need, pieces then left out
 * as a weight says nothing about them; else for the whole need. What the savings check a deal
 * against ([savingOf]).
 */
internal fun ShoppingItem.packsLeftToBuy(offer: Offer): PacksToBuy? {
    if (nothingToBuy()) return null
    val toBuy = leftToBuyGrams()
    if (toBuy == null || grams.let { it == null || it - toBuy < SCRAP_GRAMS }) return packsToBuy(offer)
    return PackSize.toBuy(offer, toBuy, null)
}

/**
 * The packs line of a row not ticked yet: [packsLeftToBuy]. A ticked row has none: what was bought
 * is not known in packs, and the whole need would count what was on hand as bought.
 */
internal fun ShoppingItem.packsStillToBuy(offer: Offer): PacksToBuy? = if (checked) null else packsLeftToBuy(offer)

/** `800 g` or `3 ×`: what was bought of a row, empty when the tick remembers no amount. */
internal fun ShoppingItem.boughtText(locale: FormatLocale): String =
    listOfNotNull(boughtPieces?.let { "${ceil(it - 1e-9).toInt()} ×" }, boughtGrams?.let { weightText(it, locale) }).joinToString(" · ")
