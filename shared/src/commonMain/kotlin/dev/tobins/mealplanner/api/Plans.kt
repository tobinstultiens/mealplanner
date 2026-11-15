package dev.tobins.mealplanner.api

import kotlinx.serialization.Serializable

/** The meals of a day, in eating order. [share] is the part of the daily calories a slot gets. */
enum class MealSlot(val share: Double) {
    BREAKFAST(0.22),
    LUNCH(0.30),
    DINNER(0.38),
    SNACK(0.10),
}

/** One meal in a week plan. */
@Serializable
data class PlannedMeal(
    /** 0 is the plan's first day. */
    val day: Int,
    val slot: MealSlot,
    val recipeId: Long,
    val title: String,
    /** How many of the recipe's servings to eat. */
    val portions: Double,
    val kcal: Double,
    val proteinG: Double,
    /** True when this is what is left of a recipe cooked on an earlier day. */
    val leftover: Boolean = false,
    /** Locked meals survive regeneration of the plan. */
    val locked: Boolean = false,
    val status: MealStatus = MealStatus.PLANNED,
    /** Estimated euros for the portions eaten, with the week's offers; null when unknown. */
    val cost: Double? = null,
)

@Serializable
data class PlanDay(
    /** ISO date, e.g. `2026-11-16`. */
    val date: String,
    val meals: List<PlannedMeal>,
    val kcal: Double,
    val proteinG: Double,
)

@Serializable
data class WeekPlan(
    /** ISO date of the first day. */
    val weekStart: String,
    val targets: DailyTargets,
    val days: List<PlanDay>,
    /** Slots the planner had no suitable recipe for. */
    val emptySlots: List<MealSlot>,
    /** Estimated euros for every planned meal with the week's offers, and at regular prices. */
    val cost: Double? = null,
    val costWithoutOffers: Double? = null,
)

/**
 * One thing to buy for a week plan. The section travels as [category] alone, and its position is
 * the order the server sends the items in.
 *
 * Apps from v0.1.2 on skip fields they don't know, so optional fields may be added here (see
 * README, "API compatibility"); removing or renaming one still breaks them.
 */
@Serializable
data class ShoppingItem(
    /** Stable identifier of the item within the list, used to tick it off. */
    val key: String,
    val name: String,
    /** Total weight needed, when every use could be converted to grams. */
    val grams: Double?,
    /** Total number of pieces, when the recipes count this ingredient in pieces. */
    val pieces: Double?,
    /** The shop section's title ([dev.tobins.mealplanner.shopping.ShopSection.title]); "Other" when unknown. */
    val category: String,
    val recipes: List<String>,
    /** True when the pantry lists it as on hand: check before buying. */
    val onHand: Boolean,
    val checked: Boolean,
    /** Store offers for this ingredient during the plan's week. */
    val offers: List<Offer> = emptyList(),
    /**
     * A picture to recognise the item by: the best offer's (the cheapest that states a price,
     * else the first), or else the AH product its regular price comes from. Null without either.
     */
    val imageUrl: String? = null,
    /**
     * Euros the needed amount costs at the regular price (AH's, or one set by hand in the
     * catalogue): what an offer's saving is measured against, with [Offer.paidShare]. Null when the
     * ingredient has no regular price or the amount isn't known in grams.
     */
    val regularPrice: Double? = null,
    /**
     * The weight of the uses that could be converted to grams, set only when [grams] is null
     * because other uses could not (see [otherAmounts]) and some weight is known.
     */
    val knownGrams: Double? = null,
    /**
     * What could not be converted to grams, summed per unit as written, scaled to the planned
     * portions and rounded up to whole units: `1 bag`, `250 ml`, `2 can`. Counts whose unit is the
     * ingredient itself (`1 | onion`) are not here; they stay in [pieces].
     */
    val otherAmounts: List<String> = emptyList(),
)

/**
 * Everything the week's meals need, without the staples that are always at home. [items] come
 * in walking order: by shop section, then by name; ticking an item off does not move it.
 *
 * Next to it, two kinds of advice that the week's meals don't need, each in walking order too and
 * ticked off like [items]: [buyAhead] and [stockUp]. They are separate lists so that apps from
 * before them (v0.1.2), which only read [items], never take them for things this week needs.
 */
@Serializable
data class ShoppingList(
    val weekStart: String,
    val items: List<ShoppingItem>,
    /** Names of staples the plan uses, as a reminder to check the cupboard. */
    val staples: List<String>,
    /** Seasoning and garnish the recipes use "to taste", without an amount. */
    val toTaste: List<String> = emptyList(),
    /**
     * Ingredients that keep and are on offer this week only, for meals of next week (named in
     * [ShoppingItem.recipes]): what one batch of each meal needs. Keys start with `ahead:`. Ticking
     * one puts it in the pantry as on hand from next week. Empty when switched off
     * ([ShoppingAdvice.buyAhead]), dismissed for this week, or when the week has no plan.
     */
    val buyAhead: List<ShoppingItem> = emptyList(),
    /**
     * Staples and long-keeping ingredients the recipes use that are on offer now, though no meal on
     * this list needs them: one pack of the offer each. Keys start with `stock:`. Advice only; ticking
     * one puts it in the pantry as on hand. Empty when switched off or dismissed for this week.
     */
    val stockUp: List<ShoppingItem> = emptyList(),
    /** True when [buyAhead] was dismissed for this week (and is empty for that reason). */
    val buyAheadDismissed: Boolean = false,
    /** True when [stockUp] was dismissed for this week (and is empty for that reason). */
    val stockUpDismissed: Boolean = false,
)

@Serializable
data class ShoppingCheck(val key: String, val checked: Boolean)

/** The extra groups of the shopping list, as named in the dismiss route `/shopping/dismissed/{group}`. */
enum class ShoppingAdviceGroup(val path: String) {
    BUY_AHEAD("buy-ahead"),
    STOCK_UP("stock-up"),
    ;

    companion object {
        fun fromPath(path: String?): ShoppingAdviceGroup? = entries.firstOrNull { it.path == path }
    }
}

/**
 * Which advice the account's shopping list gives (`GET` and `PUT /api/shopping-advice`); both on
 * until the account switches one off.
 */
@Serializable
data class ShoppingAdvice(
    /** Buy keepable offers of this week for meals of next week ([ShoppingList.buyAhead]). */
    val buyAhead: Boolean = true,
    /** Stock up on staples and long-keeping ingredients on offer ([ShoppingList.stockUp]). */
    val stockUp: Boolean = true,
)
