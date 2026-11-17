// Frozen from v0.1.5 (32d25e8a4073a3eed02ca4fd44a7b98461199be5) by scripts/freeze-api.sh. Do not edit: freeze again after a release.
package dev.tobins.mealplanner.released.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

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
    /**
     * The weight this row said when it was ticked off, on a ticked row of what the week's meals need
     * ([ShoppingList.items], [ShoppingList.noLongerNeeded]): [grams] then, or [knownGrams] when that
     * was null. When the meals need more now ([grams], or [knownGrams], above it), the tick stays
     * ([checked] true, so apps from before it never see a bought item come back) and the app can say
     * more is needed. Null when unknown: a row that said no weight, a tick from before the server kept
     * it, and the advice and own items, whose ticks go by other rules.
     */
    val boughtGrams: Double? = null,
    /** The number of pieces this row said when it was ticked off, as [boughtGrams] the weight. */
    val boughtPieces: Double? = null,
    /**
     * With [onHand] true: the grams left of the ingredient on hand for this week, from a ticked
     * buy-ahead or stock-up item bought in a known amount, less what meals ate of it, so the app can
     * say how much is still to buy. Null when that is not known (put on hand by hand, or ticked
     * without an amount), which is not none, and whenever [onHand] is false.
     */
    val onHandGrams: Double? = null,
    /**
     * On a row of what the week's meals need: the weight the meals still to cook need of it, the
     * meals to come. A meal eaten, a meal skipped and a leftover lunch cooked with its eaten dinner
     * need nothing more, as the stock went down for what they ate; [grams] stays the whole week's
     * need, as before, and so does what a tick remembers. What is still to buy is this less
     * [onHandGrams]. Exactly 0 when no meal still to cook uses it, whatever its unit (a row counted
     * in pieces or without an amount too): nothing to buy. Null when it can't be told: some meal to
     * come uses it but the need can't be weighed in full, or only without an amount; null too on
     * own items, advice and [ShoppingList.noLongerNeeded].
     */
    val toComeGrams: Double? = null,
    /**
     * On a row of what the week's meals need: the pieces the meals still to cook need of it, as
     * [pieces] is the week's, so the amount column can keep its count once meals are done. Null when
     * they need none, and on own items, advice and [ShoppingList.noLongerNeeded].
     */
    val toComePieces: Double? = null,
    /**
     * On a row of what the week's meals need: the meals still to cook that use it, as [recipes] names
     * the whole week's, so a row does not name meals already eaten as its source. Empty when none is
     * still to cook; null on own items, advice and [ShoppingList.noLongerNeeded], and from a server
     * from before it, where [recipes] stands.
     */
    val toComeRecipes: List<String>? = null,
    /**
     * The count of the ingredient itself (`2 | onion`) that could not be weighed, when the row also
     * has [otherAmounts] such as `1 bag`: [pieces] then counts the bag as well, so it could not be
     * shown next to them. A count with a weight per piece is in [knownGrams] already. Null
     * without [otherAmounts] or without such a count.
     */
    val ownPieces: Double? = null,
    /**
     * On a ticked row of what the week's meals need ([ShoppingList.items], [ShoppingList.noLongerNeeded]):
     * the euros its offer saved when it was ticked, as a store header counted it then
     * ([dev.tobins.mealplanner.shopping.savingToday], on the server's day of the tick, at the account's
     * stores); 0 when it saved nothing then. It stays as it was, so a meal eaten or an offer ended since
     * does not change what a bought row saved, and the app counts it instead of what the row would save
     * now. Null when that is not known: a row not ticked, a tick from before the server kept it or of a
     * row the list did not have, and the advice and own items, whose savings go as before.
     */
    val boughtSaving: Double? = null,
    /** The store of the offer [boughtSaving] was saved with, under which the app keeps the bought row; null when it saved nothing. */
    val boughtOfferStore: Store? = null,
)

/**
 * Everything the week's meals need, without the staples that are always at home. [items] come
 * in walking order: by shop section, then by name; ticking an item off does not move it.
 *
 * [items] also hold the account's own items (`POST /api/plans/{weekStart}/shopping/extras`), keys
 * `x:<id>`, without amounts or recipes: on the list of every week from the one they were added in
 * until ticked off, and once ticked off on that week's list only. One whose name is an ingredient of
 * the catalogue has that ingredient's section, offers, picture and [ShoppingItem.onHand], as a row
 * of its own next to the meals' row for it; any other is in section Other. They are rows of [items]
 * so that apps from before them (v0.1.2) show and tick them.
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
    /**
     * True when the meals named in [buyAhead] are only a suggestion: next week has no plan yet, so
     * they are dinners the planner could pick, not ones it has picked. False when next week is
     * planned, and when buy-ahead is off or dismissed. Meaningless when [buyAhead] is empty.
     */
    val buyAheadProvisional: Boolean = false,
    /**
     * Rows of [items] that were ticked off this week and that the meals no longer need since the plan
     * changed: food that was bought and no meal of the week uses now. Each has its key, its name as
     * the row had it, what was bought ([ShoppingItem.boughtGrams], [ShoppingItem.boughtPieces]),
     * [ShoppingItem.checked] true and [ShoppingItem.onHand] when its ingredient is on hand already; no
     * amounts needed and no recipes. Un-ticking one with its key (the usual `PUT` of the list) takes
     * it off, and so does using it up ([ShoppingRowKey]), which puts it on hand. Only ticks the server remembers the row of are here: not ticks from before it did,
     * the advice's or own items', nor a dismissed group. A separate list, so that apps from before it
     * never take it for something to buy.
     */
    val noLongerNeeded: List<ShoppingItem> = emptyList(),
)

/**
 * Ticks an item of the list off (`PUT /api/plans/{weekStart}/shopping`), or back on with [checked]
 * false.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ShoppingCheck(
    val key: String,
    val checked: Boolean,
    /**
     * For ticking off a buy-ahead or stock-up item: the grams the list advised buying, as the app
     * had it. The server goes by its own advice while the list still has the item; this counts only
     * when it no longer does, for a tick that arrives late (made offline, sent after the offers
     * changed), and must then be above 0 and at most [MAX_GRAMS]. Without it the amount is not
     * known, as for apps before it. Never sent when null, so a server from before it, which refuses
     * fields it does not know, still takes every other tick.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val grams: Double? = null,
) {
    companion object {
        /** More than any household buys of one ingredient at once: a few packs, not a pallet. */
        const val MAX_GRAMS = 10_000.0
    }
}

/**
 * One ticked row of a week's list by its [key], for what the server does with it in one step:
 * ticking a row that needs more again (`PUT /api/plans/{weekStart}/shopping/again`) and using up a
 * bought item no meal needs any more (`POST /api/plans/{weekStart}/shopping/use-up`). Routes of their
 * own rather than a field of [ShoppingCheck], so that an app can tell a server from before them by
 * its 404, where it would refuse an unknown field with the same 400 as a bad tick.
 */
@Serializable
data class ShoppingRowKey(val key: String)

/**
 * An own item to put on the shopping list (`POST /api/plans/{weekStart}/shopping/extras`), such as
 * coffee. The name is trimmed and must then be 1 to [MAX_NAME_LENGTH] characters; an account has at
 * most [MAX_OPEN] that are not ticked off.
 */
@Serializable
data class ShoppingExtraInput(val name: String) {
    companion object {
        const val MAX_NAME_LENGTH = 80
        const val MAX_OPEN = 100
    }
}

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
