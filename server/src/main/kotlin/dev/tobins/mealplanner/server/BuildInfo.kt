package dev.tobins.mealplanner.server

import java.util.Properties

/**
 * What this server was built from, read from `build-info.properties` in the jar (written by
 * server/build.gradle.kts). Without it, or outside a build that knows, both are "dev".
 */
object BuildInfo {
    private val properties = Properties().apply {
        BuildInfo::class.java.getResourceAsStream("/build-info.properties")?.use { load(it) }
    }

    /** The latest `v*` tag reachable from the built commit. */
    val version: String = properties.getProperty("version") ?: "dev"

    /** The commit's SHA: `GITHUB_SHA` in CI. */
    val commit: String = properties.getProperty("commit") ?: "dev"
}
