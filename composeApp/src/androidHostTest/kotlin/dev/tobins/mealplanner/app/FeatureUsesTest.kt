package dev.tobins.mealplanner.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which feature package of the app may use which: the uses that exist, written down in one place. A file that
 * imports from a feature not listed for its own fails here, so a new use is a decision made on purpose, with a line
 * added to [allowed], and not something that happens by an import the IDE offered.
 *
 * Every package may use the root package (what starts the app and what several features share), and the root
 * package may use all of them. Several of the uses below point both ways (`plan` and `recipes`, `shopping` and
 * `prices`): they are how the features grew together, and the list is where they are to be undone from.
 */
class FeatureUsesTest {
    private val allowed: Map<String, Set<String>> = mapOf(
        "accounts" to setOf("prices", "recipes", "shopping", "ui"),
        "ingredients" to setOf("plan", "recipes", "shopping", "ui"),
        "plan" to setOf("ingredients", "prices", "recipes", "shopping", "tracking", "ui"),
        "prices" to setOf("ingredients", "shopping", "ui"),
        "recipes" to setOf("ingredients", "plan", "prices", "ui"),
        "shopping" to setOf("accounts", "ingredients", "prices", "ui"),
        "tracking" to setOf("ingredients", "plan", "ui"),
        "ui" to setOf("recipes", "shopping"),
    )

    private val base = "dev.tobins.mealplanner.app"
    private val sourceSets = listOf("commonMain", "androidMain", "wasmJsMain")

    private fun sources(): List<File> = sourceSets.flatMap { set ->
        File("src/$set/kotlin/" + base.replace('.', '/')).walkTopDown().filter { it.extension == "kt" }.toList()
    }

    /** The feature a package belongs to: its last part under the app's package, or "root" for the app's package itself. */
    private fun featureOf(pkg: String): String = if (pkg == base) "root" else pkg.removePrefix("$base.")

    @Test
    fun theSourcesAreFound() {
        assertTrue(sources().size > 70, "the test must read the app's sources, from the module's folder")
    }

    @Test
    fun everyFileIsInThePackageOfItsFolder() {
        val wrong = sources().filter { file ->
            val pkg = file.readLines().first { it.startsWith("package ") }.removePrefix("package ")
            val folder = file.parentFile.path.substringAfter("/kotlin/").replace('/', '.')
            pkg != folder
        }
        assertEquals(emptyList(), wrong.map { it.path }, "a file's package line names its folder")
    }

    @Test
    fun noFileUsesAFeatureThatItsOwnIsNotAllowedTo() {
        val forbidden = mutableListOf<String>()
        for (file in sources()) {
            val lines = file.readLines()
            val own = featureOf(lines.first { it.startsWith("package ") }.removePrefix("package "))
            if (own == "root") continue
            val permitted = allowed[own] ?: emptySet()
            for (line in lines.filter { it.startsWith("import $base.") }) {
                val used = line.removePrefix("import $base.").substringBefore('.', missingDelimiterValue = "")
                if (used.isNotEmpty() && used != own && used !in permitted) {
                    forbidden += "${file.name} (in $own) uses $used: $line"
                }
            }
        }
        assertEquals(emptyList(), forbidden, "uses between features outside the list in FeatureUsesTest.allowed")
    }
}
