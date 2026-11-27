package dev.tobins.mealplanner.server.accounts

import dev.tobins.mealplanner.accounts.RegistrationField
import dev.tobins.mealplanner.accounts.RegistrationRules
import dev.tobins.mealplanner.api.AccountRequest
import dev.tobins.mealplanner.api.RegistrationReceipt
import dev.tobins.mealplanner.api.RegistrationRefusal
import dev.tobins.mealplanner.api.RegistrationRequest
import dev.tobins.mealplanner.server.ConflictException
import dev.tobins.mealplanner.server.NotFoundException
import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.writing
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A refused request for an account, or a refused call of the owner's routes: answered with [status] and
 * `{"error": message, "reason": reason}`, with `field` for the field that breaks a rule and `Retry-After` for a limit.
 * The message is the server's own sentence, never anything of the request.
 */
class RegistrationRefusedException(
    val status: HttpStatusCode,
    val reason: String,
    message: String,
    val field: String? = null,
    val retryAfter: Duration? = null,
) : RuntimeException(message)

/**
 * Requests for an account (issue 314). A person asks on the login screen; the registration helper next to the directory
 * makes their account there at once, in no group, with the password they chose; the owner approves (the helper puts them
 * in the group) or rejects (it deletes the account), and a request nobody decides on is rejected after [EXPIRES_AFTER].
 * This server keeps who asked ([AccountRequest]), never the password: it goes to the helper and nowhere else.
 *
 * One thing at a time ([lock]): a request, an approval, a rejection and the expiry each finish with the directory before
 * the next starts, so none acts on an account that another is still making or removing. They are few, and each waits
 * for the helper at most its time limit. The table is written in short turns around the helper's calls, never during one.
 */
class Registrations(
    private val db: Database,
    private val helper: DirectoryHelper,
    private val now: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(Registrations::class.java)
    private val q = db.registrationQueries
    private val lock = Mutex()
    private val perAddress = AttemptLimit(PER_ADDRESS, Duration.ofHours(1))
    private val overall = AttemptLimit(OVERALL, Duration.ofDays(1))

    /**
     * Counts a request from [address] against the limits, before anything of it is read: also one that turns out wrong, as
     * each one can probe a user name. An address over its limit does not use up everyone's.
     */
    @Synchronized
    fun admit(address: String) {
        val at = now()
        for ((limit, key) in listOf(perAddress to address, overall to "")) {
            limit.wait(key, at)?.let { wait ->
                throw RegistrationRefusedException(HttpStatusCode.TooManyRequests, RegistrationRefusal.TOO_MANY, "Too many requests for an account; try again later.", retryAfter = wait)
            }
        }
        perAddress.take(address, at)
        overall.take("", at)
    }

    /**
     * Makes the account [sent] asks for, in no group, and keeps the request for the owner. Refused when a field breaks a
     * rule, when as many wait as the server keeps, when the user name is that of a meal planner account or of a request
     * waiting, or when the directory has the user name or e-mail address; the last three with one answer, so the form
     * tells nobody which.
     */
    suspend fun request(sent: RegistrationRequest): RegistrationReceipt {
        val request = RegistrationRules.normalised(sent)
        RegistrationRules.problems(request).firstOrNull()?.let { field ->
            throw RegistrationRefusedException(HttpStatusCode.BadRequest, RegistrationRefusal.INVALID, ruleOf(field), field = field.key)
        }
        val name = request.username
        lock.withLock {
            reserve(request)
            when (helper.register(request)) {
                HelperAnswer.DONE -> log.info("Account requested: $name")
                HelperAnswer.TAKEN -> {
                    forget(name)
                    throw unavailable()
                }
                HelperAnswer.UNSURE -> {
                    undo(name)
                    throw directoryDown()
                }
                // The helper's rules refused what the server's let through: the request, not the directory, is the trouble.
                HelperAnswer.INVALID -> {
                    forget(name)
                    throw RegistrationRefusedException(
                        HttpStatusCode.BadRequest, RegistrationRefusal.INVALID, "The account directory does not take this request; check every field.",
                    )
                }
                else -> {
                    forget(name)
                    throw directoryDown()
                }
            }
        }
        return RegistrationReceipt(name)
    }

    /** Keeps [request] in the table before the directory is asked, so that no other request takes its user name meanwhile. */
    private fun reserve(request: RegistrationRequest) = db.writing {
        if (q.countRequests().executeAsOne() >= MAX_WAITING) {
            throw RegistrationRefusedException(HttpStatusCode.ServiceUnavailable, RegistrationRefusal.FULL, "Too many requests wait for approval; try again later.")
        }
        if (q.countAccountsNamed(request.username).executeAsOne() > 0 || q.selectRequest(request.username).executeAsOneOrNull() != null) throw unavailable()
        q.insertRequest(request.username, request.displayName, request.email, now().truncatedTo(ChronoUnit.SECONDS).toString())
    }

    /**
     * After a register whose outcome is not known: rejects the name, which deletes the account if the helper made it, and
     * forgets the request. Kept when the helper cannot say either, for the owner to see and [expire] to try again.
     */
    private suspend fun undo(name: String) {
        when (helper.reject(name)) {
            HelperAnswer.DONE, HelperAnswer.UNKNOWN, HelperAnswer.IN_GROUP -> forget(name)
            else -> log.warn("The request of $name may have made an account in the directory; it waits until rejected or expired")
        }
    }

    /** The requests that wait, oldest first. */
    fun waiting(): List<AccountRequest> = q.selectRequests().executeAsList().map {
        val at = Instant.parse(it.requestedAt)
        AccountRequest(it.username, it.displayName, it.email, it.requestedAt, at.plus(EXPIRES_AFTER).toString())
    }

    /**
     * Whether a login refused for not being in the group is one of a request that waits: [username] is asked only when
     * one does, as it may take a call to the provider.
     */
    suspend fun isWaiting(username: suspend () -> String?): Boolean {
        if (q.countRequests().executeAsOne() == 0L) return false
        val name = username()?.lowercase() ?: return false
        return q.selectRequest(name).executeAsOneOrNull() != null
    }

    /** Approves [username]'s request: the helper puts the account in the group, and the person can log in. */
    suspend fun approve(username: String): List<AccountRequest> = decide(username, "approved") { name ->
        when (helper.approve(name)) {
            HelperAnswer.DONE -> true
            HelperAnswer.UNKNOWN, HelperAnswer.IN_GROUP -> false
            else -> throw directoryDown()
        }
    }

    /**
     * Rejects [username]'s request: the helper deletes the account from the directory. One the directory no longer has is
     * gone as asked; one in a group was approved there by hand, and is left to the owner to remove there.
     */
    suspend fun reject(username: String): List<AccountRequest> = decide(username, "rejected") { name ->
        when (helper.reject(name)) {
            HelperAnswer.DONE, HelperAnswer.UNKNOWN -> true
            HelperAnswer.IN_GROUP -> false
            else -> throw directoryDown()
        }
    }

    /**
     * Decides on the waiting request of [username] with [change], which answers whether it did what was asked or found it
     * decided in the directory already; the request is forgotten either way, and the requests still waiting are answered.
     * When the helper cannot be reached the request stays, for the owner to try again.
     */
    private suspend fun decide(username: String, done: String, change: suspend (String) -> Boolean): List<AccountRequest> {
        val name = username.lowercase()
        lock.withLock {
            q.selectRequest(name).executeAsOneOrNull() ?: throw NotFoundException("No request of $name waits")
            val asked = change(name)
            forget(name)
            if (!asked) throw ConflictException("The request of $name was decided in the account directory already, so it is gone from this list.")
            log.info("Account request $done: $name")
        }
        return waiting()
    }

    /**
     * Rejects every request older than [EXPIRES_AFTER], deleting its account from the directory. One the helper cannot
     * reject now stays for the next round; answers how many went.
     */
    suspend fun expire(): Int = lock.withLock {
        val before = now().minus(EXPIRES_AFTER).truncatedTo(ChronoUnit.SECONDS).toString()
        q.selectRequestedBefore(before).executeAsList().count { name ->
            val gone = helper.reject(name) in setOf(HelperAnswer.DONE, HelperAnswer.UNKNOWN, HelperAnswer.IN_GROUP)
            if (gone) {
                forget(name)
                log.info("Account request expired: $name")
            } else {
                log.warn("The expired request of $name could not be rejected; trying again later")
            }
            gone
        }
    }

    private fun forget(name: String) = db.writing { q.deleteRequest(name) }

    private fun unavailable() = RegistrationRefusedException(
        HttpStatusCode.Conflict, RegistrationRefusal.UNAVAILABLE, "This user name or e-mail address cannot be used. Choose another.",
    )

    private fun directoryDown() = RegistrationRefusedException(
        HttpStatusCode.ServiceUnavailable, RegistrationRefusal.DIRECTORY_DOWN, "The account directory cannot be reached; try again later.",
    )

    /** What the rule of a field asks, in the server's own words; the app says it in its language, by the field. */
    private fun ruleOf(field: RegistrationField) = when (field) {
        RegistrationField.USERNAME -> "The user name must be ${RegistrationRules.USERNAME_MIN} to ${RegistrationRules.USERNAME_MAX} lower-case letters, " +
            "digits, dots, dashes or underscores, starting with a letter or digit."
        RegistrationField.DISPLAY_NAME -> "The name must be 1 to ${RegistrationRules.DISPLAY_NAME_MAX} characters."
        RegistrationField.EMAIL -> "The e-mail address is not one."
        RegistrationField.PASSWORD -> "The password must be ${RegistrationRules.PASSWORD_MIN} to ${RegistrationRules.PASSWORD_MAX} characters, and not the user name."
    }

    companion object {
        /** Requests from one address in an hour: enough for a family on one connection, few to probe user names with. */
        const val PER_ADDRESS = 5

        /** Requests from everyone in a day: a household's server gets a few a year. */
        const val OVERALL = 30

        /** Requests waiting at once; beyond it nobody can ask until the owner decides on some. */
        const val MAX_WAITING = 20

        /** A request nobody approved by then is rejected, its directory account deleted. */
        val EXPIRES_AFTER: Duration = Duration.ofDays(14)
    }
}

/**
 * At most [max] attempts per key in any [window], kept in memory: a restart forgets them, which costs little, as each
 * window is short and the waiting requests ([Registrations.MAX_WAITING]) are kept in the database. Not for two threads at
 * once: [Registrations.admit] asks and takes in one go.
 */
class AttemptLimit(private val max: Int, private val window: Duration) {
    private val attempts = mutableMapOf<String, ArrayDeque<Instant>>()

    /** How long until [key] may try again at [at]; null when it may now. */
    fun wait(key: String, at: Instant): Duration? {
        val recent = recent(key, at)
        return if (recent.size < max) null else Duration.between(at, recent.first().plus(window))
    }

    fun take(key: String, at: Instant) {
        recent(key, at).addLast(at)
    }

    /** [key]'s attempts within the window before [at]; every key's older ones are dropped, so the map stays small. */
    private fun recent(key: String, at: Instant): ArrayDeque<Instant> {
        val since = at.minus(window)
        attempts.values.forEach { it.removeAll { time -> !time.isAfter(since) } }
        attempts.values.removeAll { it.isEmpty() }
        return attempts.getOrPut(key) { ArrayDeque() }
    }
}
