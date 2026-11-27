package dev.tobins.mealplanner.server.shopping

import dev.tobins.mealplanner.api.ShoppingExtraInput
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.server.ConflictException
import dev.tobins.mealplanner.server.NotFoundException
import dev.tobins.mealplanner.server.plan.parseWeekStart
import java.time.LocalDate

/**
 * The account's own items on its shopping list, such as coffee: adding one, ticking it off as bought in a week
 * ([Ticking.setChecked] passes that on) and deleting it. Each change answers with the week's list ([ShoppingListView]).
 */
class OwnItems(private val store: ShoppingStore, private val view: ShoppingListView) {
    /**
     * Ticks an own item off as bought in the week starting [start], or back on. Ticked off, it is on
     * that week's list only; un-ticked, it is back on every list from the week it was added in. Ticking
     * one that is not on this week's list (added later, or bought in another week) is refused (409);
     * un-ticking one that was not ticked off in this week changes nothing. Another account's is a 404.
     */
    internal fun setBought(start: LocalDate, id: Long, bought: Boolean): Unit = store.writing {
        val week = start.toString()
        val changed = if (bought) store.tickOwnItem(start, id) else store.untickOwnItem(start, id)
        if (changed > 0) return@writing
        val item = store.ownItem(id) ?: throw NotFoundException("No own item with id $id")
        if (bought) throw ConflictException("${item.name} is not on the shopping list of the week of $week")
    }

    /**
     * Puts an own item, such as coffee, on the list of the week starting [weekStart] and every week
     * after until it is ticked off. The name is trimmed; blank or longer than
     * [ShoppingExtraInput.MAX_NAME_LENGTH] characters is refused (400), as is a new item when the
     * account has [ShoppingExtraInput.MAX_OPEN] that are not ticked off (409).
     */
    fun addExtra(weekStart: String, input: ShoppingExtraInput): ShoppingList {
        val start = parseWeekStart(weekStart)
        val name = input.name.trim()
        require(name.isNotEmpty()) { "name must not be blank" }
        require(name.codePointCount(0, name.length) <= ShoppingExtraInput.MAX_NAME_LENGTH) {
            "name must be at most ${ShoppingExtraInput.MAX_NAME_LENGTH} characters"
        }
        store.writing {
            // Inserted first, then counted: over the limit, the transaction takes it back. Two adds at
            // once can't both pass the count, as each takes the write lock first.
            store.insertOwnItem(name, start)
            if (store.countOpenOwnItems() > ShoppingExtraInput.MAX_OPEN) {
                throw ConflictException(
                    "The shopping list has ${ShoppingExtraInput.MAX_OPEN} own items that are not ticked off, " +
                        "the most it can have: tick off or delete some first",
                )
            }
        }
        return view.get(weekStart)
    }

    /** Takes an own item off every list for good. Another account's is a 404. */
    fun deleteExtra(weekStart: String, id: Long): ShoppingList {
        // Checked first, so that a bad week deletes nothing.
        parseWeekStart(weekStart)
        store.writing { if (store.deleteOwnItem(id) == 0L) throw NotFoundException("No own item with id $id") }
        return view.get(weekStart)
    }
}
