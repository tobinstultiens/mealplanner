package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.LabelSource
import dev.tobins.mealplanner.api.LookupProblem
import dev.tobins.mealplanner.api.ProductHit
import dev.tobins.mealplanner.api.ProductLookup

/*
 * What the product form says of a lookup (issue 354): the server asks AH and Open Food Facts, and these are its answers
 * in the app's words. Kept apart from the screen so that they are tested without drawing it.
 */

/** Open Food Facts' address, given with its values as its licence (ODbL) asks. */
const val OPEN_FOOD_FACTS_URL = "https://world.openfoodfacts.org"

/** [text] as a barcode to look up (8 to 14 digits, spaces left out), or null when it is a name to search for. */
fun barcodeIn(text: String): String? = text.filterNot { it == ' ' }.takeIf { code -> code.length in 8..14 && code.all { it in '0'..'9' } }

/** What helps to pick [this] product: its brand, its pack, and of a multi-pack how many it holds. */
fun ProductHit.detail(t: Strings): String =
    listOfNotNull(brand, netContent, packOf?.let { t(Msg.LOOKUP_PACK_OF, it) }).joinToString(" · ")

/** The name of a lookup's source ([LabelSource]): "AH" or "Open Food Facts"; null for values typed or of a source the app does not know. */
fun sourceName(source: String?): String? = when (LabelSource.parse(source)?.origin) {
    LabelSource.Origin.AH -> "AH"
    LabelSource.Origin.OPEN_FOOD_FACTS -> "Open Food Facts"
    null -> null
}

/** `AH`, or `AH, changed by hand` once a value was changed after the lookup; null as [sourceName] is. */
fun sourceLabel(source: String?, t: Strings): String? {
    val name = sourceName(source) ?: return null
    return if (LabelSource.parse(source)?.changedByHand == true) t(Msg.PRODUCT_CHANGED_BY_HAND, name) else name
}

/** `Values from AH`, or `Values from AH, changed by hand` ([sourceLabel]); another source's text as it is. */
fun sourceLine(source: String, t: Strings): String = t(Msg.PRODUCT_VALUES_FROM, sourceLabel(source, t) ?: source)

/** Whether values from [source] are Open Food Facts', which are shown with its licence. */
fun fromOpenFoodFacts(source: String?): Boolean = LabelSource.parse(source)?.origin == LabelSource.Origin.OPEN_FOOD_FACTS

/** A server's name of a value ([dev.tobins.mealplanner.api.FoundProduct.unread]) as the form's value; null for one it does not know. */
private fun labelValueOf(field: String): LabelValue? = when (field) {
    "kcal" -> LabelValue.ENERGY
    "proteinG" -> LabelValue.PROTEIN
    "fatG" -> LabelValue.FAT
    "saturatedFatG" -> LabelValue.SATURATED_FAT
    "carbsG" -> LabelValue.CARBS
    "sugarG" -> LabelValue.SUGARS
    "fibreG" -> LabelValue.FIBRE
    "sodiumMg" -> LabelValue.SALT
    else -> null
}

/** [this] value as a sentence names it: "fibre", "saturated fat". */
private fun LabelValue.lowerName(t: Strings): String = when (this) {
    LabelValue.SATURATED_FAT -> t(Msg.NUTRIENT_SATURATED_FAT)
    LabelValue.SUGARS -> t(Msg.NUTRIENT_SUGARS)
    else -> t(msg).lowercase()
}

/** A source's problem in the app's words; one it does not know as such. */
fun problemText(problem: String, t: Strings): String = t(
    when (problem) {
        LookupProblem.AH_FAILED -> Msg.LOOKUP_AH_FAILED
        LookupProblem.AH_NO_TABLE -> Msg.LOOKUP_AH_NO_TABLE
        LookupProblem.OFF_FAILED -> Msg.LOOKUP_OFF_FAILED
        LookupProblem.OFF_BUSY -> Msg.LOOKUP_OFF_BUSY
        LookupProblem.OFF_NOT_CONFIGURED -> Msg.LOOKUP_OFF_NOT_CONFIGURED
        else -> Msg.LOOKUP_OTHER_PROBLEM
    },
)

/**
 * What the form says after a lookup: what went wrong at a source, and of the product found the values not given or not
 * read, and whether they are of one item of a multi-pack. When it found nothing to count by, that the values are typed.
 */
fun lookupNotes(answer: ProductLookup, t: Strings): List<String> = buildList {
    val product = answer.product
    if (product == null && answer.problems.isEmpty()) add(t(Msg.LOOKUP_NOT_FOUND))
    answer.problems.forEach { add(problemText(it, t)) }
    if (product?.basis != null) {
        val unread = product.unread.mapNotNull(::labelValueOf).toSet()
        val values = product.per100
        val missing = mapOf(
            LabelValue.ENERGY to values.kcal, LabelValue.FAT to values.fatG, LabelValue.SATURATED_FAT to values.saturatedFatG,
            LabelValue.CARBS to values.carbsG, LabelValue.SUGARS to values.sugarG, LabelValue.FIBRE to values.fibreG,
            LabelValue.PROTEIN to values.proteinG, LabelValue.SALT to values.sodiumMg,
        ).filter { (value, number) -> number == null && value !in unread }.keys
        if (missing.isNotEmpty()) add(t(Msg.LOOKUP_NOT_GIVEN, missing.joinToString { it.lowerName(t) }))
        if (unread.isNotEmpty()) add(t(Msg.LOOKUP_UNREAD, LabelValue.entries.filter { it in unread }.joinToString { it.lowerName(t) }))
    }
    product?.packOf?.let { add(t(Msg.LOOKUP_ONE_OF_PACK, it)) }
    if (product?.basis == null) add(t(Msg.LOOKUP_TYPE_VALUES))
}
