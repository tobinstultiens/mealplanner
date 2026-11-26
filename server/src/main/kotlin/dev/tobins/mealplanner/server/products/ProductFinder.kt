package dev.tobins.mealplanner.server.products

import dev.tobins.mealplanner.api.FoundProduct
import dev.tobins.mealplanner.api.LookupProblem
import dev.tobins.mealplanner.api.ProductLookup
import dev.tobins.mealplanner.api.ProductSearch
import dev.tobins.mealplanner.api.hasValues
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * Looks a product's label up for the product's form (issue 354): by name and by AH's id at AH, by barcode at AH and then,
 * when AH does not have it or its table cannot be read, at Open Food Facts ([off]; null when the server has no contact
 * for it, and then it is never asked). Saves nothing and reads nothing of an account. A source that fails is said in the
 * answer's problems, never as a failed request: the form is still filled with what there is.
 *
 * What the app sends is checked before anything is asked ([searchText], [productId], [barcodeOf]): a request that is not
 * one of those is refused with a 400, and only digits or a query parameter reach a source.
 */
class ProductFinder(private val ah: AhProducts, private val off: OpenFoodFacts?) {
    private val log = LoggerFactory.getLogger(ProductFinder::class.java)

    suspend fun search(text: String?): ProductSearch {
        val query = searchText(text)
        return attempt("AH search") { ProductSearch(ah.search(query)) } ?: ProductSearch(problems = listOf(LookupProblem.AH_FAILED))
    }

    /** AH's product [id], one item of a multi-pack of [packOf] when given (as [ProductSearch] lists it). */
    suspend fun ahProduct(id: String?, packOf: String?): ProductLookup {
        val productId = productId(id)
        val count = packOf?.let(::packCount)
        val found = attempt("AH product") { Found(ah.product(productId, count)) } ?: return ProductLookup(problems = listOf(LookupProblem.AH_FAILED))
        val product = found.product ?: return ProductLookup()
        return ProductLookup(product, listOfNotNull(LookupProblem.AH_NO_TABLE.takeUnless { product.readable }))
    }

    /**
     * The product of a barcode: AH's when it has one with a table that reads, else Open Food Facts' when it has one, else
     * AH's without values (its name and pack still help); null when neither has it.
     */
    suspend fun barcode(code: String?): ProductLookup {
        val barcode = barcodeOf(code)
        val problems = mutableListOf<String>()
        val atAh = attempt("AH barcode") { Found(ah.barcode(barcode)?.let { ah.product(Math.toIntExact(it.id), it.packOf) }) }
        if (atAh == null) problems += LookupProblem.AH_FAILED
        val ahProduct = atAh?.product
        if (ahProduct?.readable == true) return ProductLookup(ahProduct)
        if (ahProduct != null) problems += LookupProblem.AH_NO_TABLE
        val (offProduct, offProblem) = openFoodFacts(barcode)
        offProblem?.let { problems += it }
        // Open Food Facts' without values helps no more than AH's without values, whose name is the store's.
        return ProductLookup(offProduct?.takeIf { it.hasValues || ahProduct == null } ?: ahProduct, problems)
    }

    /** Open Food Facts' product of [barcode], or none; and what kept it from answering, if anything did. */
    private suspend fun openFoodFacts(barcode: String): Pair<FoundProduct?, String?> {
        val source = off ?: return null to LookupProblem.OFF_NOT_CONFIGURED
        return when (val answer = attempt("Open Food Facts") { source.byBarcode(barcode) }) {
            null -> null to LookupProblem.OFF_FAILED
            is OpenFoodFacts.Answer.Found -> answer.product to null
            OpenFoodFacts.Answer.NotFound -> null to null
            OpenFoodFacts.Answer.Busy -> null to LookupProblem.OFF_BUSY
        }
    }

    private val FoundProduct.readable: Boolean get() = basis != null && per100.kcal != null

    /** Something found or not, told apart from a failure ([attempt] answers null for that). */
    private class Found(val product: FoundProduct?)

    /** [ask]'s answer, or null when it failed, which is logged; a cancelled call (the app went away) is passed on. */
    private suspend fun <T> attempt(what: String, ask: suspend () -> T): T? = try {
        ask()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("$what failed: ${e.message}")
        null
    }

    companion object {
        const val MAX_SEARCH = 80

        /** The text of a search: 2 to [MAX_SEARCH] characters once trimmed. */
        fun searchText(text: String?): String {
            val query = text?.trim().orEmpty()
            require(query.length in 2..MAX_SEARCH) { "A product search needs 2 to $MAX_SEARCH characters" }
            return query
        }

        /** AH's id of a product: digits only, as AH's ids are (a whole number that fits its GraphQL `Int`). */
        fun productId(text: String?): Int =
            text?.takeIf { it.length in 1..9 && it.isDigits() }?.toInt() ?: throw IllegalArgumentException("A product id is a number")

        private fun packCount(text: String): Int =
            text.takeIf { it.length in 1..2 && it.isDigits() }?.toInt()?.takeIf { it >= 1 }
                ?: throw IllegalArgumentException("packOf is a number from 1 to 99")

        /**
         * A barcode as typed: 8 to 14 digits. As AH's barcode route takes it: a 12-digit UPC-A with a leading 0 and a
         * 14-digit code without its leading 0, both the 13-digit EAN of the same product.
         */
        fun barcodeOf(text: String?): String {
            val code = text?.takeIf { it.length in 8..14 && it.isDigits() } ?: throw IllegalArgumentException("A barcode is 8 to 14 digits")
            return when {
                code.length == 12 -> "0$code"
                code.length == 14 && code.startsWith('0') -> code.drop(1)
                else -> code
            }
        }
    }
}
