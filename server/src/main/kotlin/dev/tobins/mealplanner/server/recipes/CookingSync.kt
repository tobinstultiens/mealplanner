package dev.tobins.mealplanner.server.recipes

import dev.tobins.mealplanner.api.CookingSyncStatus
import dev.tobins.mealplanner.api.SyncResult
import dev.tobins.mealplanner.server.UserScope
import dev.tobins.mealplanner.server.accounts.UserRepository
import dev.tobins.mealplanner.server.logged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Imports each account's recipe bank from its cooking site: a repository the account set itself, or else
 * the server's own (`COOKING_REPO_URL` / `COOKING_REPO_PATH`), unless the account chose to have none.
 *
 * The server's site is the bank of every account by default; with [shareServerSite] false
 * (`COOKING_REPO_SHARED=0`) only the owner's, as before. Its recipes still become rows of each account's
 * own, so that adding, rating and the rotation stay the account's: the server's site is one clone, fetched
 * once per round of the schedule or when an account asks, and its pages are imported into each account that reads it.
 *
 * Two kinds of lock keep syncs apart. One per account, taken first: no two imports into one account's
 * recipes and pictures, and no change of its site meanwhile. One for the server's clone: no fetch while
 * its files are read. A repository of an account's own is a clone of that account alone, so the account's
 * lock is its clone's lock too.
 *
 * Any account on the server's site may ask for a sync, and a fetch holds the clone's lock (up to the git
 * timeout) while every other account's import waits. So the server's clone is fetched at most once per
 * [fetchInterval] (issue 215): a sync inside it imports the clone as it is, and [status] says when it was fetched.
 */
class CookingSync(
    private val users: UserRepository,
    /** Where accounts' own clones go: `<dataDir>/users/<id>/cooking`. */
    private val dataDir: File,
    /** The server's cooking site; null when it has none. */
    private val serverSite: CookingRepo?,
    /** Whether every account may read [serverSite], or only the owner. */
    private val shareServerSite: Boolean,
    private val scope: (Long) -> UserScope,
    /** The least time between two fetches of [serverSite], from the end of one to the start of the next. */
    private val fetchInterval: Duration = FETCH_INTERVAL,
    /** The time now; a test's clock moves on its own. */
    private val now: () -> Instant = Instant::now,
) {
    private val locks = ConcurrentHashMap<Long, Mutex>()
    private val serverLock = Mutex()

    /** When the last fetch of [serverSite] ended, failed or not; written under [serverLock]. */
    @Volatile
    private var lastFetchEnded: Instant? = null

    /** When [serverSite] was last fetched successfully, for [status]; since the server started. */
    @Volatile
    private var serverFetchedAt: Instant? = null

    private fun lockOf(accountId: Long) = locks.getOrPut(accountId) { Mutex() }

    /** Where an account's bank comes from. */
    private sealed interface Site {
        /** A repository the account set, cloned for it alone. */
        data class Own(val url: String) : Site

        /** The server's cooking site, in the one clone every account on it reads. */
        data object Server : Site

        data object None : Site
    }

    private fun siteOf(accountId: Long): Site =
        siteFor(accountId, users.meta(accountId, REPO_KEY), noSite = users.meta(accountId, NO_SITE_KEY) != null)

    /** The site that [url] (an own repository) or else [noSite] means for the account. */
    private fun siteFor(accountId: Long, url: String?, noSite: Boolean): Site = when {
        url != null -> Site.Own(url)
        !noSite && offersServerSite(accountId) -> Site.Server
        else -> Site.None
    }

    private fun offersServerSite(accountId: Long) = serverSite != null && (shareServerSite || accountId == UserRepository.OWNER_ID)

    private fun repoOf(accountId: Long, site: Site): CookingRepo? = when (site) {
        is Site.Own -> CookingRepo(cloneDir(accountId), site.url)
        Site.Server -> serverSite
        Site.None -> null
    }

    /** The repository the account's bank comes from; null when it has none. */
    fun repoFor(accountId: Long): CookingRepo? = repoOf(accountId, siteOf(accountId))

    private fun cloneDir(accountId: Long) = File(dataDir, "users/$accountId/cooking")

    /**
     * Sets the account's own repository [url], or without one the server's site, or no site at all with
     * [noSite]. Moving to another site leaves the old one at once ([leaveSite]); the new one is imported by
     * the sync the app asks for next, the schedule, or (the server's) the next visit to the bank ([importFirst]).
     */
    suspend fun setRepo(accountId: Long, url: String?, noSite: Boolean = false) {
        val clean = url?.trim()?.takeIf { it.isNotEmpty() }
        require(clean == null || clean.startsWith("https://") || clean.startsWith("http://") || clean.startsWith("git@")) {
            "The repository must be a git URL such as https://github.com/you/cooking.git"
        }
        lockOf(accountId).withLock {
            withContext(Dispatchers.IO) {
                // Left first: should this stop half-way, the old site's next sync brings its recipes back.
                if (siteFor(accountId, clean, noSite) != siteOf(accountId)) leaveSite(accountId)
                users.setMeta(accountId, mapOf(REPO_KEY to clean, NO_SITE_KEY to if (clean == null && noSite) "1" else null))
            }
        }
    }

    /**
     * Forgets the account's site: its clone and its bank's pictures go, its bank's recipes leave the bank, and it counts
     * as never synced. Those it took stay its own as they are, pictures and all (issue 340); a file of the new site at
     * the path of one of them is taken already, so it is not offered.
     */
    private fun leaveSite(accountId: Long) {
        // Never the server's clone, should the configuration put it there.
        cloneDir(accountId).takeIf { it.canonicalFile != serverSite?.dir?.canonicalFile }?.deleteRecursively()
        val user = scope(accountId)
        user.pictures.removeBank()
        user.recipes.syncCookingSite(emptyList())
        users.setMeta(accountId, listOf(SYNCED_AT_KEY, SYNCED_COMMIT_KEY, SYNC_ERROR_KEY).associateWith { null })
    }

    /**
     * Fetches the account's site and imports it, when asked to (`POST /api/sync/cooking`); the server's site only
     * when its last fetch is [fetchInterval] old ([fetchServerSite]). Null without a site, or when it has no
     * `content/` folder.
     */
    suspend fun sync(accountId: Long): SyncResult? = lockOf(accountId).withLock {
        withContext(Dispatchers.IO) {
            when (val site = siteOf(accountId)) {
                is Site.Own -> syncOwn(accountId, CookingRepo(cloneDir(accountId), site.url))
                Site.Server -> importServerSite(accountId, fetch = true)
                Site.None -> null
            }
        }
    }

    /** Fetches a repository of the account's own and imports it; the caller holds the account's lock, which is the clone's. */
    private fun syncOwn(accountId: Long, repo: CookingRepo): SyncResult? = recording(accountId) {
        val commit = repo.update()
        val pages = CookingSiteReader(repo).readAll() ?: return@recording null
        val user = scope(accountId)
        importPages(accountId, user, commit, pages).also { syncPictures(accountId, user, pages) }
    }

    /**
     * Imports the server's site into the account; the caller holds the account's lock. Fetches first when
     * [fetch], or when there is no clone yet, as [fetchServerSite] allows. Reading the pages and the pictures
     * holds the clone's lock, so that no fetch changes its files meanwhile; the import into the database does
     * not, so that another account reading the clone does not wait for it. Imports into several accounts at once
     * still write one after another, each in a transaction of its own ([writing], issue 246).
     */
    private suspend fun importServerSite(accountId: Long, fetch: Boolean): SyncResult? {
        val site = serverSite ?: return null
        return recording(accountId) {
            val (commit, pages) = serverLock.withLock {
                fetchServerSite(site, fetch) to CookingSiteReader(site).readAll()
            }
            if (pages == null) return@recording null
            val user = scope(accountId)
            importPages(accountId, user, commit, pages).also { serverLock.withLock { syncPictures(accountId, user, pages) } }
        }
    }

    /**
     * Fetches the server's [site] when [wanted] or there is no clone yet, unless a fetch ended less than
     * [fetchInterval] ago; answers the commit checked out. The caller holds [serverLock], so that requests at once
     * decide one after another and fetch once. A failed fetch counts too, or a slow remote would hold the lock for
     * every request. Without a clone inside the interval (the last fetch failed) there is nothing to read: that
     * fails at once rather than trying again.
     */
    private fun fetchServerSite(site: CookingRepo, wanted: Boolean): String? {
        if (site.url == null) return null
        val due = fetchDue()
        if (site.cloned() && !(wanted && due)) return site.head()
        check(due) { "The server's cooking site could not be fetched; it is tried again ${fetchInterval.toMinutes()} minutes after the last try" }
        try {
            return site.update().also { serverFetchedAt = now() }
        } finally {
            lastFetchEnded = now()
        }
    }

    /**
     * Whether the last fetch of the server's site ended [fetchInterval] ago or more. Also when the clock went back
     * since: a fetch "in the future" would otherwise block fetching for as long as the clock jumped.
     */
    private fun fetchDue(): Boolean {
        val last = lastFetchEnded ?: return true
        return now() !in last..<last.plus(fetchInterval)
    }

    /** Brings the account's recipes in line with [pages], read at [commit], and records the sync. */
    private fun importPages(accountId: Long, user: UserScope, commit: String?, pages: List<SitePage>): SyncResult =
        user.recipes.syncCookingSite(pages).copy(autoLinked = user.ingredients.autoLink(), commit = commit).also {
            users.setMeta(accountId, mapOf(SYNCED_AT_KEY to now().toString(), SYNCED_COMMIT_KEY to it.commit, SYNC_ERROR_KEY to null))
        }

    /** Runs a sync of the account, keeping its failure for [status] before passing it on. */
    private inline fun <T> recording(accountId: Long, sync: () -> T): T = try {
        sync()
    } catch (e: Exception) {
        if (e !is CancellationException) keepError(accountId, e)
        throw e
    }

    /**
     * Keeps [error] as the account's last sync error for [status]. When even that cannot be written it is
     * logged: the caller goes on, to the next account, or to pass on the sync's own failure rather than this one.
     */
    private fun keepError(accountId: Long, error: Exception) {
        try {
            users.setMeta(accountId, SYNC_ERROR_KEY, messageOf(error))
        } catch (e: Exception) {
            log.warn("The sync error of account $accountId could not be kept (${e.message}): ${error.message}")
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

    /**
     * Imports the server's site into an account that was never synced, on its first visit to the bank, rather
     * than leaving the bank empty until the schedule comes by. Checked again under the account's lock, so that
     * two visits at once import once. It fetches only when there is no clone yet (and not twice in [fetchInterval]),
     * so it waits at most for a fetch that is running already, and other accounts not at all. A failure is kept for
     * [status], and the bank stays as it was; the next visit tries again.
     */
    suspend fun importFirst(accountId: Long) {
        if (!neverSynced(accountId)) return
        lockOf(accountId).withLock {
            if (!neverSynced(accountId)) return@withLock
            try {
                withContext(Dispatchers.IO) { importServerSite(accountId, fetch = false) }
                    ?.let { log.info("Server's cooking site imported into account $accountId: ${it.added} added, ${it.updated} updated, ${it.removed} removed") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.warn("Server's cooking site could not be imported into account $accountId: ${e.message}")
            }
        }
    }

    private fun neverSynced(accountId: Long) = users.meta(accountId, SYNCED_AT_KEY) == null && siteOf(accountId) == Site.Server

    /**
     * One round of the schedule: the server's site is fetched once (unless a sync fetched it less than
     * [fetchInterval] ago, which is as good) and imported into every account that reads it,
     * then each account's own repository is fetched and imported. A site on the server's disk (no URL) is not
     * fetched, so not scheduled. A failure is logged and kept for the account ([status]), and stops nothing else.
     */
    suspend fun syncScheduled() {
        val sites = users.all().associateWith(::siteOf)
        val onServer = sites.filterValues { it == Site.Server }.keys
        if (onServer.isNotEmpty() && serverSite?.url != null) syncServerSite(onServer)
        for ((accountId, site) in sites) {
            if (site is Site.Own) logged(accountId) { sync(accountId) }
        }
    }

    /** Fetches the server's site once, then imports it into each of [accounts] that still reads it. */
    private suspend fun syncServerSite(accounts: Collection<Long>) {
        try {
            serverLock.withLock { withContext(Dispatchers.IO) { fetchServerSite(serverSite!!, wanted = true) } }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.warn("The server's cooking site could not be fetched: ${e.message}")
            for (accountId in accounts) keepError(accountId, e)
            return
        }
        for (accountId in accounts) {
            logged(accountId) {
                lockOf(accountId).withLock {
                    // The account may have chosen another site since the round began.
                    if (siteOf(accountId) != Site.Server) return@withLock null
                    withContext(Dispatchers.IO) { importServerSite(accountId, fetch = false) }
                }
            }
        }
    }

    private suspend fun logged(accountId: Long, sync: suspend () -> SyncResult?) {
        try {
            sync()?.let { log.info("Cooking site of account $accountId synced at ${it.commit}: ${it.added} added, ${it.updated} updated, ${it.removed} removed") }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.warn("Cooking site sync of account $accountId failed: ${e.message}")
        }
    }

    fun status(accountId: Long): CookingSyncStatus {
        val site = siteOf(accountId)
        val repo = repoOf(accountId, site)
        // The server's address may hold a password or token that its owner put there; nobody else sees it.
        fun shown(text: String?) = if (site == Site.Server && accountId != UserRepository.OWNER_ID) text?.let(::withoutCredentials) else text
        return CookingSyncStatus(
            syncedAt = users.meta(accountId, SYNCED_AT_KEY),
            commit = users.meta(accountId, SYNCED_COMMIT_KEY),
            // Older versions stored "no error" as an empty value.
            error = shown(users.meta(accountId, SYNC_ERROR_KEY)?.takeIf { it.isNotBlank() }),
            everyHours = if (repo?.url != null) EVERY_HOURS else null,
            repoUrl = shown(repo?.url),
            shared = site == Site.Server,
            serverSite = offersServerSite(accountId),
            serverRepoUrl = serverSite?.url?.takeIf { offersServerSite(accountId) }?.let(::withoutCredentials),
            fetchedAt = serverFetchedAt?.takeIf { site == Site.Server }?.toString(),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(CookingSync::class.java)

        /** The cooking site changes rarely; four times a day picks up edits the same day. */
        const val EVERY_HOURS = 6

        /**
         * The least time between two fetches of the server's site (`COOKING_REPO_FETCH_INTERVAL_MINUTES`). Short
         * enough that an edit pushed to the site arrives with a Sync a few minutes later; long enough that a
         * fetch running into the git timeout (a minute) holds the clone's lock a sixth of the time at most.
         */
        val FETCH_INTERVAL: Duration = Duration.ofMinutes(5)

        private const val REPO_KEY = "cookingRepoUrl"

        /** Set when the account chose to have no cooking site, not even the server's. */
        private const val NO_SITE_KEY = "cookingNoSite"
        private const val SYNCED_AT_KEY = "cookingSyncedAt"
        private const val SYNCED_COMMIT_KEY = "cookingSyncedCommit"
        private const val SYNC_ERROR_KEY = "cookingSyncError"

        /**
         * The server's cooking site from its configuration: a clone of [url] (in [path], or `cooking` in the
         * data directory), or the checkout at [path] as it is; null without either.
         */
        fun serverSite(path: File?, url: String?, dataDir: File): CookingRepo? = when {
            url != null -> CookingRepo(path ?: File(dataDir, "cooking"), url)
            path != null -> CookingRepo(path, null)
            else -> null
        }

        private fun messageOf(e: Exception) = e.message ?: e::class.simpleName ?: "unknown error"

        /** [text] with the user name and password taken out of every URL in it (`https://user:token@host/…`). */
        fun withoutCredentials(text: String): String = text.replace(Regex("(?<=://)[^/@\\s]+@"), "")
    }
}
