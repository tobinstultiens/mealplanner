package dev.tobins.mealplanner.server.routes

import dev.tobins.mealplanner.api.GenerateRequest
import dev.tobins.mealplanner.api.RatingInput
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.server.NotFoundException
import dev.tobins.mealplanner.server.user
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** Recipes: reading and writing them, routines, generating one with Claude, and a recipe's settings, rating and picture. */
fun Route.recipeRoutes() {
    get("/api/recipes") { call.respond(call.user.recipes.list()) }

    get("/api/recipes/{id}") {
        val id = call.parameters["id"]?.toLongOrNull()
            ?: throw IllegalArgumentException("Recipe id must be a number")
        val recipe = call.user.recipes.find(id)
        if (recipe == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "No recipe with id $id"))
        } else {
            call.respond(recipe)
        }
    }

    get("/api/routines") { call.respond(call.user.recipes.routines()) }

    put("/api/routines") { call.respond(call.user.recipes.saveRoutines(call.receive<Routines>())) }

    // Writes a new recipe with Claude and stores it. Takes up to a minute or so.
    post("/api/recipes/generate") {
        val service = call.user.generation ?: return@post call.respond(
            HttpStatusCode.ServiceUnavailable,
            mapOf("error" to "Recipe generation is off: set ANTHROPIC_API_KEY on the server"),
        )
        call.respond(HttpStatusCode.Created, service.generate(call.receive<GenerateRequest>()))
    }

    post("/api/recipes") { call.respond(HttpStatusCode.Created, call.user.recipes.create(call.receive<RecipeInput>())) }

    route("/api/recipes/{id}") {
        fun io.ktor.server.routing.RoutingCall.id() =
            parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Recipe id must be a number")
        put { call.respond(call.user.recipes.update(call.id(), call.receive<RecipeInput>())) }
        delete {
            call.user.recipes.delete(call.id())
            call.respond(HttpStatusCode.NoContent)
        }
        put("/settings") { call.respond(call.user.recipes.updateSettings(call.id(), call.receive<RecipeSettings>())) }
        put("/rating") { call.respond(call.user.recipes.setRating(call.id(), call.receive<RatingInput>().rating)) }
        // The recipe's picture, at the URL in its summary. That URL changes with the picture, so
        // clients may keep it for good; private, since it is this account's.
        get("/image") {
            val picture = call.user.pictures.find(call.id()) ?: throw NotFoundException("Recipe ${call.id()} has no picture")
            call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
            call.respondFile(picture)
        }
    }
}
