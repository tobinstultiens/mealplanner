package dev.tobins.mealplanner.api

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

/**
 * The machine-readable `reason` next to the `error` sentence of the 403 a server answers to a valid login that may
 * not use it (`MEALPLANNER_OIDC_GROUP`), on every route. The app tells them apart by these, not by the wording.
 */
object LoginRefusal {
    /** The login says which groups the account is in, and the server's group is not among them. */
    const val NOT_IN_GROUP = "not_in_group"

    /**
     * The login says nothing about groups: made before the server asked for them (logging in again fixes it), or
     * the provider does not put them in its access tokens (only its owner can fix that).
     */
    const val NO_GROUPS = "no_groups"

    /**
     * Not a `reason` but the optional `detail` next to [NOT_IN_GROUP]: the account asked for access itself (issue 314) and
     * the owner has not decided yet. The reason stays [NOT_IN_GROUP], which installed apps act on (they end the session);
     * an app that knows the detail says "Your request is waiting for approval" instead of "may not use".
     */
    const val WAITING_FOR_APPROVAL = "waiting_for_approval"
}

/** The logged-in account, from `GET /api/me`. */
@Serializable
data class Me(
    val username: String?,
    val displayName: String?,
    /** Whether this account approves the requests for an account (issue 314): the owner's, on a server with registration set up. */
    val approvesRequests: Boolean = false,
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
