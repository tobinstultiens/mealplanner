package dev.tobins.mealplanner.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** What the "Write a recipe" dialog says when the server refuses: two different 503s, and no setting's name for the user (issue 271). */
class GenerationErrorTest {
    private val off = """{"error":"Recipe generation is off: set ANTHROPIC_API_KEY on the server","reason":"generation_off"}"""
    private val busy = """{"error":"The server is busy saving something else; try again in a moment"}"""

    @AfterTest
    fun backToEnglish() {
        Strings.current = Strings.English
    }

    private suspend fun answered(status: HttpStatusCode, body: String): Load.Failed {
        val client = HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }) { expectSuccess = true }
        return load { client.post("http://server/api/recipes/generate") } as Load.Failed
    }

    @Test
    fun theReasonOfTheServersAnswerIsRead() = runTest {
        assertEquals("generation_off", answered(HttpStatusCode.ServiceUnavailable, off).reason)
        assertNull(answered(HttpStatusCode.ServiceUnavailable, busy).reason, "an answer without a reason, as old servers give")
        assertNull(answered(HttpStatusCode.BadGateway, "not json").reason)
    }

    @Test
    fun aServerThatDoesNotWriteRecipesSaysSoAndKeepsTheSettingBehindDetails() = runTest {
        val shown = generationError(Strings.English, answered(HttpStatusCode.ServiceUnavailable, off))
        assertEquals("This server does not write recipes.", shown.summary)
        assertFalse("ANTHROPIC" in shown.summary)
        assertEquals("Recipe generation is off: set ANTHROPIC_API_KEY on the server", shown.details)
        assertEquals("Deze server maakt geen recepten.", generationError(Strings.Dutch, answered(HttpStatusCode.ServiceUnavailable, off)).summary)
    }

    @Test
    fun aBusyServerShowsItsOwnSentenceNotGenerationIsOff() = runTest {
        val shown = generationError(Strings.English, answered(HttpStatusCode.ServiceUnavailable, busy))
        assertEquals("Could not generate: The server is busy saving something else; try again in a moment.", shown.summary)
        assertNull(shown.details)
    }

    @Test
    fun aMissingProfileStaysItsOwnMessage() = runTest {
        val shown = generationError(Strings.English, answered(HttpStatusCode.Conflict, """{"error":"no profile"}"""))
        assertEquals("Set up your profile first; recipes are sized to your targets.", shown.summary)
    }
}
