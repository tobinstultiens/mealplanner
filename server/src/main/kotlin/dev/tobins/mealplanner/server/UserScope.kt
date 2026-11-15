package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.CookingSyncStatus
import dev.tobins.mealplanner.api.SyncResult
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.generation.RecipeGenerator
import org.slf4j.LoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The repositories of one account. Built per request; they hold no state of their own,
 * so this is cheap. Everything a route reads or writes for a user goes through here,
 * which is what keeps one account's data away from another's.
 */
class UserScope(
    val accountId: Long,
    db: Database,
    foods: FoodRepository,
    offers: OfferRepository,
    prices: PriceRepository,
    generator: RecipeGenerator?,
    /** Where recipe pictures are kept, for every account (see [RecipePictures]). */
    imageDir: File,
) {
    val ingredients = IngredientRepository(db, foods, accountId)
    val shops = ShopsRepository(db, accountId)
    val recipes = RecipeRepository(db, ingredients, accountId) { from, until -> prices.pricing(ingredients, from, until, shops.inUse()) }
    val profiles = ProfileRepository(db, accountId)
    val pictures = RecipePictures(db, imageDir, accountId)
    val sections = ShopSections(ingredients, prices)
    val shoppingAdvice = ShoppingAdviceRepository(db, accountId)
    val plans by lazy {
        PlanRepository(
            db, accountId, recipes, profiles, ingredients,
            offersByIngredient = { from, until -> prices.offers(ingredients, from, until) },
            pricing = { from, until -> prices.pricing(ingredients, from, until, shops.inUse()) },
            shops = shops::inUse,
            regularPerKg = { prices.perKg(ingredients) },
            sections = sections::byIngredient,
            keeps = sections::keepsByIngredient,
            advice = shoppingAdvice::setting,
            productImages = { prices.productImages(ingredients) },
        )
    }
    val tracking by lazy { TrackingRepository(db, accountId, plans, profiles) }
    val generation by lazy { generator?.let { GenerationService(it, recipes, ingredients, profiles, plans, offers, shops::inUse) } }
}

/**
 * Imports each account's recipes from its own cooking-site repository, set in the app.
 *
 * Account 1 (the owner, who had the data before there were accounts) also falls back to the
 * server-wide `COOKING_REPO_URL` / `COOKING_REPO_PATH` until they set a repository of their own.
 */
class CookingSync(
    private val users: UserRepository,
    /** Where accounts' clones go: `<dataDir>/users/<id>/cooking`. */
    private val dataDir: File,
    private val legacyPath: File?,
    private val legacyUrl: String?,
    private val scope: (Long) -> UserScope,
) {
    // One sync at a time per account: the scheduled one and a request must not clone into one directory.
    private val locks = ConcurrentHashMap<Long, Mutex>()

    fun repoFor(accountId: Long): CookingRepo? {
        users.meta(accountId, REPO_KEY)?.let { return CookingRepo(cloneDir(accountId), it) }
        if (accountId != OWNER_ID) return null
        return when {
            legacyUrl != null -> CookingRepo(legacyPath ?: cloneDir(accountId), legacyUrl)
            legacyPath != null -> CookingRepo(legacyPath, null)
            else -> null
        }
    }

    private fun cloneDir(accountId: Long) = File(dataDir, "users/$accountId/cooking")

    /** Accounts whose repository the server fetches itself, so syncs them on a schedule. */
    fun scheduled(): List<Long> = users.all().filter { repoFor(it)?.url != null }

    /** Sets or clears the account's repository; a new one is cloned afresh on the next sync. */
    fun setRepo(accountId: Long, url: String?) {
        val clean = url?.trim()?.takeIf { it.isNotEmpty() }
        require(clean == null || clean.startsWith("https://") || clean.startsWith("http://") || clean.startsWith("git@")) {
            "The repository must be a git URL such as https://github.com/you/cooking.git"
        }
        if (clean != users.meta(accountId, REPO_KEY)) {
            cloneDir(accountId).deleteRecursively()
            // The pictures came from the old site; the next sync brings the new site's.
            scope(accountId).pictures.removeAll()
        }
        users.setMeta(accountId, REPO_KEY, clean)
    }

    /** Fetches the account's repository if the server manages it, then imports its recipes. Null without one. */
    suspend fun sync(accountId: Long): SyncResult? = locks.getOrPut(accountId) { Mutex() }.withLock {
        withContext(Dispatchers.IO) {
            val repo = repoFor(accountId) ?: return@withContext null
            try {
                val commit = repo.update()
                val pages = CookingSiteReader(repo).readAll() ?: return@withContext null
                val user = scope(accountId)
                user.recipes.syncCookingSite(pages).copy(autoLinked = user.ingredients.autoLink(), commit = commit).also {
                    users.setMeta(accountId, SYNCED_AT_KEY, Instant.now().toString())
                    users.setMeta(accountId, SYNCED_COMMIT_KEY, it.commit)
                    users.setMeta(accountId, SYNC_ERROR_KEY, null)
                    syncPictures(accountId, user, pages)
                }
            } catch (e: Exception) {
                users.setMeta(accountId, SYNC_ERROR_KEY, e.message ?: e::class.simpleName ?: "unknown error")
                throw e
            }
        }
    }

    /** Pictures are extras: a problem with them is logged, and never fails the sync of the recipes. */
    private fun syncPictures(accountId: Long, user: UserScope, pages: List<SitePage>) {
        try {
            val outcome = user.pictures.sync(pages)
            if (outcome.stored + outcome.removed + outcome.skipped > 0) {
                log.info("Recipe pictures of account $accountId: ${outcome.stored} stored, ${outcome.removed} removed, ${outcome.skipped} skipped, ${outcome.unchanged} unchanged")
            }
        } catch (e: Exception) {
            log.warn("Recipe pictures of account $accountId could not be stored: ${e.message}")
        }
    }

    fun status(accountId: Long): CookingSyncStatus {
        val repo = repoFor(accountId)
        return CookingSyncStatus(
            syncedAt = users.meta(accountId, SYNCED_AT_KEY),
            commit = users.meta(accountId, SYNCED_COMMIT_KEY),
            // Older versions stored "no error" as an empty value.
            error = users.meta(accountId, SYNC_ERROR_KEY)?.takeIf { it.isNotBlank() },
            everyHours = if (repo?.url != null) EVERY_HOURS else null,
            repoUrl = repo?.url,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(CookingSync::class.java)

        /** The account that holds the data from before accounts existed. */
        const val OWNER_ID = 1L

        /** The cooking site changes rarely; four times a day picks up edits the same day. */
        const val EVERY_HOURS = 6
        private const val REPO_KEY = "cookingRepoUrl"
        private const val SYNCED_AT_KEY = "cookingSyncedAt"
        private const val SYNCED_COMMIT_KEY = "cookingSyncedCommit"
        private const val SYNC_ERROR_KEY = "cookingSyncError"
    }
}
