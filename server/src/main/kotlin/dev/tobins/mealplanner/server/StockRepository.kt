package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.Pantry
import dev.tobins.mealplanner.server.IngredientRepository.Companion.USED_UP_GRAMS
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.db.SelectStockOfAccount
import dev.tobins.mealplanner.server.db.Stock
import java.time.LocalDate

/** A planned meal by where it is in the plan: what a meal takes from the stock is kept under it. */
data class MealRef(val week: LocalDate, val day: Int, val slot: MealSlot)

/**
 * What put an entry of the stock at home (`stock.source`). The stock is kept per source and week
 * (issue 116), so that what takes an entry back takes back only what that entry holds: un-ticking an
 * item never takes the grams of another tick, of an item used up, or what a meal gave back elsewhere.
 */
enum class StockSource {
    /** A stock-up item ticked on the list of the entry's week; un-ticking it takes the entry back. */
    STOCK_UP,

    /** A buy-ahead item ticked on the list of the week before the entry's; un-ticking it takes the entry back. */
    BUY_AHEAD,

    /**
     * A bought item no meal needed any more, used up on the list of the entry's week. Its tick is
     * gone, so only the pantry takes it back.
     */
    USE_UP,
}

/**
 * One account's stock: what is on hand by hand, what ticks of the shopping list put at home, and
 * what eaten meals took from it. Kept apart from the catalogue ([IngredientRepository]) so that work
 * on either does not touch the other. It reads the catalogue only for the ingredients the pantry
 * names, through one it builds with its own account id, so the two can never be different accounts'.
 */
class StockRepository(private val db: Database, foods: FoodRepository, private val userId: Long) {
    private val pantry = db.pantryQueries
    private val catalogue = IngredientRepository(db, foods, userId)

    /**
     * The staples and what is on hand, with the grams left of each ingredient whose amount is known
     * (all its entries added up) and the week its stock counts from: also what was bought ahead for
     * next week, as that is at home already.
     *
     * The stock is kept per tick (issue 116), so an ingredient can have entries of several weeks, while
     * [Pantry.onHandFrom] has one week per ingredient, as apps before it read it: the earliest of them. That is the
     * week from which meals eat it, the oldest entry first, and an entry stays only while something is
     * left of it. [Pantry.onHandWeeks] has all of them. An ingredient on hand by hand only counts at once and has none.
     */
    fun pantry(): Pantry {
        val all = catalogue.list()
        val byId = all.associateBy { it.id }
        val stock = pantry.selectStockOfAccount(userId).executeAsList()
        val weeks = stock.groupBy({ it.ingredientId }, { it.fromWeek }).mapValues { (_, weeks) -> weeks.distinct().sorted() }
        return Pantry(
            staples = all.filter { it.isStaple },
            onHand = pantry.selectOnHand(userId).executeAsList().mapNotNull(byId::get),
            onHandGrams = gramsIn(stock).mapNotNull { (id, grams) -> grams?.let { id to it } }.toMap(),
            onHandFrom = weeks.mapValues { (_, weeks) -> weeks.first() },
            onHandWeeks = weeks,
        )
    }

    /**
     * Puts an ingredient on hand by hand, or takes it off. Marked by hand, the stock that ticks of
     * the shopping list put there makes way for it (see [putOnHandFrom]): the owner says it is at
     * home, so neither un-ticking nor eating meals takes it away; only removing it by hand does.
     * Either way the owner says what is at home, so un-eating a meal no longer puts back what it
     * took from the stock ([giveBack]): taken off, it would come back; on hand, it is there already.
     */
    fun setOnHand(id: Long, onHand: Boolean) = db.transaction {
        requireIngredient(id)
        // Before the stock goes: an ingredient in stock keeps its place in the pantry's list.
        if (onHand) pantry.insertOnHand(id, java.time.Instant.now().toString(), userId) else pantry.deleteOnHand(id, userId)
        pantry.deleteStockOf(id, userId)
        pantry.deleteStockUsesOf(userId, id)
    }

    /**
     * The ingredients on hand for planning or shopping in the week starting [week]: those put on hand
     * by hand, and the ticked buy-ahead and stock-up items that count from that week or earlier. An
     * item bought ahead for next week is at home already, but this week's meals need their own.
     */
    fun onHandFor(week: LocalDate): Set<Long> = onHandGramsFor(week).keys

    /**
     * The ingredients on hand for the week starting [week] ([onHandFor]), each with the grams left of
     * it: of all its stock that counts from then. Null where that is not known: put on hand by hand,
     * which says only that it is there, or ticked without an amount. Unknown is not none, so callers
     * must not read null as zero.
     */
    fun onHandGramsFor(week: LocalDate): Map<Long, Double?> {
        val stock = pantry.selectStockOfAccount(userId).executeAsList().filter { LocalDate.parse(it.fromWeek) <= week }
        return gramsIn(stock) + pantry.selectOnHandByHand(userId).executeAsList().associateWith { null }
    }

    /** The grams of each ingredient in [entries] of the stock, added up; null for one with an entry whose amount is not known. */
    private fun gramsIn(entries: List<SelectStockOfAccount>): Map<Long, Double?> =
        entries.groupBy({ it.ingredientId }, { it.grams }).mapValues { (_, grams) -> grams.takeIf { null !in it }?.sumOf { it!! } }

    /**
     * What [source] put at home: [grams] of an ingredient (null when not known), on hand from [week]
     * on, for meals to be eaten from ([useUp]). An entry of the same source and week there already
     * stays as it was, as a tick of a ticked item changes nothing. An ingredient on hand by hand gets
     * none, so un-ticking never takes away what was put there by hand.
     */
    fun putOnHandFrom(id: Long, week: LocalDate, grams: Double?, source: StockSource) {
        requireIngredient(id)
        pantry.insertStock(week.toString(), source.name, java.time.Instant.now().toString(), grams, id, userId)
    }

    /**
     * [grams] bought of an ingredient that is at home, for meals from [week] on to eat from
     * ([useUp]): a bought item of the shopping list that no meal needs any more, used up after all.
     * An entry of its own ([StockSource.USE_UP]), so that un-ticking another item of the ingredient
     * leaves it; used up twice in one week, the second adds to the first. An ingredient on hand by
     * hand stays as it is, as for [putOnHandFrom].
     */
    fun addToStock(id: Long, week: LocalDate, grams: Double) = db.transaction {
        requireIngredient(id)
        if (pantry.addToStock(grams, id, week.toString(), StockSource.USE_UP.name, userId).value == 0L) {
            putOnHandFrom(id, week, grams, StockSource.USE_UP)
        }
    }

    /**
     * An un-ticked item of the shopping list: takes back what ticking it put on hand from [week],
     * its own entry ([source]) and nothing else. What was not bought was not eaten either, so
     * un-eating a meal that took from it puts nothing back.
     */
    fun takeBackOnHand(id: Long, week: LocalDate, source: StockSource) = db.transaction {
        requireIngredient(id)
        pantry.deleteStock(id, week.toString(), source.name, userId)
        pantry.deleteStockUsesFrom(userId, id, week.toString(), source.name)
    }

    /**
     * [meal] was eaten, using [grams] of each ingredient by id. It is eaten from the stock that counts
     * from its week or before, the oldest entry first and the next once that one is used up ([takeFrom]).
     * What was put on hand by hand stays, as it always has. What was taken from each entry is kept under
     * [meal], for [giveBack]; a meal that has taken its share already takes nothing more.
     */
    fun useUp(meal: MealRef, grams: Map<Long, Double>) {
        if (grams.isEmpty()) return
        db.transaction {
            if (uses(meal).isNotEmpty()) return@transaction
            val stock = pantry.selectStock(userId, meal.week.toString(), grams.keys).executeAsList().groupBy { it.ingredientId }
            for ((id, entries) in stock) {
                var wanted = grams.getValue(id)
                for (entry in entries) {
                    if (wanted <= 0.0) break
                    val taken = takeFrom(entry, wanted)
                    pantry.insertStockUse(
                        userId, meal.week.toString(), meal.day.toLong(), meal.slot.name, id, entry.fromWeek, entry.source, entry.addedAt, taken,
                    )
                    // An entry of unknown amount covers what is wanted, as far as anyone can tell.
                    wanted = taken?.let { wanted - it } ?: 0.0
                }
            }
        }
    }

    /**
     * Takes [wanted] grams from one [entry] of the stock: what is left goes down by that, and the entry
     * goes once less than [USED_UP_GRAMS] is left. Of an entry whose amount is not known there is no
     * telling what is left, so the meal uses it up, as it did before amounts were kept. Returns the grams
     * taken: all that was left when it was used up, null when that was not known.
     */
    private fun takeFrom(entry: Stock, wanted: Double): Double? {
        val left = entry.grams?.minus(wanted)
        if (left != null && left >= USED_UP_GRAMS) {
            pantry.setStockGrams(left, entry.ingredientId, entry.fromWeek, entry.source, userId)
            return wanted
        }
        pantry.deleteStock(entry.ingredientId, entry.fromWeek, entry.source, userId)
        return entry.grams
    }

    /**
     * [meal] was not eaten after all: puts back what [useUp] took for it, each into the entry it came
     * from, so eating and un-eating leave the stock as it was. An entry that is there gets the amount
     * added; one the meal used up comes back as it was. An ingredient put on hand by hand in the
     * meantime stays as it is.
     */
    fun giveBack(meal: MealRef) = db.transaction {
        for (use in uses(meal)) {
            if (pantry.addToStock(use.grams, use.ingredientId, use.fromWeek, use.source, userId).value == 0L) {
                pantry.insertStock(use.fromWeek, use.source, use.addedAt, use.grams, use.ingredientId, userId)
            }
        }
        pantry.deleteStockUses(userId, meal.week.toString(), meal.day.toLong(), meal.slot.name)
    }

    private fun uses(meal: MealRef) =
        pantry.selectStockUses(userId, meal.week.toString(), meal.day.toLong(), meal.slot.name).executeAsList()

    /**
     * Fails unless [id] is one of the account's ingredients: the stock's statements name the account,
     * and this check, as in the catalogue, turns another account's ingredient into a 404.
     */
    private fun requireIngredient(id: Long) =
        db.ingredientQueries.selectIngredient(userId, id).executeAsOneOrNull() ?: throw NotFoundException("No ingredient with id $id")
}
