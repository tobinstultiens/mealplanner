// Frozen from v0.1.5 (32d25e8a4073a3eed02ca4fd44a7b98461199be5) by scripts/freeze-api.sh. Do not edit: freeze again after a release.
package dev.tobins.mealplanner.released

import kotlinx.serialization.Serializable

/**
 * Response of `GET /health`; also the first type shared between server and app.
 * [commit] is the commit the server was built from ("dev" outside CI); optional, so apps from before
 * it and servers from before it keep reading each other.
 */
@Serializable
data class Health(val status: String, val version: String, val commit: String? = null)

/**
 * Response of `GET /health/data`: "ok", or "stale" with what is wrong with the shared store data
 * (offers, regular prices). Public, so it says nothing about any account.
 */
@Serializable
data class DataHealth(val status: String, val problems: List<String> = emptyList())
