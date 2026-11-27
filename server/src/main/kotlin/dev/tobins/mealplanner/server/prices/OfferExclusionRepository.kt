package dev.tobins.mealplanner.server.prices

import dev.tobins.mealplanner.api.OfferExclusion
import dev.tobins.mealplanner.api.OfferExclusionRequest
import dev.tobins.mealplanner.api.OfferExclusions
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.NotFoundException
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.ingredients.IngredientRepository
import dev.tobins.mealplanner.server.writing
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The offers one account said are not one of its ingredients ("Not this ingredient", issue 316), kept by store, the
 * offer's [OfferExclusion.titleKey] and the ingredient. They take effect where the account's offers are matched
 * ([IngredientRepository.matchTerms]); the shared offers and price history stay as they are, so another account is
 * not affected. Every request is idempotent: the app may send one twice, or late from a phone that was offline.
 */
class OfferExclusionRepository(
    private val db: Database,
    private val userId: Long,
    private val ingredients: IngredientRepository,
    private val now: () -> Instant = Instant::now,
) {
    private val q = db.offerExclusionQueries

    /** The account's exclusions, the latest first (by the second, then in the order they were made). */
    fun list(): OfferExclusions = OfferExclusions(
        q.selectExclusions(userId).executeAsList().map {
            OfferExclusion(Store.valueOf(it.store), it.title, it.titleKey, it.ingredientId, it.ingredientName, it.excludedAt)
        },
    )

    /**
     * Remembers that offers like [request]'s are not its ingredient. One already remembered keeps its first title and
     * time. An ingredient that is not the account's (another account's, or one gone since a phone queued the request)
     * is a 404, so nothing is stored and the app's queue lets the request go.
     */
    fun exclude(request: OfferExclusionRequest): OfferExclusions {
        val key = keyOf(request.title)
        db.writing {
            ingredients.find(request.ingredientId) ?: throw NotFoundException("No ingredient with id ${request.ingredientId}")
            q.insertExclusion(userId, request.store.name, key, request.ingredientId, request.title.trim(), now().truncatedTo(ChronoUnit.SECONDS).toString())
        }
        return list()
    }

    /** Takes an exclusion back; one the account does not have (taken back already) changes nothing. */
    fun takeBack(request: OfferExclusionRequest): OfferExclusions {
        val key = keyOf(request.title)
        db.writing { q.deleteExclusion(userId, request.store.name, key, request.ingredientId) }
        return list()
    }

    /** The key of [title]; a title over [OfferExclusionRequest.MAX_TITLE_LENGTH] characters, or one without words, is refused (400). */
    private fun keyOf(title: String): String {
        val trimmed = title.trim()
        require(trimmed.codePointCount(0, trimmed.length) <= OfferExclusionRequest.MAX_TITLE_LENGTH) {
            "title must be at most ${OfferExclusionRequest.MAX_TITLE_LENGTH} characters"
        }
        return OfferExclusion.titleKey(trimmed).also { require(it.isNotEmpty()) { "The offer's title has no words: \"$trimmed\"" } }
    }
}
