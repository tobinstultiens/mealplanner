package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.Health
import dev.tobins.mealplanner.DataHealth
import dev.tobins.mealplanner.server.prices.OfferRepository
import dev.tobins.mealplanner.server.prices.PriceRepository
import dev.tobins.mealplanner.server.accounts.TokenVerifier
import dev.tobins.mealplanner.server.accounts.appIntentUrl
import dev.tobins.mealplanner.server.accounts.appReturnPage
import dev.tobins.mealplanner.server.accounts.assetLinks
import dev.tobins.mealplanner.server.accounts.loginScopes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.queryString
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Instant

/** What needs no login: the web client, the readiness probe, where and how clients log in, and the Android app links. */
fun Route.publicRoutes(config: ServerConfig, verifier: TokenVerifier, offers: OfferRepository, prices: PriceRepository) {
    // The web client, when the jar includes it (see server/build.gradle.kts). Public: it holds no data,
    // and asks for the token itself.
    staticResources("/", "web")

    // Unauthenticated: used by the container readiness probe. Says what runs, nothing about accounts.
    get("/health") { call.respond(Health(status = "ok", version = BuildInfo.version, commit = BuildInfo.commit)) }

    // Unauthenticated, for the monitoring (Gatus): 503 when the shared store data has gone stale, which
    // is how a broken scraper shows from outside. Only when fetching is on: with it off nothing is
    // expected to refresh. Holds the age of shared data only, nothing about any account.
    get("/health/data") {
        val problems = if (config.fetchOffers) {
            val now = Instant.now()
            offers.problems(now) + listOfNotNull(prices.problem(now))
        } else {
            emptyList()
        }
        if (problems.isEmpty()) {
            call.respond(DataHealth("ok"))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, DataHealth("stale", problems))
        }
    }

    // Unauthenticated: where and how clients log in. The scopes follow the server's group setting, not the
    // provider: an installed app asks for whatever scopes it is told, so it sends the groups scope once the
    // setting is on, and never before.
    get("/api/auth/config") { call.respond(verifier.clientConfig().copy(scopes = loginScopes(config.oidcGroup))) }

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
}
