// Frozen from v0.1.8 (1a56aa21c718bafd22d7c56d7176ef7e8bf7ca1c) by scripts/freeze-api.sh. Do not edit: freeze again after a release.
package dev.tobins.mealplanner.released.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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

/**
 * Where an account's recipe bank comes from, set with `PUT /api/sync/cooking/repo`: a repository of its
 * own ([url]), the server's cooking site (no [url]), or nothing at all ([noSite]).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CookingRepoSetting(
    /** A git URL (https://… or git@…) of the account's own repository, or null for none of its own. */
    val url: String?,
    /**
     * With no [url]: true for no cooking site at all, false (what apps before it meant by no URL) for the
     * server's own, when it offers one ([CookingSyncStatus.serverSite]). Never sent when false, so a
     * server from before it, which refuses fields it does not know, still takes every other choice.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val noSite: Boolean = false,
)
