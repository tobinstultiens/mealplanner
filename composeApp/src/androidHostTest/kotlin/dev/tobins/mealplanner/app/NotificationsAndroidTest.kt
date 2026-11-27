package dev.tobins.mealplanner.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.app.accounts.NotificationsCard
import dev.tobins.mealplanner.app.recipes.MealPlace
import dev.tobins.mealplanner.app.recipes.MealToCook
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Android side of the notifications that the screen sees (issue 225): taps arriving as intents, and the permission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationsAndroidTest {
    private val dinner = Opening(Route.Recipe(12), MealToCook(12, 1.5, 2.25, skipped = false, MealPlace("2026-11-16", 3, MealSlot.DINNER), planned = true))

    private fun tap(opening: Opening, id: String) = Intent(Intent.ACTION_MAIN).putOpening(opening, id)

    /** The state Android saves of a screen with [inbox], and gives the screen it re-creates. */
    private fun savedState(inbox: NotificationInbox) = Bundle().also(inbox::save)

    @Test
    fun aTapIsHandedOnOnce() {
        val inbox = NotificationInbox(null)
        val intent = tap(dinner, "a")
        assertEquals(dinner, inbox.take(intent))
        assertNull(inbox.take(intent), "the same intent again")
        assertNull(NotificationInbox(null).take(Intent(Intent.ACTION_MAIN)), "no notification's")
    }

    @Test
    fun aReCreatedScreenDoesNotOpenItsOldTapAgain() {
        val first = NotificationInbox(null)
        val intent = tap(dinner, "a")
        first.take(intent)
        // A rotation: the screen is made again with its saved state and the intent it was started with.
        assertNull(NotificationInbox(savedState(first)).take(intent))
    }

    @Test
    fun aTapAfterTheProcessWasKilledComesWithSavedStateAndIsHandedOn() {
        val before = NotificationInbox(null)
        before.take(tap(Opening(Route.TabRoute(Tab.SHOPPING)), "a"))
        // Android killed the process with the task in Recents; the next tap re-creates the screen from its saved
        // state, with the new intent. Saved state is there, yet the tap is new.
        val restored = NotificationInbox(savedState(before))
        assertEquals(dinner, restored.take(tap(dinner, "b")))
        assertNull(NotificationInbox(savedState(restored)).take(tap(dinner, "b")), "and once handled, it is remembered too")
    }

    @Test
    fun theTaskOpenedFromRecentsOpensNothingAgain() {
        // Without saved state (it was lost) the intent from Recents still says what it is.
        val fromRecents = tap(dinner, "a").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(NotificationInbox(null).take(fromRecents))
    }

    @Test
    fun thePermissionIsReadAgainWhenTheScreenResumes() {
        val screen = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val access = AndroidNotificationAccess(screen.get())
        screen.start().resume()
        assertFalse(access.allowed.value, "Android 15 asks first")

        // Given in the system's settings while the app was behind them, or in their own question.
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        screen.pause().resume()
        assertTrue(access.allowed.value)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        screen.pause().resume()
        assertFalse(access.allowed.value)
    }
}

/** The Notifications card follows what the platform reports, while it is open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationsCardTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theRefusalLineGoesOnceThePermissionIsGiven() {
        val allowed = MutableStateFlow(false)
        val access = object : NotificationAccess {
            override val allowed = allowed

            override fun ask() = Unit

            override fun openSettings() = Unit
        }
        compose.setContent { NotificationsCard(NotificationSettings(MemorySettingsStorage(), access)) }
        val line = "Android does not let the app post notifications."
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText(line).fetchSemanticsNodes().isNotEmpty() }
        allowed.value = true
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText(line).fetchSemanticsNodes().isEmpty() }
    }
}
