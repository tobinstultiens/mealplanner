package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.OfferSourceStatus
import dev.tobins.mealplanner.api.OffersState
import dev.tobins.mealplanner.api.Store
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.offers.OfferSource
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Weekly store offers: fetching them per store and keeping the latest successful result. */
class OfferRepository(private val db: Database, private val sources: List<OfferSource>) {
    private val q = db.planQueries
    private val log = LoggerFactory.getLogger(OfferRepository::class.java)

    fun state(date: LocalDate = LocalDate.now()): OffersState {
        val counts = q.countOffers().executeAsList().associate { it.store to it.n.toInt() }
        val recorded = q.selectSources().executeAsList().associateBy { it.store }
        return OffersState(
            offers = q.selectOffersValidOn(date.toString()).executeAsList().map {
                Offer(Store.valueOf(it.store), it.title, it.deal, it.price, it.originalPrice, it.packSize, it.category, it.validFrom, it.validUntil, it.url)
            },
            sources = Store.entries.map { store ->
                val row = recorded[store.name]
                OfferSourceStatus(store, row?.fetchedAt, counts[store.name] ?: 0, row?.error)
            },
        )
    }

    /**
     * Fetches every store, or only those whose last successful fetch is older than
     * [olderThan]. A store that fails keeps its previous offers and records the error;
     * the other stores are not affected.
     */
    suspend fun refresh(olderThan: Duration? = null, now: Instant = Instant.now()): OffersState {
        val fetched = q.selectSources().executeAsList().associate { it.store to it.fetchedAt }
        for (source in sources) {
            val last = fetched[source.store.name]?.let(Instant::parse)
            if (olderThan != null && last != null && Duration.between(last, now) < olderThan) continue
            try {
                val offers = source.fetch()
                db.transaction {
                    q.deleteOffers(source.store.name)
                    offers.forEach {
                        q.insertOffer(it.store.name, it.title, it.deal, it.price, it.originalPrice, it.packSize, it.category, it.validFrom, it.validUntil, it.url)
                    }
                    q.ensureSource(source.store.name)
                    q.recordSuccess(now.toString(), source.store.name)
                }
                log.info("Fetched ${offers.size} offers from ${source.store.label}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not fetch offers from ${source.store.label}: ${e.message}")
                q.ensureSource(source.store.name)
                q.recordFailure(e.message ?: e::class.simpleName ?: "unknown error", source.store.name)
            }
        }
        return state(LocalDate.ofInstant(now, java.time.ZoneId.of("Europe/Amsterdam")))
    }
}
