package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.server.generation.GenerationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respond

/** Thrown for requests that refer to something that does not exist; mapped to 404. */
class NotFoundException(message: String) : RuntimeException(message)

/** Thrown when a request cannot be carried out in the current state; mapped to 409. */
class ConflictException(message: String) : RuntimeException(message)

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
