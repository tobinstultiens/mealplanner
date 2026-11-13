package dev.tobins.mealplanner.server.offers

import dev.tobins.mealplanner.api.Offer

/**
 * Finds the catalogue ingredient an offer is for, from the words of its title. Titles
 * are Dutch product names ("AH Gemengd gehakt 500 gram"), so a term matches a whole
 * word, its plural ("banaan" -> "bananen"), or the end of a compound word
 * ("makreelfilet" ends with "filet") for terms of five letters or more. Compounds are
 * only matched at the end because Dutch puts the head noun last:
 * "kipfiletvleeswaren" is cold cuts, not chicken fillet.
 *
 * @param terms per ingredient id, the names and aliases it is known by.
 */
class OfferMatcher(terms: Map<Long, List<String>>) {
    private data class Term(val ingredientId: Long, val words: List<String>, val length: Int)

    private val terms: List<Term> = terms.flatMap { (id, names) ->
        names.map(::words).filter { it.isNotEmpty() && it.joinToString(" ").length >= 3 }.distinct()
            .map { Term(id, it, it.joinToString(" ").length) }
    }.sortedByDescending { it.length }

    /** The ingredient the offer is for, or null; the longest matching term wins. */
    fun match(offer: Offer): Long? {
        if (offer.category in notIngredients) return null
        val title = words(offer.title)
        return terms.firstOrNull { term -> occursIn(term.words, title) }?.ingredientId
    }

    /** Offers grouped by the ingredient they match; unmatched offers are left out. */
    fun group(offers: List<Offer>): Map<Long, List<Offer>> =
        offers.mapNotNull { offer -> match(offer)?.let { it to offer } }.groupBy({ it.first }, { it.second })

    private fun occursIn(term: List<String>, title: List<String>): Boolean {
        if (term.size == 1) return title.any { matchesWord(term[0], it) }
        // A phrase matches consecutive words; only its last word may carry a plural.
        return (0..title.size - term.size).any { start ->
            term.indices.all { i ->
                if (i == term.lastIndex) matchesWord(term[i], title[start + i]) else term[i] == title[start + i]
            }
        }
    }

    private fun matchesWord(term: String, word: String): Boolean {
        val forms = forms(term)
        return word in forms || (term.length >= 5 && forms.any { word.endsWith(it) })
    }

    /**
     * A word and its likely Dutch plurals: -s, -en, -n, a doubled vowel shortened
     * ("banaan" -> "bananen") and a final consonant doubled ("kip" -> "kippen").
     */
    private fun forms(term: String): Set<String> = buildSet {
        add(term)
        add(term + "s")
        add(term + "en")
        add(term + "n")
        if (term.length >= 3) {
            val last = term.last()
            val vowels = "aeiou"
            if (last !in vowels && term[term.length - 2] == term[term.length - 3] && term[term.length - 2] in vowels) {
                add(term.dropLast(2) + last + "en")
            }
            if (last !in vowels && term[term.length - 2] in vowels && term[term.length - 3] !in vowels) {
                add(term + last + "en")
            }
        }
    }

    companion object {
        /**
         * Store categories of ready meals, cold cuts, snacks, pastry and drinks (AH and
         * Lidl names): a match there would be a false hint, such as "leverworst met augurk"
         * for gherkins. Jumbo gives no categories, so its offers are not filtered this way.
         */
        private val notIngredients = setOf(
            "Maaltijden, salades", "Diepvries", "Borrel, chips, snacks", "Koek, snoep, chocolade", "Vleeswaren",
            "Bier, wijn, aperitieven", "Frisdrank, sappen, water", "Koffie, thee", "Glutenvrij",
            "Zoet gebak", "Gekoelde kant-en-klaarmaaltijden", "Hartige hapjes", "Worst & vleeswaren",
            "Koffie, thee & cacao", "Witte wijn", "Rode wijn", "Diepvriespizza's & snacks",
        )

        internal fun words(text: String): List<String> =
            text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    }
}
