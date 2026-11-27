package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.server.accounts.Account
import dev.tobins.mealplanner.server.accounts.DirectoryHelper
import dev.tobins.mealplanner.server.accounts.HttpDirectoryHelper
import dev.tobins.mealplanner.server.accounts.NoLogin
import dev.tobins.mealplanner.server.accounts.OidcVerifier
import dev.tobins.mealplanner.server.accounts.Registrations
import dev.tobins.mealplanner.server.accounts.TokenVerifier
import dev.tobins.mealplanner.server.accounts.UserRepository
import dev.tobins.mealplanner.server.accounts.requireMember
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.ingredients.FoodRepository
import dev.tobins.mealplanner.server.prices.OfferRepository
import dev.tobins.mealplanner.server.prices.PriceRepository
import dev.tobins.mealplanner.server.recipes.CookingSync
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
import dev.tobins.mealplanner.server.accounts.accountRequestRoutes
import dev.tobins.mealplanner.server.accounts.accountRoutes
import dev.tobins.mealplanner.server.ingredients.ingredientRoutes
import dev.tobins.mealplanner.server.plan.notificationRoutes
import dev.tobins.mealplanner.server.prices.offerRoutes
import dev.tobins.mealplanner.server.plan.planRoutes
import dev.tobins.mealplanner.server.ingredients.productRoutes
import dev.tobins.mealplanner.server.recipes.recipeRoutes
import dev.tobins.mealplanner.server.accounts.registrationRoutes
import dev.tobins.mealplanner.server.shopping.shoppingRoutes
import dev.tobins.mealplanner.server.tracking.trackingRoutes
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.http.ContentType
import io.ktor.server.routing.routing
import dev.tobins.mealplanner.server.recipes.generation.ClaudeRecipeGenerator
import dev.tobins.mealplanner.server.recipes.generation.RecipeGenerator
import dev.tobins.mealplanner.server.prices.offers.AhApp
import dev.tobins.mealplanner.server.prices.offers.AhOfferSource
import dev.tobins.mealplanner.server.prices.offers.JumboOfferSource
import dev.tobins.mealplanner.server.prices.offers.LidlOfferSource
import dev.tobins.mealplanner.server.prices.offers.OfferSource
import dev.tobins.mealplanner.server.prices.AhPriceSource
import dev.tobins.mealplanner.server.prices.PriceSource
import dev.tobins.mealplanner.server.ingredients.products.AhProducts
import dev.tobins.mealplanner.server.ingredients.products.OpenFoodFacts
import dev.tobins.mealplanner.server.ingredients.products.ProductFinder
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
    /**
     * The group at the issuer whose members may use the server (`MEALPLANNER_OIDC_GROUP`, e.g. `mealplanner`); null:
     * every account the issuer knows, as before the setting. See [requireMember].
     */
    val oidcGroup: String? = null,
    /**
     * Whether people may ask for an account (`MEALPLANNER_REGISTRATION`, issue 314, `POST /api/registration`). Off unless set,
     * and then only with the registration helper set up ([HttpDirectoryHelper.fromEnv]) and [oidcGroup] set: without the
     * group check, an account in no group, waiting for approval, could use the meal planner at once.
     */
    val registration: Boolean = false,
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
    /**
     * How Open Food Facts can reach whoever runs this server (`MEALPLANNER_OPENFOODFACTS_CONTACT`, an e-mail address or a
     * web page), sent in the `User-Agent` its terms ask for; null: it is never asked, and a barcode is looked up at AH only.
     */
    val openFoodFactsContact: String? = null,
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
                oidcGroup = env["MEALPLANNER_OIDC_GROUP"]?.trim()?.takeIf { it.isNotEmpty() },
                registration = env["MEALPLANNER_REGISTRATION"]?.trim()?.lowercase() in setOf("on", "true", "1"),
                owner = env["MEALPLANNER_OWNER"]?.takeIf { it.isNotBlank() },
                androidCertificates = env["MEALPLANNER_ANDROID_CERTS"].orEmpty().split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() },
                dataDir = dataDir,
                imageDir = env["MEALPLANNER_IMAGE_DIR"]?.takeIf { it.isNotBlank() }?.let(::File)
                    ?: File(databaseDir ?: dataDir, "recipe-images"),
                openFoodFactsContact = env["MEALPLANNER_OPENFOODFACTS_CONTACT"]?.trim()?.takeIf { it.isNotEmpty() },
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
    /** The registration helper next to the directory (issue 314); null when none is set up, and then nobody can ask for an account. */
    directory: DirectoryHelper? = HttpDirectoryHelper.fromEnv(),
    /** Where a product's label is looked up (issue 354); a test gives one that answers from recorded answers. */
    products: ProductFinder = defaultProductFinder(config),
) {
    if (config.oidcIssuer == null && verifier === NoLogin) log.error("MEALPLANNER_OIDC_ISSUER is not set: nobody can log in")
    if (config.openFoodFactsContact == null) log.info("MEALPLANNER_OPENFOODFACTS_CONTACT is not set: a barcode is looked up at AH only")
    val registrations = directory?.let { Registrations(database, it) }
    val registrationOpen = config.registration && registrations != null && config.oidcGroup != null
    if (config.registration && !registrationOpen) {
        log.error(
            "MEALPLANNER_REGISTRATION is on, but nobody can ask for an account: it needs MEALPLANNER_REGISTRATION_HELPER_URL, " +
                "MEALPLANNER_REGISTRATION_HELPER_SECRET and MEALPLANNER_OIDC_GROUP",
        )
    }
    // The owner's account decides on the requests for an account, on a server with the helper set up; no other does.
    fun approves(account: Account) = registrations != null && UserRepository.isOwner(account, config.owner)
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
    // Requests for an account nobody decided on in time are rejected; also after registration was switched off.
    registrations?.let { launch { repeatEvery(1.hours, "Expiry of account requests") { it.expire() } } }
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
        // Access tokens from Authelia (or another OpenID Connect provider). Any account it knows
        // may log in, or with MEALPLANNER_OIDC_GROUP only its members; the token's subject decides
        // whose data a call sees.
        bearer("api") {
            authenticate { credential ->
                val identity = verifier.verify(credential.token) ?: return@authenticate null
                // Authelia's access tokens may not name the account; then the provider is asked, only when a request waits.
                requireMember(identity, config.oidcGroup) {
                    registrations?.isWaiting { identity.username ?: verifier.userinfo(credential.token)?.username } == true
                }
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
        registrationRoutes(registrationOpen, registrations)
        authenticate("api") {
            accountRequestRoutes(registrations, ::approves)
            accountRoutes(cooking, ::approves)
            recipeRoutes(cooking)
            ingredientRoutes(config, foods, users, ::scope)
            planRoutes()
            shoppingRoutes()
            trackingRoutes()
            notificationRoutes { account -> registrations?.takeIf { approves(account) }?.waiting().orEmpty().map { it.username } }
            offerRoutes(offers, prices)
            productRoutes(products)
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

/** AH's app API, whose token the regular prices and the product lookup share. */
private val defaultAh by lazy { AhApp(HttpClient(OkHttp) { install(HttpTimeout) { requestTimeoutMillis = 30_000 } }) }

fun defaultPriceSource(): PriceSource = AhPriceSource(defaultAh)

/** The product lookup at AH and, with a contact configured, at Open Food Facts; a person waits for it, so not too long. */
fun defaultProductFinder(config: ServerConfig): ProductFinder = ProductFinder(
    AhProducts(defaultAh),
    config.openFoodFactsContact?.let { OpenFoodFacts(HttpClient(OkHttp) { install(HttpTimeout) { requestTimeoutMillis = 15_000 } }, it) },
)
