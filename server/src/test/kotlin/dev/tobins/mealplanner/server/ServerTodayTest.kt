package dev.tobins.mealplanner.server

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerTodayTest {
    private fun at(utc: String) = serverToday(Clock.fixed(Instant.parse(utc), ZoneOffset.UTC))

    @Test
    fun todayIsTheDayInAmsterdamNotInTheContainersZone() {
        // 23:30 UTC on 15 November is already 16 November in Amsterdam (one hour ahead in winter).
        assertEquals(LocalDate.of(2026, 11, 16), at("2026-11-15T23:30:00Z"))
        assertEquals(LocalDate.of(2026, 11, 15), at("2026-11-15T22:30:00Z"))
        // In summer Amsterdam is two hours ahead.
        assertEquals(LocalDate.of(2026, 7, 2), at("2026-07-01T22:30:00Z"))
    }
}
