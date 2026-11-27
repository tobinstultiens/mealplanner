package dev.tobins.mealplanner.server.shopping

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.ingredients.IngredientRepository
import dev.tobins.mealplanner.server.ingredients.StockRepository
import dev.tobins.mealplanner.server.plan.WeekMeals.Companion.DAYS
import dev.tobins.mealplanner.server.plan.parseWeekStart
import dev.tobins.mealplanner.shopping.ShopSection
import java.time.LocalDate

/**
 * What a tick in `shoppingCheck` remembers of its row of the meals' needs when it was ticked: what
 * the row said, and what its offer saved. All null when it remembers nothing: a tick of another
 * kind of key, or one from before ticks did; [saving] is null too for a tick from before version 16.
 */
internal class Tick(val name: String?, val grams: Double?, val pieces: Double?, val saving: Saving?)

/** The euros a row's offer saved as it was ticked, and the store of that offer when it saved anything. */
internal class Saving(val euros: Double, val store: Store?)

/**
 * One account's shopping list for a week as the app shows it ([ShoppingList]), which every change of it answers:
 * what the planned meals need and the account's own items ([WeekNeeds]), with the ticks, what is on hand and the
 * offers on each row, and the advice next to it ([ListAdvice]). The ticks are the account's own rows of
 * `shoppingCheck`, kept under [ShoppingKey]s with what a row of the meals' needs said and saved when it was ticked
 * ([Ticking]), except those of own items, which are kept with the item in `shoppingExtra` ([OwnItems]).
 */
class ShoppingListView(
    private val store: ShoppingStore,
    private val weekNeeds: WeekNeeds,
    private val listAdvice: ListAdvice,
    private val ingredients: IngredientRepository,
    private val stock: StockRepository,
    /** Offers valid between two dates at every store, grouped by ingredient id. */
    private val offersByIngredient: (LocalDate, LocalDate) -> Map<Long, List<Offer>>,
    /** Regular euros per kg of the account's ingredients, by id; without the offers. */
    private val regularPerKg: () -> Map<Long, Double>,
    /** The picture of the product each of the account's ingredients is priced with, by id. */
    private val productImages: () -> Map<Long, String>,
) {
    /**
     * What the week's meals need. Each recipe is scaled to the portions planned for it,
     * leftovers included; staples are left out and only named as a reminder. Items come in
     * the order of the walk through the shop, by section and then by name; whether an item
     * is ticked or on hand does not move it; one on hand says how much is left of it when that is
     * known ([ShoppingItem.onHandGrams]), and each says what the meals still to cook need of it
     * ([ShoppingItem.toComeGrams]). The account's own items ([OwnItems.addExtra]) are among them,
     * also when the week has no meals. Next to it, the advice of [ShoppingList.buyAhead] and
     * [ShoppingList.stockUp], unless switched off or dismissed for the week, and what was bought
     * that the meals no longer need ([ShoppingList.noLongerNeeded]).
     */
    fun get(weekStart: String): ShoppingList = list(parseWeekStart(weekStart), advised = true)

    /**
     * The list of [get]; without the advice when not [advised], which is the costly part and holds no
     * row of what the meals need.
     */
    private fun list(start: LocalDate, advised: Boolean): ShoppingList {
        val needs = weekNeeds.of(start)
        val ticks = ticks(start)
        val checked = ticks.keys
        // Only own items on this week's list come back, so one with a week it was bought in was ticked off in this one.
        val ticked = checked + needs.ownRows.filter { it.boughtWeek != null }.map { ShoppingKey.Extra(it.id) }
        val stock = this.stock.onHandGramsFor(start)
        val onHand = stock.keys
        // Every store's offers: the app chooses among those of the stores it shows.
        val offers = offersByIngredient(start, start.plusDays(DAYS - 1L))
        val images = productImages()
        val perKg = regularPerKg()
        val rows = (needs.week.needs + needs.own).sortedWith(walkingOrder)
        val items = rows.map { need ->
            val id = need.ingredient?.id
            need.toItem(need.key, need.key in ticked, id in onHand, id?.let { offers[it] }.orEmpty(), id?.let { images[it] }, id?.let { perKg[it] })
                .withBought(ticks[need.key])
                .withStock(id, stock)
                .withToCome(need, needs.toCook[need.key])
        }

        val catalogue = ingredients.list()
        val advice = listAdvice.of(start, advised, needs, rows, checked, onHand, offers, perKg, images, catalogue)
        return ShoppingList(
            weekStart = start.toString(),
            items = items,
            staples = needs.week.staples,
            toTaste = needs.week.toTaste,
            buyAhead = advice.buyAhead,
            stockUp = advice.stockUp,
            buyAheadDismissed = advice.buyAheadDismissed,
            stockUpDismissed = advice.stockUpDismissed,
            buyAheadProvisional = advice.buyAheadProvisional,
            noLongerNeeded = noLongerNeeded(ticks, rows.mapTo(HashSet()) { it.key }, stock, needs.sectionOf, staples = catalogue.filter { it.isStaple }.mapTo(HashSet()) { it.id }),
            preppedBoughtIn = weekNeeds.preppedBoughtIn(start),
        )
    }

    /**
     * This row with what the meals still to cook need of it ([toComeGrams]), their pieces and the
     * meals themselves, so the row does not name or count what was eaten. An own item is for no meal.
     */
    private fun ShoppingItem.withToCome(need: ShoppingNeed, toCook: ShoppingNeed?): ShoppingItem {
        val forMeals = need.key is ShoppingKey.Ingredient || need.key is ShoppingKey.Text
        return copy(
            toComeGrams = toComeGrams(need, toCook),
            toComePieces = toCook?.pieces?.takeIf { forMeals && it > 0 },
            toComeRecipes = if (forMeals) toCook?.recipes?.toList().orEmpty() else null,
            toComeFoodSlots = if (forMeals) toCook?.foodSlotsInOrder().orEmpty() else null,
        )
    }

    /**
     * The weight the meals still to cook need of [need], a row of the list: what [toCook], their row
     * for it, says. 0 when none of them uses it ([toCook] null), whatever its unit, so a row counted in
     * pieces or without an amount is done with too once its meals are. Null when it can't be told:
     * the week's need has no total weight, or the meals still to cook use it only without an amount
     * ("a bit of pesto"), which says nothing about how much. Null for an own item, which is for no meal.
     */
    private fun toComeGrams(need: ShoppingNeed, toCook: ShoppingNeed?): Double? = when {
        need.key !is ShoppingKey.Ingredient && need.key !is ShoppingKey.Text -> null
        toCook == null -> 0.0
        need.totalGrams == null -> null
        else -> toCook.totalGrams
    }

    /**
     * The week's ticks by key, dismissed groups among them. A stored key that is no key
     * ([ShoppingKey.parseOrNull]) matched no row before either, so it is left out.
     */
    internal fun ticks(start: LocalDate): Map<ShoppingKey, Tick> =
        store.checks(start).mapNotNull { row ->
            // A store this server does not know (written by a later one) is as good as none.
            val saving = row.saving?.let { Saving(it, Store.entries.firstOrNull { store -> store.name == row.savingStore }) }
            ShoppingKey.parseOrNull(row.itemKey)?.let { it to Tick(row.name, row.grams, row.pieces, saving) }
        }.toMap()

    /** This row with what [tick] remembers was bought of it and saved; as it is when not ticked. */
    private fun ShoppingItem.withBought(tick: Tick?): ShoppingItem = if (tick == null) {
        this
    } else {
        copy(boughtGrams = tick.grams, boughtPieces = tick.pieces, boughtSaving = tick.saving?.euros, boughtOfferStore = tick.saving?.store)
    }

    /** This row of ingredient [id] with the grams left of it in [stock] (by id), where that is known. */
    private fun ShoppingItem.withStock(id: Long?, stock: Map<Long, Double?>): ShoppingItem =
        id?.let(stock::get)?.let { copy(onHandGrams = it) } ?: this

    /**
     * The week's ticks of rows of the meals' needs that are not on the list any more ([listed] holds
     * the keys of its rows): bought, and no meal needs them since the plan changed. Only ticks that
     * remember their row (by its name) count: one from before ticks did may be of a row that was never
     * on this week's list, so it stays a plain tick that nothing shows. Advice ticks and dismissed
     * groups are not rows of the meals' needs. A [staples] ingredient is left out: it was made a staple
     * after it was bought, so it left the list although meals still use it, and a staple is always at
     * home (it shows up again as stock-up advice when on offer). In walking order, as the list.
     */
    private fun noLongerNeeded(
        ticks: Map<ShoppingKey, Tick>,
        listed: Set<ShoppingKey>,
        stock: Map<Long, Double?>,
        sectionOf: Map<Long, ShopSection>,
        staples: Set<Long>,
    ): List<ShoppingItem> {
        // Rows that need nothing: no amounts, recipes or offers, only what was bought.
        val gone = ticks.mapNotNull { (key, tick) ->
            val name = tick.name ?: return@mapNotNull null
            val id = when (key) {
                is ShoppingKey.Ingredient -> key.id.takeIf { it !in staples } ?: return@mapNotNull null
                is ShoppingKey.Text -> null
                else -> return@mapNotNull null
            }
            if (key in listed) return@mapNotNull null
            ShoppingNeed(key, null, name, id?.let { sectionOf[it] } ?: ShopSection.OTHER)
        }
        return gone.sortedWith(walkingOrder).map { row ->
            val id = (row.key as? ShoppingKey.Ingredient)?.id
            row.toItem(row.key, checked = true, onHand = id in stock, offers = emptyList(), productImage = null, perKg = null)
                .withBought(ticks[row.key])
                .withStock(id, stock)
        }
    }

    /**
     * The row of what the week's meals need under [key], as the list shows it now, with the offers and
     * stock its saving goes by; null for a key of another kind, or one the meals don't need. The list
     * without its advice ([list]): that is the costly part, and an own item's tick is kept elsewhere.
     */
    internal fun neededRow(start: LocalDate, key: ShoppingKey): ShoppingItem? {
        if (key !is ShoppingKey.Ingredient && key !is ShoppingKey.Text) return null
        return list(start, advised = false).items.firstOrNull { it.key == key.toString() }
    }
}

/**
 * The order of the walk through the shop, as [shoppingNeeds] gives it: by section, then by name, the
 * name of the ingredient a row is for where it has one. So an own item named by an alias
 * ("Olijfolie") sorts next to the meals' row of its ingredient ("Olive oil"), after it, as the sort
 * keeps the meals' rows first among equals (issue 116).
 */
private val walkingOrder = compareBy<ShoppingNeed>({ it.section.ordinal }, { (it.ingredient?.name ?: it.name).lowercase() })
