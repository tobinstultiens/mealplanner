package dev.tobins.mealplanner.app.ingredients

import dev.tobins.mealplanner.api.FoodDataset
import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.IngredientSection
import dev.tobins.mealplanner.api.KeepsChoice
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.Pantry
import dev.tobins.mealplanner.api.ProductHit
import dev.tobins.mealplanner.api.ProductLookup
import dev.tobins.mealplanner.api.ProductSearch
import dev.tobins.mealplanner.api.SectionChoice
import dev.tobins.mealplanner.api.UnmappedText
import dev.tobins.mealplanner.app.ApiClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart

/*
 * The ingredients' server calls: the catalogue, its mapping and sections, products and foods, the pantry.
 * They are extensions of [ApiClient], which keeps the address, the login, the errors and the retries for all of them.
 */

/** Replaces ingredient [id] with [input], such as a product with the values of its label and its portions (issue 351). */
suspend fun ApiClient.updateIngredient(id: Long, input: IngredientInput): Ingredient = http.put("api/ingredients/$id") {
    contentType(ContentType.Application.Json)
    setBody(input)
}.body()

/** AH's products for a name, to fill the product form with one of (issue 354). */
suspend fun ApiClient.searchProducts(text: String): ProductSearch = http.get("api/products/search") { parameter("q", text) }.body()

/** AH's product [hit] with its label's values; of a multi-pack, its single item. */
suspend fun ApiClient.ahProduct(hit: ProductHit): ProductLookup =
    http.get("api/products/ah/${hit.id}") { hit.packOf?.let { parameter("packOf", it) } }.body()

/** The product of [barcode] (digits only), at AH or else at Open Food Facts. */
suspend fun ApiClient.productByBarcode(barcode: String): ProductLookup = http.get("api/products/barcode/${barcode.encodeURLPathPart()}").body()

suspend fun ApiClient.setStaple(ingredientId: Long, staple: Boolean): Pantry =
    (if (staple) http.put("api/pantry/staples/$ingredientId") else http.delete("api/pantry/staples/$ingredientId")).body()

suspend fun ApiClient.pantry(): Pantry = http.get("api/pantry").body()

suspend fun ApiClient.unmapped(): List<UnmappedText> = http.get("api/mappings/unmapped").body()

suspend fun ApiClient.map(request: MappingRequest): Ingredient = http.put("api/mappings") {
    contentType(ContentType.Application.Json)
    setBody(request)
}.body()

suspend fun ApiClient.ingredientSections(): List<IngredientSection> = http.get("api/ingredients/sections").body()

/** Sets an ingredient's shop section by hand; null goes back to the automatic one. */
suspend fun ApiClient.setSection(ingredientId: Long, section: String?): IngredientSection = http.put("api/ingredients/$ingredientId/section") {
    contentType(ContentType.Application.Json)
    setBody(SectionChoice(section))
}.body()

/** Sets by hand whether an ingredient keeps until next week; null goes back to its section's answer. */
suspend fun ApiClient.setKeeps(ingredientId: Long, keeps: Boolean?): IngredientSection = http.put("api/ingredients/$ingredientId/keeps") {
    contentType(ContentType.Application.Json)
    setBody(KeepsChoice(keeps))
}.body()

suspend fun ApiClient.foodDataset(): FoodDataset = http.get("api/foods/dataset").body()
