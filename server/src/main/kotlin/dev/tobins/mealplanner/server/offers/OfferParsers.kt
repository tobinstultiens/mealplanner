package dev.tobins.mealplanner.server.offers

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.Store
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId

/*
 * Parsers for the three stores' offer data. None of it is an official API, so every
 * parser is lenient: an entry it cannot read is skipped rather than failing the batch.
 * See docs/data-sources.md for where each format comes from.
 */

private val json = Json { ignoreUnknownKeys = true }

private val JsonElement?.obj: JsonObject? get() = this as? JsonObject
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

/** "voor 4,99" or "VOOR 3.49" -> 4.99; anything else (multi-buys, percentages) -> null. */
internal fun singlePrice(text: String?): Double? =
    text?.trim()?.let { Regex("(?i)^voor\\s+(\\d+)[,.](\\d{2})$").find(it) }
        ?.let { "${it.groupValues[1]}.${it.groupValues[2]}".toDouble() }

object AhParser {
    /** Bonus categories that hold no food. */
    private val nonFood = setOf(
        "Drogisterij", "Huishouden", "Baby en kind", "Gezondheid en sport", "Koken, tafelen, vrije tijd", "AH Voordeelshop",
    )

    /**
     * Section paths (relative to `mobile-services/`) of the current week's national food
     * offers, from the bonus metadata. The spotlight section only repeats offers listed
     * elsewhere, and partner shops (Etos, Gall & Gall) are not groceries.
     */
    fun sectionPaths(metadata: String): List<String> {
        val period = json.parseToJsonElement(metadata).jsonObject["periods"]?.jsonArray?.firstOrNull()?.obj ?: return emptyList()
        return period["tabs"]?.jsonArray.orEmpty()
            .flatMap { it.obj?.get("urlMetadataList")?.jsonArray.orEmpty() }
            .mapNotNull { it.obj }
            .filter { it.str("bonusType") == "NATIONAL" && it.str("description") !in nonFood }
            .mapNotNull { it.str("url") }
            .distinct()
    }

    /** Offers of one bonus section. An entry is a single product or a group of products. */
    fun section(body: String): List<Offer> =
        json.parseToJsonElement(body).jsonObject["bonusGroupOrProducts"]?.jsonArray.orEmpty().mapNotNull { entry ->
            entry.obj?.get("product")?.obj?.let(::product) ?: entry.obj?.get("bonusGroup")?.obj?.let(::group)
        }

    private fun product(p: JsonObject): Offer? {
        val deal = p.str("bonusMechanism") ?: firstLabel(p)
        // Small multi-pack discounts are flagged as bonus but save next to nothing.
        if (deal?.contains("volume voordeel", ignoreCase = true) == true) return null
        return Offer(
            store = Store.AH,
            title = p.str("title") ?: return null,
            deal = deal,
            price = p.num("currentPrice") ?: singlePrice(deal),
            originalPrice = p.num("priceBeforeBonus"),
            packSize = p.str("salesUnitSize"),
            category = p.str("mainCategory"),
            validFrom = p.str("bonusStartDate") ?: return null,
            validUntil = p.str("bonusEndDate") ?: return null,
            url = (p["webshopId"] as? JsonPrimitive)?.longOrNull?.let { "https://www.ah.nl/producten/product/wi$it" },
        )
    }

    private fun group(g: JsonObject): Offer? = Offer(
        store = Store.AH,
        title = g.str("segmentDescription") ?: return null,
        deal = g.str("discountDescription")?.lowercase()?.replaceFirstChar { it.uppercase() } ?: firstLabel(g),
        category = g.str("category"),
        validFrom = g.str("bonusStartDate") ?: return null,
        validUntil = g.str("bonusEndDate") ?: return null,
        url = g.str("id")?.let { "https://www.ah.nl/bonus/groep/$it" },
    )

    private fun firstLabel(o: JsonObject): String? =
        o["discountLabels"]?.jsonArray?.firstOrNull()?.obj?.str("defaultDescription")
}

object JumboParser {
    private val reactive = setOf("Reactive", "ShallowReactive", "Ref", "ShallowRef")

    /**
     * Promotions from the offers page. Its data sits in `<script id="__NUXT_DATA__">` as
     * a flat JSON array in which objects and lists refer to other entries by index.
     */
    fun page(html: String): List<Offer> {
        val payload = Regex("""<script[^>]*id="__NUXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return emptyList()
        val entries = json.parseToJsonElement(payload).jsonArray

        fun resolve(index: Int, depth: Int): JsonElement {
            if (index !in entries.indices || depth > 8) return JsonNull
            return when (val value = entries[index]) {
                is JsonObject -> JsonObject(value.mapValues { (_, ref) -> (ref as? JsonPrimitive)?.intOrNull?.let { resolve(it, depth + 1) } ?: JsonNull })
                is JsonArray -> {
                    val head = (value.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (head in reactive) resolve(value.getOrNull(1)?.jsonPrimitive?.intOrNull ?: -1, depth + 1)
                    else JsonArray(value.map { ref -> (ref as? JsonPrimitive)?.intOrNull?.let { resolve(it, depth + 1) } ?: JsonNull })
                }
                else -> value
            }
        }

        val required = setOf("title", "tags", "start", "end", "url")
        return entries.withIndex()
            .filter { (_, value) -> value is JsonObject && value.keys.containsAll(required) }
            .mapNotNull { (index, _) -> promotion(resolve(index, 0).jsonObject) }
            // The page lists a promotion in several sections.
            .distinctBy { it.url ?: it.title }
    }

    private fun promotion(p: JsonObject): Offer? {
        val tags = p["tags"] as? JsonArray ?: return null
        val tagTexts = tags.mapNotNull { it.obj?.str("text") }
        val deal = tagTexts.joinToString(" · ").ifBlank { null }
        return Offer(
            store = Store.JUMBO,
            title = p.str("title") ?: return null,
            deal = deal,
            price = tagTexts.firstNotNullOfOrNull(::singlePrice),
            packSize = p.str("subtitle"),
            validFrom = p["start"].obj?.str("iso")?.take(10) ?: return null,
            validUntil = p["end"].obj?.str("iso")?.take(10) ?: return null,
            url = p.str("url")?.let { "https://www.jumbo.com$it" },
        )
    }
}

object LidlParser {
    /** Lidl's own tile categories that are food. Others are plants, flowers and non-food. */
    private val foodCategories = setOf("Food", "F+V")
    private val amsterdam: ZoneId = ZoneId.of("Europe/Amsterdam")

    /** Product tiles of the offers page; each carries its data as HTML-escaped JSON. */
    fun page(html: String): List<Offer> =
        Regex("""data-grid-data="([^"]*)"""").findAll(html).mapNotNull { match ->
            runCatching { json.parseToJsonElement(htmlUnescape(match.groupValues[1])).jsonObject }.getOrNull()?.let(::tile)
        }.distinctBy { it.url ?: it.title }.toList()

    private fun tile(t: JsonObject): Offer? {
        if (t.str("category") !in foodCategories) return null
        val price = t["price"].obj
        val discount = price?.get("discount").obj?.str("discountText")
        return Offer(
            store = Store.LIDL,
            title = t.str("fullTitle") ?: t.str("title") ?: return null,
            deal = discount,
            price = price?.num("price"),
            originalPrice = price?.num("oldPrice"),
            packSize = price?.get("packaging").obj?.str("text"),
            category = t["keyfacts"].obj?.str("wonCategoryPrimary")?.substringAfterLast('/') ?: t.str("category"),
            // Tiles without store dates are regular-priced items whose discount needs the Lidl Plus app.
            validFrom = date(t["storeStartDate"]) ?: return null,
            validUntil = date(t["storeEndDate"]) ?: return null,
            url = t.str("canonicalUrl")?.let { "https://www.lidl.nl$it" },
        )
    }

    private fun date(epochSeconds: JsonElement?): String? =
        (epochSeconds as? JsonPrimitive)?.longOrNull?.let { Instant.ofEpochSecond(it).atZone(amsterdam).toLocalDate().toString() }
}

/** Decodes the HTML character references used in attribute values. */
internal fun htmlUnescape(text: String): String =
    Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(text) { match ->
        val name = match.groupValues[1]
        when {
            name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toInt(16).let { String(Character.toChars(it)) }
            name.startsWith("#") -> name.drop(1).toInt().let { String(Character.toChars(it)) }
            else -> when (name) {
                "quot" -> "\""
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "apos" -> "'"
                "nbsp" -> " "
                else -> match.value
            }
        }
    }
