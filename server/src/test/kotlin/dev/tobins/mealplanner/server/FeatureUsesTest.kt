package dev.tobins.mealplanner.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which feature package of the server may use which (docs/architecture.md), as the code has it today. A file that
 * uses a feature this list does not allow fails the test; so does an entry no file uses any more, so that the list
 * only gets shorter. It reads the source files' package and import lines, nothing more.
 *
 * Some entries are uses one would rather not have (a cycle, such as plan and shopping using each other); they are
 * here because they exist, and removing them is later work, never a reason to add another.
 * The root package may use every feature (it starts the server and builds each account's repositories) and every
 * feature may use the root, so neither is listed.
 */
class FeatureUsesTest {
    private val allowed: Map<String, Set<String>> = mapOf(
        "accounts" to setOf("recipes"),
        "ingredients" to setOf("accounts", "prices", "recipes", "shopping"),
        "plan" to setOf("accounts", "ingredients", "recipes", "shopping", "tracking"),
        "prices" to setOf("ingredients"),
        "recipes" to setOf("accounts", "ingredients", "plan", "prices"),
        "shopping" to setOf("accounts", "ingredients", "plan", "prices", "recipes"),
        "tracking" to setOf("accounts", "ingredients", "plan"),
    )

    private val base = "dev.tobins.mealplanner.server"
    private val sourceRoot = File("src/main/kotlin/dev/tobins/mealplanner/server")
    private val features = allowed.keys

    /** Every production file with the feature it lives in (null for the root package) and the features it names. */
    private class Source(val file: File, val feature: String?, val uses: Set<String>)

    private fun sources(): List<Source> = sourceRoot.walkTopDown().filter { it.extension == "kt" }.map { file ->
        val text = file.readText()
        val packageName = Regex("^package (\\S+)", RegexOption.MULTILINE).find(text)!!.groupValues[1]
        val feature = packageName.removePrefix(base).removePrefix(".").substringBefore('.').ifEmpty { null }
        // Any mention of a feature's package, in an import or written out in code.
        val uses = Regex(Regex.escape("$base.") + "(\\w+)\\.").findAll(text).map { it.groupValues[1] }.filter { it in features }
        Source(file, feature, uses.toSet())
    }.toList()

    @Test
    fun everyFileIsInTheRootOrInTheFolderOfItsFeature() {
        for (source in sources()) {
            val folder = source.file.parentFile.relativeTo(sourceRoot).path.substringBefore('/').ifEmpty { null }
            assertEquals(folder, source.feature, "${source.file.name}: its package does not follow its folder")
            assertTrue(source.feature == null || source.feature in features, "${source.file.name}: ${source.feature} is no feature")
        }
    }

    @Test
    fun aFeatureUsesOnlyTheFeaturesThatListAllows() {
        val forbidden = sources().filter { it.feature != null }.flatMap { source ->
            (source.uses - source.feature!! - allowed.getValue(source.feature)).map { "${source.file.name} (${source.feature}) uses $it" }
        }
        assertEquals(emptyList(), forbidden, "uses between features that the list in FeatureUsesTest does not allow")
    }

    @Test
    fun theListHasNoUseThatNoFileHasAnyMore() {
        val used = sources().filter { it.feature != null }.groupBy({ it.feature!! }, { it.uses }).mapValues { (_, sets) -> sets.flatten().toSet() }
        val unused = allowed.flatMap { (feature, others) -> (others - used[feature].orEmpty()).map { "$feature uses $it" } }
        assertEquals(emptyList(), unused, "entries of the list that no file needs: remove them")
    }
}
