// Frozen from v0.1.10 (de4360a169911ad09794223687f4df8e42a3ebe4) by scripts/freeze-api.sh. Do not edit: freeze again after a release.
package dev.tobins.mealplanner.released.api

import kotlinx.serialization.Serializable

/**
 * "Not this ingredient" (issue 316): an offer the account says is not one of its ingredients, which the server then
 * no longer matches to it, wherever a match is used (the offers, the shopping list, the plan's cost, the planning
 * suggestions, the price verdicts). `PUT /api/offers/exclusions` takes it, `DELETE` with the same three as query
 * parameters takes it back; both are idempotent, so a request sent twice or late from a phone that was offline does no harm.
 */
@Serializable
data class OfferExclusionRequest(
    val store: Store,
    /** The offer's title as the store wrote it; remembered by its [OfferExclusion.titleKey], at most [MAX_TITLE_LENGTH] characters. */
    val title: String,
    /** One of the account's own ingredients. */
    val ingredientId: Long,
) {
    companion object {
        /** The longest title taken (characters, trimmed); the stores' titles of November 2026 had at most 113. */
        const val MAX_TITLE_LENGTH = 300
    }
}

/** One of the account's exclusions, as `GET /api/offers/exclusions` lists them. */
@Serializable
data class OfferExclusion(
    val store: Store,
    /** The offer's title as the store wrote it when the account first excluded it. */
    val title: String,
    /** What the exclusion is kept by: [titleKey] of the title. Every offer of [store] with this key is not the ingredient. */
    val titleKey: String,
    val ingredientId: Long,
    val ingredientName: String,
    /** ISO instant of the first exclusion, to the second; one sent again keeps it. */
    val excludedAt: String,
) {
    companion object {
        /**
         * The title an exclusion is remembered by, so that the same product of the same store is excluded next week
         * and in another pack too: its words in lower case (a run of letters or digits; everything else, such as
         * punctuation and a "*", separates words), without the pack sizes in it (a number, or numbers in a row such as
         * "125-150" and "1.5", followed by a unit of [PACK_UNITS]: "500 gram", "6-pack") and without a first word
         * "Alle", joined by single spaces. "AH Blauwe bessen 150 gram" and "AH Blauwe bessen 300 gram" are both
         * "ah blauwe bessen"; "Alle Lipton Ice Tea 1.5 liter" and "Lipton Ice tea 1.5 literflessen 6-pack" are both
         * "lipton ice tea". A number without a unit stays: "Heineken 0.0" is not "Heineken". Jumbo and Lidl give the pack
         * apart from the title, which this leaves out as well.
         */
        fun titleKey(title: String): String {
            val words = words(title)
            val kept = ArrayList<String>(words.size)
            var i = 0
            while (i < words.size) {
                var end = i
                while (end < words.size && words[end].all { it.isDigit() }) end++
                if (end > i && end < words.size && words[end] in PACK_UNITS) {
                    i = end + 1
                } else {
                    kept += words[i]
                    i++
                }
            }
            return (if (kept.firstOrNull() == "alle") kept.drop(1) else kept).joinToString(" ")
        }

        /** The units of the pack sizes in the stores' titles, as seen in the offers of November 2026, and their usual short forms. */
        private val PACK_UNITS = setOf(
            "g", "gr", "gram", "kg", "kilo", "ml", "cl", "l", "liter", "literflessen", "stuk", "stuks", "pack", "packs",
        )

        private fun words(text: String): List<String> = buildList {
            val word = StringBuilder()
            for (c in text.lowercase()) {
                if (c.isLetterOrDigit()) {
                    word.append(c)
                } else if (word.isNotEmpty()) {
                    add(word.toString())
                    word.clear()
                }
            }
            if (word.isNotEmpty()) add(word.toString())
        }
    }
}

/** `GET`, `PUT` and `DELETE /api/offers/exclusions`: the account's exclusions, the latest first. */
@Serializable
data class OfferExclusions(val exclusions: List<OfferExclusion>)
