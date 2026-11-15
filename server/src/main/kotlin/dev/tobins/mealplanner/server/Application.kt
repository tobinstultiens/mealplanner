package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.Health
import dev.tobins.mealplanner.api.CookingRepoSetting
import dev.tobins.mealplanner.api.Me
import dev.tobins.mealplanner.api.GenerateRequest
import dev.tobins.mealplanner.api.IngredientInput
import dev.tobins.mealplanner.api.KeepsChoice
import dev.tobins.mealplanner.api.MappingRequest
import dev.tobins.mealplanner.api.LogEntry
import dev.tobins.mealplanner.api.MealSlot
import dev.tobins.mealplanner.api.MealStatus
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RatingInput
import dev.tobins.mealplanner.api.RecipeSettings
import dev.tobins.mealplanner.api.SectionChoice
import dev.tobins.mealplanner.api.Routines
import dev.tobins.mealplanner.api.ShoppingAdvice
import dev.tobins.mealplanner.api.ShoppingAdviceGroup
import dev.tobins.mealplanner.api.ShoppingCheck
import dev.tobins.mealplanner.api.ShoppingExtraInput
import dev.tobins.mealplanner.api.ShoppingRowKey
import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.api.SyncResult
import dev.tobins.mealplanner.server.db.Database
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.principal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondFile
import io.ktor.server.response.header
import io.ktor.http.HttpHeaders
import io.ktor.server.request.queryString
import io.ktor.http.ContentType
import io.ktor.server.routing.get
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import dev.tobins.mealplanner.server.generation.ClaudeRecipeGenerator
import dev.tobins.mealplanner.server.generation.GenerationException
import dev.tobins.mealplanner.server.generation.RecipeGenerator
import dev.tobins.mealplanner.server.offers.AhOfferSource
import dev.tobins.mealplanner.server.offers.JumboOfferSource
import dev.tobins.mealplanner.server.offers.LidlOfferSource
import dev.tobins.mealplanner.server.offers.OfferSource
import dev.tobins.mealplanner.server.prices.AhPriceSource
import dev.tobins.mealplanner.server.prices.PriceSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import kotlin.time.Duration.Companion.hours

const val APP_VERSION = "0.1.0"

/** Runtime settings, read from the environment in [ServerConfig.fromEnv]. */
data class ServerConfig(
    val port: Int,
    /** JDBC URL of the SQLite database. */
    val databaseUrl: String,
    /** Local checkout of the cooking repo, for the owner account (see [CookingSync]); null when none. */
    val cookingRepo: File?,
    /** Git URL of the owner's cooking repo, until they set one in the app. */
    val cookingRepoUrl: String? = null,
    /** The NEVO main csv to import nutrition data from; null leaves the food table as it is. */
    val nevoFile: File? = null,
    /** Fetch store offers in the background. Off unless set, so tests never reach the stores. */
    val fetchOffers: Boolean = false,
    /** The OpenID Connect issuer whose access tokens are accepted (Authelia), e.g. https://authelia.example.nl. */
    val oidcIssuer: String? = null,
    /** This app's client id at the issuer. */
    val oidcClientId: String = "mealplanner",
    /** Username that takes over the data from before accounts; unset: the first account to log in. */
    val owner: String? = null,
    /** Where per-account files (cooking repo clones) go. */
    val dataDir: File = File(".runtime"),
    /**
     * Where web-sized copies of recipe pictures go, one folder per account. Belongs with the database:
     * the database says which copies exist, so it must survive (and be backed up) with it.
     */
    val imageDir: File = File(dataDir, "recipe-images"),
    /**
     * SHA-256 fingerprints of the Android app's signing certificates, published in
     * `/.well-known/assetlinks.json` so Android opens the app for the login redirect.
     */
    val androidCertificates: List<String> = emptyList(),
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            val databaseUrl = env["MEALPLANNER_DB_URL"] ?: "jdbc:sqlite:.runtime/mealplanner.db"
            // The database file's folder; null for an in-memory database.
            val databaseDir = databaseUrl.removePrefix("jdbc:sqlite:").takeIf { !it.startsWith(":") }?.let { File(it).absoluteFile.parentFile }
            val dataDir = env["MEALPLANNER_DATA_DIR"]?.takeIf { it.isNotBlank() }?.let(::File) ?: databaseDir ?: File(".runtime")
            return ServerConfig(
                port = env["PORT"]?.toInt() ?: 8080,
                databaseUrl = databaseUrl,
                cookingRepo = (env["COOKING_REPO_PATH"]?.takeIf { it.isNotBlank() }
                    ?: env["COOKING_REPO_URL"]?.takeIf { it.isNotBlank() }?.let { ".runtime/cooking" })?.let(::File),
                cookingRepoUrl = env["COOKING_REPO_URL"]?.takeIf { it.isNotBlank() },
                nevoFile = env["NEVO_CSV_PATH"]?.takeIf { it.isNotBlank() }?.let(::File),
                fetchOffers = env["MEALPLANNER_FETCH_OFFERS"]?.lowercase() !in setOf("false", "off", "0"),
                oidcIssuer = env["MEALPLANNER_OIDC_ISSUER"]?.takeIf { it.isNotBlank() },
                oidcClientId = env["MEALPLANNER_OIDC_CLIENT_ID"]?.takeIf { it.isNotBlank() } ?: "mealplanner",
                owner = env["MEALPLANNER_OWNER"]?.takeIf { it.isNotBlank() },
                androidCertificates = env["MEALPLANNER_ANDROID_CERTS"].orEmpty().split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() },
                dataDir = dataDir,
                imageDir = env["MEALPLANNER_IMAGE_DIR"]?.takeIf { it.isNotBlank() }?.let(::File)
                    ?: File(databaseDir ?: dataDir, "recipe-images"),
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
    priceSource: PriceSource? = defaultPriceSource(),
    generator: RecipeGenerator? = ClaudeRecipeGenerator.fromEnv(),
    verifier: TokenVerifier = config.oidcIssuer?.let { OidcVerifier(it, config.oidcClientId) } ?: NoLogin,
) {
    if (config.oidcIssuer == null && verifier === NoLogin) log.error("MEALPLANNER_OIDC_ISSUER is not set: nobody can log in")
    // Shared by every account: nutrition data, store offers and regular prices.
    val foods = FoodRepository(database)
    val offers = OfferRepository(database, offerSources)
    val prices = PriceRepository(database, priceSource, foods, offers)
    val users = UserRepository(database)
    // Everything else belongs to one account and is reached through its scope.
    fun scope(accountId: Long) = UserScope(accountId, database, foods, offers, prices, generator, config.imageDir)
    val cooking = CookingSync(users, config.dataDir, config.cookingRepo, config.cookingRepoUrl, ::scope)

    // Accounts with a repository of their own: synced a few times a day.
    launch {
        while (true) {
            for (accountId in cooking.scheduled()) {
                try {
                    cooking.sync(accountId)?.let { log.info("Cooking repo of account $accountId synced at ${it.commit}: ${it.added} added, ${it.updated} updated, ${it.removed} removed") }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    log.warn("Cooking repo sync of account $accountId failed: ${e.message}")
                }
            }
            delay(CookingSync.EVERY_HOURS.hours)
        }
    }
    if (config.fetchOffers) {
        // Stores publish their offers weekly; checking every six hours and fetching
        // what is older than a day keeps up without hammering them.
        launch {
            while (true) {
                offers.refresh(olderThan = Duration.ofHours(24))
                // Regular prices barely move; a weekly lookup (a few minutes of searches) is enough.
                try {
                    prices.refresh(olderThan = Duration.ofDays(7))
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    log.warn("Price lookup failed: ${e.message}")
                }
                delay(6.hours)
            }
        }
    }
    // First start with a dataset configured: import it so nutrition works without a manual step.
    config.nevoFile?.takeIf { it.isFile && foods.dataset().foodCount == 0 }?.let {
        log.info("Imported ${foods.importNevo(it)} foods from ${it.name}")
    }

    // New default ingredients reach every account's catalogue; new accounts get them on first login.
    for (accountId in users.all()) {
        scope(accountId).ingredients.applySeed().takeIf { it > 0 }?.let { log.info("Added $it default ingredients to account $accountId") }
    }
    install(CallLogging)
    // Mostly for the web client: its WebAssembly files shrink to about a third. Recipe pictures are
    // JPEGs, already compressed: gzip would only cost time.
    install(Compression) {
        gzip {
            minimumSize(1024)
            excludeContentType(ContentType.Image.JPEG, ContentType.Image.PNG)
        }
    }
    install(ContentNegotiation) { json() }
    install(StatusPages) {
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
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad request")))
        }
    }
    install(Authentication) {
        // Access tokens from Authelia (or another OpenID Connect provider). Any account it
        // knows may log in; the token's subject decides whose data a call sees.
        bearer("api") {
            authenticate { credential ->
                val identity = verifier.verify(credential.token) ?: return@authenticate null
                // Access tokens may not carry names; ask once, when the account is new.
                val known = database.userQueries.selectBySubject(identity.subject).executeAsOneOrNull() != null
                val named = if (identity.username == null && !known) verifier.userinfo(credential.token) ?: identity else identity
                users.resolve(named, config.owner) { accountId -> scope(accountId).ingredients.applySeed() }
            }
        }
    }

    attributes.put(ScopeFactory, ::scope)

    routing {
        // The web client, when the jar includes it (see server/build.gradle.kts). Public: it holds no data,
        // and asks for the token itself.
        staticResources("/", "web")

        // Unauthenticated: used by the container readiness probe.
        get("/health") { call.respond(Health(status = "ok", version = APP_VERSION)) }

        // Unauthenticated: where and how clients log in.
        get("/api/auth/config") { call.respond(verifier.clientConfig()) }

        // The Android app's login redirect (Authelia only allows http(s) redirects). Android opens
        // the app for it directly once assetlinks.json vouches for the app; otherwise the browser
        // lands here. Chrome only opens apps through intent:// URLs, and only without a tap when
        // that is a redirect within the login's own navigation, so Android browsers get one;
        // the page with a link is for anything else.
        get("/app/login") {
            val query = call.request.queryString()
            if (call.request.headers["User-Agent"].orEmpty().contains("Android")) {
                call.respondRedirect(appIntentUrl(query))
            } else {
                call.respondText(appReturnPage(query), ContentType.Text.Html)
            }
        }
        get("/.well-known/assetlinks.json") {
            call.respondText(assetLinks(config.androidCertificates), ContentType.Application.Json)
        }

        authenticate("api") {
            get("/api/ping") { call.respond(mapOf("pong" to true)) }

            get("/api/me") {
                val account = call.principal<Account>()!!
                call.respond(Me(account.username, account.displayName))
            }

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

            get("/api/profile") { call.respond(call.user.profiles.state()) }

            put("/api/profile") { call.respond(call.user.profiles.save(call.receive<Profile>())) }

            // The stores the account shops at; offers elsewhere don't count for its plans and costs.
            get("/api/shops") { call.respond(call.user.shops.setting()) }

            put("/api/shops") { call.respond(call.user.shops.save(call.receive<ShopsSetting>())) }

            // Whether the shopping list advises buying ahead for next week and stocking up.
            get("/api/shopping-advice") { call.respond(call.user.shoppingAdvice.setting()) }

            put("/api/shopping-advice") { call.respond(call.user.shoppingAdvice.save(call.receive<ShoppingAdvice>())) }

            route("/api/plans/{weekStart}") {
                fun io.ktor.server.routing.RoutingCall.week() = parameters["weekStart"]!!
                fun io.ktor.server.routing.RoutingCall.day() =
                    parameters["day"]?.toIntOrNull()?.takeIf { it in 0..6 } ?: throw IllegalArgumentException("day must be 0 to 6")
                fun io.ktor.server.routing.RoutingCall.slot() = MealSlot.entries.firstOrNull { it.name.equals(parameters["slot"], ignoreCase = true) }
                    ?: throw IllegalArgumentException("slot must be one of ${MealSlot.entries.joinToString()}")

                get { call.respond(call.user.plans.get(call.week())) }

                get("/shopping") { call.respond(call.user.shopping.get(call.week())) }

                // Ticks an item of the shopping list off, or back on.
                put("/shopping") { call.respond(call.user.shopping.setChecked(call.week(), call.receive<ShoppingCheck>())) }

                // Ticks a ticked row that needs more again, for what it needs now, in one step.
                put("/shopping/again") { call.respond(call.user.shopping.tickAgain(call.week(), call.receive<ShoppingRowKey>())) }

                // Puts a bought item no meal needs any more on hand with what was bought of it; answers with the week's list.
                post("/shopping/use-up") { call.respond(call.user.shopping.useUp(call.week(), call.receive<ShoppingRowKey>())) }

                // Puts an own item (coffee, toilet paper) on the list from this week on; answers with the week's list.
                post("/shopping/extras") {
                    call.respond(HttpStatusCode.Created, call.user.shopping.addExtra(call.week(), call.receive<ShoppingExtraInput>()))
                }

                // Takes an own item off every list; answers with the week's list.
                delete("/shopping/extras/{id}") {
                    val id = call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Own item id must be a number")
                    call.respond(call.user.shopping.deleteExtra(call.week(), id))
                }

                // PUT hides the buy-ahead or stock-up group of the week's list, DELETE shows it again.
                for (dismissed in listOf(true, false)) {
                    route("/shopping/dismissed/{group}", if (dismissed) HttpMethod.Put else HttpMethod.Delete) {
                        handle {
                            val group = ShoppingAdviceGroup.fromPath(call.parameters["group"])
                                ?: throw IllegalArgumentException("group must be one of ${ShoppingAdviceGroup.entries.joinToString { it.path }}")
                            call.respond(call.user.shopping.setDismissed(call.week(), group, dismissed))
                        }
                    }
                }

                // Replans every meal that is not locked.
                post("/generate") { call.respond(call.user.plans.generate(call.week())) }

                route("/meals/{day}/{slot}") {
                    post("/reroll") { call.respond(call.user.plans.reroll(call.week(), call.day(), call.slot())) }
                    put("/lock") { call.respond(call.user.plans.setLocked(call.week(), call.day(), call.slot(), locked = true)) }
                    delete("/lock") { call.respond(call.user.plans.setLocked(call.week(), call.day(), call.slot(), locked = false)) }
                    delete { call.respond(call.user.plans.remove(call.week(), call.day(), call.slot())) }
                    // Writes a new recipe for this meal with Claude and puts it in the plan.
                    post("/generate") {
                        val service = call.user.generation ?: return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Recipe generation is off: set ANTHROPIC_API_KEY on the server"),
                        )
                        val slot = call.slot()
                        val recipe = service.generate(call.receive<GenerateRequest>().copy(slot = slot))
                        val candidate = call.user.recipes.candidate(recipe.summary.id)
                            ?: throw GenerationException("The generated recipe has no amounts to plan with")
                        call.respond(call.user.plans.place(call.week(), call.day(), slot, candidate))
                    }
                    // Body: "EATEN", "SKIPPED" or "PLANNED".
                    put("/status") {
                        call.respond(call.user.plans.setStatus(call.week(), call.day(), call.slot(), call.receive<MealStatus>()))
                    }
                }
            }

            // One day: planned meals with their status, extras, and totals against the targets.
            get("/api/days/{date}") { call.respond(call.user.tracking.day(call.parameters["date"]!!)) }

            post("/api/log") { call.respond(HttpStatusCode.Created, call.user.tracking.log(call.receive<LogEntry>())) }

            delete("/api/log/{id}") {
                call.user.tracking.deleteLog(call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Log id must be a number"))
                call.respond(HttpStatusCode.NoContent)
            }

            get("/api/weights") { call.respond(call.user.tracking.weights()) }

            put("/api/weights/{date}") { call.respond(call.user.tracking.setWeight(call.parameters["date"]!!, call.receive<Double>())) }

            delete("/api/weights/{date}") { call.respond(call.user.tracking.deleteWeight(call.parameters["date"]!!)) }

            get("/api/pantry") { call.respond(call.user.ingredients.pantry()) }

            // PUT adds an ingredient to the staples or on-hand list, DELETE removes it.
            for (list in listOf("staples", "on-hand")) {
                for (present in listOf(true, false)) {
                    route("/api/pantry/$list/{id}", if (present) HttpMethod.Put else HttpMethod.Delete) {
                        handle {
                            val id = call.parameters["id"]?.toLongOrNull()
                                ?: throw IllegalArgumentException("Ingredient id must be a number")
                            if (list == "staples") call.user.ingredients.setStaple(id, present) else call.user.ingredients.setOnHand(id, present)
                            call.respond(call.user.ingredients.pantry())
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

            get("/api/offers") {
                val date = call.request.queryParameters["date"]?.let {
                    runCatching { LocalDate.parse(it) }.getOrElse { throw IllegalArgumentException("date must be an ISO date") }
                } ?: LocalDate.now()
                call.respond(call.user.ingredients.let { offers.state(date, it.matchTerms(), it.list().associate { i -> i.id to i.name }) })
            }

            // Fetches every store now, regardless of when it was last fetched.
            post("/api/offers/refresh") { call.respond(offers.refresh()) }
            get("/api/prices") { call.respond(prices.state(call.user.ingredients)) }
            // A lookup takes minutes: it runs in the background and the app polls GET /api/prices.
            post("/api/prices/refresh") {
                this@module.launch { prices.refresh() }
                call.respond(prices.state(call.user.ingredients).copy(refreshing = true))
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

            get("/api/sync/cooking") { call.respond(cooking.status(call.accountId)) }

            // The account's own cooking-site repository; synced right away when set.
            put("/api/sync/cooking/repo") {
                cooking.setRepo(call.accountId, call.receive<CookingRepoSetting>().url)
                call.respond(cooking.status(call.accountId))
            }

            // Fetches the account's cooking repo and brings its recipes in line with it.
            post("/api/sync/cooking") {
                val result = cooking.sync(call.accountId)
                if (result == null) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "No cooking site repository is set, or it has no content/ folder"),
                    )
                } else {
                    call.respond(result)
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

private val ScopeFactory = io.ktor.util.AttributeKey<(Long) -> UserScope>("scopeFactory")
private val CallScope = io.ktor.util.AttributeKey<UserScope>("userScope")

/** The calling account's id; only inside `authenticate`. */
val io.ktor.server.application.ApplicationCall.accountId: Long get() = principal<Account>()!!.id

/** The calling account's repositories, made once per call. */
val io.ktor.server.application.ApplicationCall.user: UserScope
    get() = attributes.computeIfAbsent(CallScope) { application.attributes[ScopeFactory](accountId) }

/** The app's login return as an Android intent URL, the form Chrome opens apps from. */
internal fun appIntentUrl(query: String): String =
    "intent://login" + (if (query.isEmpty()) "" else "?$query") + "#Intent;scheme=dev.tobins.mealplanner;package=dev.tobins.mealplanner;end"

/** HTML that sends the browser on to the app with the login answer; with a link in case it doesn't go by itself. */
internal fun appReturnPage(query: String): String {
    val target = appIntentUrl(query)
    val href = target.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    return """
        <!doctype html>
        <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Meal planner</title>
        <style>body{font-family:sans-serif;margin:2rem;color-scheme:light dark}a{font-size:1.2rem}</style></head>
        <body><p>Logged in. Returning to the app…</p><p><a id="open" href="$href">Open the Meal planner app</a></p>
        <script>location.replace(document.getElementById("open").href)</script></body></html>
    """.trimIndent()
}

/** Digital Asset Links: lets Android open the app for the login redirect on this host. */
internal fun assetLinks(certificates: List<String>): String {
    if (certificates.isEmpty()) return "[]"
    val prints = certificates.joinToString(",") { "\"${it.uppercase()}\"" }
    return """[{"relation":["delegate_permission/common.handle_all_urls"],""" +
        """"target":{"namespace":"android_app","package_name":"dev.tobins.mealplanner","sha256_cert_fingerprints":[$prints]}}]"""
}

fun defaultPriceSource(): PriceSource = AhPriceSource(HttpClient(OkHttp) {
    install(HttpTimeout) { requestTimeoutMillis = 30_000 }
})
