package dev.tobins.mealplanner.server

import kotlin.test.Test
import kotlin.test.assertEquals

class DelimitedTextTest {
    @Test
    fun splitsPlainAndQuotedFields() {
        val text = "\"a\"|\"b|c\"|d\r\n1||\"say \"\"hi\"\"\"\n\n\"multi\nline\"|x|"
        assertEquals(
            listOf(
                listOf("a", "b|c", "d"),
                listOf("1", "", "say \"hi\""),
                listOf("multi\nline", "x", ""),
            ),
            parseDelimited(text, '|'),
        )
    }

    @Test
    fun emptyInputHasNoRecords() {
        assertEquals(emptyList(), parseDelimited("", '|'))
        assertEquals(emptyList(), parseDelimited("\r\n\n", '|'))
    }
}
