package dev.tobins.mealplanner.app

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenMemoryTest {
    @Test
    fun aValueIsGivenBackForItsKeyOnly() {
        val memory = ScreenMemory()
        memory.keep("plan:2026-11-16", listOf(1, 2))
        assertEquals(listOf(1, 2), memory.recall<List<Int>>("plan:2026-11-16"))
        assertNull(memory.recall<List<Int>>("plan:2026-11-23"))
    }

    @Test
    fun nothingIsGivenBackFromAMemoryMadeForAnotherLogin() {
        val first = ScreenMemory()
        first.keep("recipes", "of the first account")
        val next = ScreenMemory()
        assertNull(next.recall<String>("recipes"))
    }

    @Test
    fun theOldestEntriesGoWhenThereAreTooMany() {
        val memory = ScreenMemory(capacity = 2)
        memory.keep("a", 1)
        memory.keep("b", 2)
        memory.keep("a", 1) // looked at again: now the newest
        memory.keep("c", 3)
        assertNull(memory.recall<Int>("b"))
        assertEquals(1, memory.recall<Int>("a"))
        assertEquals(3, memory.recall<Int>("c"))
    }

    @Test
    fun aScreenStartsFromWhatWasKeptAndKeepsWhatItLoadsNext() = runTest {
        val memory = ScreenMemory()
        assertTrue(LoadState<String>(memory, "recipes").value is Load.Loading)
        memory.keep("recipes", "old")
        val state = LoadState<String>(memory, "recipes")
        assertEquals(Load.Ready("old"), state.value)
        state.refresh { "new" }
        assertEquals(Load.Ready("new"), state.value)
        assertEquals("new", memory.recall<String>("recipes"))
    }

    @Test
    fun aFailedRefreshKeepsWhatIsShown() = runTest {
        val memory = ScreenMemory()
        memory.keep("recipes", "shown")
        val state = LoadState<String>(memory, "recipes")
        state.refresh { throw IOException("offline") }
        assertEquals(Load.Ready("shown"), state.value)
        assertEquals("shown", memory.recall<String>("recipes"))
        assertTrue(state.refreshFailed)
        state.refresh { "fresh" }
        assertEquals(false, state.refreshFailed)
    }

    @Test
    fun theFailureStaysWhileARetryIsUnderWayAndGoesOnlyWithASuccess() = runTest {
        val memory = ScreenMemory()
        memory.keep("recipes", "shown")
        val state = LoadState<String>(memory, "recipes")
        state.refresh { throw IOException("offline") }
        state.refresh {
            assertTrue(state.refreshFailed)
            assertTrue(state.refreshing)
            throw IOException("still offline")
        }
        assertTrue(state.refreshFailed)
        assertEquals(false, state.refreshing)
        state.refresh {
            assertTrue(state.refreshFailed)
            "fresh"
        }
        assertEquals(false, state.refreshFailed)
    }

    @Test
    fun aRecipeTheServerSaysIsGoneIsShownAsFailedAndForgotten() = runTest {
        val api = ApiClient(
            ServerSettings("http://localhost"),
            engine = MockEngine { respond("""{"error":"No such recipe"}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json")) },
        )
        val memory = ScreenMemory()
        memory.keep("recipe:4", "shown")
        val state = LoadState<Any>(memory, "recipe:4")
        state.refresh { api.recipe(4) }
        assertEquals(404, (state.value as Load.Failed).status)
        assertNull(memory.recall<Any>("recipe:4"))
    }

    @Test
    fun aScreenWithNothingYetShowsTheFailure() = runTest {
        val state = LoadState<String>(ScreenMemory(), "recipes")
        state.refresh { throw IOException("offline") }
        assertTrue(state.value is Load.Failed)
    }
}
