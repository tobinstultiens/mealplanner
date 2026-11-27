package dev.tobins.mealplanner.server.accounts

import dev.tobins.mealplanner.api.RegistrationRefusal
import dev.tobins.mealplanner.api.RegistrationRequest
import dev.tobins.mealplanner.server.openDatabase
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The limits of [Registrations] that take time to show: everyone's requests in a day, and the expiry after 14 days. */
class RegistrationsTest {
    private val directory = FakeDirectory()
    private val db = openDatabase("jdbc:sqlite::memory:").second
    private var now = Instant.parse("2026-11-25T12:00:00Z")
    private val registrations = Registrations(db, directory) { now }

    private fun request(name: String) = runBlocking { registrations.request(RegistrationRequest(name, name, "$name@example.org", "a good long password")) }

    @Test
    fun everyonesRequestsInADay() {
        repeat(Registrations.OVERALL) { registrations.admit("192.0.2.$it") }
        val refused = assertFailsWith<RegistrationRefusedException> { registrations.admit("198.51.100.1") }
        assertEquals(RegistrationRefusal.TOO_MANY, refused.reason)
        assertEquals(Duration.ofDays(1), refused.retryAfter)
        // A day after the first, there is room for one again.
        now = now.plus(Duration.ofDays(1)).plusSeconds(1)
        registrations.admit("198.51.100.1")
    }

    @Test
    fun anAddressOverItsLimitDoesNotUseUpEveryonesRequests() {
        repeat(Registrations.PER_ADDRESS) { registrations.admit("192.0.2.1") }
        repeat(100) { assertFailsWith<RegistrationRefusedException> { registrations.admit("192.0.2.1") } }
        // Only the ones that went through count for everyone.
        repeat(Registrations.OVERALL - Registrations.PER_ADDRESS) { registrations.admit("198.51.100.$it") }
        assertFailsWith<RegistrationRefusedException> { registrations.admit("203.0.113.1") }
    }

    @Test
    fun aRequestNobodyDecidedOnExpiresAfterFourteenDaysWithItsAccount() = runBlocking {
        request("anna")
        now = now.plus(Duration.ofDays(7))
        request("bob")
        now = now.plus(Duration.ofDays(7)).plusSeconds(1)

        assertEquals(1, registrations.expire())
        assertNull(directory.users["anna"], "deleted from the directory")
        assertEquals(listOf("bob"), registrations.waiting().map { it.username })

        // The helper down: bob stays, and goes at the next round that reaches it.
        now = now.plus(Duration.ofDays(7))
        directory.failing = HelperAnswer.FAILED
        assertEquals(0, registrations.expire())
        assertEquals(listOf("bob"), registrations.waiting().map { it.username })
        directory.failing = null
        assertEquals(1, registrations.expire())
        assertEquals(emptyList(), registrations.waiting())
        assertNull(directory.users["bob"])
    }

    @Test
    fun anExpiredRequestApprovedByHandInTheDirectoryStaysThere() = runBlocking {
        request("anna")
        directory.users.getValue("anna") += "mealplanner"
        now = now.plus(Duration.ofDays(15))
        assertEquals(1, registrations.expire())
        assertEquals(listOf("mealplanner"), directory.users["anna"]?.toList(), "not deleted: not the helper's any more")
        assertEquals(emptyList(), registrations.waiting())
    }
}
