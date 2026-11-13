package dev.tobins.mealplanner.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdatesTest {
    private fun release(tag: String, assets: String = """[{"name":"mealplanner-$tag.apk","browser_download_url":"https://example.org/$tag.apk"}]""", prerelease: Boolean = false) =
        """{"tag_name":"$tag","body":"## What's changed\n* Dark mode","html_url":"https://example.org/r/$tag","prerelease":$prerelease,"assets":$assets,"extra":1}"""

    @Test
    fun versionsCompareNumerically() {
        assertTrue(AppUpdates.isNewer("0.1.10", "0.1.9"))
        assertTrue(AppUpdates.isNewer("v1.0.0", "0.9.9"))
        assertFalse(AppUpdates.isNewer("0.1.0", "0.1.0"))
        assertFalse(AppUpdates.isNewer("0.1.0", "0.2.0"))
        assertFalse(AppUpdates.isNewer("nightly", "0.1.0"))
    }

    @Test
    fun aNewerReleaseWithAnApkIsAnUpdate() {
        val update = AppUpdates.parse(release("v0.2.0"), currentVersion = "0.1.0")!!
        assertEquals("0.2.0", update.version)
        assertEquals("https://example.org/v0.2.0.apk", update.apkUrl)
        assertTrue(update.notes.contains("Dark mode"))
    }

    @Test
    fun noUpdateWhenCurrentWithoutApkOrPrerelease() {
        assertNull(AppUpdates.parse(release("v0.1.0"), "0.1.0"))
        assertNull(AppUpdates.parse(release("v0.2.0", assets = "[]"), "0.1.0"))
        assertNull(AppUpdates.parse(release("v0.2.0", prerelease = true), "0.1.0"))
    }
}
