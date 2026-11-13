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

    /** The words of all of an ingredient's names. */
    private val nameWordsById: Map<Long, Set<String>> = terms.mapValues { (_, names) -> names.flatMap(::words).toSet() }

    private val terms: List<Term> = terms.flatMap { (id, names) ->
        names.map(::words).filter { it.isNotEmpty() && it.joinToString(" ").length >= 3 }.distinct()
            .map { Term(id, it, it.joinToString(" ").length) }
    }.sortedByDescending { it.length }

    /** The ingredient the offer is for, or null; the longest matching term wins. */
    fun match(offer: Offer): Long? = match(offer.title, offer.category)

    /**
     * The ingredient a product with this [title] in store [category] is, or null.
     * [checkCategory] false skips the non-ingredient categories, for a caller that checks
     * the category itself.
     *
     * - What follows "met", "with" or "in" is a side ingredient: "olijven gevuld met ansjovis"
     *   is olives, "tonijn in olie" is tuna.
     * - A prepared product ("pastasaus basilicum", "maaltijdmix broccoli") is not its
     *   ingredient, unless the ingredient is itself one ("sojasaus", "bouillon").
     */
    fun match(title: String, category: String?, checkCategory: Boolean = true): Long? {
        if (checkCategory && category in notIngredients) return null
        val all = words(title)
        val cut = all.indexOfFirst { it in sideWords }.takeIf { it > 0 } ?: all.size
        val head = all.subList(0, cut)
        val id = terms.firstOrNull { term -> occursIn(term.words, head) }?.ingredientId ?: return null
        // "sojasaus" may be soy sauce, but "sap" inside "sinaasappel" does not make orange juice an orange.
        val names = nameWordsById[id].orEmpty()
        val prepared = all.any { word ->
            preparedEndings.any { ending -> word.endsWith(ending) && names.none { it.endsWith(ending) } }
        }
        return id.takeUnless { prepared }
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
            "Baby en kind", "Drogisterij", "Huishouden", "Huisdier",
        )

        /** Words after which a title names what a product comes with, not what it is. */
        private val sideWords = setOf("met", "with", "in", "gevuld")

        /** Word endings of prepared products (Dutch compounds end in their head noun). */
        private val preparedEndings = listOf(
            "saus", "sauce", "mix", "schotel", "ovenpasta", "maaltijd", "sap", "juice", "drink", "shot", "siroop",
            "reep", "repen", "chips", "soep", "dressing", "smeerkaas", "roomkaas", "smeltkaas", "spread", "salade",
            "pizza", "marinade", "bouillon", "snack", "taart", "gebak", "smaak", "creme", "crème", "wafels", "crackers",
            "oats", "crunch", "hoemoes", "hummus", "pasta", "wok", "oven", "moes", "noodles", "pancake", "crispy",
            "crispies", "flavor", "flavour", "rösti", "rosti",
        )

        internal fun words(text: String): List<String> =
            text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    }
}
