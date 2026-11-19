package dev.tobins.mealplanner.android

import androidx.lifecycle.Lifecycle
import androidx.work.Configuration
import androidx.work.WorkManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals

/**
 * The real MainActivity starts: made, created and resumed as Android does it, with the app's manifest and
 * theme. Everything else is tested piece by piece, so this is what notices the screen itself failing to
 * start, such as a field that reads the context while the activity is being constructed (#242).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppStartTest {
    @Before
    fun startWorkManager() {
        // On a device WorkManager starts itself through the app's startup provider, which Robolectric does not run.
        WorkManager.initialize(RuntimeEnvironment.getApplication(), Configuration.Builder().build())
    }

    @Test
    fun theAppStartsAndReachesResumed() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { screen ->
            assertEquals(Lifecycle.State.RESUMED, screen.get().lifecycle.currentState)
        }
    }
}
