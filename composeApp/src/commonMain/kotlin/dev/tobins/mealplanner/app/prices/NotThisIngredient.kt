package dev.tobins.mealplanner.app.prices

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.OfferExclusion
import dev.tobins.mealplanner.api.OfferExclusionRequest
import dev.tobins.mealplanner.api.OffersState
import dev.tobins.mealplanner.api.ShoppingItem
import dev.tobins.mealplanner.api.ShoppingList
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.app.ui.Load
import kotlinx.serialization.Serializable

/**
 * "Not this ingredient" (issue 316) made on this device ([excluded]) or taken back, as it waits to be sent: kept on the
 * device until the server has it ([OfferExclusionQueue]). Stored as JSON, so fields are only added, with defaults.
 */
@Serializable
internal data class ExclusionChange(
    val store: Store,
    /** The offer's title as the store wrote it; the server keeps it by its [OfferExclusion.titleKey]. */
    val title: String,
    val ingredientId: Long,
    /** For the list in Settings and the snackbar, before the server has answered with its own. */
    val ingredientName: String,
    val excluded: Boolean,
    /** When it was made, as an ISO instant. */
    val at: String,
) {
    /** What the server keeps it by: the same store, title key and ingredient are one exclusion, whatever the pack. */
    val key: ExclusionKey get() = ExclusionKey(store, OfferExclusion.titleKey(title), ingredientId)

    val request: OfferExclusionRequest get() = OfferExclusionRequest(store, title, ingredientId)

    /** The exclusion this makes, as the server would list it. */
    fun asExclusion() = OfferExclusion(store, title, key.titleKey, ingredientId, ingredientName, at)
}

/** What one exclusion is kept by, on the server as here. */
internal data class ExclusionKey(val store: Store, val titleKey: String, val ingredientId: Long)

internal val OfferExclusion.key: ExclusionKey get() = ExclusionKey(store, titleKey, ingredientId)

/**
 * The exclusions this device goes by: the server's [known] list with the [waiting] changes laid over it, the newest
 * first. A waiting exclusion counts at once, and a waiting take-back takes one out at once, so the screens follow what
 * the user chose and not what the server has heard so far.
 */
internal fun exclusionsInEffect(known: List<OfferExclusion>, waiting: List<ExclusionChange>): List<OfferExclusion> {
    if (waiting.isEmpty()) return known
    val changed = waiting.associateBy { it.key }
    val knownKeys = known.map { it.key }.toSet()
    val added = waiting.filter { it.excluded && it.key !in knownKeys }.reversed().map { it.asExclusion() }
    return added + known.filter { changed[it.key]?.excluded != false }
}

/** Whether [offer], matched to ingredient [ingredientId], is one that [exclusions] leave out. */
internal fun excludes(exclusions: List<OfferExclusion>, offer: Offer, ingredientId: Long?): Boolean {
    if (ingredientId == null || exclusions.none { it.ingredientId == ingredientId && it.store == offer.store }) return false
    val key = ExclusionKey(offer.store, OfferExclusion.titleKey(offer.title), ingredientId)
    return exclusions.any { it.key == key }
}

/**
 * The ingredient of a row of the meals' needs (`i7`), of buying ahead (`ahead:i7`) or of stocking up (`stock:i7`): what
 * its offers were matched to. Null for an own item and a recipe line linked to nothing, which have no offers.
 */
internal val ShoppingItem.ingredientId: Long?
    get() = INGREDIENT_KEYS.firstNotNullOfOrNull { prefix -> key.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toLongOrNull() }

/** The key prefixes of the rows of an ingredient, the longer first: `ahead:i7` is not read as `i`. */
private val INGREDIENT_KEYS = listOf("ahead:i", "stock:i", "i")

/**
 * The list without the offers [exclusions] leave out, on every row that has offers: the chip, its saving and its verdict
 * go with the offer, and an advice row left without one is no longer shown ([adviceAt]). What a ticked row saved stays,
 * as the server keeps it too. The server leaves them out itself once it has the exclusion; this shows it at once, also
 * offline, and keeps a list fetched before it from bringing the deal back.
 */
internal fun ShoppingList.withoutExcluded(exclusions: List<OfferExclusion>): ShoppingList {
    if (exclusions.isEmpty()) return this
    fun List<ShoppingItem>.kept() = map { item ->
        val id = item.ingredientId
        if (item.offers.none { excludes(exclusions, it, id) }) item else item.copy(offers = item.offers.filterNot { excludes(exclusions, it, id) })
    }
    return copy(items = items.kept(), buyAhead = buyAhead.kept(), stockUp = stockUp.kept())
}

/** The offers with the matches [exclusions] leave out undone: no ingredient and no verdict, which is about the account's own offers. */
internal fun OffersState.withoutExcluded(exclusions: List<OfferExclusion>): OffersState {
    if (exclusions.isEmpty()) return this
    return copy(offers = offers.map { if (excludes(exclusions, it, it.ingredientId)) it.copy(ingredientId = null, ingredientName = null, verdict = null) else it })
}

/** [load]'s list without what [exclusions] leave out ([withoutExcluded]). */
internal fun Load<ShoppingList>.withoutExcluded(exclusions: List<OfferExclusion>): Load<ShoppingList> =
    if (this is Load.Ready && exclusions.isNotEmpty()) Load.Ready(value.withoutExcluded(exclusions)) else this
