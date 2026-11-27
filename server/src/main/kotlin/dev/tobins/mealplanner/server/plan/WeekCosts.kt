package dev.tobins.mealplanner.server.plan

import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.PlannedMeal
import dev.tobins.mealplanner.api.RecipeCost
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.api.WeekRest
import dev.tobins.mealplanner.planner.OpenPacks
import dev.tobins.mealplanner.planner.Pack
import dev.tobins.mealplanner.pricing.Pricing
import dev.tobins.mealplanner.server.ingredients.StockRepository
import dev.tobins.mealplanner.server.plan.WeekMeals.Companion.DAYS
import dev.tobins.mealplanner.server.recipes.RecipeCandidates
import dev.tobins.mealplanner.shopping.bestOffer
import java.time.LocalDate

/**
 * What a week costs and gives: the prices and offers of the week at the account's stores, what each meal and the whole
 * week cost with them, the kcal, protein and fibre of a day, and the small rests of fresh packs the week's shopping
 * leaves. The Plan ([WeekView]) and the planning screen ([PlanningRepository]) count with the same rules here.
 */
class WeekCosts(
    private val weekMeals: WeekMeals,
    private val recipes: RecipeCandidates,
    private val stock: StockRepository,
    private val adjustments: MealAdjustments,
    private val foods: SlotFoods,
    /** Offers valid between two dates at every store, grouped by ingredient id. */
    private val offersByIngredient: (LocalDate, LocalDate) -> Map<Long, List<Offer>>,
    /** Prices with the offers valid between two dates, at the stores the account shops at. */
    private val pricing: (LocalDate, LocalDate) -> Pricing,
    /** The stores the account shops at: only their offers make the planner prefer a meal. */
    private val shops: () -> Set<Store>,
    /** The packs of the account's fresh ingredients, by ingredient id: the planner uses up the rest of an opened one. */
    private val freshPacks: () -> Map<Long, Pack>,
) {
    /** The prices of the week starting [start], with its offers at the account's stores. */
    internal fun prices(start: LocalDate): Pricing = pricing(start, start.plusDays(DAYS - 1L))

    /** The best offer of the week starting [start] at the account's stores for each ingredient on offer there, by ingredient id. */
    internal fun bestOffers(start: LocalDate): Map<Long, Offer> {
        val inUse = shops()
        return offersByIngredient(start, start.plusDays(DAYS - 1L)).mapNotNull { (id, offers) -> bestOffer(offers.filter { it.store in inUse })?.let { id to it } }.toMap()
    }

    /**
     * The ingredients on offer during the week starting [start] at a store the account shops at:
     * those the planner prefers. The shopping list asks it for next week, to buy ahead only what
     * will not be on offer then.
     */
    fun onOffer(start: LocalDate): Set<Long> {
        val inUse = shops()
        return offersByIngredient(start, start.plusDays(DAYS - 1L)).filterValues { offers -> offers.any { it.store in inUse } }.keys
    }

    /** What a serving of each recipe costs with the prices of the week starting [start], each recipe looked up once. */
    internal fun costsIn(start: LocalDate): (Long) -> RecipeCost? {
        val prices = prices(start)
        val costs = HashMap<Long, RecipeCost?>()
        return { recipeId -> costs.getOrPut(recipeId) { recipes.cost(recipeId, prices) } }
    }

    /**
     * The [kept] meals of the week starting [start], as stored, and [chosen] ones not stored yet, with the numbers the Plan
     * gives them ([WeekView]): a kept meal's as adjusted, the fibre of each, and what each costs ([costOf]).
     */
    internal fun withNumbers(start: LocalDate, kept: List<PlannedMeal>, chosen: List<PlannedMeal>, costOf: (Long) -> RecipeCost?): List<PlannedMeal> =
        (foods.withFoods(adjustments.withNutrition(start, kept)) + adjustments.withNutrition(start, chosen, changes = emptyMap())).map { priced(it, costOf) }

    /**
     * [meal] with what its portions cost with the week's offers ([costOf]), the foods next to its recipe with it (issue 358),
     * and its side's. Not known when its recipe's or its foods' cost is not.
     */
    internal fun priced(meal: PlannedMeal, costOf: (Long) -> RecipeCost?): PlannedMeal = meal.copy(
        cost = pricedParts(meal).map { (id, portions) -> costOf(id)?.let { it.perServingWithOffers * portions } }.takeIf { null !in it }?.sumOf { it ?: 0.0 },
        side = meal.side?.let { side -> side.copy(cost = costOf(side.recipeId)?.let { it.perServingWithOffers * side.portions }) },
    )

    /** What [meal] is priced as, each a recipe and its portions: its own recipe, and one portion of the foods next to it. */
    private fun pricedParts(meal: PlannedMeal): List<Pair<Long, Double>> =
        listOfNotNull(meal.recipeId to meal.portions, meal.foodsId?.takeUnless { meal.foodsOnly }?.let { it to 1.0 })

    /**
     * What [meals] cost with the week's offers and at regular prices: of each meal's main and side whose cost is known
     * ([costOf]); a skipped meal is not eaten and counts for nothing, nor does its side. Nulls when no cost is known.
     */
    internal fun costOfWeek(meals: List<PlannedMeal>, costOf: (Long) -> RecipeCost?): Pair<Double?, Double?> {
        val eaten = meals.filter { it.status != MealStatus.SKIPPED }
        val priced = eaten.flatMap(::pricedParts) + eaten.mapNotNull { it.side }.map { it.recipeId to it.portions }
        val known = priced.mapNotNull { (id, portions) -> costOf(id)?.let { it to portions } }.takeIf { it.isNotEmpty() } ?: return null to null
        return known.sumOf { (cost, portions) -> cost.perServingWithOffers * portions } to known.sumOf { (cost, portions) -> cost.perServing * portions }
    }

    /** What [meals] give of kcal, protein and fibre, sides included; a skipped meal counts for nothing. */
    internal class Sums(val kcal: Double, val proteinG: Double, val fibreG: Double)

    /** [Sums] of [meals]: what a day of the Plan shows, and the planning screen's totals. */
    internal fun sums(meals: List<PlannedMeal>): Sums {
        val counted = meals.filter { it.status != MealStatus.SKIPPED }
        return Sums(counted.sumOf { it.totalKcal }, counted.sumOf { it.totalProteinG }, counted.sumOf { (it.fibreG ?: 0.0) + (it.side?.fibreG ?: 0.0) })
    }

    /**
     * The small rests of fresh packs the week's shopping leaves (issue 244), by the bookkeeping the planner uses
     * ([OpenPacks.smallRestsOf]) over the meals as the shopping list buys them ([WeekMeals.forShopping]): a prepped batch
     * is one purchase of all its portions, sides included, on the day it is made, and a skipped meal buys nothing.
     */
    internal fun restOf(start: LocalDate, week: List<PlannedMeal>): List<WeekRest> {
        val packs = freshPacks().takeIf { it.isNotEmpty() } ?: return emptyList()
        val onHand = stock.onHandFor(start)
        val bought = weekMeals.forShopping(start, week).filter { it.status != MealStatus.SKIPPED }
        val candidates = bought.map { it.recipeId }.distinct().mapNotNull { recipes.candidate(it, packs, onHand) }.associateBy { it.recipeId }
        val rests = OpenPacks.smallRestsOf(bought, candidates).takeIf { it.isNotEmpty() } ?: return emptyList()
        val names = recipes.ingredients.list().associate { it.id to it.name }
        return rests.mapNotNull { rest -> names[rest.ingredientId]?.let { WeekRest(it, rest.grams, rest.euros) } }
    }
}
