package dev.tobins.mealplanner.server.accounts

import dev.tobins.mealplanner.accounts.RegistrationRules
import dev.tobins.mealplanner.api.RegistrationRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The server's rules for a request for an account judge `scripts/registration-rules-cases.json` as the registration helper
 * does (its test reads the same file): a request the server sends on is never refused by the helper as invalid, which the
 * person would otherwise be told as a refusal with no field named. Here, in the server's tests, as only the JVM reads files.
 */
class RegistrationRulesAgreementTest {
    @Test
    fun theServerJudgesTheSharedCasesAsTheHelperDoes() {
        val file = File("../scripts/registration-rules-cases.json")
        assertTrue(file.isFile, "run from the server project: ${file.absolutePath}")
        val rules = Json.parseToJsonElement(file.readText()).jsonObject
        val base = rules.getValue("base").jsonObject.strings()
        val cases = rules.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size > 40)
        for (case in cases) {
            val fields = base + case.getValue("set").jsonObject.strings()
            val request = RegistrationRequest(fields.getValue("username"), fields.getValue("displayName"), fields.getValue("email"), fields.getValue("password"))
            val why = case.getValue("why").jsonPrimitive.content
            assertEquals(request, RegistrationRules.normalised(request), "$why: the cases are as the server sends them on")
            assertEquals(case.getValue("valid").jsonPrimitive.boolean, RegistrationRules.problems(request).isEmpty(), why)
        }
    }

    private fun JsonObject.strings(): Map<String, String> = mapValues { it.value.jsonPrimitive.content }
}
