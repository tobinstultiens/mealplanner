package dev.tobins.mealplanner.server.shopping

import dev.tobins.mealplanner.api.ShoppingAdviceGroup

/**
 * What a row of a week's shopping list is, as its key: [ShoppingItem.key][dev.tobins.mealplanner.api.ShoppingItem.key]
 * on the wire and the key of a tick in `shoppingCheck`. [toString] gives the string and [parse]
 * reads it back; nothing else builds or takes apart these strings, so a new kind of row is one more
 * case here and the compiler shows every place that has to handle it.
 *
 * The strings are those the app and the database already hold, so they never change.
 */
sealed interface ShoppingKey {
    /** What the week's meals need of one of the account's ingredients. */
    data class Ingredient(val id: Long) : ShoppingKey {
        override fun toString() = "$INGREDIENT$id"
    }

    /** What the meals need of a recipe line with no ingredient linked, by its normalised text. */
    data class Text(val text: String) : ShoppingKey {
        override fun toString() = "$TEXT$text"
    }

    /** An ingredient to buy ahead now for next week's meals. */
    data class Ahead(val id: Long) : ShoppingKey {
        override fun toString() = "$AHEAD$id"
    }

    /** An ingredient to stock up on while it is on offer. */
    data class Stock(val id: Long) : ShoppingKey {
        override fun toString() = "$STOCK$id"
    }

    /**
     * An own item: something the account put on the list itself, by its id in `shoppingExtra`. Its
     * tick is kept with the item, not in `shoppingCheck`.
     */
    data class Extra(val id: Long) : ShoppingKey {
        override fun toString() = "$EXTRA$id"
    }

    /** Not a row: a dismissed advice group is kept with the week's ticks, under a key no row has. */
    data class Dismissed(val group: ShoppingAdviceGroup) : ShoppingKey {
        override fun toString() = "$DISMISSED${group.path}"
    }

    companion object {
        private const val INGREDIENT = "i"
        private const val TEXT = "t:"
        private const val AHEAD = "ahead:i"
        private const val STOCK = "stock:i"
        private const val EXTRA = "x:"
        private const val DISMISSED = "dismissed:"

        /** The key [key] stands for; an [IllegalArgumentException] (a 400) when it is no key a list has. */
        fun parse(key: String): ShoppingKey = parseOrNull(key) ?: throw IllegalArgumentException("Unknown shopping item $key")

        /**
         * The key [key] stands for, or null. Only the exact string [toString] gives is a key: `ahead:i007`
         * names no row (the row is `ahead:i7`), so a tick under it would never show.
         */
        fun parseOrNull(key: String): ShoppingKey? {
            val parsed = when {
                key.startsWith(AHEAD) -> key.removePrefix(AHEAD).toLongOrNull()?.let(::Ahead)
                key.startsWith(STOCK) -> key.removePrefix(STOCK).toLongOrNull()?.let(::Stock)
                key.startsWith(EXTRA) -> key.removePrefix(EXTRA).toLongOrNull()?.let(::Extra)
                key.startsWith(DISMISSED) -> ShoppingAdviceGroup.fromPath(key.removePrefix(DISMISSED))?.let(::Dismissed)
                key.startsWith(TEXT) -> Text(key.removePrefix(TEXT))
                key.startsWith(INGREDIENT) -> key.removePrefix(INGREDIENT).toLongOrNull()?.let(::Ingredient)
                else -> null
            }
            return parsed?.takeIf { it.toString() == key }
        }
    }
}
