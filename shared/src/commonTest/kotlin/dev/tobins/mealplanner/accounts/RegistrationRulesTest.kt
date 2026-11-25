package dev.tobins.mealplanner.accounts

import dev.tobins.mealplanner.accounts.RegistrationField.DISPLAY_NAME
import dev.tobins.mealplanner.accounts.RegistrationField.EMAIL
import dev.tobins.mealplanner.accounts.RegistrationField.PASSWORD
import dev.tobins.mealplanner.accounts.RegistrationField.USERNAME
import dev.tobins.mealplanner.api.RegistrationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RegistrationRulesTest {
    private val good = RegistrationRequest("anna", "Anna de Vries", "anna@example.org", "correct horse")

    private fun problems(change: RegistrationRequest.() -> RegistrationRequest) = RegistrationRules.problems(good.change())

    @Test
    fun aGoodRequestHasNoProblems() {
        assertEquals(emptyList(), RegistrationRules.problems(good))
        assertEquals(emptyList(), problems { copy(username = "  Anna.de-Vries_2 ", displayName = " Anna ", email = " a@b.nl ") }, "taken as normalised")
    }

    @Test
    fun theUserNameIsWhatTheDirectoryKeepsAsItIs() {
        assertEquals("anna.de-vries_2", RegistrationRules.normalised(good.copy(username = "  Anna.de-Vries_2 ")).username)
        for (name in listOf("an", "a".repeat(33), "anna smith", "anna@home", ".anna", "-anna", "änna", "anna,ou=people", "")) {
            assertEquals(listOf(USERNAME), problems { copy(username = name) }, name)
        }
        assertEquals(emptyList(), problems { copy(username = "a".repeat(32)) })
        assertEquals(emptyList(), problems { copy(username = "007") })
    }

    @Test
    fun theOtherFields() {
        assertEquals(listOf(DISPLAY_NAME), problems { copy(displayName = "   ") })
        assertEquals(listOf(DISPLAY_NAME), problems { copy(displayName = "A".repeat(65)) })
        assertEquals(listOf(DISPLAY_NAME), problems { copy(displayName = "Anna\nAdmin") })
        assertEquals(listOf(EMAIL), problems { copy(email = "anna") })
        assertEquals(listOf(EMAIL), problems { copy(email = "anna@localhost") })
        assertEquals(listOf(EMAIL), problems { copy(email = "an na@example.org") })
        assertEquals(listOf(EMAIL), problems { copy(email = "a".repeat(250) + "@b.nl") })
        assertEquals(listOf(PASSWORD), problems { copy(password = "123456789") })
        assertEquals(listOf(PASSWORD), problems { copy(password = "x".repeat(129)) })
        assertEquals(listOf(PASSWORD), problems { copy(username = "annasmith1", password = "AnnaSmith1") }, "not the user name")
        assertEquals(emptyList(), problems { copy(password = " leading and trailing spaces count ") })
        assertEquals(listOf(USERNAME, DISPLAY_NAME, EMAIL, PASSWORD), RegistrationRules.problems(RegistrationRequest("", "", "", "")), "in the form's order")
    }

    @Test
    fun thePasswordIsNotInTheRequestsText() {
        assertFalse("correct horse" in good.toString(), good.toString())
    }
}
