package dev.tobins.mealplanner.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.MainScope
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** The whole app on a fake server: a tapped notification opens its screen, from a closed app and from an open one (issue 225). */
@OptIn(ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class NotificationOpeningsTest {
    @get:Rule
    val compose = createComposeRule()

    private val backend = FakeBackend()
    private val openings = Openings()

    /** "Plan next week": the fake server's today is in the week of 16 November. */
    private val nextWeek = Opening(Route.TabRoute(Tab.PLAN), week = "2026-11-23")

    @Before
    fun fixTimeAndLanguage() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        appClock = object : Clock {
            override fun now(): Instant = Instant.parse("${FakeBackend.TODAY}T10:30:00Z")
        }
        RuntimeEnvironment.setQualifiers("en-rGB-w411dp-h900dp")
    }

    @After
    fun tearDown() {
        backend.close()
        appClock = Clock.System
    }

    private fun startApp() {
        val storage = MemorySettingsStorage().apply { saveSession(Session("anna-token", expiresAt = Long.MAX_VALUE)) }
        val launcher = object : LoginLauncher {
            override fun redirectUri(serverUrl: String) = "$serverUrl/app/login"
            override fun open(url: String) = Unit
        }
        compose.setContent { App(backend.settings, storage, launcher, LoginExchange(storage, MainScope()), openings = openings) }
    }

    private fun waitFor(text: String) =
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun planNextWeekOpensThePlanOnNextWeekFromAClosedApp() {
        // The notification was tapped before the app started: its screen is the first one drawn.
        openings.open(nextWeek)
        startApp()
        waitFor("Week of 23 Nov")
    }

    @Test
    fun planNextWeekOpensThePlanOnNextWeekFromAnOpenApp() {
        startApp()
        waitFor("Today")
        openings.open(nextWeek)
        waitFor("Week of 23 Nov")

        // Stepped back to this week and on to Today; the next notification shows next week afresh.
        compose.onNodeWithContentDescription("Previous week").performClick()
        waitFor("Week of 16 Nov")
        openings.open(Opening(Route.TabRoute(Tab.TODAY)))
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Week of 16 Nov").fetchSemanticsNodes().isEmpty() }
        openings.open(nextWeek)
        waitFor("Week of 23 Nov")
    }

    @Test
    fun theTabsKeepTheirPlaceWhenNoWeekIsAskedFor() {
        startApp()
        waitFor("Today")
        compose.onAllNodesWithText("Plan").onFirst().performClick()
        waitFor("Week of 16 Nov")
        compose.onNodeWithContentDescription("Next week").performClick()
        waitFor("Week of 23 Nov")
        openings.open(Opening(Route.TabRoute(Tab.TODAY)))
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Week of 23 Nov").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithText("Plan").onFirst().performClick()
        waitFor("Week of 23 Nov")
    }
}
