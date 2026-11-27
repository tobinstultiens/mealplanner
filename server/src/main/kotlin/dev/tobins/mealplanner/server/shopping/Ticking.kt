package dev.tobins.mealplanner.server.shopping

import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.ShoppingRowKey
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.ConflictException
import dev.tobins.mealplanner.server.NotFoundException
import dev.tobins.mealplanner.server.ingredients.IngredientRepository
import dev.tobins.mealplanner.server.ingredients.StockRepository
import dev.tobins.mealplanner.server.ingredients.StockSource
import dev.tobins.mealplanner.server.plan.WeekMeals.Companion.DAYS
import dev.tobins.mealplanner.server.plan.parseWeekStart
import dev.tobins.mealplanner.shopping.savingOffer
import dev.tobins.mealplanner.shopping.savingToday
import java.time.LocalDate

/**
 * Ticking rows of a week's shopping list off and back on, with what a tick remembers and puts on hand; ticking a
 * row again for what it needs now; using up a bought item no meal needs any more; and hiding an advice group, which
 * is kept as a tick of its own. An own item's tick goes to [OwnItems]. Each change answers with the week's list
 * ([ShoppingListView]).
 */
class Ticking(
    private val store: ShoppingStore,
    private val view: ShoppingListView,
    private val ownItems: OwnItems,
    private val stock: StockRepository,
    /** The stores the account shops at: only their offers count for what a tick saves. */
    private val shops: () -> Set<Store>,
    /** Whether a store was recorded with an offer for an ingredient (by name) valid on a day of the range: the store a late tick names must have had one. */
    private val offered: (Store, String, LocalDate, LocalDate) -> Boolean,
    /** Today as the household sees it: the day a tick's saving is worked out for. */
    private val today: () -> LocalDate,
) {
    /**
     * Ticks an item off, or back on. A buy-ahead item ticked is on hand from next week, a stock-up
     * item from this week, in the amount the list advises buying ([boughtGrams]); un-ticking takes
     * that back (see [StockRepository.putOnHandFrom]). An own item ticked off is bought in this
     * week ([OwnItems.setBought]).
     * A row of what the meals need remembers what it says as it is ticked ([ShoppingListView.neededRow]), and what its
     * offer saves then ([savingNow]): at the moment the server stores the tick, except for one the app
     * sent late, which says what it showed ([ShoppingCheck.shown], within bounds). Ticking a ticked one again keeps what it said
     * and saved first. A key no list has is refused; one that names no row of this week's list (an
     * app's list from before the plan changed) is ticked all the same, as it always was, and remembers
     * nothing.
     */
    fun setChecked(weekStart: String, check: ShoppingCheck): ShoppingList {
        val start = parseWeekStart(weekStart)
        val key = ShoppingKey.parse(check.key)
        require(key !is ShoppingKey.Dismissed) { "Groups are dismissed at /shopping/dismissed/{group}" }
        if (key is ShoppingKey.Extra) {
            ownItems.setBought(start, key.id, check.checked)
            return view.get(weekStart)
        }
        // Worked out before anything is stored, so that refused grams store nothing.
        val bought = if (check.checked) boughtGrams(weekStart, key, check.grams) else null
        val row = if (check.checked) view.neededRow(start, key) else null
        store.writing {
            onHandFromTick(start, key, check.checked, bought)
            if (check.checked) insertTick(start, key, row, check.shown) else store.deleteCheck(start, key)
        }
        return view.get(weekStart)
    }

    /**
     * Stores the tick of [key] with what [row] says, the weight the known part when not all could be
     * weighed, and what it saves now ([savingNow]); a tick there already stays as it is. A tick sent
     * late brings what the phone [shown] of the row, which replaces those parts that are within its
     * bounds ([gramsFor], [piecesFor], [savingFor]); the row's name is always the server's.
     */
    private fun insertTick(start: LocalDate, key: ShoppingKey, row: ShoppingItem?, shown: ShoppingCheck.Shown? = null) {
        val grams = row?.let { shown?.gramsFor(it) ?: it.grams ?: it.knownGrams }
        val pieces = row?.let { shown?.piecesFor(it) ?: it.pieces }
        val saving = row?.let { savingKept(it, shown) }
        store.insertCheck(start, key, row?.name, grams, pieces, saving?.euros, saving?.store)
    }

    /** The saving a tick of [row] keeps: the phone's [shown] one when it is to be believed, else what the row saves now. */
    private fun savingKept(row: ShoppingItem, shown: ShoppingCheck.Shown?): Saving {
        val day = today()
        val sent = shown?.savingFor(row, day) ?: return row.savingNow()
        return Saving(sent, shown.storeFor(row, day, shops(), offered)?.takeIf { sent > 0 })
    }

    /**
     * What this row's offer saves as it is ticked now, as the app's store header counts it
     * ([savingToday]) at the account's stores ([shops]): 0 when nothing is known to be saved, which the
     * app keeps like any other saving. The store is that of the offer it was saved with, which the app
     * lists the row under ([savingOffer]); none when it saved nothing.
     */
    private fun ShoppingItem.savingNow(): Saving {
        val inUse = shops()
        val day = today().toString()
        val euros = savingToday(inUse, day) ?: 0.0
        return Saving(euros, savingOffer(inUse, day)?.store?.takeIf { euros > 0 })
    }

    /**
     * Ticks a ticked row of what the meals need (keys `i<id>` and `t:<text>`) again, so that it
     * remembers what the row says and saves now: the plan came to need more since it was bought, and
     * the rest was bought. In one step, so that no failed request can leave the row un-ticked with what was
     * bought forgotten, as un-ticking and ticking it could. Advice and own items are refused (400):
     * their ticks remember no row. One the account has no tick of in that week is a 404, which
     * keeps another account's ticks out of reach; one no meal needs any more a 409, as ticking it
     * again would forget what was bought of it.
     */
    fun tickAgain(weekStart: String, input: ShoppingRowKey): ShoppingList {
        val start = parseWeekStart(weekStart)
        val key = ShoppingKey.parse(input.key)
        require(key is ShoppingKey.Ingredient || key is ShoppingKey.Text) { "Only rows of what the meals need are ticked again, not ${input.key}" }
        val row = view.neededRow(start, key)
        store.writing {
            if (store.deleteCheck(start, key) == 0L) throw NotFoundException("No tick of ${input.key} in the week of $start")
            if (row == null) throw ConflictException("No meal of the week of $start needs ${input.key} any more")
            insertTick(start, key, row)
        }
        return view.get(weekStart)
    }

    /**
     * Puts a bought item no meal needs any more ([ShoppingList.noLongerNeeded], key `i<id>`) on hand
     * and takes it out of that group, in one step. What its tick remembers was bought is stock from
     * the list's week on ([StockRepository.addToStock]), which meals eat from and the pantry and
     * the list count; without a remembered weight it is put on hand by hand, as the pantry does. Then
     * the tick goes: the pantry has it now. Only an item of that group is used up: another kind of
     * key is refused (400), one the meals need again is a 409, as it is a row of the list then, and
     * any other (not bought in that week, a staple, another account's ingredient) a 404.
     */
    fun useUp(weekStart: String, input: ShoppingRowKey): ShoppingList {
        val start = parseWeekStart(weekStart)
        val key = ShoppingKey.parse(input.key) as? ShoppingKey.Ingredient
            ?: throw IllegalArgumentException("Only ingredients of the catalogue are put on hand, not ${input.key}")
        // Only what the group lists, by its own rules: not a plain tick from before ticks remembered their row, nor a staple.
        if (view.get(weekStart).noLongerNeeded.none { it.key == input.key }) {
            if (view.neededRow(start, key) != null) throw ConflictException("The meals of the week of $start need ${input.key} again")
            throw NotFoundException("Nothing bought of ${input.key} in the week of $start that no meal needs")
        }
        store.writing {
            // Un-ticked by another request in the meantime.
            val tick = view.ticks(start)[key] ?: throw NotFoundException("Nothing bought of ${input.key} in the week of $start")
            val grams = tick.grams?.takeIf { it >= IngredientRepository.USED_UP_GRAMS }
            // Both refuse an ingredient of another account (NotFoundException), and the transaction takes it all back.
            if (grams == null) stock.setOnHand(key.id, true) else stock.addToStock(key.id, start, grams)
            store.deleteCheck(start, key)
        }
        return view.get(weekStart)
    }

    /**
     * The grams a tick of the buy-ahead or stock-up item under [key] puts on hand: what the week's
     * list advises buying as it is now, while it still has the item. Otherwise, as for a tick sent
     * late after the offers changed, [sent], what the app says the list advised; that is refused
     * (400) unless above 0 and at most [ShoppingCheck.MAX_GRAMS], as nothing is left to check it
     * against. Null for any other key, and when neither is known.
     */
    private fun boughtGrams(weekStart: String, key: ShoppingKey, sent: Double?): Double? {
        if (key !is ShoppingKey.Ahead && key !is ShoppingKey.Stock) return null
        val list = view.get(weekStart)
        val advised = (list.buyAhead + list.stockUp).firstOrNull { it.key == key.toString() }
        if (advised != null || sent == null) return advised?.grams
        require(sent > 0 && sent <= ShoppingCheck.MAX_GRAMS) {
            "grams must be above 0 and at most ${ShoppingCheck.MAX_GRAMS.toInt()}, not $sent"
        }
        return sent
    }

    /** [grams] is the amount bought; null when not known, such as for an item the list no longer advises. */
    private fun onHandFromTick(start: LocalDate, key: ShoppingKey, checked: Boolean, grams: Double?) {
        val (id, from, source) = when (key) {
            is ShoppingKey.Ahead -> Triple(key.id, start.plusDays(DAYS.toLong()), StockSource.BUY_AHEAD)
            is ShoppingKey.Stock -> Triple(key.id, start, StockSource.STOCK_UP)
            else -> return
        }
        // Both refuse an ingredient of another account (NotFoundException), before anything is stored.
        if (checked) stock.putOnHandFrom(id, from, grams, source) else stock.takeBackOnHand(id, from, source)
    }

    /** Hides one of the advice groups of the week's list, or with [dismissed] false shows it again. Nothing else changes. */
    fun setDismissed(weekStart: String, group: ShoppingAdviceGroup, dismissed: Boolean): ShoppingList {
        val start = parseWeekStart(weekStart)
        val key = ShoppingKey.Dismissed(group)
        store.writing { if (dismissed) store.insertCheck(start, key, null, null, null, null, null) else store.deleteCheck(start, key) }
        return view.get(weekStart)
    }
}
