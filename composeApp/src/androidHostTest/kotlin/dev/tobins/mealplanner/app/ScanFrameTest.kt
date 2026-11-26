package dev.tobins.mealplanner.app

import kotlin.test.Test
import kotlin.test.assertTrue

/** The aiming frame of the barcode scanner (issue 359): inside the screen with a margin, held upright and sideways. */
class ScanFrameTest {
    private fun assertFits(width: Float, height: Float) {
        val frame = scanFrameSize(width, height)
        val sideMargin = (width - frame.width) / 2
        val topMargin = (height - frame.height) / 2
        assertTrue(frame.width > frame.height, "wider than tall as a barcode is")
        assertTrue(sideMargin >= 16f, "left and right edges keep a margin, was $sideMargin")
        assertTrue(topMargin >= 16f, "top and bottom edges keep a margin, was $topMargin")
    }

    @Test
    fun theFrameFitsHeldUpright() = assertFits(360f, 780f)

    @Test
    fun theFrameFitsHeldSideways() = assertFits(780f, 360f)

    @Test
    fun theFrameFitsASmallPhoneSideways() = assertFits(640f, 320f)

    @Test
    fun theFrameFitsWhatALargeFontsHelpLineLeavesSideways() = assertFits(780f, 120f)

    @Test
    fun theFrameNeverExceedsAVeryShortSpace() {
        val frame = scanFrameSize(780f, 40f)
        assertTrue(frame.height <= 40f - 32f)
        assertTrue(frame.width >= 0f)
    }
}
