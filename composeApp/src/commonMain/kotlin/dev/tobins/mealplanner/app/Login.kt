package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.AuthConfig
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Opens the login page and brings the result back: the system browser on Android (back
 * through an App Link on the server's `/app/login`), the same tab on the web.
 */
interface LoginLauncher {
    /**
     * Where the provider sends the browser after login, for the server at [serverUrl]; must be
     * registered with the client at Authelia, which only allows http(s) addresses.
     */
    fun redirectUri(serverUrl: String): String

    fun open(url: String)
}

/** A logged-in session: the tokens from Authelia. */
@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String? = null,
    /** Epoch seconds when [accessToken] stops working. */
    val expiresAt: Long = 0,
)

/** What is kept between starting a login and coming back from it. */
@Serializable
private data class PendingLogin(val state: String, val verifier: String, val tokenEndpoint: String, val clientId: String, val redirectUri: String)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
)

private const val SESSION_KEY = "auth.session"
private const val PENDING_KEY = "auth.pending"
private val json = Json { ignoreUnknownKeys = true }

fun SettingsStorage.session(): Session? = get(SESSION_KEY)?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString<Session>(it) }.getOrNull() }

fun SettingsStorage.saveSession(session: Session?) = set(SESSION_KEY, session?.let { json.encodeToString(it) } ?: "")

/**
 * Logging in with the server's OpenID Connect provider (Authelia): the authorization code
 * flow with PKCE, as a public client, so the app holds no secret. The access token goes
 * with every API call; the refresh token gets a new one when it runs out.
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalTime::class)
class Login(
    private val storage: SettingsStorage,
    private val http: HttpClient = HttpClient { install(ContentNegotiation) { json(json) } },
) {

    /** Asks the server how to log in, remembers a PKCE secret, and returns the login page to open. */
    suspend fun start(serverUrl: String, redirectUri: String): String {
        val config = authConfig(serverUrl)
        val verifier = randomToken(48)
        val state = randomToken(16)
        storage.set(PENDING_KEY, json.encodeToString(PendingLogin(state, verifier, config.tokenEndpoint, config.clientId, redirectUri)))
        return URLBuilder(config.authorizationEndpoint).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", config.clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("scope", config.scopes.joinToString(" "))
            parameters.append("state", state)
            parameters.append("code_challenge", Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(Sha256.digest(verifier.encodeToByteArray())))
            parameters.append("code_challenge_method", "S256")
        }.buildString()
    }

    /** True when [url] is the redirect back from the login page. */
    fun isCallback(url: String): Boolean {
        val parsed = runCatching { Url(url) }.getOrNull() ?: return false
        return parsed.parameters["state"] != null && (parsed.parameters["code"] != null || parsed.parameters["error"] != null)
    }

    /** Finishes a login from the redirect [url]: checks it belongs to the login we started and swaps the code for tokens. */
    suspend fun finish(url: String): Session {
        val pending = storage.get(PENDING_KEY)?.takeIf { it.isNotBlank() }?.let { json.decodeFromString<PendingLogin>(it) }
            ?: error("No login was started")
        val params = Url(url).parameters
        params["error"]?.let { error(params["error_description"] ?: it) }
        check(params["state"] == pending.state) { "The login answer does not match the login that was started" }
        val code = params["code"] ?: error("The login answer has no code")
        storage.set(PENDING_KEY, "")
        val response = http.submitForm(
            pending.tokenEndpoint,
            parameters {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", pending.redirectUri)
                append("client_id", pending.clientId)
                append("code_verifier", pending.verifier)
            },
        )
        check(response.status.isSuccess()) { "Logging in failed (${response.status.value})" }
        return toSession(json.decodeFromString(response.bodyAsText()), previousRefresh = null).also { storage.saveSession(it) }
    }

    /**
     * A new access token from the refresh token, saved; null (and logged out) when the
     * refresh token no longer works, e.g. after a long time unused.
     */
    suspend fun refresh(serverUrl: String, session: Session): Session? {
        val refreshToken = session.refreshToken ?: return null
        val config = runCatching { authConfig(serverUrl) }.getOrNull() ?: return null
        val response = http.submitForm(
            config.tokenEndpoint,
            parameters {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", config.clientId)
            },
        )
        if (!response.status.isSuccess()) {
            // A network problem says nothing about the session; only a refusal ends it.
            if (response.status.value in 400..499) storage.saveSession(null)
            return null
        }
        return toSession(json.decodeFromString(response.bodyAsText()), refreshToken).also { storage.saveSession(it) }
    }

    /** Decoded here, leniently: providers add fields of their own (token_type, id_token, scope). */
    private suspend fun authConfig(serverUrl: String): AuthConfig {
        val response = http.get(serverUrl.trimEnd('/') + "/api/auth/config")
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            // The server explains itself as {"error": "..."}.
            val reason = runCatching { json.decodeFromString<Map<String, String>>(body)["error"] }.getOrNull()
            error(reason ?: "The server answered ${response.status.value} for its login settings")
        }
        return json.decodeFromString(body)
    }

    private fun toSession(tokens: TokenResponse, previousRefresh: String?) = Session(
        accessToken = tokens.accessToken,
        // Providers may rotate the refresh token, or keep the old one valid and send none.
        refreshToken = tokens.refreshToken ?: previousRefresh,
        expiresAt = Clock.System.now().epochSeconds + tokens.expiresIn,
    )

    fun close() = http.close()

    private fun randomToken(bytes: Int) = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(secureRandomBytes(bytes))
}

/** SHA-256 (FIPS 180-4), for the PKCE code challenge; the web has no synchronous digest. */
object Sha256 {
    private val k = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
    )

    fun digest(message: ByteArray): ByteArray {
        val h = intArrayOf(0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6, 0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19)
        val bitLength = message.size.toLong() * 8
        val padded = ByteArray(((message.size + 9 + 63) / 64) * 64)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
        val w = IntArray(64)
        for (chunk in padded.indices step 64) {
            for (i in 0 until 16) {
                w[i] = (padded[chunk + 4 * i].toInt() and 0xff shl 24) or (padded[chunk + 4 * i + 1].toInt() and 0xff shl 16) or
                    (padded[chunk + 4 * i + 2].toInt() and 0xff shl 8) or (padded[chunk + 4 * i + 3].toInt() and 0xff)
            }
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var (a, b, c, d) = h
            var e = h[4]
            var f = h[5]
            var g = h[6]
            var hh = h[7]
            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + k[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        return ByteArray(32) { (h[it / 4] ushr (24 - 8 * (it % 4))).toByte() }
    }
}
