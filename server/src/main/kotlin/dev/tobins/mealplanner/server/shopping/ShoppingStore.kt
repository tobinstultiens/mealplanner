package dev.tobins.mealplanner.server.shopping

import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.db.SelectChecks
import dev.tobins.mealplanner.server.db.ShoppingExtra
import dev.tobins.mealplanner.server.writing
import java.time.LocalDate

/**
 * What the shopping list's rules read and write in the database for one account (`Shopping.sq`): the ticks of a
 * week's list (`shoppingCheck`, dismissed advice groups among them) and the account's own items (`shoppingExtra`).
 * Rows in, values out, and no rule of what may change. It is built for the account [userId], so a caller never names
 * one, and every query it runs is of that account only. The rules ([ShoppingListView], [Ticking], [OwnItems], ...)
 * call it and no query.
 */
class ShoppingStore(private val db: Database, private val userId: Long) {
    private val q = db.shoppingQueries

    /**
     * Runs [body] in a transaction that holds the write lock ([writing]); inside another one on this thread it joins
     * that one, so a rule that calls another rule's change still makes one transaction of the two.
     */
    fun <T> writing(body: () -> T): T = db.writing(body)

    /** The ticks of the week starting [start], as stored: their keys are not parsed here. */
    fun checks(start: LocalDate): List<SelectChecks> = q.selectChecks(userId, start.toString()).executeAsList()

    /** Stores a tick of [key] in the week starting [start] with what it remembers; one there already stays as it is. */
    fun insertCheck(start: LocalDate, key: ShoppingKey, name: String?, grams: Double?, pieces: Double?, saving: Double?, savingStore: Store?) {
        q.insertCheck(userId, start.toString(), key.toString(), name, grams, pieces, saving, savingStore?.name)
    }

    /** Deletes the tick of [key] in the week starting [start]; gives how many were deleted, 0 or 1. */
    fun deleteCheck(start: LocalDate, key: ShoppingKey): Long = q.deleteCheck(userId, start.toString(), key.toString()).value

    /** The own items on the list of the week starting [start], in the order they were added. */
    fun ownItemsOf(start: LocalDate): List<ShoppingExtra> = q.selectExtrasForWeek(userId, start.toString()).executeAsList()

    /** Own item [id]; null when the account has none such. */
    fun ownItem(id: Long): ShoppingExtra? = q.selectExtra(userId, id).executeAsOneOrNull()

    /** How many own items are not ticked off in any week. */
    fun countOpenOwnItems(): Long = q.countOpenExtras(userId).executeAsOne()

    /** Writes a new own item named [name], on the lists from the week starting [start] on. */
    fun insertOwnItem(name: String, start: LocalDate) {
        q.insertExtra(userId, name, start.toString())
    }

    /** Ticks own item [id] off in the week starting [start] when it is on that week's list; gives how many changed, 0 or 1. */
    fun tickOwnItem(start: LocalDate, id: Long): Long = q.tickExtra(start.toString(), userId, id).value

    /** Un-ticks own item [id] when it was ticked off in the week starting [start]; gives how many changed, 0 or 1. */
    fun untickOwnItem(start: LocalDate, id: Long): Long = q.untickExtra(userId, id, start.toString()).value

    /** Deletes own item [id]; gives how many were deleted, 0 or 1. */
    fun deleteOwnItem(id: Long): Long = q.deleteExtra(userId, id).value
}
