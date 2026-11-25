package dev.tobins.mealplanner.api

import kotlinx.serialization.Serializable

/** `GET /api/registration`, public: whether this server takes requests for an account now (issue 314). */
@Serializable
data class RegistrationStatus(val open: Boolean)

/**
 * `POST /api/registration`, public: a person asks for an account (issue 314). The account is made in the directory at
 * once, in no group, with this password; the owner approves or rejects it. The password goes to the directory and
 * nowhere else, and [toString] leaves it out, so that a log line or an error that prints the request cannot hold it.
 */
@Serializable
data class RegistrationRequest(
    /** What the person logs in with; the server takes it in lower case ([RegistrationReceipt.username]). */
    val username: String,
    val displayName: String,
    val email: String,
    val password: String,
) {
    override fun toString() = "RegistrationRequest(username=$username, displayName=$displayName, email=$email)"
}

/** The answer (201) to a request that was taken: the user name to log in with, in lower case as the directory keeps it. */
@Serializable
data class RegistrationReceipt(val username: String)

/**
 * A request for an account that waits for the owner (`GET /api/registrations`, the owner's account only), oldest first.
 * [requestedAt] and [expiresAt] are instants (ISO, UTC): a request not approved by [expiresAt] is rejected by itself.
 */
@Serializable
data class AccountRequest(
    val username: String,
    val displayName: String,
    val email: String,
    val requestedAt: String,
    val expiresAt: String,
)

/**
 * The machine-readable `reason` next to the `error` sentence of a refused request for an account, or of the owner's
 * routes; the app tells them apart by these, not by the wording.
 */
object RegistrationRefusal {
    /** 404: this server takes no requests (`MEALPLANNER_REGISTRATION` off, or not set up). */
    const val OFF = "registration_off"

    /** 400: a field breaks a rule of [dev.tobins.mealplanner.accounts.RegistrationRules]; `field` names it, when one does. */
    const val INVALID = "invalid"

    /**
     * 409: the user name or e-mail address cannot be used: in use in the directory, or the user name of an account the
     * meal planner had. One answer for all of them, so the form tells nobody which.
     */
    const val UNAVAILABLE = "unavailable"

    /** 429: too many requests from this address, or from everyone, for now; `Retry-After` says when to try again. */
    const val TOO_MANY = "too_many"

    /** 503: as many requests wait for the owner as the server keeps. */
    const val FULL = "full"

    /** 503: the directory could not be reached; nothing was made. */
    const val DIRECTORY_DOWN = "directory_down"

    /** 403: the owner's routes, called by another account. */
    const val NOT_OWNER = "not_owner"
}
