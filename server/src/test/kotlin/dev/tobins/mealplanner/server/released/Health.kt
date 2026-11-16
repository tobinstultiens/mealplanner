// Frozen from v0.1.3 (7123262bceced77d65a2a7e3eda0b816bd69dbad) by scripts/freeze-api.sh. Do not edit: freeze again after a release.
package dev.tobins.mealplanner.released

import kotlinx.serialization.Serializable

/** Response of `GET /health`; also the first type shared between server and app. */
@Serializable
data class Health(val status: String, val version: String)
