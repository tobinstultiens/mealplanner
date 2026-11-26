package dev.tobins.mealplanner.server.products

import dev.tobins.mealplanner.api.FoundProduct
import dev.tobins.mealplanner.api.LabelSource
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.api.ProductHit
import dev.tobins.mealplanner.nutrition.Salt
import dev.tobins.mealplanner.server.offers.AhApp
import dev.tobins.mealplanner.server.offers.ok
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * AH's products through its app API (issue 354; `docs/data-sources.md`, "Nutrition: a product's label"): the product
 * search for a name, the barcode route for the id of a barcode, and the app's GraphQL endpoint for a product's table,
 * which no REST route gives any more. Throws when AH does not answer or answers in a form that is not understood.
 */
class AhProducts(private val ah: AhApp) {
    /** The products AH finds for [text], at most [SEARCH_SIZE]. */
    suspend fun search(text: String): List<ProductHit> {
        val body = ah.get("/mobile-services/product/search/v2") {
            parameter("query", text)
            parameter("sortOn", "RELEVANCE")
            parameter("size", SEARCH_SIZE)
        }.ok().bodyAsText()
        return AhProductReader.hits(body)
    }

    /** The product of [barcode] (digits only), as a search lists it; null when AH has none (404). */
    suspend fun barcode(barcode: String): ProductHit? {
        require(barcode.isDigits()) { "A barcode is digits only" }
        val response = ah.get("/mobile-services/product/search/v1/gtin/$barcode")
        if (response.status == HttpStatusCode.NotFound) return null
        return AhProductReader.hit(AhProductReader.json.parseToJsonElement(response.ok().bodyAsText()))
            ?: error("AH's answer for a barcode holds no product")
    }

    /**
     * Product [id] with its table; null when AH has no such product. [packOf] says it is one item of a multi-pack sold
     * as one, which the answer says on: AH cannot tell from the item itself.
     */
    suspend fun product(id: Int, packOf: Int? = null): FoundProduct? {
        val body = buildJsonObject {
            put("query", PRODUCT_QUERY)
            putJsonObject("variables") { put("productId", id) }
        }
        val answer = ah.post("/graphql") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.ok().bodyAsText()
        return AhProductReader.product(answer, packOf)
    }

    companion object {
        const val SEARCH_SIZE = 15

        /** Only what is read; the fields were confirmed on 26 November 2026 (issue 339, step 0). */
        const val PRODUCT_QUERY = "query FetchProduct(\$productId: Int!) { product(id: \$productId) { id title brand salesUnitSize " +
            "tradeItem { contents { netContents } nutritions { basisQuantity nutrients { type value } } } } }"
    }
}

/** Reads AH's answers: pure, so that they are tested on recorded ones. */
object AhProductReader {
    internal val json = Json { ignoreUnknownKeys = true }

    private val NO_VALUES = Nutrition(null, null, null, null, null, null, null, null)

    /** The products of a search answer, each as [hit] reads it. */
    fun hits(body: String): List<ProductHit> =
        json.parseToJsonElement(body).jsonObject["products"]?.jsonArray.orEmpty().mapNotNull(::hit)

    /**
     * A product as the search and the barcode route list it: of a multi-pack sold as one (`isVirtualBundle`, which has no
     * table of its own) the id of its first item and how many of it the pack holds.
     */
    fun hit(element: JsonElement): ProductHit? {
        val product = element as? JsonObject ?: return null
        val id = product.long("webshopId") ?: return null
        val title = product.text("title") ?: return null
        val item = product["virtualBundleItems"]?.takeIf { product.flag("isVirtualBundle") && it !is JsonNull }
            ?.jsonArray?.firstOrNull() as? JsonObject
        return ProductHit(
            id = item?.long("productId") ?: id,
            name = title,
            brand = product.text("brand"),
            netContent = product.text("salesUnitSize"),
            packOf = item?.let { it["quantity"]?.jsonPrimitive?.intOrNull ?: 1 },
        )
    }

    /**
     * The product of a GraphQL answer with its table per 100 g or 100 ml, salt as sodium; null for `data: null`, which is
     * how AH answers an id it does not have. A product without such a table (a bundle, one per portion only) comes with
     * no basis and no values: a table on another basis is not converted.
     */
    fun product(body: String, packOf: Int? = null): FoundProduct? {
        val answer = json.parseToJsonElement(body).jsonObject
        check("data" in answer) { "AH's GraphQL answer has no data: ${answer["errors"] ?: body.take(200)}" }
        val product = answer["data"].objectOrNull()?.get("product").objectOrNull() ?: return null
        val id = product.long("id") ?: error("AH's product has no id")
        val trade = product["tradeItem"].objectOrNull()
        val table = trade?.get("nutritions")?.takeIf { it !is JsonNull }?.jsonArray.orEmpty().mapNotNull { it.objectOrNull() }
            .firstOrNull { it.text("basisQuantity")?.let(::basisOf) != null }
        val netContents = trade?.get("contents").objectOrNull()?.get("netContents")?.takeIf { it !is JsonNull }?.jsonArray.orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull }
        val values = table?.let(::values)
        val pack = packOf(netContents + listOfNotNull(product.text("salesUnitSize")))
        return FoundProduct(
            source = LabelSource(LabelSource.Origin.AH, id.toString()).toString(),
            name = product.text("title") ?: error("AH's product has no title"),
            brand = product.text("brand"),
            basis = table?.text("basisQuantity")?.let(::basisOf),
            per100 = values?.first ?: NO_VALUES,
            unread = values?.second.orEmpty(),
            pack = if (packOf != null) pack?.copy(single = true) else pack,
            packOf = packOf,
        )
    }

    /** The eight values of a table, by their GS1 nutrient codes, and the names of those given that did not read. */
    private fun values(table: JsonObject): Pair<Nutrition, List<String>> {
        val given = table["nutrients"]?.takeIf { it !is JsonNull }?.jsonArray.orEmpty().mapNotNull { it.objectOrNull() }
            .mapNotNull { row -> row.text("type")?.let { it to row.text("value").orEmpty() } }
            .distinctBy { it.first }.toMap()
        val unread = mutableListOf<String>()
        fun read(type: String, field: String, parse: (String) -> Double?): Double? {
            val text = given[type] ?: return null
            return parse(text).also { if (it == null) unread += field }
        }
        val nutrition = Nutrition(
            kcal = read("ENER-", "kcal", ::labelKcal),
            proteinG = read("PRO-", "proteinG", ::labelGrams),
            fatG = read("FAT", "fatG", ::labelGrams),
            saturatedFatG = read("FASAT", "saturatedFatG", ::labelGrams),
            carbsG = read("CHOAVL", "carbsG", ::labelGrams),
            sugarG = read("SUGAR-", "sugarG", ::labelGrams),
            fibreG = read("FIBTG", "fibreG", ::labelGrams),
            sodiumMg = read("SALTEQ", "sodiumMg", ::labelGrams)?.let(Salt::sodiumMg),
        )
        return nutrition to unread
    }

    private fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.flag(key: String): Boolean = text(key) == "true"
}
