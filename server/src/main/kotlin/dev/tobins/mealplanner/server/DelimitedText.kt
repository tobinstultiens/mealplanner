package dev.tobins.mealplanner.server

/**
 * Splits delimiter-separated text into records. Fields may be wrapped in double
 * quotes, in which case they can contain the delimiter, line breaks and doubled
 * quotes (`""`). Blank lines are skipped.
 */
fun parseDelimited(text: String, delimiter: Char): List<List<String>> {
    val records = mutableListOf<List<String>>()
    var record = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var fieldWasQuoted = false

    fun endField() {
        record += field.toString()
        field.clear()
        fieldWasQuoted = false
    }

    fun endRecord() {
        endField()
        if (record.size > 1 || record[0].isNotEmpty()) records += record
        record = mutableListOf()
    }

    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            inQuotes && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
            inQuotes && c == '"' -> inQuotes = false
            inQuotes -> field.append(c)
            c == '"' && field.isEmpty() && !fieldWasQuoted -> { inQuotes = true; fieldWasQuoted = true }
            c == delimiter -> endField()
            c == '\n' -> endRecord()
            c == '\r' -> Unit
            else -> field.append(c)
        }
        i++
    }
    if (field.isNotEmpty() || record.isNotEmpty()) endRecord()
    return records
}
