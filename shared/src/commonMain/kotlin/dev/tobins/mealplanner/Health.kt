package dev.tobins.mealplanner

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
