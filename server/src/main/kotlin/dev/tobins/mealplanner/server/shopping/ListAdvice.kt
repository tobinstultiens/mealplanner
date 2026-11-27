package dev.tobins.mealplanner.server.shopping

import dev.tobins.mealplanner.api.Ingredient
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingAdvice
import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.ingredients.IngredientRepository
import dev.tobins.mealplanner.shopping.ShopSection
import java.time.LocalDate

/**
 * The advice next to a week's list: the buy-ahead and stock-up items, whether the account dismissed either group for
 * the week, and whether the buy-ahead items are for a provisional pick of next week's meals.
 */
internal class Advice(
    val buyAhead: List<ShoppingItem>,
    val stockUp: List<ShoppingItem>,
    val buyAheadDismissed: Boolean,
    val stockUpDismissed: Boolean,
    val buyAheadProvisional: Boolean,
)

/**
 * The advice a week's shopping list gives next to its rows ([dev.tobins.mealplanner.api.ShoppingList.buyAhead] and
 * [dev.tobins.mealplanner.api.ShoppingList.stockUp]), unless the account switched it off or dismissed it for the week:
 * which offers of the account's stores it goes by, and what [ShoppingAdvisor] and [BuyingAhead] make of them.
 */
class ListAdvice(
    private val buyingAhead: BuyingAhead,
    private val ingredients: IngredientRepository,
    /** Which advice the account's shopping list gives. */
    private val advice: () -> ShoppingAdvice,
    /** The stores the account shops at: only their offers count for the advice. */
    private val shops: () -> Set<Store>,
    /** Whether each ingredient keeps until next week, by id, given the shop section of each. */
    private val keeps: (Map<Long, ShopSection>) -> Map<Long, Boolean>,
    /** Offers with the price verdicts on them, for the stock-up advice; as they are when the verdicts are not to hand. */
    private val withVerdicts: (List<Offer>) -> List<Offer>,
) {
    /**
     * The advice for the week starting [start] whose list holds [rows] for [needs], with the week's [checked] ticks,
     * the ingredients [onHand], every store's [offers], the regular [perKg] prices, the product [images] and the
     * account's [catalogue] (all by ingredient id). Without the advice when not [advised], which is the costly part.
     */
    internal fun of(
        start: LocalDate,
        advised: Boolean,
        needs: WeekNeeds.Needs,
        rows: List<ShoppingNeed>,
        checked: Set<ShoppingKey>,
        onHand: Set<Long>,
        offers: Map<Long, List<Offer>>,
        perKg: Map<Long, Double>,
        images: Map<Long, String>,
        catalogue: List<Ingredient>,
    ): Advice {
        val wanted = if (advised) advice() else ShoppingAdvice(buyAhead = false, stockUp = false)
        val buyAheadDismissed = ShoppingKey.Dismissed(ShoppingAdviceGroup.BUY_AHEAD) in checked
        val stockUpDismissed = ShoppingKey.Dismissed(ShoppingAdviceGroup.STOCK_UP) in checked
        // Advice goes by the offers of the stores the account shops at only: it is about saving money there.
        val inUse = shops()
        // Stocking up goes by the price verdict too (issue 118), so the offers are judged only when it is advised.
        val inUseOffers = offers.mapValues { (_, all) -> all.filter { it.store in inUse } }.filterValues { it.isNotEmpty() }
        val advisor = ShoppingAdvisor(
            offers = if (wanted.stockUp && !stockUpDismissed) judged(inUseOffers) else inUseOffers,
            perKg = perKg,
            sectionOf = needs.sectionOf,
            keeps = keeps(needs.sectionOf),
            productImages = images,
            checked = checked,
        )
        // Buying ahead goes with a plan: without meals this week there is nothing to shop with.
        val ahead = if (wanted.buyAhead && !buyAheadDismissed && needs.meals.isNotEmpty()) {
            buyingAhead.buyAhead(start, needs.meals, advisor)
        } else {
            BuyAheadAdvice(emptyList(), false)
        }
        val buyAhead = ahead.items
        val stockUp = if (wanted.stockUp && !stockUpDismissed) {
            val listed = rows.mapNotNullTo(HashSet()) { it.ingredient?.id } +
                buyAhead.mapNotNull { (ShoppingKey.parse(it.key) as? ShoppingKey.Ahead)?.id }
            advisor.stockUp(catalogue, ingredients.usedIds(), onHand, listed)
        } else {
            emptyList()
        }
        return Advice(buyAhead, stockUp, buyAheadDismissed, stockUpDismissed, ahead.provisional)
    }

    /** [offers] by ingredient, each with its verdict. */
    private fun judged(offers: Map<Long, List<Offer>>): Map<Long, List<Offer>> {
        val all = offers.flatMap { (id, list) -> list.map { id to it } }
        val withVerdict = withVerdicts(all.map { it.second })
        return all.indices.groupBy({ all[it].first }, { withVerdict[it] })
    }
}
