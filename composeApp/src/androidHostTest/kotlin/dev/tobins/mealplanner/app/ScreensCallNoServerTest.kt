package dev.tobins.mealplanner.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The screens that have a state class (see `docs/architecture.md`) do not call the server from their composables: what
 * they load and send is the state class's, which a test drives without drawing. The check reads the files that hold such a
 * screen's composables and fails on a call of the server in them, so that one added by habit is seen at once. A screen
 * that gets its state class is added to [screens] with the files of its composables.
 *
 * What it looks for, in the code without its comments: a call on a value named `api` (the screen's `ApiClient`), except
 * the one that makes the state's server, and a call of a function of the feature's `*Calls.kt`. What it cannot see: a call
 * made through another name for the client (`val client = api`), a composable of another file or feature that is given
 * the client and calls the server itself (the screen's other screens still are), and a call made in a file that is not in
 * the list.
 */
class ScreensCallNoServerTest {
    /** A screen's composables: the files, in the feature's folder, and the calls on `api` they may make. */
    private class Screen(val feature: String, val files: List<String>, val allowedOnApi: Set<String>)

    private val screens = listOf(
        Screen("plan", listOf("PlanScreen.kt", "Prep.kt", "Sides.kt", "StandardMeals.kt"), allowedOnApi = setOf("planServer")),
        Screen("recipes", listOf("RecipeScreens.kt", "ToppingsSettings.kt"), allowedOnApi = setOf("recipeServer")),
    )

    private val appFolder = "src/commonMain/kotlin/dev/tobins/mealplanner/app"

    private fun file(feature: String, name: String) = File("$appFolder/$feature/$name")

    /** The code of [file] without `//` comments, KDoc and block comments, so that a sentence that names a call is no call. */
    private fun codeOf(file: File): String = file.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    /** The names of the server calls the feature has: `suspend fun ApiClient.name(` in its `*Calls.kt`. */
    private fun callsOf(feature: String): Set<String> = File("$appFolder/$feature").listFiles { f -> f.name.endsWith("Calls.kt") }.orEmpty()
        .flatMap { Regex("""fun ApiClient\.(\w+)\(""").findAll(it.readText()).map { m -> m.groupValues[1] }.toList() }
        .toSet()

    /** Each place in [code] that calls the server: what is called and the line. */
    private fun serverCalls(code: String, calls: Set<String>, allowedOnApi: Set<String>): List<String> {
        val found = mutableListOf<String>()
        code.lines().forEachIndexed { index, line ->
            for (m in Regex("""(?<![.\w])api\s*\.\s*(\w+)""").findAll(line)) {
                if (m.groupValues[1] !in allowedOnApi) found += "line ${index + 1}: api.${m.groupValues[1]}"
            }
            for (name in calls) {
                if (Regex("""(?<![.\w])$name\s*\(""").containsMatchIn(line) && !line.trimStart().startsWith("fun ")) found += "line ${index + 1}: $name("
            }
        }
        return found
    }

    @Test
    fun theFilesAndTheCallsOfEveryScreenAreFound() {
        for (screen in screens) {
            for (name in screen.files) assertTrue(file(screen.feature, name).isFile, "${screen.feature}/$name must exist, read from the module's folder")
            assertTrue(callsOf(screen.feature).size > 10, "the calls of ${screen.feature} are found")
        }
    }

    @Test
    fun noComposableOfAScreenWithAStateClassCallsTheServer() {
        val calls = screens.flatMap { screen ->
            val names = callsOf(screen.feature)
            screen.files.flatMap { name -> serverCalls(codeOf(file(screen.feature, name)), names, screen.allowedOnApi).map { "${screen.feature}/$name $it" } }
        }
        assertEquals(emptyList(), calls, "these composables call the server; it is the screen's state class that does")
    }

    @Test
    fun theCheckSeesACallOnTheClientAndACallOfTheFeaturesCalls() {
        val names = setOf("rerollMeal", "plan")
        assertEquals(listOf("line 1: api.rerollMeal"), serverCalls("api.rerollMeal(week, 1, slot)", names, emptySet()))
        assertEquals(listOf("line 1: plan("), serverCalls("with(client) { plan(week) }", names, emptySet()))
        assertEquals(emptyList(), serverCalls("val server = api.planServer()", names, setOf("planServer")))
        assertEquals(emptyList(), serverCalls(codeOf(File.createTempFile("code", ".kt").apply { writeText("/** api.plan(week) */\nval x = 1"); deleteOnExit() }), names, emptySet()))
    }
}
