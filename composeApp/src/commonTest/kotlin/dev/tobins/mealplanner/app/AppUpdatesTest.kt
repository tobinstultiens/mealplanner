package dev.tobins.mealplanner.app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    @Test
    fun notesAreReadFromTheReleaseBody() {
        val body = "- Dark mode\n- Shopping list"
        val update = AppUpdates.parse("""{"tag_name":"v0.2.0","body":${Json.encodeToString(body)},"html_url":"u","assets":[{"name":"a.apk","browser_download_url":"d"}]}""", "0.1.0")!!
        assertEquals(body, update.notes)
    }

    @Test
    fun missingOrEmptyNotesGiveNoLines() {
        val noBody = AppUpdates.parse("""{"tag_name":"v0.2.0","html_url":"u","assets":[{"name":"a.apk","browser_download_url":"d"}]}""", "0.1.0")!!
        val nullBody = AppUpdates.parse("""{"tag_name":"v0.2.0","body":null,"html_url":"u","assets":[{"name":"a.apk","browser_download_url":"d"}]}""", "0.1.0")!!
        assertEquals("", noBody.notes)
        assertEquals("", nullBody.notes)
        assertEquals(emptyList(), noteLines(noBody.notes))
        assertEquals(emptyList(), noteLines("  \n\n"))
    }

    @Test
    fun bulletsBecomePlainLines() {
        assertEquals(listOf("\u2022 Dark mode", "\u2022 Shopping list", "Thanks"), noteLines("## What's changed\n- Dark mode\n* Shopping list\n\nThanks"))
    }
}
