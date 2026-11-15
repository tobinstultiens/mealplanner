package dev.tobins.mealplanner.app

/**
 * How numbers, money and dates are written for one locale. `commonMain` has no locale API, so
 * the platform only says which locale the device uses ([deviceLocaleTag]) and this class holds
 * what the app needs from it: the decimal separator, short day and month names and whether
 * the month comes first. Only the number, money and date formats follow the locale; the
 * app's own texts stay English. Languages without names here show English ones.
 */
class FormatLocale(
    val decimalSeparator: Char,
    val dayNames: List<String>,
    val monthNames: List<String>,
    /** `Fri, Nov 13` instead of `Fri 13 Nov`. */
    val monthFirst: Boolean = false,
) {
    companion object {
        /** Decimal points and English names: for text typed into fields, and for tests. */
        val English = FormatLocale(
            '.',
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
            listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"),
        )

        /** American English: month before day. */
        val EnglishUs = FormatLocale(English.decimalSeparator, English.dayNames, English.monthNames, monthFirst = true)

        val Dutch = FormatLocale(
            ',',
            listOf("ma", "di", "wo", "do", "vr", "za", "zo"),
            listOf("jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec"),
        )

        val German = FormatLocale(
            ',',
            listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"),
            listOf("Jan", "Feb", "Mär", "Apr", "Mai", "Jun", "Jul", "Aug", "Sep", "Okt", "Nov", "Dez"),
        )

        val French = FormatLocale(
            ',',
            listOf("lun", "mar", "mer", "jeu", "ven", "sam", "dim"),
            listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc."),
        )

        val Spanish = FormatLocale(
            ',',
            listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom"),
            listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic"),
        )

        /** Languages that write a decimal comma. */
        private val decimalComma = setOf(
            "nl", "de", "fr", "es", "it", "pt", "sv", "da", "nb", "nn", "no", "fi", "pl", "cs", "sk",
            "ru", "uk", "tr", "ro", "hu", "id", "el", "bg", "hr", "sl",
        )

        /** The locale for a BCP-47 tag such as `nl-NL` or `en_US`; English when it is blank. */
        fun of(tag: String): FormatLocale {
            val parts = tag.split('-', '_')
            val language = parts.firstOrNull().orEmpty().lowercase()
            val region = parts.drop(1).firstOrNull { it.length == 2 }?.uppercase()
            val base = when (language) {
                "nl" -> Dutch
                "de" -> German
                "fr" -> French
                "es" -> Spanish
                "en" -> if (region == "US") EnglishUs else English
                else -> English
            }
            return if (language in decimalComma && base.decimalSeparator != ',') {
                FormatLocale(',', base.dayNames, base.monthNames, base.monthFirst)
            } else {
                base
            }
        }

        /** The device's locale, read once when first needed. */
        val device: FormatLocale by lazy { of(deviceLocaleTag()) }
    }
}
