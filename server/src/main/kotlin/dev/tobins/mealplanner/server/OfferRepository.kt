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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not fetch offers from ${source.store.label}: ${e.message}")
                q.ensureSource(source.store.name)
                q.recordFailure(e.message ?: e::class.simpleName ?: "unknown error", source.store.name)
            }
        }
        val today = LocalDate.ofInstant(now, java.time.ZoneId.of("Europe/Amsterdam"))
        warnings(today).forEach { log.warn(it) }
        return state(today)
    }
}
