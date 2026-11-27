package dev.tobins.mealplanner.server.accounts

import dev.tobins.mealplanner.api.RegistrationRefusal
import dev.tobins.mealplanner.api.RegistrationRequest
import dev.tobins.mealplanner.api.RegistrationStatus
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A request for an account is a few short fields; anything longer is no such request. */
private const val MAX_BODY = 4096L

/**
 * Asking for an account (issue 314), without a login: whether this server takes requests ([open]: `MEALPLANNER_REGISTRATION`
 * on and set up), and the request itself, which is counted against the limits before it is read.
 */
fun Route.registrationRoutes(open: Boolean, registrations: Registrations?) {
    get("/api/registration") { call.respond(RegistrationStatus(open)) }

    post("/api/registration") {
        if (!open || registrations == null) {
            throw RegistrationRefusedException(HttpStatusCode.NotFound, RegistrationRefusal.OFF, "This server takes no requests for an account.")
        }
        registrations.admit(clientAddress(call.request))
        call.respond(HttpStatusCode.Created, registrations.request(call.registrationRequest()))
    }
}

/**
 * The request in the body, read by hand rather than with `receive`: what fails to parse is answered without its text, as
 * `receive`'s error would quote it, and it may hold the password.
 */
private suspend fun RoutingCall.registrationRequest(): RegistrationRequest {
    val text = receiveChannel().readRemaining(MAX_BODY + 1).readString()
    val request = if (text.length > MAX_BODY) {
        null
    } else {
        try {
            Json.decodeFromString<RegistrationRequest>(text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    return request ?: throw RegistrationRefusedException(HttpStatusCode.BadRequest, RegistrationRefusal.INVALID, "This is not a request for an account.")
}

/**
 * The address a request came from, for the limit per address: the last one in `X-Forwarded-For`, which the proxy in front
 * (Traefik) adds for the connection it took; any before it are whatever the client wrote, and are not believed. Without
 * the header, the connection's own address.
 */
fun clientAddress(request: ApplicationRequest): String =
    request.headers.getAll(HttpHeaders.XForwardedFor).orEmpty().flatMap { it.split(',') }.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        ?: request.local.remoteAddress

/**
 * The owner's (issue 314): the requests for an account that wait, and approving or rejecting one, each answering the
 * requests still waiting. Refused to every account that does not [approve][approves] them.
 */
fun Route.accountRequestRoutes(registrations: Registrations?, approves: (Account) -> Boolean) {
    fun RoutingCall.owners(): Registrations =
        registrations?.takeIf { approves(principal<Account>()!!) } ?: throw RegistrationRefusedException(
            HttpStatusCode.Forbidden, RegistrationRefusal.NOT_OWNER, "Only the owner of this server decides on requests for an account.",
        )

    get("/api/registrations") { call.respond(call.owners().waiting()) }
    post("/api/registrations/{username}/approve") { call.respond(call.owners().approve(call.parameters["username"]!!)) }
    post("/api/registrations/{username}/reject") { call.respond(call.owners().reject(call.parameters["username"]!!)) }
}
