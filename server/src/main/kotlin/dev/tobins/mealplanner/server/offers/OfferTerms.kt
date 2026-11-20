package dev.tobins.mealplanner.server.offers

import dev.tobins.mealplanner.api.OfferExclusion
import dev.tobins.mealplanner.api.Store

/**
 * What one account's offers are matched with ([OfferMatcher]): each ingredient's names and aliases, by ingredient id,
 * and the matches the account said are wrong ([excluded]). One type for both, so that no place that matches an
 * account's offers can leave the exclusions out.
 */
data class OfferTerms(val names: Map<Long, List<String>>, val excluded: Set<ExcludedMatch> = emptySet())

/** The account said that the offers of [store] whose title has [titleKey] ([OfferExclusion.titleKey]) are not [ingredientId]. */
data class ExcludedMatch(val store: Store, val titleKey: String, val ingredientId: Long)
