package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.Health
import dev.tobins.mealplanner.server.db.Database
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

const val APP_VERSION = "0.1.0"

/** Runtime settings, read from the environment in [ServerConfig.fromEnv]. */
data class ServerConfig(
    val port: Int,
    /** Single-user bearer token every `/api` call must present. */
    val apiToken: String,
    /** JDBC URL of the SQLite database. */
    val databaseUrl: String,
    /** Local checkout of the cooking repo to import recipes from; null disables the sync. */
    val cookingRepo: File?,
    /** The NEVO main csv to import nutrition data from; null leaves the food table as it is. */
    val nevoFile: File? = null,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            val token = env["MEALPLANNER_API_TOKEN"]?.takeIf { it.isNotBlank() }
                ?: error("MEALPLANNER_API_TOKEN must be set")
            return ServerConfig(
                port = env["PORT"]?.toInt() ?: 8080,
                apiToken = token,
                databaseUrl = env["MEALPLANNER_DB_URL"] ?: "jdbc:sqlite:.runtime/mealplanner.db",
                cookingRepo = env["COOKING_REPO_PATH"]?.takeIf { it.isNotBlank() }?.let(::File),
                nevoFile = env["NEVO_CSV_PATH"]?.takeIf { it.isNotBlank() }?.let(::File),
            )
        }
    }
}

fun main() {
    val config = ServerConfig.fromEnv()
    val (_, database) = openDatabase(config.databaseUrl)
    embeddedServer(Netty, port = config.port) { module(config, database) }.start(wait = true)
}

fun Application.module(config: ServerConfig, database: Database) {
    val recipes = RecipeRepository(database)
    val foods = FoodRepository(database)
    // First start with a dataset configured: import it so nutrition works without a manual step.
    config.nevoFile?.takeIf { it.isFile && foods.dataset().foodCount == 0 }?.let {
        log.info("Imported ${foods.importNevo(it)} foods from ${it.name}")
    }

    install(CallLogging)
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad request")))
        }
    }
    install(Authentication) {
        bearer("api") {
            authenticate { credential ->
                val matches = MessageDigest.isEqual(
                    credential.token.toByteArray(),
                    config.apiToken.toByteArray(),
                )
                if (matches) UserIdPrincipal("owner") else null
            }
        }
    }

    routing {
        // Unauthenticated: used by the container readiness probe.
        get("/health") { call.respond(Health(status = "ok", version = APP_VERSION)) }

        authenticate("api") {
            get("/api/ping") { call.respond(mapOf("pong" to true)) }

            get("/api/recipes") { call.respond(recipes.list()) }

            get("/api/recipes/{id}") {
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: throw IllegalArgumentException("Recipe id must be a number")
                val recipe = recipes.find(id)
                if (recipe == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "No recipe with id $id"))
                } else {
                    call.respond(recipe)
                }
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
                    withContext(Dispatchers.IO) { foods.importNevo(file) }
                    call.respond(foods.dataset())
                }
            }

            // Re-reads the cooking repo checkout and brings the stored recipes in line with it.
            post("/api/sync/cooking") {
                val parsed = config.cookingRepo?.let { CookingSiteReader(it).readAll() }
                if (parsed == null) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "COOKING_REPO_PATH is not set or has no content/ folder"),
                    )
                } else {
                    call.respond(withContext(Dispatchers.IO) { recipes.syncCookingSite(parsed) })
                }
            }
        }
    }
}
