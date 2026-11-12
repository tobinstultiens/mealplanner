package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.Food
import dev.tobins.mealplanner.api.FoodBasis
import dev.tobins.mealplanner.api.FoodDataset
import dev.tobins.mealplanner.api.Nutrition
import dev.tobins.mealplanner.server.db.Database
import java.io.File
import dev.tobins.mealplanner.server.db.Food as FoodRow

/**
 * The NEVO reference foods. NEVO's conditions of use require the data to be used
 * unchanged and with a source reference, so this class only imports and reads.
 */
class FoodRepository(private val db: Database) {
    private val q = db.foodQueries

    /** All foods, cached: the dataset is small (about 2,300 rows) and only changes on import. */
    @Volatile
    private var cache: List<Food>? = null

    fun all(): List<Food> = cache ?: q.selectAllFoods().executeAsList().map { it.toFood() }.also { cache = it }

    fun dataset(): FoodDataset = FoodDataset(
        foodCount = q.countFoods().executeAsOne().toInt(),
        reference = q.selectMeta(VERSION_KEY).executeAsOneOrNull()?.let(::referenceFor),
    )

    fun find(nevoCode: Int): Food? = q.selectFood(nevoCode.toLong()).executeAsOneOrNull()?.toFood()

    /**
     * Foods whose Dutch name, English name or synonym contains every word of [query].
     * Best matches first: a name that starts with the query, then shorter names.
     */
    fun search(query: String, limit: Int = 20): List<Food> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val phrase = words.joinToString(" ")
        return all().mapNotNull { food ->
            val names = listOfNotNull(food.nameEn, food.nameNl, food.synonym).map { it.lowercase() }
            val haystack = names.joinToString(" | ")
            if (!words.all { it in haystack }) return@mapNotNull null
            val best = names.filter { name -> words.all { it in name } }.minOfOrNull { name ->
                val rank = when {
                    name == phrase -> 0
                    name.startsWith(phrase) -> 1
                    name.split(Regex("[^\\p{L}\\p{N}]+")).containsAll(words) -> 2
                    else -> 3
                }
                rank * 1000 + name.length
            } ?: (4000 + haystack.length)
            food to best
        }.sortedWith(compareBy({ it.second }, { it.first.nevoCode })).take(limit).map { it.first }
    }

    /**
     * Replaces all foods with the contents of the NEVO main file
     * (`NEVO2025_v9.0.csv`: UTF-8, `|`-separated, decimal commas).
     * @return the number of foods imported.
     */
    fun importNevo(file: File): Int {
        val records = parseDelimited(file.readText(Charsets.UTF_8).removePrefix("\uFEFF"), '|')
        val header = records.firstOrNull() ?: throw IllegalArgumentException("NEVO file is empty")
        fun column(name: String) = header.indexOf(name).also {
            require(it >= 0) { "NEVO file has no column '$name'; is this the main NEVO csv?" }
        }
        val version = column("NEVO-versie/NEVO-version")
        val code = column("NEVO-code")
        val nameNl = column("Voedingsmiddelnaam/Dutch food name")
        val nameEn = column("Engelse naam/Food name")
        val synonym = column("Synoniem")
        val groupNl = column("Voedingsmiddelgroep")
        val groupEn = column("Food group")
        val quantity = column("Hoeveelheid/Quantity")
        val nutrients = listOf("ENERCC (kcal)", "PROT (g)", "FAT (g)", "FASAT (g)", "CHO (g)", "SUGAR (g)", "FIBT (g)", "NA (mg)")
            .map(::column)

        val rows = records.drop(1)
        fun basisOf(row: List<String>) = when (row[quantity]) {
            "per 100g" -> FoodBasis.PER_100_G
            "per 100ml" -> FoodBasis.PER_100_ML
            else -> throw IllegalArgumentException("NEVO food ${row[code]} has unknown quantity '${row[quantity]}'")
        }
        // Validate every row before touching the table.
        rows.forEach(::basisOf)
        db.transaction {
            q.deleteAllFoods()
            for (row in rows) {
                val values = nutrients.map { row[it].replace(',', '.').toDoubleOrNull() }
                q.insertFood(
                    nevoCode = row[code].toLong(),
                    nameNl = row[nameNl],
                    nameEn = row[nameEn],
                    synonym = row[synonym].ifBlank { null },
                    groupNl = row[groupNl],
                    groupEn = row[groupEn],
                    basis = basisOf(row).name,
                    kcal = values[0], protein = values[1], fat = values[2], saturatedFat = values[3],
                    carbs = values[4], sugar = values[5], fibre = values[6], sodiumMg = values[7],
                )
            }
            rows.firstOrNull()?.let { q.upsertMeta(VERSION_KEY, it[version]) }
        }
        cache = null
        return rows.size
    }

    private fun FoodRow.toFood() = Food(
        nevoCode = nevoCode.toInt(),
        nameNl = nameNl,
        nameEn = nameEn,
        synonym = synonym,
        groupEn = groupEn,
        basis = FoodBasis.valueOf(basis),
        per100 = Nutrition(kcal, protein, fat, saturatedFat, carbs, sugar, fibre, sodiumMg),
    )

    companion object {
        private const val VERSION_KEY = "nevoVersion"

        /** `NEVO-Online 2025 9.0` -> the reference wording RIVM prescribes. */
        fun referenceFor(version: String): String {
            val match = Regex("(\\d{4})\\s+(\\d+(?:\\.\\d+)?)").find(version)
            val label = match?.let { "${it.groupValues[1]}/${it.groupValues[2]}" } ?: version
            return "NEVO-online version $label, RIVM, Bilthoven"
        }
    }
}
