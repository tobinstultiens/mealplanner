package dev.tobins.mealplanner.server.offers

import dev.tobins.mealplanner.api.Offer
import dev.tobins.mealplanner.api.OfferExclusion
import dev.tobins.mealplanner.api.Store

/**
 * Finds the catalogue ingredient an offer is for, from the words of its title. Titles
 * are Dutch product names ("AH Gemengd gehakt 500 gram"), so a term matches a whole
 * word, its plural ("banaan" -> "bananen"), or the end of a compound word
 * ("makreelfilet" ends with "filet") for terms of five letters or more. Compounds are
 * only matched at the end because Dutch puts the head noun last:
 * "kipfiletvleeswaren" is cold cuts, not chicken fillet. A compound whose front names a food
 * of its own is that food, not the ingredient its end names: "hertenbiefstuk" is no beef steak.
 *
 * @param terms per ingredient id, the names and aliases it is known by.
 * @param excluded the matches an account said are wrong ("Not this ingredient"): such an offer is matched as if the
 *   ingredient were not in the catalogue, so it may still be another ingredient whose term it has.
 */
class OfferMatcher(terms: Map<Long, List<String>>, excluded: Set<ExcludedMatch> = emptySet()) {
    /** An account's terms, with the matches it said are wrong. */
    constructor(terms: OfferTerms) : this(terms.names, terms.excluded)

    /** [namesOtherFood]: the term is, or contains, one of [namesOfOtherFoods], so its words are its own to match. */
    private data class Term(val ingredientId: Long, val words: List<String>, val length: Int, val namesOtherFood: Boolean)

    /** The words of all of an ingredient's names. */
    private val nameWordsById: Map<Long, Set<String>> = terms.mapValues { (_, names) -> names.flatMap(::words).toSet() }

    private val otherFoods: List<List<String>> = namesOfOtherFoods.map(::words)

    private val terms: List<Term> = terms.flatMap { (id, names) ->
        names.map(::words).filter { it.isNotEmpty() && it.joinToString(" ").length >= 3 }.distinct()
            .map { words -> Term(id, words, words.joinToString(" ").length, otherFoods.any { occurs(it, words, ::isForm) }) }
    }.sortedByDescending { it.length }

    /** Each form ([forms]) of each one-word term, with the ingredients it names: what makes the front of a compound another food. */
    private val foodWords: Map<String, Set<Long>> = this.terms.filter { it.words.size == 1 }
        .flatMap { term -> forms(term.words[0]).map { it to term.ingredientId } }
        .groupBy({ it.first }, { it.second }).mapValues { (_, ids) -> ids.toSet() }

    /** The ingredients each offer is not, by store and [OfferExclusion.titleKey]. */
    private val excludedByOffer: Map<Pair<Store, String>, Set<Long>> =
        excluded.groupBy({ it.store to it.titleKey }, { it.ingredientId }).mapValues { (_, ids) -> ids.toSet() }

    /** The ingredient the offer is for, or null; the longest matching term wins. */
    fun match(offer: Offer): Long? = match(offer.title, offer.category, store = offer.store)

    /**
     * The ingredient a product with this [title] in store [category] is, or null.
     * [checkCategory] false skips the non-ingredient categories, for a caller that checks
     * the category itself. With the [store], an ingredient the account excluded for this title is left out.
     *
     * - What follows "met", "with" or "in" is a side ingredient: "olijven gevuld met ansjovis"
     *   is olives, "tonijn in olie" is tuna.
     * - A prepared product ("pastasaus basilicum", "pastasauzen", "maaltijdmix broccoli") is not its
     *   ingredient, unless the ingredient is itself one ("sojasaus", "bouillon").
     * - The name of another food that ends in an ingredient's word ([namesOfOtherFoods]: "zoete aardappel")
     *   is not that ingredient, unless the ingredient is known by that name.
     */
    fun match(title: String, category: String?, checkCategory: Boolean = true, store: Store? = null): Long? {
        if (checkCategory && category in notIngredients) return null
        val all = words(title)
        val cut = all.indexOfFirst { it in sideWords }.takeIf { it > 0 } ?: all.size
        val head = all.subList(0, cut)
        val withoutOtherFoods = withoutOtherFoods(head)
        val notThis = store?.takeIf { excludedByOffer.isNotEmpty() }?.let { excludedByOffer[it to OfferExclusion.titleKey(title)] }.orEmpty()
        val id = terms.firstOrNull { term ->
            term.ingredientId !in notThis && occurs(term.words, if (term.namesOtherFood) head else withoutOtherFoods) { word, it -> matchesWord(term.ingredientId, word, it) }
        }?.ingredientId ?: return null
        // "sojasaus" may be soy sauce, but "sap" inside "sinaasappel" does not make orange juice an orange.
        val names = nameWordsById[id].orEmpty()
        val prepared = all.any { word ->
            preparedEndings.any { (ending, forms) -> forms.any { word.endsWith(it) } && names.none { it.endsWith(ending) } }
        }
        return id.takeUnless { prepared }
    }

    /** Offers grouped by the ingredient they match; unmatched offers are left out. */
    fun group(offers: List<Offer>): Map<Long, List<Offer>> =
        offers.mapNotNull { offer -> match(offer)?.let { it to offer } }.groupBy({ it.first }, { it.second })

    /** Whether [phrase] is consecutive words of [title]: all as they are but the last, which may carry a plural ([lastMatches]). */
    private fun occurs(phrase: List<String>, title: List<String>, lastMatches: (String, String) -> Boolean): Boolean =
        (0..title.size - phrase.size).any { start -> matchesAt(phrase, title, start, lastMatches) }

    private fun matchesAt(phrase: List<String>, title: List<String>, start: Int, lastMatches: (String, String) -> Boolean): Boolean =
        phrase.indices.all { i -> if (i == phrase.lastIndex) lastMatches(phrase[i], title[start + i]) else phrase[i] == title[start + i] }

    /** [words] with the [namesOfOtherFoods] in them blanked, so that no term of an ingredient they end in matches there. */
    private fun withoutOtherFoods(words: List<String>): List<String> {
        val left = words.toMutableList()
        for (name in otherFoods) {
            for (start in 0..words.size - name.size) {
                if (matchesAt(name, words, start, ::isForm)) name.indices.forEach { left[start + it] = "" }
            }
        }
        return left
    }

    private fun isForm(term: String, word: String) = word in forms(term)

    /**
     * Whether [word] is [term] of ingredient [ingredientId]: one of its forms, or, for a term of five letters or more,
     * the end of a compound whose front is no food of its own ([isOtherFood]).
     */
    private fun matchesWord(ingredientId: Long, term: String, word: String): Boolean {
        val forms = forms(term)
        return word in forms || (term.length >= 5 && forms.any { word.endsWith(it) && !isOtherFood(word.dropLast(it.length), ingredientId) })
    }

    /**
     * Whether the [front] of a compound names a food of its own, which makes the compound another food than [ingredientId]:
     * a term of another of the account's ingredients that has no name word in common with this one ("kaneel" in
     * "kaneelbroodjes": cinnamon buns are no bread rolls; but "tomaten" in "tomatenketchup" is what "Tomato ketchup" says
     * it is made of), or one of [otherAnimals] that the ingredient's own names do not mention ("herten" in
     * "hertenbiefstuk": venison is no beef steak). A compound that is the ingredient's own name or alias matches as a
     * whole word before this is asked.
     */
    private fun isOtherFood(front: String, ingredientId: Long): Boolean {
        val own = nameWordsById[ingredientId].orEmpty()
        val owners = foodWords[front].orEmpty()
        if (ingredientId !in owners && owners.any { other -> nameWordsById[other].orEmpty().none { it in own } }) return true
        val animal = animalForms[front] ?: return false
        return own.none { animal in it }
    }

    companion object {
        private const val VOWELS = "aeiou"

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

        /** Word endings of prepared products (Dutch compounds end in their head noun), each with its -zen plural where it has one ("sauzen"). */
        private val preparedEndings = listOf(
            "saus", "sauce", "mix", "schotel", "ovenpasta", "maaltijd", "sap", "juice", "drink", "shot", "siroop",
            "reep", "repen", "chips", "soep", "dressing", "smeerkaas", "roomkaas", "smeltkaas", "spread", "salade",
            "pizza", "marinade", "bouillon", "snack", "taart", "gebak", "smaak", "creme", "crème", "wafels", "crackers",
            "oats", "crunch", "hoemoes", "hummus", "pasta", "wok", "oven", "moes", "noodles", "pancake", "crispy",
            "crispies", "flavor", "flavour", "rösti", "rosti",
            // Sausages, spreads and ready-made meat: "Kips snijleverworst" is not chicken.
            "worst", "paté", "pate", "schnitzel", "burger", "shoarma", "cordon", "tonight", "drinkyoghurt",
        ).map { ending -> ending to setOfNotNull(ending, zPlural(ending)) }

        /**
         * Dutch words of three letters that double their last letter in the plural ("kippen", "vissen", "bessen") and
         * never take a plain -s, so that a brand such as "Kips" is not the ingredient. A list rather than every short
         * word of consonant, vowel, consonant: English aliases have that shape too ("fig", "rib", "nut") and do take -s.
         * In the offers of November 2026 "Kips" was the only word of such a term with an -s.
         */
        private val noPlainPlural = setOf("kip", "vis", "bes", "rog", "pit")

        /**
         * Animals whose name in front of a cut or a product makes it another meat than a term of the cut alone says:
         * "hertenbiefstuk" is venison, "lamsgehakt" is no minced beef. Game and the less common meats only: a catalogue
         * that has chicken or pork names them itself, and [isOtherFood] then finds them as another ingredient's term.
         */
        private val otherAnimals = listOf(
            "hert", "ree", "hinde", "wild", "everzwijn", "fazant", "parelhoen", "eend", "gans", "kalkoen", "konijn", "lam",
            "geit", "paard", "struisvogel",
        )

        /** Each form of each of [otherAnimals] ("herten", "lams"), with the animal. */
        private val animalForms: Map<String, String> = otherAnimals.flatMap { animal -> forms(animal).map { it to animal } }.toMap()

        /**
         * Names of foods of their own that end in a word of another ingredient: "zoete aardappel" (sweet potato) is no
         * potato. Found in the offers by the price auditor (issue 316); an ingredient known by such a name still matches it.
         */
        private val namesOfOtherFoods = listOf("zoete aardappel")

        /**
         * A word and its likely Dutch plurals: -s, -en, -n, a doubled vowel shortened
         * ("banaan" -> "bananen"), a final consonant doubled ("kip" -> "kippen") and an -s after
         * two vowels as -zen ("saus" -> "sauzen", "kaas" -> "kazen").
         * A Dutch word of [noPlainPlural] takes no plain -s: "Kips" on a liver sausage is a brand,
         * not chicken. Other short words do: an English alias "fig", "rib" or "nut" matches "figs",
         * "ribs" and "nuts".
         */
        private fun forms(term: String): Set<String> = buildSet {
            add(term)
            if (term !in noPlainPlural) add(term + "s")
            add(term + "en")
            add(term + "n")
            if (term.length >= 3) {
                val last = term.last()
                val beforeLast = term[term.length - 2]
                val third = term[term.length - 3]
                if (last !in VOWELS && beforeLast == third && beforeLast in VOWELS) {
                    add(term.dropLast(2) + last + "en")
                }
                if (last !in VOWELS && beforeLast in VOWELS && third !in VOWELS) {
                    add(term + last + "en")
                }
            }
            zPlural(term)?.let(::add)
        }

        /** The plural of a word that ends in an -s after two vowels: "saus" -> "sauzen", "kaas" -> "kazen"; null for others. */
        private fun zPlural(word: String): String? {
            if (word.length < 3 || word.last() != 's') return null
            val beforeLast = word[word.length - 2]
            val third = word[word.length - 3]
            if (beforeLast !in VOWELS || third !in VOWELS) return null
            return (if (beforeLast == third) word.dropLast(2) else word.dropLast(1)) + "zen"
        }

        internal fun words(text: String): List<String> =
            text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    }
}
