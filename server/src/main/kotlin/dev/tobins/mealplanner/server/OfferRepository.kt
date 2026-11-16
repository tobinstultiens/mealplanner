package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.OfferSourceStatus
import dev.tobins.mealplanner.api.OffersState
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.offers.LidlGuard
import dev.tobins.mealplanner.server.offers.OfferMatcher
import dev.tobins.mealplanner.server.offers.OfferSource
import dev.tobins.mealplanner.server.offers.StoreImages
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Weekly store offers: fetching them per store and keeping the latest successful result. */
class OfferRepository(private val db: Database, private val sources: List<OfferSource>) {
    private val q = db.planQueries
    private val history = PriceHistoryRepository(db)
    private val log = LoggerFactory.getLogger(OfferRepository::class.java)

    /**
     * Offers valid at some point between [from] and [until], grouped by the catalogue
     * ingredient they are for. [terms] are each ingredient's names and aliases.
     */
    fun byIngredient(from: LocalDate, until: LocalDate, terms: Map<Long, List<String>>): Map<Long, List<Offer>> =
        OfferMatcher(terms).group(q.selectOffersOverlapping(until = until.toString(), from = from.toString()).executeAsList().map { it.toOffer() })

    /**
     * Offers valid on [date] with the store statuses. With [terms] (see [byIngredient])
     * each offer also names the ingredient it matches.
     */
    fun state(date: LocalDate = LocalDate.now(), terms: Map<Long, List<String>>? = null, names: Map<Long, String> = emptyMap()): OffersState {
        val matcher = terms?.let(::OfferMatcher)
        val counts = q.countOffers().executeAsList().associate { it.store to it.n.toInt() }
        val recorded = q.selectSources().executeAsList().associateBy { it.store }
        return OffersState(
            offers = q.selectOffersValidOn(date.toString()).executeAsList().map { row ->
                val offer = row.toOffer()
                matcher?.match(offer)?.let { offer.copy(ingredientId = it, ingredientName = names[it]) } ?: offer
            },
            sources = Store.entries.map { store ->
                val row = recorded[store.name]
                OfferSourceStatus(store, row?.fetchedAt, counts[store.name] ?: 0, row?.error, warning = if (store == Store.LIDL) lidlWarning(date) else null)
            },
        )
    }

    /**
     * Warnings about the stored offers as of [date], for the server log; the same texts reach
     * the apps as [OfferSourceStatus.warning]. Only Lidl's page needs one so far.
     */
    fun warnings(date: LocalDate): List<String> = listOfNotNull(lidlWarning(date))

    /**
     * What is wrong with the stored offers as of [now], for `GET /health/data`: a store whose last
     * successful fetch is older than [STALE_AFTER] (with the error of its last failed fetch, when it
     * has one), a store that never succeeded and keeps failing, and the [warnings]. Empty when all is well.
     * A store with nothing recorded yet is not a problem: a new server fills in within hours.
     */
    fun problems(now: Instant): List<String> {
        val stale = q.selectSources().executeAsList().mapNotNull { row ->
            val store = Store.valueOf(row.store)
            val last = row.fetchedAt?.let(Instant::parse)
            val because = row.error?.let { " ($it)" }.orEmpty()
            when {
                last == null && row.error != null -> "No offers from ${store.label} were ever fetched$because"
                last != null && Duration.between(last, now) > STALE_AFTER ->
                    "The offers of ${store.label} were last fetched at ${row.fetchedAt}, more than ${STALE_AFTER.toHours()} hours ago$because"
                else -> null
            }
        }
        return stale + warnings(LocalDate.ofInstant(now, AMSTERDAM))
    }

    private fun lidlWarning(date: LocalDate): String? {
        // The widest range: the guard needs the ended offers too.
        val ends = q.selectOffersOverlapping(until = "9999-12-31", from = "0000-01-01").executeAsList()
            .filter { it.store == Store.LIDL.name }.map { LocalDate.parse(it.validUntil) }
        return LidlGuard.warning(ends, date)
    }

    private fun dev.tobins.mealplanner.server.db.Offer.toOffer() =
        Offer(Store.valueOf(store), title, deal, price, originalPrice, packSize, category, validFrom, validUntil, url, imageUrl = imageUrl)

    /**
     * Fetches every store, or only those whose last successful fetch is older than
     * [olderThan]. A store that fails keeps its previous offers and records the error;
     * the other stores are not affected. What a store offers is also kept in the price
     * history once its offers are stored ([recordHistory]); a store that fails records
     * nothing there either.
     */
    suspend fun refresh(olderThan: Duration? = null, now: Instant = Instant.now()): OffersState {
        val fetched = q.selectSources().executeAsList().associate { it.store to it.fetchedAt }
        val ingredientOf by lazy { history.ingredientNames() }
        for (source in sources) {
            val last = fetched[source.store.name]?.let(Instant::parse)
            if (olderThan != null && last != null && Duration.between(last, now) < olderThan) continue
            try {
                val offers = source.fetch()
                val today = LocalDate.ofInstant(now, AMSTERDAM)
                refusal(source.store, offers.size, q.selectOffersValidOn(today.toString()).executeAsList().count { it.store == source.store.name })
                    ?.let { throw RefusedResult(it) }
                db.transaction {
                    q.deleteOffers(source.store.name)
                    offers.forEach {
                        q.insertOffer(
                            it.store.name, it.title, it.deal, it.price, it.originalPrice, it.packSize, it.category, it.validFrom, it.validUntil, it.url,
                            // The apps load it: only a picture on a store's image host, whatever the page said.
                            StoreImages.accept(it.imageUrl),
                        )
                    }
                    q.ensureSource(source.store.name)
                    q.recordSuccess(now.toString(), source.store.name)
                }
                log.info("Fetched ${offers.size} offers from ${source.store.label}")
                // Looked up inside, so that a failure to read the catalogues is the history's too.
                recordHistory(source.store, offers, { ingredientOf(it) }, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RefusedResult) {
                // Said loudly: the nightly job has nobody watching, and this means a store's page probably changed.
                log.error("Refused the offers of ${source.store.label}, kept the stored ones: ${e.message}")
                q.ensureSource(source.store.name)
                q.recordFailure(e.message!!, source.store.name)
            } catch (e: Exception) {
                log.warn("Could not fetch offers from ${source.store.label}: ${e.message}")
                q.ensureSource(source.store.name)
                q.recordFailure(e.message ?: e::class.simpleName ?: "unknown error", source.store.name)
            }
        }
        val today = LocalDate.ofInstant(now, AMSTERDAM)
        warnings(today).forEach { log.warn(it) }
        return state(today)
    }

    /** A fetch that parsed but cannot be the store's real week; thrown to keep the stored offers like a failed fetch. */
    private class RefusedResult(message: String) : Exception(message)

    /**
     * Why a fetch of [fetched] offers must not replace the [valid] stored ones that are still valid
     * today, or null when it may. None of the store pages is an official API: when one changes shape the
     * parser finds nothing or a few, and storing that would wipe good offers without an error.
     * Nothing valid stored means anything is better than nothing.
     */
    private fun refusal(store: Store, fetched: Int, valid: Int): String? = when {
        fetched == 0 -> "${store.label} returned no offers; kept the stored ones"
        valid > 0 && fetched * MIN_SHARE_DENOMINATOR < valid ->
            "${store.label} returned $fetched offers where $valid stored ones are still valid; kept the stored ones"
        else -> null
    }

    /**
     * Keeps what [store]'s refresh saw in the price history, in a transaction of its own once the
     * offers are stored: the history is kept on the side, so whatever goes wrong there is logged and
     * never costs the store its offers (issue 116). A failed recording writes nothing, so the history
     * stays as the last good one left it, and the next good refresh continues its periods from there:
     * no gap, and nothing twice.
     */
    private fun recordHistory(store: Store, offers: List<Offer>, ingredientOf: (Offer) -> String?, now: Instant) {
        try {
            db.transaction {
                history.pruneOffers(now)
                history.recordOffers(store, offers, ingredientOf, now)
            }
        } catch (e: Exception) {
            log.warn("Could not keep the offers of ${store.label} in the price history; the offers themselves are stored", e)
        }
    }

    companion object {
        /** How long a store's offers may go without a successful fetch before the health check says so (the job runs every six hours). */
        val STALE_AFTER: Duration = Duration.ofHours(48)

        private val AMSTERDAM: java.time.ZoneId = java.time.ZoneId.of("Europe/Amsterdam")

        /**
         * A result with fewer than 1 in this many of the valid stored offers is refused. A third: a quiet
         * week is rarely under half of a big one, while a changed page typically yields none or a handful.
         * A truly small week after a big one is refused once and accepted the next time, when the big
         * week's offers have ended.
         */
        private const val MIN_SHARE_DENOMINATOR = 3
    }
}
