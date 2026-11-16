package dev.tobins.mealplanner.server.routes

import dev.tobins.mealplanner.Health
import dev.tobins.mealplanner.server.APP_VERSION
import dev.tobins.mealplanner.server.ServerConfig
import dev.tobins.mealplanner.server.TokenVerifier
import dev.tobins.mealplanner.server.appIntentUrl
import dev.tobins.mealplanner.server.appReturnPage
import dev.tobins.mealplanner.server.assetLinks
import io.ktor.http.ContentType
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.queryString
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** What needs no login: the web client, the readiness probe, where and how clients log in, and the Android app links. */
fun Route.publicRoutes(config: ServerConfig, verifier: TokenVerifier) {
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
}
