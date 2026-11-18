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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

/**
 * Opens the login page; the answer comes back through the platform to [LoginExchange.deliver]. On
 * Android a Custom Tab over the app (back through an App Link on the server's `/app/login`), on the
 * web the same tab.
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

/**
 * A run of renewals the token endpoint answered with an error that does not end the session at once
 * (see [Login.refresh]): [count] in a row, the first at [since] (epoch seconds), none renewed in between.
 */
@Serializable
private data class FailedRenewals(val count: Int, val since: Long)

private const val SESSION_KEY = "auth.session"
private const val PENDING_KEY = "auth.pending"
private const val LOGGED_OUT_KEY = "auth.loggedOut"
private const val FAILED_RENEWALS_KEY = "auth.failedRenewals"
private val json = Json { ignoreUnknownKeys = true }

/** Failed renewals in a row that may end a session, when they went on for long enough (see [Login.refresh]). */
private const val FAILED_RENEWALS_TO_END = 3

/** How long a token endpoint may answer renewals with another client error (403, 404, …): a proxy's 404 while the provider restarts lasts a minute or two. */
private val REFUSING_FOR = 15.minutes

/** How long a token endpoint may answer renewals as a provider in trouble (5xx, 408, 429): an outage is waited out for a day. */
private val IN_TROUBLE_FOR = 24.hours

fun SettingsStorage.session(): Session? = get(SESSION_KEY)?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString<Session>(it) }.getOrNull() }

/** Saves [session] (a new one, a renewed one, or none); the renewals that failed before belong to the one it replaces. */
fun SettingsStorage.saveSession(session: Session?) {
    set(SESSION_KEY, session?.let { json.encodeToString(it) } ?: "")
    set(FAILED_RENEWALS_KEY, "")
}

private fun SettingsStorage.failedRenewals(): FailedRenewals? =
    get(FAILED_RENEWALS_KEY)?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString<FailedRenewals>(it) }.getOrNull() }

/**
 * What the app knows about why there is no session. It decides what the login screen says and whether
 * the app may open the login page by itself, which it does once per logged-out state (see
 * [LoginExchange.claimAutoOpen]). None on a fresh install, and again after each login.
 */
enum class LoggedOut {
    /**
     * A login page was opened, by the app or by the user, or another server was chosen without a session
     * to end; from now on only the user opens one.
     */
    LOGIN_OPENED,

    /** The user logged out, or chose another server (which has its own accounts) while logged in. */
    BY_USER,

    /** The provider refused to renew the session: it ran out, or was ended there. */
    EXPIRED,

    /** The token endpoint kept answering renewals with an error for long (see [Login.refresh]). */
    NOT_RENEWED,
}

fun SettingsStorage.loggedOut(): LoggedOut? = get(LOGGED_OUT_KEY)?.let { stored -> LoggedOut.entries.firstOrNull { it.name == stored } }

private fun SettingsStorage.saveLoggedOut(reason: LoggedOut?) = set(LOGGED_OUT_KEY, reason?.name ?: "")

/**
 * Logging in with the server's OpenID Connect provider (Authelia): the authorization code
 * flow with PKCE, as a public client, so the app holds no secret. The access token goes
 * with every API call; the refresh token gets a new one when it runs out.
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalTime::class)
class Login(
    private val storage: SettingsStorage,
    private val http: HttpClient = HttpClient { install(ContentNegotiation) { json(json) } },
    private val clock: Clock = Clock.System,
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

    /**
     * True when [url] is a redirect back from the login page while a login waits for its answer, so
     * it should be [finish]ed. With no login waiting the answer was used already: Android hands the
     * same redirect over again, e.g. when it re-creates the screen, and reporting it would put
     * "Could not log in" over a session that works. An answer to another login than the waiting one
     * does count here, and [finish] refuses it.
     */
    fun isAwaitedAnswer(url: String): Boolean = isCallback(url) && pending() != null

    /**
     * Finishes a login from the redirect [url]: checks it belongs to the login we started and swaps
     * the code for tokens. An answer that belongs to it is used up, whatever it says, so it works once.
     */
    suspend fun finish(url: String): Session {
        val pending = pending() ?: error(Strings.current(Msg.LOGIN_NOT_STARTED))
        val params = Url(url).parameters
        // Checked first, so that a foreign answer can neither end our login nor put its own text on screen.
        check(params["state"] == pending.state) { Strings.current(Msg.LOGIN_ANSWER_MISMATCH) }
        abandon()
        params["error"]?.let { error(params["error_description"] ?: it) }
        val code = params["code"] ?: error(Strings.current(Msg.LOGIN_NO_CODE))
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
        check(response.status.isSuccess()) { Strings.current(Msg.LOGIN_FAILED_STATUS, response.status.value) }
        val tokens = json.decodeFromString<TokenResponse>(response.bodyAsText())
        // Called off while the provider answered (a log-out, another server: LoginExchange.logOut): nothing is kept.
        currentCoroutineContext().ensureActive()
        return toSession(tokens, previousRefresh = null).also { storage.saveSession(it) }
    }

    /**
     * Forgets the login waiting for its answer, so that an answer that still comes is let pass
     * ([isAwaitedAnswer]); with [page], only when that login is the one [start] returned [page] for.
     */
    fun abandon(page: String? = null) {
        val waiting = pending() ?: return
        if (page == null || Url(page).parameters["state"] == waiting.state) storage.set(PENDING_KEY, "")
    }

    /**
     * A new access token from the refresh token, saved. Asked for when the server refused the access
     * token, so null means the request fails; whether the session is over too depends on why:
     * - the provider refused the refresh token or the client (400, 401; RFC 6749, 5.2), or there is
     *   none: over at once, [LoggedOut.EXPIRED];
     * - the token endpoint answered with another error: over only when that went on, on at least
     *   [FAILED_RENEWALS_TO_END] renewals in a row with the last at least [REFUSING_FOR] (another 4xx: a
     *   403, a 404) or [IN_TROUBLE_FOR] (5xx, 408, 429) after the first, [LoggedOut.NOT_RENEWED]. Counted
     *   from the first failure, not from when the access token expired: after a week unused it expired a
     *   week ago, and one hiccup of the provider must not end the session then;
     * - no answer (a network error, which is thrown) or none of the server's login settings: nothing is
     *   known about the session, which stays, and the run of failures neither grows nor starts afresh.
     * An answer for [session] when it is no longer the stored one (logged out, or another login meanwhile)
     * is dropped: it must neither bring back a session the user ended nor end the one that replaced it.
     */
    suspend fun refresh(serverUrl: String, session: Session): Session? {
        val refreshToken = session.refreshToken
        if (refreshToken == null) {
            end(LoggedOut.EXPIRED)
            return null
        }
        val config = runCatching { authConfig(serverUrl) }.getOrNull() ?: return null
        val response = http.submitForm(
            config.tokenEndpoint,
            parameters {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", config.clientId)
            },
        )
        val body = response.bodyAsText()
        if (storage.session() != session) return null
        if (!response.status.isSuccess()) {
            renewalFailed(response.status.value)
            return null
        }
        return toSession(json.decodeFromString(body), refreshToken).also { storage.saveSession(it) }
    }

    /** Counts a renewal the token endpoint answered with [status], and ends the session when [refresh] says so. */
    private fun renewalFailed(status: Int) {
        if (status == 400 || status == 401) return end(LoggedOut.EXPIRED)
        val now = clock.now().epochSeconds
        val run = storage.failedRenewals()?.let { it.copy(count = it.count + 1) } ?: FailedRenewals(1, now)
        val inTrouble = status >= 500 || status == 408 || status == 429
        val patience = if (inTrouble) IN_TROUBLE_FOR else REFUSING_FOR
        if (run.count >= FAILED_RENEWALS_TO_END && now - run.since >= patience.inWholeSeconds) end(LoggedOut.NOT_RENEWED)
        else storage.set(FAILED_RENEWALS_KEY, json.encodeToString(run))
    }

    /** The session is over: gone from storage, with [reason] for the login screen to say. */
    private fun end(reason: LoggedOut) {
        storage.saveSession(null)
        storage.saveLoggedOut(reason)
    }

    /** Decoded here, leniently: providers add fields of their own (token_type, id_token, scope). */
    private suspend fun authConfig(serverUrl: String): AuthConfig {
        val response = http.get(serverUrl.trimEnd('/') + "/api/auth/config")
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            // The server explains itself as {"error": "..."}.
            val reason = runCatching { json.decodeFromString<Map<String, String>>(body)["error"] }.getOrNull()
            error(reason ?: Strings.current(Msg.LOGIN_SETTINGS_STATUS, response.status.value))
        }
        return json.decodeFromString(body)
    }

    private fun toSession(tokens: TokenResponse, previousRefresh: String?) = Session(
        accessToken = tokens.accessToken,
        // Providers may rotate the refresh token, or keep the old one valid and send none.
        refreshToken = tokens.refreshToken ?: previousRefresh,
        expiresAt = clock.now().epochSeconds + tokens.expiresIn,
    )

    /** The login waiting for its answer; an unreadable one counts as none, as an unreadable session does. */
    private fun pending(): PendingLogin? =
        storage.get(PENDING_KEY)?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString<PendingLogin>(it) }.getOrNull() }

    fun close() = http.close()

    private fun randomToken(bytes: Int) = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(secureRandomBytes(bytes))
}

/** What has come of the answers handed to [LoginExchange], for the screen that is there to show. */
sealed interface LoginProgress {
    /** Nothing under way, or what came of it has been taken by the screen. */
    data object Idle : LoginProgress

    /** An answer came back and its code is being swapped for tokens. */
    data object Exchanging : LoginProgress

    data class Failed(val message: String) : LoginProgress

    /** Logged in and saved; the screen takes it with [LoginExchange.acknowledge]. */
    data class Done(val session: Session) : LoginProgress
}

/**
 * The login as the app goes through it, kept outside the screen. Android re-creates the screen for a
 * rotation, a dark mode or a language change; the token exchange (about a second) must neither be
 * cancelled with the old screen, which would lose the login since its answer works only once (#176),
 * nor be made twice. So the platform keeps one of these as long as its process (Android) or page (web)
 * lives, hands it every redirect, and whichever screen is there reads [progress]. [scope] must outlive
 * the screens, and run on one thread (the main one): a log-out then comes either before the exchange
 * saved its session, and keeps it from being saved, or after, and ends it.
 */
class LoginExchange(
    private val storage: SettingsStorage,
    scope: CoroutineScope,
    val login: Login = Login(storage),
) {
    private val answers = Channel<String>(Channel.UNLIMITED)
    private val state = MutableStateFlow<LoginProgress>(LoginProgress.Idle)
    val progress: StateFlow<LoginProgress> = state.asStateFlow()

    /** The exchange under way, if any, for [callOff] to cancel. */
    private var exchanging: Job? = null

    /** How often a login was called off, so that a [start] still asking the server knows it was. */
    private var calledOff = 0

    init {
        // One answer at a time, in the order they came: a second delivery of the same answer finds its
        // login used up and passes unseen, as the screen's own collector did before. Each in a job of its
        // own, so that calling one off leaves the next ones to come.
        scope.launch {
            for (url in answers) launch { exchange(url) }.also { exchanging = it }.join()
        }
    }

    /**
     * Hands over a redirect the platform received. Any answer means a login page was open, so the app
     * opens none by itself after it: not on the web page loaded by a cancelled or failed login either.
     */
    fun deliver(url: String) {
        if (!login.isCallback(url)) return
        noteLoginOpened()
        answers.trySend(url)
    }

    private suspend fun exchange(url: String) {
        if (!login.isAwaitedAnswer(url)) return
        state.value = LoginProgress.Exchanging
        state.value = try {
            LoginProgress.Done(login.finish(url)).also { storage.saveLoggedOut(null) }
        } catch (e: CancellationException) {
            // Called off: whoever did it has said what the screen shows.
            throw e
        } catch (e: Exception) {
            LoginProgress.Failed(e.message ?: Strings.current(Msg.LOGIN_FAILED))
        }
    }

    /** Takes [done] off [progress], so that a later screen does not take the same login again. */
    fun acknowledge(done: LoginProgress.Done) {
        state.compareAndSet(done, LoginProgress.Idle)
    }

    /**
     * Starts a login at [serverUrl] and returns the page to open; null when it was called off while the
     * server was asked how to log in (a log-out, another server, another login started), so there is
     * nothing to open. It replaces any login under way, which may be at another server: only the last
     * login started may log in. A failure shown before belongs to the last attempt and goes; from now on
     * the app opens no login page by itself.
     */
    suspend fun start(serverUrl: String, redirectUri: String): String? {
        callOff()
        noteLoginOpened()
        val since = calledOff
        val page = login.start(serverUrl, redirectUri)
        if (calledOff == since) return page
        // Its page is not opened, and a login started after it keeps waiting for its own answer.
        login.abandon(page)
        return null
    }

    /**
     * Whether the app should open the login page by itself now: logged out, with a server it knows, and
     * only once per logged-out state. True at most once; the claim is kept in storage, so neither Back,
     * a rotation, a return to the front, a re-created screen nor a restarted app opens it again after the
     * user closed it, and after logging out or an expired session the login screen says why instead.
     */
    fun claimAutoOpen(serverKnown: Boolean): Boolean {
        if (!serverKnown || storage.session() != null || storage.loggedOut() != null) return false
        storage.saveLoggedOut(LoggedOut.LOGIN_OPENED)
        return true
    }

    /**
     * A new access token for the stored session, or null. [Login.refresh] says when that ends the session
     * (gone from storage, with the reason for the login screen to say) and when it stays for the next
     * request to try again; a log-out meanwhile keeps its own reason.
     */
    suspend fun renew(serverUrl: String): Session? = storage.session()?.let { login.refresh(serverUrl, it) }

    /**
     * Ends the session on the user's word: Log out, or another server chosen, which has its own accounts.
     * A login under way is called off with it, as it may belong to the old server. The login screen says
     * "You are logged out." when there was a session to end; without one (an install that never logged
     * in, or whose session had ended already) it says nothing, as a reason given before was about the old
     * server. Either way it opens nothing by itself.
     */
    fun logOut() {
        callOff()
        val ended = storage.session() != null
        storage.logOut()
        storage.saveLoggedOut(if (ended) LoggedOut.BY_USER else LoggedOut.LOGIN_OPENED)
        // A login nobody took before the log-out was ended with it.
        state.value = LoginProgress.Idle
    }

    /**
     * Calls off the login under way: a token exchange running is cancelled and its result dropped, and a
     * login page still open answers nothing. A failure shown goes with it; a finished login stays for the
     * screen to take, as its session is saved.
     */
    private fun callOff() {
        calledOff++
        exchanging?.cancel()
        login.abandon()
        state.update { if (it is LoginProgress.Done) it else LoginProgress.Idle }
    }

    /** Keeps a reason already known: the login screen goes on saying why the last session ended. */
    private fun noteLoginOpened() {
        if (storage.session() == null && storage.loggedOut() == null) storage.saveLoggedOut(LoggedOut.LOGIN_OPENED)
    }
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
