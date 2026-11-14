package dev.tobins.mealplanner.server

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.tobins.mealplanner.api.AuthConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/** Checks access tokens and says whose they are. */
interface TokenVerifier {
    /** The identity behind a valid, unexpired token for this app; null for any other token. */
    suspend fun verify(token: String): Identity?

    /** The account's names from the provider's userinfo endpoint, for a token [verify] accepted. */
    suspend fun userinfo(token: String): Identity? = null

    /** What clients need to log in. */
    suspend fun clientConfig(): AuthConfig
}

/**
 * Without MEALPLANNER_OIDC_ISSUER nobody can log in: the server still starts (health, web page)
 * and says what is missing, instead of failing to start.
 */
object NoLogin : TokenVerifier {
    override suspend fun verify(token: String): Identity? = null

    override suspend fun clientConfig(): AuthConfig =
        throw ConflictException("Login is not set up on the server: set MEALPLANNER_OIDC_ISSUER to the Authelia address")
}

/** Scopes clients ask for: offline_access gives a refresh token, so the app stays logged in. */
val LOGIN_SCOPES = listOf("openid", "profile", "email", "offline_access")

/**
 * Verifies JWT access tokens issued by an OpenID Connect provider (Authelia with
 * `access_token_signed_response_alg: RS256`) against its published keys: signature, issuer,
 * expiry, and that the token was issued to [clientId].
 */
class OidcVerifier(
    private val issuer: String,
    private val clientId: String,
    private val http: HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    },
) : TokenVerifier {
    private val log = LoggerFactory.getLogger(OidcVerifier::class.java)
    private val lock = Mutex()
    private var discovered: Discovery? = null
    private var keys: JwkProvider? = null

    @Serializable
    private data class Discovery(
        val issuer: String,
        @SerialName("authorization_endpoint") val authorizationEndpoint: String,
        @SerialName("token_endpoint") val tokenEndpoint: String,
        @SerialName("userinfo_endpoint") val userinfoEndpoint: String? = null,
        @SerialName("jwks_uri") val jwksUri: String,
    )

    @Serializable
    private data class UserInfo(
        val sub: String,
        @SerialName("preferred_username") val preferredUsername: String? = null,
        val name: String? = null,
    )

    /** The provider's discovery document, fetched once and kept. */
    private suspend fun discovery(): Discovery = lock.withLock {
        discovered ?: run {
            val url = issuer.trimEnd('/') + "/.well-known/openid-configuration"
            val response = http.get(url)
            check(response.status.isSuccess()) { "$url answered ${response.status.value}" }
            response.body<Discovery>().also {
                discovered = it
                keys = JwkProviderBuilder(URI(it.jwksUri).toURL()).cached(10, 24, TimeUnit.HOURS).rateLimited(10, 1, TimeUnit.MINUTES).build()
            }
        }
    }

    override suspend fun verify(token: String): Identity? {
        val decoded = runCatching { JWT.decode(token) }.getOrNull() ?: return null
        if (decoded.algorithm != "RS256") return null
        discovery()
        return try {
            val key = withContext(Dispatchers.IO) { keys!!.get(decoded.keyId).publicKey as RSAPublicKey }
            val verified = JWT.require(Algorithm.RSA256(key, null))
                .withIssuer(issuer.trimEnd('/'), issuer.trimEnd('/') + "/")
                .acceptLeeway(30)
                .build()
                .verify(token)
            // RFC 9068 access tokens name their client; other providers use azp or the audience.
            val forThisApp = verified.getClaim("client_id").asString() == clientId ||
                verified.getClaim("azp").asString() == clientId ||
                verified.audience.orEmpty().contains(clientId)
            val subject = verified.subject
            if (!forThisApp || subject.isNullOrBlank()) return null
            Identity(subject, verified.getClaim("preferred_username").asString(), verified.getClaim("name").asString())
        } catch (e: Exception) {
            log.debug("Rejected a token: ${e.message}")
            null
        }
    }

    override suspend fun userinfo(token: String): Identity? = runCatching {
        val endpoint = discovery().userinfoEndpoint ?: return null
        val response = http.get(endpoint) { bearerAuth(token) }
        if (!response.status.isSuccess()) return null
        val info = response.body<UserInfo>()
        Identity(info.sub, info.preferredUsername, info.name)
    }.getOrNull()

    override suspend fun clientConfig(): AuthConfig {
        val d = discovery()
        return AuthConfig(d.issuer, clientId, d.authorizationEndpoint, d.tokenEndpoint, LOGIN_SCOPES)
    }
}
