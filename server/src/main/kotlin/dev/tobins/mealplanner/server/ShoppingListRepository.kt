package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.ShoppingAdvice
import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingExtraInput
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.ShoppingRowKey
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.planner.BuyAhead
import dev.tobins.mealplanner.recipes.IngredientLine
import dev.tobins.mealplanner.server.PlanRepository.Companion.DAYS
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.db.ShoppingExtra
import dev.tobins.mealplanner.shopping.ShopSection
import dev.tobins.mealplanner.shopping.savingOffer
import dev.tobins.mealplanner.shopping.savingToday
import java.time.LocalDate

/**
 * One account's shopping list for a week: what the planned meals need, the account's own items, the
 * advice next to it, and the ticks. The meals come from [plans], which plans them and never asks for
 * the list; the ticks are the account's own rows of `shoppingCheck`, kept under [ShoppingKey]s with
 * what a row of the meals' needs said and saved when it was ticked, except those of own items, which
 * are kept with the item in `shoppingExtra`.
 */
class ShoppingListRepository(
    private val db: Database,
    private val userId: Long,
    private val plans: PlanRepository,
    private val recipes: RecipeRepository,
    private val profiles: ProfileRepository,
    private val ingredients: IngredientRepository,
    private val stock: StockRepository,
    /** Offers valid between two dates at every store, grouped by ingredient id. */
    private val offersByIngredient: (LocalDate, LocalDate) -> Map<Long, List<Offer>> = { _, _ -> emptyMap() },
    /** The stores the account shops at: only their offers count for the advice. */
    private val shops: () -> Set<Store> = { Store.entries.toSet() },
    /** Regular euros per kg of the account's ingredients, by id; without the offers. */
    private val regularPerKg: () -> Map<Long, Double> = { emptyMap() },
    /** The shop section of each of the account's ingredients, by id. */
    private val sections: () -> Map<Long, ShopSection> = { emptyMap() },
    /** Whether each ingredient keeps until next week, by id, given the [sections]. */
    private val keeps: (Map<Long, ShopSection>) -> Map<Long, Boolean> = { emptyMap() },
    /** Which advice the account's shopping list gives. */
    private val advice: () -> ShoppingAdvice = { ShoppingAdvice() },
    /** The picture of the product each of the account's ingredients is priced with, by id. */
    private val productImages: () -> Map<Long, String> = { emptyMap() },
    /** Today as the household sees it: the day a tick's saving is worked out for. */
    private val today: () -> LocalDate = ::serverToday,
) {
    private val q = db.shoppingQueries

    /**
     * What the week's meals need. Each recipe is scaled to the portions planned for it,
     * leftovers included; staples are left out and only named as a reminder. Items come in
     * the order of the walk through the shop, by section and then by name; whether an item
     * is ticked or on hand does not move it; one on hand says how much is left of it when that is
     * known ([ShoppingItem.onHandGrams]), and each says what the meals still to cook need of it
     * ([ShoppingItem.toComeGrams]). The account's own items ([addExtra]) are among them,
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
        // A prepped batch is bought in the week it is made, as one meal of all its portions.
        val meals = plans.forShopping(start)
        val sectionOf = sections()
        val lines = recipes.linesWithIngredients(meals.map { it.recipeId })
        val week = needsOf(meals, sectionOf, lines)
        // The stock went down for the meals eaten, so what is still needed goes by the meals still to cook.
        val toCook = needsOf(plans.stillToCook(start, meals), sectionOf, lines).needs.associateBy { it.key }
        countTaken(plans.shortfallsIn(start, meals), week.needs.associateBy { it.key }, toCook)
        val ownRows = q.selectExtrasForWeek(userId, start.toString()).executeAsList()
        val own = ownNeeds(ownRows, sectionOf)
        val ticks = ticks(start)
        val checked = ticks.keys
        // Only own items on this week's list come back, so one with a week it was bought in was ticked off in this one.
        val ticked = checked + ownRows.filter { it.boughtWeek != null }.map { ShoppingKey.Extra(it.id) }
        val stock = this.stock.onHandGramsFor(start)
        val onHand = stock.keys
        // Every store's offers: the app chooses among those of the stores it shows.
        val offers = offersByIngredient(start, start.plusDays(DAYS - 1L))
        val images = productImages()
        val perKg = regularPerKg()
        val rows = (week.needs + own).sortedWith(walkingOrder)
        val items = rows.map { need ->
            val id = need.ingredient?.id
            need.toItem(need.key, need.key in ticked, id in onHand, id?.let { offers[it] }.orEmpty(), id?.let { images[it] }, id?.let { perKg[it] })
                .withBought(ticks[need.key])
                .withStock(id, stock)
                .withToCome(need, toCook[need.key])
        }

        val wanted = if (advised) advice() else ShoppingAdvice(buyAhead = false, stockUp = false)
        val buyAheadDismissed = ShoppingKey.Dismissed(ShoppingAdviceGroup.BUY_AHEAD) in checked
        val stockUpDismissed = ShoppingKey.Dismissed(ShoppingAdviceGroup.STOCK_UP) in checked
        // Advice goes by the offers of the stores the account shops at only: it is about saving money there.
        val inUse = shops()
        val advisor = ShoppingAdvisor(
            offers = offers.mapValues { (_, all) -> all.filter { it.store in inUse } }.filterValues { it.isNotEmpty() },
            perKg = perKg,
            sectionOf = sectionOf,
            keeps = keeps(sectionOf),
            productImages = images,
            checked = checked,
        )
        // Buying ahead goes with a plan: without meals this week there is nothing to shop with.
        val ahead = if (wanted.buyAhead && !buyAheadDismissed && meals.isNotEmpty()) buyAhead(start, meals, advisor) else BuyAheadAdvice(emptyList(), false)
        val buyAhead = ahead.items
        val catalogue = ingredients.list()
        val stockUp = if (wanted.stockUp && !stockUpDismissed) {
            val listed = rows.mapNotNullTo(HashSet()) { it.ingredient?.id } +
                buyAhead.mapNotNull { (ShoppingKey.parse(it.key) as? ShoppingKey.Ahead)?.id }
            advisor.stockUp(catalogue, ingredients.usedIds(), onHand, listed)
        } else {
            emptyList()
        }
        return ShoppingList(
            weekStart = start.toString(),
            items = items,
            staples = week.staples,
            toTaste = week.toTaste,
            buyAhead = buyAhead,
            stockUp = stockUp,
            buyAheadDismissed = buyAheadDismissed,
            stockUpDismissed = stockUpDismissed,
            buyAheadProvisional = ahead.provisional,
            noLongerNeeded = noLongerNeeded(ticks, rows.mapTo(HashSet()) { it.key }, stock, sectionOf, staples = catalogue.filter { it.isStaple }.mapTo(HashSet()) { it.id }),
            preppedBoughtIn = preppedBoughtIn(start),
        )
    }

    /** The earliest week before the one starting [start] whose list bought a batch eaten in it ([ShoppingList.preppedBoughtIn]). */
    private fun preppedBoughtIn(start: LocalDate): String? =
        plans.meals(start).flatMap { listOfNotNull(it.prep, it.side?.prep) }
            .map { Batch.mondayOf(LocalDate.parse(it.madeOn)) }.filter { it < start }.minOrNull()?.toString()

    /**
     * What [meals] need, in walking order, with the staples and to-taste names apart. [lines] are their
     * recipes' lines, which the list reads once for the week's meals and for those still to cook.
     */
    private fun needsOf(meals: List<PlannedMeal>, sectionOf: Map<Long, ShopSection>, lines: Map<Long, RecipeLines>): ShoppingNeeds =
        shoppingNeeds(planned(meals), lines, sectionOf)

    /**
     * Counts what adjusted meals took of what was at home beyond their recipes as their need (issue 226), on
     * the rows of the ingredients that leaves a meal still to cook short of ([PlanRepository.shortfallsIn]): in
     * the week's need ([week]), and for a meal still to cook in that of the meals to come ([toCook]), with the
     * meal named among the row's. So a ticked row needs more than was bought, and one not ticked more than is on
     * hand, by what the later meal is short. Every other row stays as the recipes have it, as an adjustment buys
     * nothing itself, and so does a row whose weight is not known in full, as there is nothing to add it to.
     */
    private fun countTaken(shortfalls: Shortfalls, week: Map<ShoppingKey, ShoppingNeed>, toCook: Map<ShoppingKey, ShoppingNeed>) {
        for ((id, taken) in shortfalls.taken) {
            val key = ShoppingKey.Ingredient(id)
            val need = week[key]?.takeIf { it.totalGrams != null } ?: continue
            for (took in taken) {
                val rows = listOfNotNull(need, toCook[key]?.takeIf { took.demand.toCook })
                for (row in rows) {
                    row.grams += took.grams
                    row.recipes += took.demand.meal.title
                }
            }
        }
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
     * What a tick in `shoppingCheck` remembers of its row of the meals' needs when it was ticked: what
     * the row said, and what its offer saved. All null when it remembers nothing: a tick of another
     * kind of key, or one from before ticks did; [saving] is null too for a tick from before version 16.
     */
    private class Tick(val name: String?, val grams: Double?, val pieces: Double?, val saving: Saving?)

    /** The euros a row's offer saved as it was ticked, and the store of that offer when it saved anything. */
    private class Saving(val euros: Double, val store: Store?)

    /**
     * The week's ticks by key, dismissed groups among them. A stored key that is no key
     * ([ShoppingKey.parseOrNull]) matched no row before either, so it is left out.
     */
    private fun ticks(start: LocalDate): Map<ShoppingKey, Tick> =
        q.selectChecks(userId, start.toString()).executeAsList().mapNotNull { row ->
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
     * The account's own items as rows of the list: no amounts and no recipes. One whose name is an
     * ingredient of the catalogue, found the way a recipe line's is, has that ingredient's section (and
     * with it its offers, picture and on-hand mark); it stays a row of its own next to the meals' row
     * for that ingredient, so the meals' amounts stay readable. Any other is in section Other.
     */
    private fun ownNeeds(rows: List<ShoppingExtra>, sectionOf: Map<Long, ShopSection>): List<ShoppingNeed> {
        if (rows.isEmpty()) return emptyList()
        val resolve = ingredients.resolver()
        return rows.map { row ->
            val ingredient = resolve(IngredientLine(null, null, null, null, row.name, null))
            ShoppingNeed(ShoppingKey.Extra(row.id), ingredient, row.name, ingredient?.let { sectionOf[it.id] } ?: ShopSection.OTHER)
        }
    }

    /** Planned meals as recipes to shop for: each recipe once, with all its portions (leftovers included). */
    private fun planned(meals: List<PlannedMeal>): List<PlannedRecipe> =
        meals.groupBy { it.recipeId }.map { (id, ofRecipe) -> PlannedRecipe(id, ofRecipe.first().title, ofRecipe.sumOf { it.portions }) }

    /** The buy-ahead items, and whether their meals are a provisional pick rather than next week's plan. */
    private class BuyAheadAdvice(val items: List<ShoppingItem>, val provisional: Boolean)

    /**
     * What to buy now for next week's meals. When next week is planned already, those are its
     * meals as planned; otherwise a provisional choice among the dinners the planner could pick
     * then ([BuyAhead.provisional]), one batch each. Nothing of it is stored: next week is planned
     * as usual later, with what was ticked here on hand. The meals of a batch made this week are bought
     * with it ([PlanRepository.forShopping]): a next week with only those counts as not planned, and the
     * provisional choice is for the dinners they leave free (issue 263), none when they fill every one.
     */
    private fun buyAhead(start: LocalDate, thisWeek: List<PlannedMeal>, advisor: ShoppingAdvisor): BuyAheadAdvice {
        val next = start.plusDays(DAYS.toLong())
        val nextWeek = plans.forShopping(next)
        val limit = if (nextWeek.isEmpty()) minOf(BuyAhead.MAX_MEALS, freeDinners(next)) else BuyAhead.MAX_MEALS
        // Next week still has no plan of its own: the advice is empty, not about planned meals.
        if (limit == 0) return BuyAheadAdvice(emptyList(), provisional = true)
        val (meals, ratings) = if (nextWeek.isNotEmpty()) {
            planned(nextWeek) to emptyMap()
        } else {
            val targets = profiles.state().targets ?: return BuyAheadAdvice(emptyList(), false)
            val candidates = BuyAhead.provisional(
                recipes.planCandidates(dislikes = profiles.profile()?.dislikes.orEmpty()),
                targets,
                profiles.portions(),
                plannedThisWeek = thisWeek.mapTo(HashSet()) { it.recipeId },
            )
            candidates.map { PlannedRecipe(it.recipeId, it.title, it.servings.toDouble()) } to candidates.associate { it.recipeId to it.rating }
        }
        val items = advisor.buyAhead(
            meals,
            recipes.linesWithIngredients(meals.map { it.recipeId }),
            ratings,
            onHand = stock.onHandFor(next),
            onOfferNextWeek = plans.onOffer(next),
            limit = limit,
        )
        return BuyAheadAdvice(items, provisional = nextWeek.isEmpty())
    }

    /** The dinners of the week starting [start] that hold no meal yet: those the meals of a batch made the week before leave free. */
    private fun freeDinners(start: LocalDate): Int = DAYS - plans.meals(start).count { it.slot == MealSlot.DINNER }

    /**
     * Ticks an item off, or back on. A buy-ahead item ticked is on hand from next week, a stock-up
     * item from this week, in the amount the list advises buying ([boughtGrams]); un-ticking takes
     * that back (see [StockRepository.putOnHandFrom]). An own item ticked off is bought in this
     * week ([setBought]).
     * A row of what the meals need remembers what it says as it is ticked ([neededRow]), and what its
     * offer saves then ([savingNow]): at the moment the server stores the tick, also for one the app
     * sent late, as a tick does not say when it was made. Ticking a ticked one again keeps what it said
     * and saved first. A key no list has is refused; one that names no row of this week's list (an
     * app's list from before the plan changed) is ticked all the same, as it always was, and remembers
     * nothing.
     */
    fun setChecked(weekStart: String, check: ShoppingCheck): ShoppingList {
        val start = parseWeekStart(weekStart)
        val key = ShoppingKey.parse(check.key)
        require(key !is ShoppingKey.Dismissed) { "Groups are dismissed at /shopping/dismissed/{group}" }
        if (key is ShoppingKey.Extra) {
            setBought(start, key.id, check.checked)
            return get(weekStart)
        }
        // Worked out before anything is stored, so that refused grams store nothing.
        val bought = if (check.checked) boughtGrams(weekStart, key, check.grams) else null
        val row = if (check.checked) neededRow(start, key) else null
        db.writing {
            onHandFromTick(start, key, check.checked, bought)
            if (check.checked) insertTick(start, key, row) else q.deleteCheck(userId, start.toString(), key.toString())
        }
        return get(weekStart)
    }

    /**
     * Stores the tick of [key] with what [row] says, the weight the known part when not all could be
     * weighed, and what it saves now ([savingNow]); a tick there already stays as it is.
     */
    private fun insertTick(start: LocalDate, key: ShoppingKey, row: ShoppingItem?) {
        val saving = row?.savingNow()
        q.insertCheck(userId, start.toString(), key.toString(), row?.name, row?.let { it.grams ?: it.knownGrams }, row?.pieces, saving?.euros, saving?.store?.name)
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
        val row = neededRow(start, key)
        db.transaction {
            if (q.deleteCheck(userId, start.toString(), key.toString()).value == 0L) throw NotFoundException("No tick of ${input.key} in the week of $start")
            if (row == null) throw ConflictException("No meal of the week of $start needs ${input.key} any more")
            insertTick(start, key, row)
        }
        return get(weekStart)
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
        if (get(weekStart).noLongerNeeded.none { it.key == input.key }) {
            if (neededRow(start, key) != null) throw ConflictException("The meals of the week of $start need ${input.key} again")
            throw NotFoundException("Nothing bought of ${input.key} in the week of $start that no meal needs")
        }
        db.writing {
            // Un-ticked by another request in the meantime.
            val tick = ticks(start)[key] ?: throw NotFoundException("Nothing bought of ${input.key} in the week of $start")
            val grams = tick.grams?.takeIf { it >= IngredientRepository.USED_UP_GRAMS }
            // Both refuse an ingredient of another account (NotFoundException), and the transaction takes it all back.
            if (grams == null) stock.setOnHand(key.id, true) else stock.addToStock(key.id, start, grams)
            q.deleteCheck(userId, start.toString(), key.toString())
        }
        return get(weekStart)
    }

    /**
     * The row of what the week's meals need under [key], as the list shows it now, with the offers and
     * stock its saving goes by; null for a key of another kind, or one the meals don't need. The list
     * without its advice ([list]): that is the costly part, and an own item's tick is kept elsewhere.
     */
    private fun neededRow(start: LocalDate, key: ShoppingKey): ShoppingItem? {
        if (key !is ShoppingKey.Ingredient && key !is ShoppingKey.Text) return null
        return list(start, advised = false).items.firstOrNull { it.key == key.toString() }
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
        val list = get(weekStart)
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

    /**
     * Ticks an own item off as bought in the week starting [start], or back on. Ticked off, it is on
     * that week's list only; un-ticked, it is back on every list from the week it was added in. Ticking
     * one that is not on this week's list (added later, or bought in another week) is refused (409);
     * un-ticking one that was not ticked off in this week changes nothing. Another account's is a 404.
     */
    private fun setBought(start: LocalDate, id: Long, bought: Boolean) = db.transaction {
        val week = start.toString()
        val changed = if (bought) q.tickExtra(week, userId, id).value else q.untickExtra(userId, id, week).value
        if (changed > 0) return@transaction
        val item = q.selectExtra(userId, id).executeAsOneOrNull() ?: throw NotFoundException("No own item with id $id")
        if (bought) throw ConflictException("${item.name} is not on the shopping list of the week of $week")
    }

    /**
     * Puts an own item, such as coffee, on the list of the week starting [weekStart] and every week
     * after until it is ticked off. The name is trimmed; blank or longer than
     * [ShoppingExtraInput.MAX_NAME_LENGTH] characters is refused (400), as is a new item when the
     * account has [ShoppingExtraInput.MAX_OPEN] that are not ticked off (409).
     */
    fun addExtra(weekStart: String, input: ShoppingExtraInput): ShoppingList {
        val week = parseWeekStart(weekStart).toString()
        val name = input.name.trim()
        require(name.isNotEmpty()) { "name must not be blank" }
        require(name.codePointCount(0, name.length) <= ShoppingExtraInput.MAX_NAME_LENGTH) {
            "name must be at most ${ShoppingExtraInput.MAX_NAME_LENGTH} characters"
        }
        db.transaction {
            // Inserted first, then counted: the insert takes the write lock, so two adds at once
            // can't both pass the count; over the limit, the transaction takes it back.
            q.insertExtra(userId, name, week)
            if (q.countOpenExtras(userId).executeAsOne() > ShoppingExtraInput.MAX_OPEN) {
                throw ConflictException(
                    "The shopping list has ${ShoppingExtraInput.MAX_OPEN} own items that are not ticked off, " +
                        "the most it can have: tick off or delete some first",
                )
            }
        }
        return get(weekStart)
    }

    /** Takes an own item off every list for good. Another account's is a 404. */
    fun deleteExtra(weekStart: String, id: Long): ShoppingList {
        // Checked first, so that a bad week deletes nothing.
        parseWeekStart(weekStart)
        if (q.deleteExtra(userId, id).value == 0L) throw NotFoundException("No own item with id $id")
        return get(weekStart)
    }

    /** Hides one of the advice groups of the week's list, or with [dismissed] false shows it again. Nothing else changes. */
    fun setDismissed(weekStart: String, group: ShoppingAdviceGroup, dismissed: Boolean): ShoppingList {
        val start = parseWeekStart(weekStart)
        val key = ShoppingKey.Dismissed(group).toString()
        if (dismissed) q.insertCheck(userId, start.toString(), key, null, null, null, null, null) else q.deleteCheck(userId, start.toString(), key)
        return get(weekStart)
    }
}

/**
 * The order of the walk through the shop, as [shoppingNeeds] gives it: by section, then by name, the
 * name of the ingredient a row is for where it has one. So an own item named by an alias
 * ("Olijfolie") sorts next to the meals' row of its ingredient ("Olive oil"), after it, as the sort
 * keeps the meals' rows first among equals (issue 116).
 */
private val walkingOrder = compareBy<ShoppingNeed>({ it.section.ordinal }, { (it.ingredient?.name ?: it.name).lowercase() })
