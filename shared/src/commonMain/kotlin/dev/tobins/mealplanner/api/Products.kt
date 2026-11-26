package dev.tobins.mealplanner.api

import kotlinx.serialization.Serializable

/*
 * Looking a product's label up (issue 354): the server asks AH, and for a barcode AH cannot answer Open Food Facts; the
 * app asks only the server. An answer is a suggestion that fills the product's form: nothing is saved by it.
 */

/** `GET /api/products/search?q=`: AH's products for a name, to pick one of; [problems] as in [ProductLookup]. */
@Serializable
data class ProductSearch(
    val products: List<ProductHit> = emptyList(),
    val problems: List<String> = emptyList(),
)

/** One product a search found: what helps to pick it, and the id its values are looked up by. */
@Serializable
data class ProductHit(
    /** AH's product id, for `GET /api/products/ah/{id}`; of a multi-pack sold as one ([packOf]), that of its single item. */
    val id: Long,
    val name: String,
    val brand: String? = null,
    /** The pack as AH writes it: `50 g`, `1 l`, `4 stuks`. */
    val netContent: String? = null,
    /** A multi-pack sold as one (a "4-pack"): how many items it holds, [id] being one of them; null for a single product. */
    val packOf: Int? = null,
)

/**
 * `GET /api/products/ah/{id}` and `GET /api/products/barcode/{code}`: the product found, null when no source has it or
 * none could be asked; and what went wrong on the way ([LookupProblem]), which the form says. Both empty: not found.
 */
@Serializable
data class ProductLookup(
    val product: FoundProduct? = null,
    val problems: List<String> = emptyList(),
)

/**
 * A product as a source gives it, to fill the form with. Values are as the source gives them, salt as sodium ([per100],
 * [dev.tobins.mealplanner.nutrition.Salt]); one it does not give is null, and one it gives in a form that cannot be read
 * is null and named in [unread]. [basis] is null when the source has no table per 100 g or 100 ml.
 */
@Serializable
data class FoundProduct(
    /** What `LabelNutrition.source` keeps once it is saved ([LabelSource]): `ah:61758`, `off:8712800035626`. */
    val source: String,
    val name: String,
    val brand: String? = null,
    val basis: FoodBasis? = null,
    val per100: Nutrition = Nutrition(null, null, null, null, null, null, null, null),
    /** The values given but not read, by the name of their field of [Nutrition] (`fibreG`, salt as `sodiumMg`). */
    val unread: List<String> = emptyList(),
    /** The pack's net content, to offer as a portion; null when it cannot be read. */
    val pack: PackSize? = null,
    /** Of a multi-pack sold as one: how many items it holds; the values and [pack] are those of one item. */
    val packOf: Int? = null,
)

/** A pack's net content: [grams] or [ml]; [single] when it is one item of a multi-pack ("6 x 250 ml" is 250 ml). */
@Serializable
data class PackSize(
    val grams: Double? = null,
    val ml: Double? = null,
    val single: Boolean = false,
)

/**
 * What can go wrong in a lookup, as [ProductLookup.problems] and [ProductSearch.problems] name it. Codes rather than an
 * enum, so that an app that does not know a later one still reads the answer (it says something went wrong).
 */
object LookupProblem {
    /** AH did not answer, or answered in a form that is not understood. */
    const val AH_FAILED = "ah_failed"

    /** AH has the product but no table per 100 g or 100 ml with its energy (a bundle's, a table per portion only). */
    const val AH_NO_TABLE = "ah_no_table"

    /** Open Food Facts did not answer, or answered in a form that is not understood. */
    const val OFF_FAILED = "off_failed"

    /** Open Food Facts was asked as often as its terms allow this minute; try again in a moment. */
    const val OFF_BUSY = "off_busy"

    /** Open Food Facts would have been asked, but the server has no contact for it (`MEALPLANNER_OPENFOODFACTS_CONTACT`). */
    const val OFF_NOT_CONFIGURED = "off_not_configured"
}

/**
 * Where a label's values came from, as `LabelNutrition.source` keeps it: `ah:<product id>` or `off:<barcode>`, followed by
 * `;changed` once a value was changed by hand after the lookup. Text of another form (none is written) reads as null.
 */
data class LabelSource(val origin: Origin, val reference: String, val changedByHand: Boolean = false) {
    enum class Origin(val key: String) { AH("ah"), OPEN_FOOD_FACTS("off") }

    fun changed(): LabelSource = copy(changedByHand = true)

    override fun toString(): String = "${origin.key}:$reference" + if (changedByHand) CHANGED else ""

    companion object {
        private const val CHANGED = ";changed"

        fun parse(text: String?): LabelSource? {
            val changed = text?.endsWith(CHANGED) == true
            val (key, reference) = text?.removeSuffix(CHANGED)?.split(':', limit = 2)?.takeIf { it.size == 2 } ?: return null
            val origin = Origin.entries.firstOrNull { it.key == key } ?: return null
            return reference.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.let { LabelSource(origin, it, changed) }
        }
    }
}
