package dev.tobins.mealplanner.android

import android.Manifest
import android.app.NotificationManager
import dev.tobins.mealplanner.app.DueNotification
import dev.tobins.mealplanner.app.NotificationKind
import dev.tobins.mealplanner.app.Opening
import dev.tobins.mealplanner.app.Route
import dev.tobins.mealplanner.app.Strings
import dev.tobins.mealplanner.app.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What Android's tray holds once notifications are posted (issue 263): food to throw away is told per batch, so a batch told
 * later the same day goes next to the first and the same batches posted again update theirs; a kind without a tag keeps
 * one notification, which a newer one replaces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationPostTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val plan = Opening(Route.TabRoute(Tab.PLAN), week = "2026-11-16")

    @Before
    fun allowNotifications() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun throwAway(ids: String, title: String) =
        DueNotification(NotificationKind.THROW_AWAY, title, "It kept until Fri 20 Nov.", plan, "2026-11-21:$ids", tag = "throwAway:2026-11-21:$ids")

    private fun shown(): List<Pair<String?, String>> =
        manager.activeNotifications.map { it.tag to it.notification.extras.getCharSequence("android.title").toString() }.sortedBy { it.second }

    @Test
    fun aBatchToldLaterTheSameDayGoesNextToTheFirst() {
        assertTrue(post(context, throwAway("7", "Throw away 1 portion of Chili con carne"), Strings.English))
        assertTrue(post(context, throwAway("9", "Throw away 1 side of Lentil salad"), Strings.English))
        assertEquals(
            listOf("throwAway:2026-11-21:9" to "Throw away 1 side of Lentil salad", "throwAway:2026-11-21:7" to "Throw away 1 portion of Chili con carne").sortedBy { it.second },
            shown(),
        )
        // The same batch posted again updates its notification rather than adding one.
        post(context, throwAway("7", "Throw away 1 portion of Chili con carne, again"), Strings.English)
        assertEquals(2, manager.activeNotifications.size)
        assertTrue(shown().any { it.second == "Throw away 1 portion of Chili con carne, again" })
    }

    @Test
    fun aNewerNotificationOfAKindWithoutATagReplacesTheOlder() {
        post(context, DueNotification(NotificationKind.DINNER_TODAY, "Tonight: Chili con carne", "6 servings to cook", null, "2026-11-20"), Strings.English)
        post(context, DueNotification(NotificationKind.DINNER_TODAY, "Tonight: Lentil soup", "2 servings to cook", null, "2026-11-21"), Strings.English)
        assertEquals(listOf(null to "Tonight: Lentil soup"), shown())
        assertEquals(NotificationKind.DINNER_TODAY.ordinal + 1, manager.activeNotifications.single().id)
    }
}
