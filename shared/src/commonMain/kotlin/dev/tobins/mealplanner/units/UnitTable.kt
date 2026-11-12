package dev.tobins.mealplanner.units

enum class Dimension { WEIGHT, VOLUME, COUNT }

/**
 * One unit of measure. [factor] is how many base units one of this unit equals:
 * grams for [Dimension.WEIGHT], millilitres for [Dimension.VOLUME]. Count units
 * have no base unit, so their factor is 1.
 */
data class UnitDef(
    val key: String,
    val dimension: Dimension,
    val factor: Double = 1.0,
    val aliases: List<String> = emptyList(),
)

/** Looks up units by canonical key or alias, case-insensitively. */
class UnitTable(val units: List<UnitDef>) {
    private val byToken: Map<String, UnitDef> = buildMap {
        for (unit in units) {
            put(unit.key.lowercase(), unit)
            unit.aliases.forEach { put(it.lowercase(), unit) }
        }
    }

    fun resolve(token: String): UnitDef? = byToken[token.trim().lowercase()]

    companion object {
        /**
         * Mirrors `data/units.yaml` in the cooking repo, which stays the source of
         * truth; the server replaces this table with the file's contents on sync.
         */
        val Default = UnitTable(
            listOf(
                UnitDef("g", Dimension.WEIGHT, 1.0, listOf("gram", "grams", "gr", "gramme", "grammes")),
                UnitDef("kg", Dimension.WEIGHT, 1000.0, listOf("kilogram", "kilograms", "kilo", "kilos")),
                UnitDef("oz", Dimension.WEIGHT, 28.3495, listOf("ounce", "ounces")),
                UnitDef("lb", Dimension.WEIGHT, 453.592, listOf("lbs", "pound", "pounds")),
                UnitDef("ml", Dimension.VOLUME, 1.0, listOf("milliliter", "milliliters", "millilitre", "millilitres")),
                UnitDef("l", Dimension.VOLUME, 1000.0, listOf("liter", "liters", "litre", "litres")),
                UnitDef("tsp", Dimension.VOLUME, 4.92892, listOf("teaspoon", "teaspoons", "theelepel", "theelepels", "tl")),
                UnitDef("tbsp", Dimension.VOLUME, 14.7868, listOf("tablespoon", "tablespoons", "eetlepel", "eetlepels", "el")),
                UnitDef("floz", Dimension.VOLUME, 29.5735, listOf("fl oz", "fluid ounce", "fluid ounces")),
                UnitDef("cup", Dimension.VOLUME, 236.588, listOf("cups", "kop", "koppen")),
                UnitDef("clove", Dimension.COUNT, aliases = listOf("cloves", "teentje", "teentjes")),
                UnitDef("piece", Dimension.COUNT, aliases = listOf("pieces", "stuk", "stuks")),
            ),
        )
    }
}
