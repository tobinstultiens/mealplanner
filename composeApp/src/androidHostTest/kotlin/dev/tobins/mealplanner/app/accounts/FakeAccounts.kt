package dev.tobins.mealplanner.app.accounts

import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.ProfileState
import dev.tobins.mealplanner.api.RegistrationReceipt
import dev.tobins.mealplanner.api.RegistrationRequest
import dev.tobins.mealplanner.api.RegistrationStatus
import dev.tobins.mealplanner.app.FakeBackend
import dev.tobins.mealplanner.app.FakeBackend.Refusal
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/* The accounts' part of [FakeBackend]: the profile and the requests for an account. */

/** `GET /api/profile`, and `PUT` which stores what was sent (the targets stay the fixed ones). */
internal fun FakeBackend.profileJson(method: String, sent: String): String {
    if (method == "PUT") {
        profile = json.decodeFromString<Profile>(sent)
        changes += "PUT /api/profile $sent"
    }
    return json.encodeToString(ProfileState(profile, targets.takeIf { profile != null }, targets.takeIf { profile != null }, 2400.takeIf { profile != null }))
}

/**
 * Asking for an account and the owner's decisions (issue 314), as the server answers them: nothing from a server from
 * before registration ([registrationOpen] null); the owner's routes refused to any other account.
 */
internal fun FakeBackend.registrationRoute(method: String, parts: List<String>, sent: String): String? {
    val open = registrationOpen ?: return null
    if (parts[1] == "registration") {
        if (method == "GET") return json.encodeToString(RegistrationStatus(open))
        registered += sent
        registrationRefusal?.let { (status, reason) -> throw Refusal(status, "The server's own sentence.", reason) }
        return json.encodeToString(RegistrationReceipt(json.decodeFromString<RegistrationRequest>(sent).username.lowercase()))
    }
    if (!owner) throw Refusal(403, "Only the owner of this server decides on requests for an account.", "not_owner")
    // POST /api/registrations/{username}/approve or .../reject
    if (method == "POST" && parts.size == 4) {
        changes += "POST /${parts.joinToString("/")}"
        if (!accountRequests.removeIf { it.username == parts[2] }) throw Refusal(404, "No request of ${parts[2]} waits")
    }
    return json.encodeToString(accountRequests.toList())
}
