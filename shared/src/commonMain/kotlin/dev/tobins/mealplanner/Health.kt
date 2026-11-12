package dev.tobins.mealplanner

import kotlinx.serialization.Serializable

/** Response of `GET /health`; also the first type shared between server and app. */
@Serializable
data class Health(val status: String, val version: String)
