package dev.tobins.mealplanner.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PlainReasonTest {
    @Test
    fun aTechnicalFailureIsToldInPlainWords() {
        val lost = Load.Failed("unexpected end of stream on http://192.168.1.2:8080/api/weights", technical = true, lostConnection = true)
        assertEquals("the server could not be reached", plainReason(lost))
        assertEquals("the server's answer could not be read", plainReason(Load.Failed("Unexpected JSON token", technical = true, unreadable = true)))
        assertEquals("something went wrong", plainReason(Load.Failed("Cannot read property of undefined", technical = true)))
        assertFalse("http" in plainReason(lost), "the exception's text stays behind Details")
    }
}

class FailureSentenceTest {
    private val unreachable = Load.Failed("unexpected end of stream on http://192.168.1.2:8080/api/offers", technical = true, lostConnection = true)

    @AfterTest
    fun backToEnglish() {
        Strings.current = Strings.English
    }

    @Test
    fun aFailedStapleTapIsACapitalisedSentenceWithAFullStop() {
        assertEquals("The server could not be reached.", failureSentence(unreachable))
        Strings.current = Strings.Dutch
        assertEquals("De server is niet bereikbaar.", failureSentence(unreachable))
    }

    @Test
    fun aFailedOffersRefreshGivesThePlainReasonNotTheExceptionText() {
        assertEquals("Could not refresh: the server could not be reached.", failureSentence(unreachable, Msg.REFRESH_FAILED))
        Strings.current = Strings.Dutch
        assertEquals("Vernieuwen is niet gelukt: de server is niet bereikbaar.", failureSentence(unreachable, Msg.REFRESH_FAILED))
    }

    @Test
    fun aFailedEatenTapOnTodayShowsTheSentenceNotTheExceptionText() = runTest {
        val shown = failureOf { throw IOException("unexpected end of stream on http://localhost:18081/api/today") }
        assertEquals("The server could not be reached.", shown)
        assertNull(failureOf { })
    }

    @Test
    fun theServersOwnExplanationStaysAndGetsNoSecondFullStop() {
        assertEquals("Not allowed.", failureSentence(Load.Failed("Not allowed.", status = 403)))
        assertEquals("Could not refresh: no offers today.", failureSentence(Load.Failed("no offers today", status = 502), Msg.REFRESH_FAILED))
    }

    @Test
    fun anEmptyOrBlankSentenceIsLeftAloneInsteadOfThrowing() {
        assertEquals("", sentence(""))
        assertEquals("  ", sentence("  "))
        assertEquals("", failureSentence(Load.Failed("", status = 500)))
    }

    @Test
    fun anEmptyServerErrorFallsBackToTheStatusLine() = runTest {
        val client = HttpClient(MockEngine { respond("""{"error": ""}""", HttpStatusCode.BadGateway, headersOf(HttpHeaders.ContentType, "application/json")) }) {
            expectSuccess = true
        }
        val failed = load { client.get("http://server/api/x") } as Load.Failed
        assertEquals("The server answered 502 Bad Gateway.", failureSentence(failed))
    }

    @Test
    fun everyActionFailureShownInASentenceHidesTheExceptionText() = runTest {
        val raw = IOException("unexpected end of stream on http://localhost:18081/api/x")
        assertEquals("Could not save: the server could not be reached.", failureSentence(failedFrom(raw), Msg.SAVE_FAILED))
        assertEquals("The server could not be reached.", sentence(reasonOf(raw)))
        // The app's own sentences (error(...), check(...)) are not exception text and stay.
        assertEquals("The login answer has no code", reasonOf(IllegalStateException("The login answer has no code")))
        // A cancellation is one by type on every platform, but its text is a library's.
        assertEquals("Something went wrong.", sentence(reasonOf(CancellationException("Request timeout has expired [url=http://server/x]"))))
        Strings.current = Strings.Dutch
        assertEquals("De server is niet bereikbaar.", sentence(reasonOf(raw)))
    }
}
