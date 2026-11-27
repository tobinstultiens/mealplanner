package dev.tobins.mealplanner.app.prices

import dev.tobins.mealplanner.api.CheaperPack
import dev.tobins.mealplanner.api.IngredientPrice
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.OfferExclusion
import dev.tobins.mealplanner.api.OfferExclusionRequest
import dev.tobins.mealplanner.api.OfferExclusions
import dev.tobins.mealplanner.api.OfferSourceStatus
import dev.tobins.mealplanner.api.OffersState
import dev.tobins.mealplanner.api.PastOffer
import dev.tobins.mealplanner.api.PriceRaise
import dev.tobins.mealplanner.api.PriceVerdict
import dev.tobins.mealplanner.api.PricesState
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.VerdictKind
import dev.tobins.mealplanner.api.VerdictMeasure
import dev.tobins.mealplanner.app.FakeBackend
import dev.tobins.mealplanner.app.FakeBackend.Companion.TODAY
import dev.tobins.mealplanner.app.FakeBackend.Refusal
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/* The prices' part of [FakeBackend]: offers and their verdicts, regular prices and the offers excluded. */

internal fun FakeBackend.changeExclusions(method: String, sent: String, query: Map<String, String>): String {
    val request = when (method) {
        "PUT" -> json.decodeFromString<OfferExclusionRequest>(sent)
        "DELETE" -> OfferExclusionRequest(Store.valueOf(query.getValue("store")), query.getValue("title"), query.getValue("ingredientId").toLong())
        else -> null
    }
    if (request != null) {
        changes += "$method /api/offers/exclusions ${request.store} ${request.title} ${request.ingredientId}"
        val key = OfferExclusion.titleKey(request.title)
        exclusions.removeAll { it.store == request.store && it.titleKey == key && it.ingredientId == request.ingredientId }
        if (method == "PUT") {
            val name = excludable[request.ingredientId] ?: throw Refusal(404, "No ingredient with id ${request.ingredientId}")
            exclusions.add(0, OfferExclusion(request.store, request.title, key, request.ingredientId, name, "${TODAY}T10:30:00Z"))
        }
    }
    return json.encodeToString(OfferExclusions(exclusions.toList()))
}

internal val FakeBackend.offerList get() = listOf(
    Offer(Store.AH, "Rode paprika 500 g", "25% korting", 2.24, 2.99, "500 g", "Groente", "2026-11-16", "2026-11-22", ingredientId = 9, ingredientName = "Red bell pepper", imageUrl = "https://static.ah.nl/dam/product/paprika.webp"),
    Offer(Store.JUMBO, "Kipdijfilet 600 g", "2 voor 9,00", 4.50, 5.99, "600 g", "Vlees", "2026-11-16", "2026-11-22", ingredientId = 1, ingredientName = "Chicken thigh fillet", imageUrl = "https://www.jumbo.com/INTERSHOP/static/kip.png"),
    Offer(Store.LIDL, "Griekse feta 200 g", "1+1 gratis", 1.79, null, "200 g", "Zuivel", "2026-11-16", "2026-11-22", ingredientId = 2, ingredientName = "Feta", imageUrl = "https://www.lidl.nl/assets/feta.png"),
    Offer(Store.AH, "Bio havermout met een heel lange productnaam voor de test", "voor 1,49", 1.49, 1.89, "500 g", null, "2026-11-16", "2026-11-22"),
)

/**
 * What the server's price history says of an offer valid today ([Offer.verdict]), by its title. Early on, two days
 * into the history, every one is too new to judge. Later the pepper is a real deal; the chicken usually cheaper, its
 * kilo pack cheapest per kg before; the feta better than usual next to a Lidl price; the oats always on offer; the
 * minced beef no real discount after a raise; the rice cheaper another way; the rest the usual deal. The oats are no
 * ingredient on the Offers screen, so not judged there.
 */
internal fun FakeBackend.verdictOf(offer: Offer): PriceVerdict? {
    if (offer.validFrom > TODAY || offer.validUntil < TODAY) return null
    if (!laterVerdicts) return PriceVerdict(VerdictKind.NOT_ENOUGH_HISTORY, daysRead = 2, daysNeeded = 56)
    fun past(from: String, until: String, deal: String?, value: Double, price: Double? = null, endedEarly: Boolean = false) =
        PastOffer(from, until, deal, price, value = value, endedEarly = endedEarly)
    val known = PriceVerdict(daysRead = 70, daysNeeded = 56)
    return when (offer.title) {
        "Rode paprika 500 g" -> known.copy(
            verdict = VerdictKind.REAL_DEAL, measure = VerdictMeasure.PER_KG, value = 4.48, typical = 4.99,
            earlier = listOf(
                past("2026-11-02", "2026-11-08", "voor 2,49", 4.98),
                past("2026-10-12", "2026-10-18", "2 voor 5,00", 5.00),
                past("2026-09-21", "2026-09-27", "25% korting", 4.98),
            ),
        )
        "Kipdijfilet 600 g" -> known.copy(
            verdict = VerdictKind.WORSE_THAN_USUAL, measure = VerdictMeasure.PER_KG, value = 7.50, typical = 6.65,
            lastAsCheap = past("2026-11-02", "2026-11-08", "1+1 gratis", 4.99),
            cheaperPack = CheaperPack(PastOffer("2026-10-19", "2026-10-25", "voor 5,99", 5.99, "1 kg", 5.99), VerdictMeasure.PER_KG, now = false),
            earlier = listOf(
                past("2026-11-02", "2026-11-08", "1+1 gratis", 4.99),
                past("2026-10-19", "2026-10-25", "2 voor 8,00", 6.65, endedEarly = true),
                past("2026-09-28", "2026-10-04", "25% korting", 7.49),
            ),
        )
        "Griekse feta 200 g" -> known.copy(
            verdict = VerdictKind.USUAL_DEAL, measure = VerdictMeasure.PER_KG, value = 4.48, typical = 5.20, betterThanUsual = true,
            lastAsCheap = past("2026-10-19", "2026-10-25", "1+1 gratis", 4.20),
            alongside = listOf(PastOffer("2026-11-16", "2026-11-22", null, 2.29, "400 g")),
        )
        "Bio havermout met een heel lange productnaam voor de test" -> known.copy(verdict = VerdictKind.ALWAYS_ON_OFFER).takeIf { offer.ingredientName != null }
        "Rundergehakt 500 g" -> known.copy(verdict = VerdictKind.NOT_A_DEAL, measure = VerdictMeasure.PAID_SHARE, value = 0.98, raise = PriceRaise(3.29, 3.69))
        "Basmati rijst 1 kg" -> known.copy(
            verdict = VerdictKind.CHEAPER_ANOTHER_WAY, measure = VerdictMeasure.PER_KG, value = 1.15,
            cheaperNow = PastOffer("2026-11-16", "2026-11-22", "2 voor 1,80", value = 0.90),
        )
        else -> known.copy(verdict = VerdictKind.USUAL_DEAL, measure = VerdictMeasure.PAID_SHARE, value = 0.75, typical = 0.75)
    }
}

internal fun FakeBackend.judged(offer: Offer): Offer = offer.copy(verdict = verdictOf(offer))

/** [list] as the server sends it: its offers with their verdicts, wherever they are on it. */
internal fun FakeBackend.judged(list: ShoppingList): ShoppingList {
    fun List<ShoppingItem>.judged() = map { item -> item.copy(offers = item.offers.map { judged(it) }) }
    return list.copy(items = list.items.judged(), buyAhead = list.buyAhead.judged(), stockUp = list.stockUp.judged(), noLongerNeeded = list.noLongerNeeded.judged())
}

internal val FakeBackend.offers get() = OffersState(
    offers = offerList.map { judged(it) },
    sources = listOf(
        OfferSourceStatus(Store.AH, "2026-11-17T06:00:00Z", 2, null),
        OfferSourceStatus(Store.JUMBO, "2026-11-17T06:00:00Z", 1, null),
        OfferSourceStatus(
            Store.LIDL, "2026-11-17T06:00:00Z", 1, null,
            warning = "No Lidl offer runs into next week (the last ends 2026-11-22); the offers page may not show next week's offers.",
        ),
    ),
)

internal val FakeBackend.prices get() = PricesState(
    prices = listOf(
        IngredientPrice(1, "Chicken thigh fillet", Store.AH, "AH Kipdijfilet", "600 g", 5.99, 9.98),
        IngredientPrice(2, "Spinach", Store.AH, "AH Verse spinazie", "300 g", 1.99, 6.63),
        IngredientPrice(3, "Feta", Store.AH, "Set by hand", null, null, 11.50),
        IngredientPrice(4, "Rolled oats", Store.AH, "AH Havermout", "500 g", 1.09, 2.18),
    ),
    unpriced = listOf("Harissa", "Smoked paprika"),
    fetchedAt = "2026-11-17T06:00:00Z",
)
