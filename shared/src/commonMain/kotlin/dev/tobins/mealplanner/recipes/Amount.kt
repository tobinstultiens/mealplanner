package dev.tobins.mealplanner.recipes

/**
 * Parses an ingredient amount as written in the cooking repo: a decimal (`1.5`),
 * a fraction (`1/3`) or a mixed number (`1 1/2`). Returns null for anything else,
 * including ranges and zero denominators.
 */
fun parseAmount(text: String): Double? {
    val parts = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when (parts.size) {
        1 -> parseSimple(parts[0])
        2 -> {
            val whole = parts[0].toIntOrNull() ?: return null
            if (!parts[1].contains('/')) return null
            val fraction = parseSimple(parts[1]) ?: return null
            whole + fraction
        }
        else -> null
    }
}

private fun parseSimple(token: String): Double? {
    val value = if ('/' in token) {
        val (numerator, denominator) = token.split('/', limit = 2)
        val n = numerator.toIntOrNull() ?: return null
        val d = denominator.toIntOrNull()?.takeIf { it != 0 } ?: return null
        n.toDouble() / d
    } else {
        // Reject forms such as "1e3", "NaN" or "0x10" that toDouble would accept.
        if (!Regex("\\d+(\\.\\d+)?|\\.\\d+").matches(token)) return null
        token.toDouble()
    }
    return value.takeIf { it >= 0 }
}
