package dev.tobins.mealplanner.app

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
