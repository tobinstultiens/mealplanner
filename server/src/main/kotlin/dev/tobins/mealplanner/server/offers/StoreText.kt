package dev.tobins.mealplanner.server.offers

import dev.tobins.mealplanner.api.Offer

/** What an offer's text shows where the store has a line break in it: the lines of one text stay on one line. */
const val STORE_TEXT_SEPARATOR = " · "

/**
 * The lines of a text as a store wrote it (issue 320): split at its `<br>` line breaks, any other markup taken out,
 * HTML character references (`&amp;`) decoded, spaces collapsed, empty lines dropped. Jumbo puts markup in its
 * subtitles ("Alle flesjes à 500 ml<br />M.u.v. Gekoeld<br /><br />"), and the others escape characters.
 */
fun storeLines(text: String): List<String> =
    text.split(lineBreak).map { htmlUnescape(it.replace(markup, " ")).replace(spaces, " ").trim() }.filter { it.isNotEmpty() }

/**
 * [text] as an offer shows it: its [storeLines] joined by [STORE_TEXT_SEPARATOR]. Cleaning it again changes nothing,
 * so a text read from the history gives the same text as the offer that is read from the store today. Decoding can
 * make markup of an escaped tag (`&lt;b&gt;`), which the next pass would strip, so it is cleaned until it stays.
 */
fun storeText(text: String): String {
    val cleaned = storeLines(text.replace(STORE_TEXT_SEPARATOR, "<br>")).joinToString(STORE_TEXT_SEPARATOR)
    return if (cleaned == text) cleaned else storeText(cleaned)
}

/** The offer with the markup taken out of the texts the app shows for it; the parsers return offers so. */
internal fun Offer.cleaned(): Offer = copy(title = storeText(title), packSize = packSize?.let(::storeText)?.ifEmpty { null })

private val lineBreak = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val markup = Regex("""<[^>]*>""")
private val spaces = Regex("""\s+""")
