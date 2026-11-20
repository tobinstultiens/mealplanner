package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.server.generation.GenerationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respond
import kotlin.coroutines.cancellation.CancellationException

/** Thrown for requests that refer to something that does not exist; mapped to 404. */
class NotFoundException(message: String) : RuntimeException(message)

/** Thrown when a request cannot be carried out in the current state; mapped to 409. */
class ConflictException(message: String) : RuntimeException(message)

/**
 * The machine-readable `reason` of the 503 answered when recipe generation is not set up, next to its
 * sentence: the app tells it from the 503 of a busy write (a wording is not something to compare).
 * An optional field; apps that do not know it read the `error` sentence only.
 */
const val GENERATION_OFF = "generation_off"

/** Answers 503 "Recipe generation is off", for a server without an `ANTHROPIC_API_KEY`. */
suspend fun ApplicationCall.respondGenerationOff() = respond(
    HttpStatusCode.ServiceUnavailable,
    mapOf("error" to "Recipe generation is off: set ANTHROPIC_API_KEY on the server", "reason" to GENERATION_OFF),
)

/** Maps what routes throw to a status and `{"error": ...}`. */
fun StatusPagesConfig.errorMapping() {
    exception<NotFoundException> { call, cause ->
        call.respond(HttpStatusCode.NotFound, mapOf("error" to (cause.message ?: "not found")))
    }
    exception<GenerationException> { call, cause ->
        call.respond(HttpStatusCode.BadGateway, mapOf("error" to (cause.message ?: "generation failed")))
    }
    exception<ConflictException> { call, cause ->
        call.respond(HttpStatusCode.Conflict, mapOf("error" to (cause.message ?: "conflict")))
    }
    exception<BadRequestException> { call, cause ->
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad request")))
    }
    // A write that waited too long for another to finish ([writing]): the server's trouble, not the request's, and
    // worth trying again. Logged with where that other write is, which is what needs fixing.
    exception<DatabaseBusyException> { call, cause ->
        call.application.log.error("Request ${call.request.local.uri} gave up waiting to write", cause)
        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "The server is busy saving something else; try again in a moment"))
    }
    // A call cancelled, most often because the app went away while it loaded ("Response was cancelled"): nobody waits
    // for an answer and nothing failed. Ktor's engine logs it at debug level, as it does when no handler is installed;
    // caught below, it would be logged as an error and answered with a 500 (issue 263).
    exception<CancellationException> { _, cause -> throw cause }
    exception<Exception> { call, cause ->
        call.application.log.error("Request ${call.request.local.uri} failed", cause)
        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: cause::class.simpleName ?: "internal error")))
    }
    // Answered as the caller's mistake, but a number that fails to parse or a `require` deep in a
    // repository can be the server's own bug: leave a trace of the path and the message.
    exception<IllegalArgumentException> { call, cause ->
        call.application.log.warn("Request ${call.request.local.uri} refused as a bad request: ${cause.message}")
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad request")))
    }
}
