package dev.tobins.mealplanner.server.routes

import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.KeepsChoice
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.SectionChoice
import dev.tobins.mealplanner.server.FoodRepository
import dev.tobins.mealplanner.server.ServerConfig
import dev.tobins.mealplanner.server.UserRepository
import dev.tobins.mealplanner.server.UserScope
import dev.tobins.mealplanner.server.user
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The ingredient catalogue: its shop sections, the pantry, the mappings of recipe lines, and the NEVO foods behind it. */
fun Route.ingredientRoutes(config: ServerConfig, foods: FoodRepository, users: UserRepository, scope: (Long) -> UserScope) {
    get("/api/ingredients") { call.respond(call.user.ingredients.list()) }

    post("/api/ingredients") {
        call.respond(HttpStatusCode.Created, call.user.ingredients.create(call.receive<IngredientInput>()))
    }

    put("/api/ingredients/{id}") {
        val id = call.parameters["id"]?.toLongOrNull()
            ?: throw IllegalArgumentException("Ingredient id must be a number")
        call.respond(call.user.ingredients.update(id, call.receive<IngredientInput>()))
    }

    // Where each ingredient is in the shop, which orders the shopping list.
    get("/api/ingredients/sections") { call.respond(call.user.sections.list()) }

    // Sets an ingredient's section by hand; a null section goes back to the automatic one.
    put("/api/ingredients/{id}/section") {
        val id = call.parameters["id"]?.toLongOrNull()
            ?: throw IllegalArgumentException("Ingredient id must be a number")
        call.respond(call.user.sections.set(id, call.receive<SectionChoice>()))
    }

    // Sets by hand whether an ingredient keeps until next week; null goes back to its section's answer.
    put("/api/ingredients/{id}/keeps") {
        val id = call.parameters["id"]?.toLongOrNull()
            ?: throw IllegalArgumentException("Ingredient id must be a number")
        call.respond(call.user.sections.setKeeps(id, call.receive<KeepsChoice>()))
    }

    get("/api/pantry") { call.respond(call.user.stock.pantry()) }

    // PUT adds an ingredient to the staples or on-hand list, DELETE removes it.
    for (list in listOf("staples", "on-hand")) {
        for (present in listOf(true, false)) {
            route("/api/pantry/$list/{id}", if (present) HttpMethod.Put else HttpMethod.Delete) {
                handle {
                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: throw IllegalArgumentException("Ingredient id must be a number")
                    if (list == "staples") call.user.ingredients.setStaple(id, present) else call.user.stock.setOnHand(id, present)
                    call.respond(call.user.stock.pantry())
                }
            }
        }
    }

    // Recipe line texts that still need an ingredient, with suggestions.
    get("/api/mappings/unmapped") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 50
        call.respond(call.user.ingredients.unmapped(limit))
    }

    put("/api/mappings") { call.respond(call.user.ingredients.map(call.receive<MappingRequest>())) }

    delete("/api/mappings") {
        val text = call.request.queryParameters["text"]
            ?: throw IllegalArgumentException("Query parameter text is required")
        call.user.ingredients.unmap(text)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/api/foods/dataset") { call.respond(foods.dataset()) }

    get("/api/foods") {
        val query = call.request.queryParameters["q"]
            ?: throw IllegalArgumentException("Query parameter q is required")
        call.respond(foods.search(query))
    }

    // Replaces the food table with the configured NEVO file, e.g. after a new NEVO release.
    post("/api/foods/import") {
        val file = config.nevoFile?.takeIf { it.isFile }
        if (file == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "NEVO_CSV_PATH is not set or the file does not exist"),
            )
        } else {
            withContext(Dispatchers.IO) {
                foods.importNevo(file)
                for (accountId in users.all()) {
                    scope(accountId).ingredients.applySeed()
                    scope(accountId).ingredients.autoLink()
                }
            }
            call.respond(foods.dataset())
        }
    }
}
