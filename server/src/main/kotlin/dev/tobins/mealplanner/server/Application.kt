package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.Health
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.server.db.Database
import io.ktor.http.HttpMethod
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
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import dev.tobins.mealplanner.server.offers.AhOfferSource
import dev.tobins.mealplanner.server.offers.JumboOfferSource
import dev.tobins.mealplanner.server.offers.LidlOfferSource
import dev.tobins.mealplanner.server.offers.OfferSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.LocalDate
import kotlin.time.Duration.Companion.hours
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
    /** Fetch store offers in the background. Off unless set, so tests never reach the stores. */
    val fetchOffers: Boolean = false,
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
                fetchOffers = env["MEALPLANNER_FETCH_OFFERS"]?.lowercase() !in setOf("false", "off", "0"),
            )
        }
    }
}

fun main() {
    val config = ServerConfig.fromEnv()
    val (_, database) = openDatabase(config.databaseUrl)
    embeddedServer(Netty, port = config.port) { module(config, database) }.start(wait = true)
}

fun Application.module(
    config: ServerConfig,
    database: Database,
    offerSources: List<OfferSource> = defaultOfferSources(),
) {
    val foods = FoodRepository(database)
    val ingredients = IngredientRepository(database, foods)
    val recipes = RecipeRepository(database, ingredients)
    val profiles = ProfileRepository(database)
    val plans = PlanRepository(database, recipes, profiles)
    val tracking = TrackingRepository(database, plans, profiles)
    val offers = OfferRepository(database, offerSources)
    if (config.fetchOffers) {
        // Stores publish their offers weekly; checking every six hours and fetching
        // what is older than a day keeps up without hammering them.
        launch {
            while (true) {
                offers.refresh(olderThan = Duration.ofHours(24))
                delay(6.hours)
            }
        }
    }
    // First start with a dataset configured: import it so nutrition works without a manual step.
    config.nevoFile?.takeIf { it.isFile && foods.dataset().foodCount == 0 }?.let {
        log.info("Imported ${foods.importNevo(it)} foods from ${it.name}")
    }

    ingredients.applySeed().takeIf { it > 0 }?.let { log.info("Added $it default ingredients to the catalogue") }
    install(CallLogging)
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, mapOf("error" to (cause.message ?: "not found")))
        }
        exception<ConflictException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, mapOf("error" to (cause.message ?: "conflict")))
        }
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad request")))
        }
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

            get("/api/ingredients") { call.respond(ingredients.list()) }

            post("/api/ingredients") {
                call.respond(HttpStatusCode.Created, ingredients.create(call.receive<IngredientInput>()))
            }

            put("/api/ingredients/{id}") {
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: throw IllegalArgumentException("Ingredient id must be a number")
                call.respond(ingredients.update(id, call.receive<IngredientInput>()))
            }

            get("/api/profile") { call.respond(profiles.state()) }

            put("/api/profile") { call.respond(profiles.save(call.receive<Profile>())) }

            route("/api/plans/{weekStart}") {
                fun io.ktor.server.routing.RoutingCall.week() = parameters["weekStart"]!!
                fun io.ktor.server.routing.RoutingCall.day() =
                    parameters["day"]?.toIntOrNull()?.takeIf { it in 0..6 } ?: throw IllegalArgumentException("day must be 0 to 6")
                fun io.ktor.server.routing.RoutingCall.slot() = MealSlot.entries.firstOrNull { it.name.equals(parameters["slot"], ignoreCase = true) }
                    ?: throw IllegalArgumentException("slot must be one of ${MealSlot.entries.joinToString()}")

                get { call.respond(plans.get(call.week())) }

                get("/shopping") { call.respond(plans.shoppingList(call.week())) }

                // Ticks an item of the shopping list off, or back on.
                put("/shopping") { call.respond(plans.setChecked(call.week(), call.receive<ShoppingCheck>())) }

                // Replans every meal that is not locked.
                post("/generate") { call.respond(plans.generate(call.week())) }

                route("/meals/{day}/{slot}") {
                    post("/reroll") { call.respond(plans.reroll(call.week(), call.day(), call.slot())) }
                    put("/lock") { call.respond(plans.setLocked(call.week(), call.day(), call.slot(), locked = true)) }
                    delete("/lock") { call.respond(plans.setLocked(call.week(), call.day(), call.slot(), locked = false)) }
                    delete { call.respond(plans.remove(call.week(), call.day(), call.slot())) }
                    // Body: "EATEN", "SKIPPED" or "PLANNED".
                    put("/status") {
                        call.respond(plans.setStatus(call.week(), call.day(), call.slot(), call.receive<MealStatus>()))
                    }
                }
            }

            // One day: planned meals with their status, extras, and totals against the targets.
            get("/api/days/{date}") { call.respond(tracking.day(call.parameters["date"]!!)) }

            post("/api/log") { call.respond(HttpStatusCode.Created, tracking.log(call.receive<LogEntry>())) }

            delete("/api/log/{id}") {
                tracking.deleteLog(call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Log id must be a number"))
                call.respond(HttpStatusCode.NoContent)
            }

            get("/api/weights") { call.respond(tracking.weights()) }

            put("/api/weights/{date}") { call.respond(tracking.setWeight(call.parameters["date"]!!, call.receive<Double>())) }

            delete("/api/weights/{date}") { call.respond(tracking.deleteWeight(call.parameters["date"]!!)) }

            get("/api/pantry") { call.respond(ingredients.pantry()) }

            // PUT adds an ingredient to the staples or on-hand list, DELETE removes it.
            for (list in listOf("staples", "on-hand")) {
                for (present in listOf(true, false)) {
                    route("/api/pantry/$list/{id}", if (present) HttpMethod.Put else HttpMethod.Delete) {
                        handle {
                            val id = call.parameters["id"]?.toLongOrNull()
                                ?: throw IllegalArgumentException("Ingredient id must be a number")
                            if (list == "staples") ingredients.setStaple(id, present) else ingredients.setOnHand(id, present)
                            call.respond(ingredients.pantry())
                        }
                    }
                }
            }

            // Recipe line texts that still need an ingredient, with suggestions.
            get("/api/mappings/unmapped") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 50
                call.respond(ingredients.unmapped(limit))
            }

            put("/api/mappings") { call.respond(ingredients.map(call.receive<MappingRequest>())) }

            delete("/api/mappings") {
                val text = call.request.queryParameters["text"]
                    ?: throw IllegalArgumentException("Query parameter text is required")
                ingredients.unmap(text)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/api/offers") {
                val date = call.request.queryParameters["date"]?.let {
                    runCatching { LocalDate.parse(it) }.getOrElse { throw IllegalArgumentException("date must be an ISO date") }
                } ?: LocalDate.now()
                call.respond(offers.state(date))
            }

            // Fetches every store now, regardless of when it was last fetched.
            post("/api/offers/refresh") { call.respond(offers.refresh()) }

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
                        ingredients.applySeed()
                        ingredients.autoLink()
                    }
                    call.respond(foods.dataset())
                }
            }

            post("/api/recipes") { call.respond(HttpStatusCode.Created, recipes.create(call.receive<RecipeInput>())) }

            route("/api/recipes/{id}") {
                fun io.ktor.server.routing.RoutingCall.id() =
                    parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Recipe id must be a number")
                put { call.respond(recipes.update(call.id(), call.receive<RecipeInput>())) }
                delete {
                    recipes.delete(call.id())
                    call.respond(HttpStatusCode.NoContent)
                }
                put("/settings") { call.respond(recipes.updateSettings(call.id(), call.receive<RecipeSettings>())) }
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
                    call.respond(
                        withContext(Dispatchers.IO) {
                            recipes.syncCookingSite(parsed).let { it.copy(autoLinked = ingredients.autoLink()) }
                        },
                    )
                }
            }
        }
    }
}

/** The real stores, sharing one HTTP client. */
fun defaultOfferSources(): List<OfferSource> {
    val http = HttpClient(OkHttp) {
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }
    return listOf(AhOfferSource(http), JumboOfferSource(http), LidlOfferSource(http))
}
