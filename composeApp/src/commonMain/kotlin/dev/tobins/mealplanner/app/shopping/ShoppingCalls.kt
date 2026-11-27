package dev.tobins.mealplanner.app.shopping

import dev.tobins.mealplanner.api.OfferExclusionRequest
import dev.tobins.mealplanner.api.OfferExclusions
import dev.tobins.mealplanner.api.ShoppingAdvice
import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingExtraInput
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.ShoppingRowKey
import dev.tobins.mealplanner.app.ApiClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/*
 * The shopping list's server calls: the list, its ticks and own items, its advice, and the offers its account excluded.
 * They are extensions of [ApiClient], which keeps the address, the login, the errors and the retries for all of them.
 */

/** The offers the account said are not one of its ingredients ("Not this ingredient", issue 316), the latest first. */
suspend fun ApiClient.offerExclusions(): OfferExclusions = http.get("api/offers/exclusions").body()

/** Says that offers like [request]'s are not its ingredient, or with [excluded] false takes that back; answers the account's list. */
suspend fun ApiClient.setOfferExcluded(request: OfferExclusionRequest, excluded: Boolean): OfferExclusions =
    if (excluded) {
        http.put("api/offers/exclusions") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    } else {
        http.delete("api/offers/exclusions") {
            parameter("store", request.store.name)
            parameter("title", request.title)
            parameter("ingredientId", request.ingredientId)
        }.body()
    }

suspend fun ApiClient.shoppingList(weekStart: String): ShoppingList = http.get("api/plans/$weekStart/shopping").body()

/** Ticks an item off or back on; [grams] is what the list advised buying of a buy-ahead or stock-up item, [shown] what the phone showed of a row when it made the tick that is sent late. */
suspend fun ApiClient.setShoppingChecked(weekStart: String, key: String, checked: Boolean, grams: Double? = null, shown: ShoppingCheck.Shown? = null): ShoppingList =
    http.put("api/plans/$weekStart/shopping") {
        contentType(ContentType.Application.Json)
        setBody(ShoppingCheck(key, checked, grams, shown))
    }.body()

/** Ticks the ticked row [key] again for what it needs now, in one step; null from a server from before that. */
suspend fun ApiClient.tickShoppingAgain(weekStart: String, key: String): ShoppingList? = unlessUnknown {
    http.put("api/plans/$weekStart/shopping/again") {
        contentType(ContentType.Application.Json)
        setBody(ShoppingRowKey(key))
    }.body()
}

/** Puts what was bought of [key], which no meal needs any more, on hand; null from a server from before that. */
suspend fun ApiClient.useUpShopping(weekStart: String, key: String): ShoppingList? = unlessUnknown {
    http.post("api/plans/$weekStart/shopping/use-up") {
        contentType(ContentType.Application.Json)
        setBody(ShoppingRowKey(key))
    }.body()
}

/** Hides the buy-ahead or stock-up advice of one week's list, or with [dismissed] false shows it again. */
suspend fun ApiClient.setShoppingDismissed(weekStart: String, group: ShoppingAdviceGroup, dismissed: Boolean): ShoppingList {
    val path = "api/plans/$weekStart/shopping/dismissed/${group.path}"
    return if (dismissed) http.put(path).body() else http.delete(path).body()
}

/** Puts an own item such as "Coffee" on the list from [weekStart] on; the answer is that week's list. */
suspend fun ApiClient.addShoppingExtra(weekStart: String, name: String): ShoppingList =
    http.post("api/plans/$weekStart/shopping/extras") {
        contentType(ContentType.Application.Json)
        setBody(ShoppingExtraInput(name))
    }.body()

/** Takes the own item [id] (the number in its key `x:<id>`) off the list for good; the answer is [weekStart]'s list. */
suspend fun ApiClient.removeShoppingExtra(weekStart: String, id: Long): ShoppingList =
    http.delete("api/plans/$weekStart/shopping/extras/$id").body()

/** Whether the account's shopping list advises buying ahead and stocking up. */
suspend fun ApiClient.shoppingAdvice(): ShoppingAdvice = http.get("api/shopping-advice").body()

suspend fun ApiClient.saveShoppingAdvice(advice: ShoppingAdvice): ShoppingAdvice = http.put("api/shopping-advice") {
    contentType(ContentType.Application.Json)
    setBody(advice)
}.body()
