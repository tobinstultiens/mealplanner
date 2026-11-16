// Frozen from v0.1.3 (7123262bceced77d65a2a7e3eda0b816bd69dbad) by scripts/freeze-api.sh. Do not edit: freeze again after a release.
package dev.tobins.mealplanner.released.api

import kotlinx.serialization.Serializable

/**
 * How clients log in, from the public `GET /api/auth/config`: the OpenID Connect provider
 * (Authelia) and this app's client there. Clients use the authorization code flow with PKCE.
 */
@Serializable
data class AuthConfig(
    val issuer: String,
    val clientId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val scopes: List<String>,
)

/** The logged-in account, from `GET /api/me`. */
@Serializable
data class Me(
    val username: String?,
    val displayName: String?,
)

/** An account's cooking-site repository, set with `PUT /api/sync/cooking/repo`. */
@Serializable
data class CookingRepoSetting(
    /** A git URL (https://… or git@…), or null for none. */
    val url: String?,
)
