package dev.tobins.mealplanner.app

import androidx.compose.foundation.verticalScroll
import dev.tobins.mealplanner.app.ui.AppError
import dev.tobins.mealplanner.app.ui.Spacing
import dev.tobins.mealplanner.app.ui.reasonOf
import dev.tobins.mealplanner.app.ui.sentence
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A newer version of the Android app, published as a GitHub release. */
data class AppUpdate(
    /** Without the leading "v", e.g. `0.2.0`. */
    val version: String,
    /** The release notes (Markdown). */
    val notes: String,
    val apkUrl: String,
    val pageUrl: String,
)

/** Downloads and installs an [AppUpdate]; only the Android app has one. */
interface UpdateInstaller {
    /** The installed app's version, e.g. `0.1.0`. */
    val currentVersion: String

    /** Downloads the APK and hands it to the system installer. */
    fun install(update: AppUpdate)
}

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tag: String,
    val body: String? = null,
    @SerialName("html_url") val pageUrl: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
)

/** Checks the GitHub releases for a newer app, the way Mihon does. */
object AppUpdates {
    const val LATEST_RELEASE = "https://api.github.com/repos/tobinstultiens/mealplanner/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The latest release if it is newer than [currentVersion] and has an APK; null when the
     * app is up to date. Throws when GitHub cannot be reached.
     */
    suspend fun check(currentVersion: String, url: String = LATEST_RELEASE): AppUpdate? {
        val http = HttpClient { install(ContentNegotiation) { json(json) } }
        try {
            val response = http.get(url) { header("Accept", "application/vnd.github+json") }
            if (!response.status.isSuccess()) throw AppError(Strings.current(AppMsg.GITHUB_ANSWERED, response.status.value))
            return newer(response.body<GithubRelease>(), currentVersion)
        } finally {
            http.close()
        }
    }

    /** Parses a release (the `releases/latest` response) and returns it when it is an update. */
    fun parse(body: String, currentVersion: String): AppUpdate? = newer(json.decodeFromString<GithubRelease>(body), currentVersion)

    private fun newer(release: GithubRelease, currentVersion: String): AppUpdate? {
        if (release.draft || release.prerelease) return null
        val version = release.tag.removePrefix("v")
        if (!isNewer(version, currentVersion)) return null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") } ?: return null
        return AppUpdate(version, release.body.orEmpty().trim(), apk.downloadUrl, release.pageUrl)
    }

    /** True when [candidate] is a later `major.minor.patch` than [current]; anything unparsable is not. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate) ?: return false
        val b = parts(current) ?: return true
        return compareValuesBy(a, b, { it[0] }, { it[1] }, { it[2] }) > 0
    }

    private fun parts(version: String): List<Int>? =
        version.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }
            .takeIf { it.size == 3 }
}

/**
 * The release notes as lines of plain text: a Markdown bullet (`- ` or `* `) becomes a dot, blank
 * lines and `#` headings are left out. Notes are written by the release script, so nothing more
 * than bullets is expected.
 */
fun noteLines(notes: String): List<String> =
    notes.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { if (it.startsWith("- ") || it.startsWith("* ")) "\u2022 " + it.drop(2).trim() else it }

/** What the update dialog says: the version and, when the release has notes, "What's new" with them. */
@androidx.compose.runtime.Composable
fun UpdateBody(update: AppUpdate, currentVersion: String) {
    val t = LocalStrings.current
    val lines = noteLines(update.notes)
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.Related)) {
        androidx.compose.material3.Text(t(AppMsg.UPDATE_EXPLAIN, currentVersion))
        if (lines.isNotEmpty()) {
            androidx.compose.material3.Text(t(AppMsg.WHATS_NEW), style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            lines.forEach { androidx.compose.material3.Text(it) }
        }
    }
}

/** Asks whether to install [update]; long notes scroll while the buttons stay in view. */
@androidx.compose.runtime.Composable
fun UpdateDialog(update: AppUpdate, currentVersion: String, onUpdate: () -> Unit, onLater: () -> Unit) {
    val t = LocalStrings.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onLater,
        title = { androidx.compose.material3.Text(t(AppMsg.UPDATE_AVAILABLE, update.version)) },
        text = {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                UpdateBody(update, currentVersion)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onUpdate) { androidx.compose.material3.Text(t(AppMsg.UPDATE)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onLater) { androidx.compose.material3.Text(t(AppMsg.LATER)) } },
    )
}

/** What the settings say when the check for a newer app failed with [e]: a plain sentence, never GitHub's or the HTTP library's own text. */
internal suspend fun updateCheckFailure(t: Strings, e: Throwable): String = sentence(t(AppMsg.CHECK_FAILED, reasonOf(e)))
