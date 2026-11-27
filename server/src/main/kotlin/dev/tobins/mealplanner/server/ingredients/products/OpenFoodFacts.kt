package dev.tobins.mealplanner.server.ingredients.products

import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.FoundProduct
import dev.tobins.mealplanner.api.LabelSource
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.nutrition.Salt
import dev.tobins.mealplanner.server.BuildInfo
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Duration
import java.time.Instant

/**
 * At most [count] requests in any [per], for the whole server: Open Food Facts allows 15 product reads a minute per IP
 * address and bans beyond that. A request over the limit is not sent and not queued: the person is told to try again.
 */
class RequestLimit(private val count: Int, private val per: Duration, private val now: () -> Instant = Instant::now) {
    private val sent = ArrayDeque<Instant>()

    /** Takes a place for a request now; false when [count] were sent in the last [per], and then nothing may be sent. */
    @Synchronized
    fun take(): Boolean {
        val now = now()
        while (sent.isNotEmpty() && !sent.first().isAfter(now - per)) sent.removeFirst()
        if (sent.size >= count) return false
        sent.addLast(now)
        return true
    }
}

/**
 * Open Food Facts by barcode (issue 354), the second source for a barcode AH cannot answer: its API v3, with only the
 * fields read, and a `User-Agent` naming the app, its version and [contact] (`MEALPLANNER_OPENFOODFACTS_CONTACT`), as its
 * terms ask. Throws when it does not answer or answers in a form that is not understood.
 */
class OpenFoodFacts(
    private val http: HttpClient,
    contact: String,
    private val limit: RequestLimit = RequestLimit(15, Duration.ofMinutes(1)),
    private val baseUrl: String = "https://world.openfoodfacts.org",
) {
    private val userAgent = "MealPlanner/${BuildInfo.version} ($contact)"

    /** What a lookup came to: the product, none, or not asked as the [limit] is reached. */
    sealed interface Answer {
        data class Found(val product: FoundProduct) : Answer

        data object NotFound : Answer

        data object Busy : Answer
    }

    suspend fun byBarcode(barcode: String): Answer {
        require(barcode.isDigits()) { "A barcode is digits only" }
        if (!limit.take()) return Answer.Busy
        val response = http.get("$baseUrl/api/v3/product/$barcode") {
            header("User-Agent", userAgent)
            parameter("fields", FIELDS)
        }
        val body = response.bodyAsText()
        return when {
            response.status == HttpStatusCode.NotFound && "product_not_found" in body -> Answer.NotFound
            response.status != HttpStatusCode.OK -> error("Open Food Facts answered ${response.status.value}")
            else -> OpenFoodFactsReader.product(body, barcode)?.let(Answer::Found) ?: Answer.NotFound
        }
    }

    private companion object {
        const val FIELDS = "product_name,brands,quantity,nutrition_data_per,nutriments"
    }
}

/** Reads Open Food Facts' answers: pure, so that they are tested on recorded ones. */
object OpenFoodFactsReader {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The product of an answer of API v3, its values per 100 g, salt as sodium; null when it says the product is not found.
     * Its `_100g` values are per 100 ml for a drink, which its quantity tells (`1000 ml`, else per 100 g: `57 g`); without
     * a quantity that reads the basis is left open, not guessed. Values given per portion only (`nutrition_data_per` other
     * than `100g`) are not read.
     */
    fun product(body: String, barcode: String): FoundProduct? {
        val answer = json.parseToJsonElement(body).jsonObject
        val result = (answer["result"] as? JsonObject)?.text("id")
        if (result == "product_not_found") return null
        val product = answer["product"] as? JsonObject ?: error("Open Food Facts' answer holds no product ($result)")
        val pack = product.text("quantity")?.let { packOf(listOf(it)) }
        val per100 = product.text("nutrition_data_per")?.let { it == "100g" } ?: true
        val nutriments = (product["nutriments"] as? JsonObject).takeIf { per100 }
        val unread = mutableListOf<String>()
        fun read(key: String, field: String): Double? {
            val value = (nutriments?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
            return value.doubleOrNull.also { if (it == null) unread += field }
        }
        val brand = product.text("brands")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
        val name = product.text("product_name").orEmpty()
        return FoundProduct(
            source = LabelSource(LabelSource.Origin.OPEN_FOOD_FACTS, barcode).toString(),
            // Its names often leave the brand out ("drinkyoghurt framboos"), which AH's titles have.
            name = if (brand != null && !name.contains(brand, ignoreCase = true)) "$brand $name".trim() else name,
            brand = brand,
            basis = when {
                !per100 -> null
                pack?.ml != null -> FoodBasis.PER_100_ML
                pack?.grams != null -> FoodBasis.PER_100_G
                else -> null
            },
            per100 = Nutrition(
                kcal = read("energy-kcal_100g", "kcal"),
                proteinG = read("proteins_100g", "proteinG"),
                fatG = read("fat_100g", "fatG"),
                saturatedFatG = read("saturated-fat_100g", "saturatedFatG"),
                carbsG = read("carbohydrates_100g", "carbsG"),
                sugarG = read("sugars_100g", "sugarG"),
                fibreG = read("fiber_100g", "fibreG"),
                sodiumMg = read("salt_100g", "sodiumMg")?.let(Salt::sodiumMg),
            ),
            unread = unread,
            pack = pack,
        )
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
