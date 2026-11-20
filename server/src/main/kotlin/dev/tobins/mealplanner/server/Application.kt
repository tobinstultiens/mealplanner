package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.server.db.Database
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
import dev.tobins.mealplanner.server.routes.accountRoutes
import dev.tobins.mealplanner.server.routes.ingredientRoutes
import dev.tobins.mealplanner.server.routes.notificationRoutes
import dev.tobins.mealplanner.server.routes.offerRoutes
import dev.tobins.mealplanner.server.routes.planRoutes
import dev.tobins.mealplanner.server.routes.publicRoutes
import dev.tobins.mealplanner.server.routes.recipeRoutes
import dev.tobins.mealplanner.server.routes.shoppingRoutes
import dev.tobins.mealplanner.server.routes.trackingRoutes
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.http.ContentType
import io.ktor.server.routing.routing
import dev.tobins.mealplanner.server.generation.ClaudeRecipeGenerator
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
import kotlinx.coroutines.launch
import java.io.File
import java.time.Duration
import java.time.LocalDate
import kotlin.time.Duration.Companion.hours

/** Runtime settings, read from the environment in [ServerConfig.fromEnv]. */
data class ServerConfig(
    val port: Int,
    /** JDBC URL of the SQLite database. */
    val databaseUrl: String,
    /** Local checkout of the server's cooking site (see [CookingSync]); null when none. */
    val cookingRepo: File?,
    /** Git URL of the server's cooking site, cloned into [cookingRepo]. */
    val cookingRepoUrl: String? = null,
    /** Whether the server's cooking site is the bank of every account without a site of its own, or only the owner's. */
    val cookingRepoShared: Boolean = true,
    /** The least time between two fetches of the server's cooking site, which any account on it can ask for. */
    val cookingFetchInterval: Duration = CookingSync.FETCH_INTERVAL,
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
                cookingRepoShared = env["COOKING_REPO_SHARED"]?.trim()?.lowercase() !in setOf("false", "off", "0"),
                cookingFetchInterval = env["COOKING_REPO_FETCH_INTERVAL_MINUTES"]?.takeIf { it.isNotBlank() }?.let(::minutes)
                    ?: CookingSync.FETCH_INTERVAL,
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

        /** A whole number of minutes, 0 or more; anything else stops the start rather than being guessed at. */
        private fun minutes(text: String): Duration {
            val minutes = requireNotNull(text.trim().toLongOrNull()?.takeIf { it >= 0 }) { "Not a number of minutes: '$text'" }
            return Duration.ofMinutes(minutes)
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
    /** Today as the household sees it, for every account ([UserScope]); a test sets the day, so it does not depend on when it runs. */
    today: () -> LocalDate = ::serverToday,
) {
    if (config.oidcIssuer == null && verifier === NoLogin) log.error("MEALPLANNER_OIDC_ISSUER is not set: nobody can log in")
    // Shared by every account: nutrition data, store offers and regular prices.
    val foods = FoodRepository(database)
    val offers = OfferRepository(database, offerSources)
    val prices = PriceRepository(database, priceSource, foods, offers)
    val users = UserRepository(database)
    // Everything else belongs to one account and is reached through its scope.
    fun scope(accountId: Long) = UserScope(accountId, database, foods, offers, prices, generator, config.imageDir, today)
    val serverSite = CookingSync.serverSite(config.cookingRepo, config.cookingRepoUrl, config.dataDir)
    val cooking = CookingSync(users, config.dataDir, serverSite, config.cookingRepoShared, ::scope, config.cookingFetchInterval)

    // Cooking sites the server fetches itself: synced a few times a day.
    launch { repeatEvery(CookingSync.EVERY_HOURS.hours, "Cooking site sync") { cooking.syncScheduled() } }
    if (config.fetchOffers) {
        // Stores publish their offers weekly; checking every six hours and fetching
        // what is older than a day keeps up without hammering them. Each job is logged
        // on its own, so that one failing does not skip the other or end the loop.
        launch {
            repeatEvery(6.hours, "Offer round") {
                logged("Offer refresh") { offers.refresh(olderThan = Duration.ofHours(24)) }
                // Regular prices barely move; a weekly lookup (a few minutes of searches) is enough.
                logged("Price lookup") { prices.refresh(olderThan = Duration.ofDays(7)) }
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
    install(StatusPages) { errorMapping() }
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
        publicRoutes(config, verifier, offers, prices)
        authenticate("api") {
            accountRoutes(cooking)
            recipeRoutes(cooking)
            ingredientRoutes(config, foods, users, ::scope)
            planRoutes()
            shoppingRoutes()
            trackingRoutes()
            notificationRoutes()
            offerRoutes(offers, prices)
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

fun defaultPriceSource(): PriceSource = AhPriceSource(HttpClient(OkHttp) {
    install(HttpTimeout) { requestTimeoutMillis = 30_000 }
})
