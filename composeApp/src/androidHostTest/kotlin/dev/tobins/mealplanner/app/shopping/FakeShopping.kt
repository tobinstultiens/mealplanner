package dev.tobins.mealplanner.app.shopping

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.app.FakeBackend
import dev.tobins.mealplanner.app.FakeBackend.Companion.WEEK_START
import dev.tobins.mealplanner.app.prices.offerList
import dev.tobins.mealplanner.app.ui.plusDays
import dev.tobins.mealplanner.nutrition.Salt
import dev.tobins.mealplanner.shopping.ShopSection

/* The shopping list's part of [FakeBackend]: the week's list. */

internal fun FakeBackend.shopping(weekStart: String): ShoppingList {
    if (emptyWeek) return ShoppingList(weekStart, emptyList(), emptyList())
    // The week's only meal is of last week's batch, which last week's list bought.
    if (tailOnly) return ShoppingList(weekStart, emptyList(), emptyList(), preppedBoughtIn = plusDays(WEEK_START, -7))
    // In walking order, as the server sends it: by shop section, then by name. The regular price is the
    // amount needed at the price per kg of `prices`; the pepper, counted in pieces only, has none.
    fun item(key: String, name: String, grams: Double?, pieces: Double?, section: ShopSection, recipe: String?, checked: Boolean = false, offers: List<Offer> = emptyList(), imageUrl: String? = null, regularPrice: Double? = null, knownGrams: Double? = null, otherAmounts: List<String> = emptyList(), boughtGrams: Double? = null, onHand: Boolean = false, onHandGrams: Double? = null, toComeGrams: Double? = null) =
        ShoppingItem(key, name, grams, pieces, section.title, listOfNotNull(recipe), onHand = onHand, checked = checked, knownGrams = knownGrams, otherAmounts = otherAmounts, offers = offers, imageUrl = imageUrl, regularPrice = regularPrice, boughtGrams = boughtGrams, onHandGrams = onHandGrams, toComeGrams = toComeGrams)
    // Offers that end this week, for the advice: with their share already worked out, as the server sends them.
    fun deal(store: Store, title: String, deal: String, price: Double, paidShare: Double) =
        Offer(store, title, deal, price, null, null, null, "2026-11-16", "2026-11-22", paidShare = paidShare)
    val chicken = "Slow-cooked chicken thighs with coconut, lemon and spinach"
    val chickpea = "Chickpea salad with feta and roasted peppers"
    return ShoppingList(
        weekStart = weekStart,
        // Keyed as the server keys them, by the ingredient's id ([ingredients]; the pepper's is 9), which "Not this ingredient" reads.
        items = listOf(
            item("i9", "Red bell pepper", null, 4.0, ShopSection.FRUIT_VEG, chickpea, offers = listOf(offerList[0].copy(paidShare = 0.75)), imageUrl = offerList[0].imageUrl),
            // Weighed in one recipe and counted in bags in another: the weight is known only in part.
            // Two whole plants are counted in a third, next to the bag.
            item("i8", "Spinach", null, 3.0, ShopSection.FRUIT_VEG, chicken, knownGrams = 600.0, otherAmounts = listOf("1 bag"))
                .copy(ownPieces = 2.0),
            item(
                // Bought for the 800 g a reroll needed; the meals need 1.4 kg now: 600 g more. 300 g
                // bought ahead last week is in the freezer: counted in when the row said 800 g, so the
                // ticked row doesn't name it again.
                "i1", "Chicken thigh fillet", 1400.0, null, ShopSection.MEAT, chicken, checked = true,
                offers = listOf(offerList[1].copy(paidShare = 0.75)), imageUrl = offerList[1].imageUrl, regularPrice = 13.97, boughtGrams = 800.0,
                onHand = true, onHandGrams = 300.0, toComeGrams = 1400.0,
            ),
            // On hand by hand, so how much is not known.
            item(
                "i2", "Feta", 400.0, null, ShopSection.DAIRY, chickpea, offers = listOf(offerList[2].copy(paidShare = 0.5)), imageUrl = offerList[2].imageUrl,
                regularPrice = 4.60, onHand = true, toComeGrams = 400.0,
            ),
            // Lidl's second week starts on Thursday, two days after the fixed "today": a badge with its day, nothing in the saving.
            item(
                "i3", "Greek yoghurt", 500.0, null, ShopSection.DAIRY, chickpea,
                offers = listOf(Offer(Store.LIDL, "Griekse yoghurt 500 g", "voor 1,19", 1.19, 1.79, "500 g", null, "2026-11-19", "2026-11-25", paidShare = 0.66)), regularPrice = 1.79,
                // Stocked up on earlier: more than the meals need, so nothing to buy: in a group of its own per store.
                onHand = true, onHandGrams = 650.0, toComeGrams = 500.0,
            ),
            // What is left of a stock-up bag covers part of it. Wednesday's breakfast was eaten from it
            // (50 g): the six still to come need 300 g, the amount shown, so 100 g is still to buy.
            // The eaten breakfast is no longer named as its source: only the meals still to cook are.
            item(
                "i6", "Rolled oats", 350.0, null, ShopSection.BREAKFAST, "Overnight oats with blueberries",
                offers = listOf(offerList[3].copy(paidShare = 0.79, ingredientName = "Rolled oats")), regularPrice = 0.76, onHand = true, onHandGrams = 200.0, toComeGrams = 300.0,
            ).let { it.copy(recipes = listOf("Porridge with banana") + it.recipes, toComeRecipes = listOf("Overnight oats with blueberries")) },
            // Own items, typed by hand: one the server filed under a section, one it could not place.
            item("x:1", "Coffee", null, null, ShopSection.DRINKS, null),
            item("x:2", "Birthday candles", null, null, ShopSection.OTHER, null),
        ),
        // Bought, then the meal that needed it was swapped out.
        noLongerNeeded = listOf(item("i31", "Broccoli", null, null, ShopSection.FRUIT_VEG, null, checked = true, boughtGrams = 500.0)),
        staples = listOf("Olive oil", "Salt"),
        toTaste = listOf("Black pepper", "Fresh parsley"),
        // Two meals of next week, provisional: one item for both, one for one; the second is ticked.
        buyAhead = listOf(
            item(
                "ahead:i21", "Minced beef", 1000.0, null, ShopSection.MEAT, "Beef chilli with beans",
                offers = listOf(deal(Store.JUMBO, "Rundergehakt 500 g", "2 voor 7,00", 3.50, 0.6)), regularPrice = 11.98,
            ).let { it.copy(recipes = it.recipes + "Lasagne") },
            item(
                "ahead:i22", "Frozen spinach", 450.0, null, ShopSection.FROZEN, "Lasagne", checked = true,
                offers = listOf(deal(Store.AH, "AH Spinazie diepvries 450 g", "25% korting", 1.12, 0.75)), regularPrice = 1.49,
            ),
        ),
        buyAheadProvisional = true,
        stockUp = listOf(
            item("stock:i23", "Rice", 1000.0, null, ShopSection.WORLD, null, offers = listOf(deal(Store.LIDL, "Basmati rijst 1 kg", "1+1 gratis", 2.29, 0.5)), regularPrice = 2.29),
            item("stock:i24", "Chopped tomatoes", 400.0, null, ShopSection.TINS, null, offers = listOf(deal(Store.AH, "AH Tomatenblokjes 400 g", "2e halve prijs", 0.62, 0.75)), regularPrice = 0.69),
        ),
    )
}
