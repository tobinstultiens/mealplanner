package dev.tobins.mealplanner.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
    fun theServersOwnExplanationStaysAndGetsNoSecondFullStop() {
        assertEquals("Not allowed.", failureSentence(Load.Failed("Not allowed.", status = 403)))
        assertEquals("Could not refresh: no offers today.", failureSentence(Load.Failed("no offers today", status = 502), Msg.REFRESH_FAILED))
    }
}
