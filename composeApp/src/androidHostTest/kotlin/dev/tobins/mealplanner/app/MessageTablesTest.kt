package dev.tobins.mealplanner.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `StringsTest` checks the texts of the enums in [messageTables] and [pluralTables]; this one checks that those
 * lists hold every enum of texts the app has, so a feature's new enum cannot go unchecked.
 */
class MessageTablesTest {
    private val sources = File("src/commonMain/kotlin/dev/tobins/mealplanner/app").walkTopDown().filter { it.extension == "kt" }.toList()

    private fun declared(implements: String): Set<String> =
        sources.flatMap { file -> Regex("""enum class (\w+)\([^{]*\) : $implements \{""").findAll(file.readText()).map { it.groupValues[1] } }.toSet()

    @Test
    fun theSourcesAreFound() {
        assertTrue(sources.size > 70, "the test must read the app's sources, from the module's folder")
    }

    @Test
    fun everyEnumOfTextsIsInTheListThatIsChecked() {
        assertEquals(declared("Msg"), messageTables.map { it.first()::class.simpleName!! }.toSet(), "enums of texts and messageTables")
        assertEquals(declared("Plural"), pluralTables.map { it.first()::class.simpleName!! }.toSet(), "enums of plurals and pluralTables")
    }

    @Test
    fun aFeaturesTextsAreInItsPackage() {
        for (file in sources) {
            for (name in Regex("""enum class (\w+)(Msg|Plural)\(""").findAll(file.readText()).map { it.groupValues[1] }) {
                val expected = when (name) {
                    "App" -> "app"
                    "Common" -> "ui"
                    else -> name.lowercase()
                }
                assertEquals(expected, file.parentFile.name, "${name}Msg or ${name}Plural is in the folder of its feature")
            }
        }
    }
}
